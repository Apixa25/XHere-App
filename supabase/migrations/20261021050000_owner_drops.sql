-- Owners can edit and soft-delete their own drops.
--
-- A removed drop stays in the database so confirmations, impact notes, and
-- reports keep their row. status = 'removed' with removed_reason = 'author'
-- is the soft delete. deleted_at is when the author did it. status_before_delete
-- is what Undo puts back.
--
-- Author deletes are not moderation strikes and do not take reputation points.
-- Campaign totals, founding ranks, the leaderboard, First drop, the Here Card,
-- the public API, and map reads already skip status 'removed'. This migration
-- also hides deleted_at and refreshes those counts by writing that status.
--
-- Photos stay in Storage for the undo window. A nightly job deletes the
-- objects a day later. The drop_media rows stay, so the history of the drop
-- is still there.

alter table public.drops
  add column deleted_at timestamptz;

alter table public.drops
  add column status_before_delete public.drop_status;

comment on column public.drops.deleted_at is
  'Set when the author soft-deletes the drop. Null while the drop is live.';

comment on column public.drops.status_before_delete is
  'Status to restore if the author undoes a delete. Null unless removed_reason is author.';

create index drops_author_deleted_idx
  on public.drops (author_id, deleted_at desc)
  where deleted_at is not null;

-- Hard delete would drop confirmation and impact rows. The RPC is the only path.
drop policy drops_delete_own on public.drops;
create policy drops_delete_own on public.drops
  for delete to authenticated
  using (false);

comment on policy drops_delete_own on public.drops is
  'Authors cannot hard-delete. delete_my_drop soft-removes the row instead.';

-- ---------------------------------------------------------------------------
-- A deleted drop is invisible, including to the author. Map, Here Card, and
-- the public API all go through can_view_drop or status in (active, confirmed).
-- ---------------------------------------------------------------------------
create or replace function private.can_view_drop(p_drop public.drops, p_viewer uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when p_drop.deleted_at is not null then false
    when p_drop.author_id is not null and p_drop.author_id = p_viewer then
      p_drop.status in ('active', 'confirmed', 'pending_review', 'disputed')
      and (p_drop.expires_at is null or p_drop.expires_at > now())
    else
      p_drop.status in ('active', 'confirmed')
      and (p_drop.expires_at is null or p_drop.expires_at > now())
      and exists (select 1 from public.categories c where c.id = p_drop.category_id and c.active)
      and (p_drop.author_id is null or exists (
        select 1 from public.profiles p where p.id = p_drop.author_id and p.deleted_at is null))
      and not private.is_blocked_between(p_viewer, p_drop.author_id)
      and (p_viewer is null or not exists (
        select 1 from public.hidden_drops h where h.user_id = p_viewer and h.drop_id = p_drop.id))
  end;
$$;

create or replace function private.drop_counts_in_region(
  p_drop public.drops,
  p_region public.regions,
  p_from timestamptz,
  p_until timestamptz
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select p_drop.counts_for_credit
    and p_drop.deleted_at is null
    and p_drop.source = 'user'::public.drop_source
    and p_drop.author_id is not null
    and p_drop.status in ('active'::public.drop_status, 'confirmed'::public.drop_status)
    and (p_drop.expires_at is null or p_drop.expires_at > now())
    and (p_from is null or p_drop.created_at >= p_from)
    and (p_until is null or p_drop.created_at < p_until)
    and exists (
      select 1 from public.profiles p
      where p.id = p_drop.author_id
        and p.deleted_at is null
        and not p.is_demo
    )
    and extensions.st_x(p_drop.geog::extensions.geometry) between p_region.min_lng and p_region.max_lng
    and extensions.st_y(p_drop.geog::extensions.geometry) between p_region.min_lat and p_region.max_lat;
$$;

-- Author delete drops the drop from badge counts and does not write a strike.
create or replace function private.has_reputation_strike(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.drops d
    where d.author_id = p_user
      and d.status = 'removed'
      and d.removed_reason is distinct from 'author'
      and d.updated_at > now() - interval '90 days'
  );
$$;

create or replace function private.sync_drop_reputation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid;
  v_viewer uuid;
  v_penalty integer;
begin
  if tg_op = 'INSERT' then
    if new.source = 'user' and new.author_id is not null and new.status <> 'removed'
       and new.counts_for_credit
       and private.earns_reputation(new.author_id) then
      perform private.award_finished_quests(new.author_id);
      perform private.apply_tier(new.author_id, true);
      perform private.sync_reputation_badges(new.author_id);
    end if;
    return null;
  end if;

  if new.author_id is null or new.source <> 'user' then
    delete from public.reputation_ledger where drop_id = new.id;
    if old.author_id is not null then
      perform private.apply_tier(old.author_id, true);
      perform private.sync_reputation_badges(old.author_id);
    end if;
    return null;
  end if;

  if not private.earns_reputation(new.author_id) or not new.counts_for_credit then
    delete from public.reputation_ledger where drop_id = new.id;
    perform private.apply_tier(new.author_id, true);
    perform private.sync_reputation_badges(new.author_id);
    return null;
  end if;

  if old.counts_for_credit is not true and new.counts_for_credit then
    for v_actor in
      select c.user_id from public.confirmations c where c.drop_id = new.id
    loop
      perform private.resync_confirm_direction(new.author_id, v_actor);
      perform private.resync_confirm_direction(v_actor, new.author_id);
    end loop;
    for v_viewer in
      select v.viewer_id from public.drop_views v where v.drop_id = new.id
    loop
      perform private.sync_view_reputation(new.id, v_viewer);
    end loop;
    perform private.apply_tier(new.author_id, true);
    perform private.sync_reputation_badges(new.author_id);
    return null;
  end if;

  if new.status = 'removed' and old.status is distinct from 'removed' then
    delete from public.reputation_ledger
    where drop_id = new.id and kind in ('confirm', 'deny', 'view');
    -- The author took it down. Recount badges. Do not charge a moderation penalty.
    if new.removed_reason is distinct from 'author' then
      select r.moderation_penalty into v_penalty from public.reputation_rules r where r.id = 1;
      insert into public.reputation_ledger (user_id, drop_id, kind, source_key, points)
      values (new.author_id, new.id, 'moderation', 'moderation:' || new.id::text, -v_penalty)
      on conflict (source_key) do update
        set points = excluded.points, left_out = null;
    end if;
    perform private.apply_tier(new.author_id, true);
    perform private.sync_reputation_badges(new.author_id);
    return null;
  end if;

  if old.status = 'removed' and new.status is distinct from 'removed' then
    delete from public.reputation_ledger where source_key = 'moderation:' || new.id::text;
    for v_actor in
      select c.user_id from public.confirmations c where c.drop_id = new.id
    loop
      perform private.resync_confirm_direction(new.author_id, v_actor);
      perform private.resync_confirm_direction(v_actor, new.author_id);
    end loop;
    for v_viewer in
      select v.viewer_id from public.drop_views v where v.drop_id = new.id
    loop
      perform private.sync_view_reputation(new.id, v_viewer);
    end loop;
    perform private.apply_tier(new.author_id, true);
    perform private.sync_reputation_badges(new.author_id);
    return null;
  end if;

  if new.status is distinct from old.status then
    perform private.sync_reputation_badges(new.author_id);
  end if;
  return null;
end;
$$;

-- Rebuilding a score must not invent a strike for a drop the author deleted.
do $rebuild$
declare
  v_sql text := pg_get_functiondef('private.rebuild_user_reputation(uuid)'::regprocedure);
  v_old constant text := 'where d.author_id = p_user and d.source = ''user'' and d.status = ''removed''';
  v_new constant text := 'where d.author_id = p_user and d.source = ''user'' and d.status = ''removed'' and d.removed_reason is distinct from ''author''';
begin
  if position('removed_reason is distinct from ''author''' in v_sql) > 0 then
    return;
  end if;
  if position(v_old in v_sql) = 0 then
    raise exception 'rebuild_user_reputation author-delete anchor missing';
  end if;
  execute replace(v_sql, v_old, v_new);
end;
$rebuild$;

-- ---------------------------------------------------------------------------
-- Show the name the person picked. The handle stays for reports and blocks.
-- ---------------------------------------------------------------------------
alter type public.drop_card add attribute author_display_name text;

do $name$
declare
  v_sql text := pg_get_functiondef('private.to_drop_card(public.drops, uuid, extensions.geography)'::regprocedure);
  v_old constant text := 'v_card.author_handle := (select p.handle from public.profiles p where p.id = p_drop.author_id);';
  v_new constant text := 'select p.handle, nullif(btrim(p.display_name), '''')
      into v_card.author_handle, v_card.author_display_name
    from public.profiles p where p.id = p_drop.author_id;';
begin
  if position('author_display_name' in v_sql) > 0 then
    return;
  end if;
  if position(v_old in v_sql) = 0 then
    raise exception 'to_drop_card display name anchor missing';
  end if;
  execute replace(v_sql, v_old, v_new);
end;
$name$;

-- ---------------------------------------------------------------------------
-- Short-lived read of a revealed photo. The bucket stays private.
-- drop_detail writes a grant only when the card includes the media.
-- ---------------------------------------------------------------------------
create table public.media_grants (
  user_id uuid not null references public.profiles (id) on delete cascade,
  storage_path text not null,
  expires_at timestamptz not null,
  primary key (user_id, storage_path)
);

comment on table public.media_grants is
  'Lets the signed-in viewer read a drop photo after drop_detail revealed it. Expires in minutes. Not a public URL.';

alter table public.media_grants enable row level security;
revoke all on table public.media_grants from anon, authenticated;

create function private.grant_drop_media(p_drop uuid, p_uid uuid)
returns void
language sql
volatile
security definer
set search_path = ''
as $$
  insert into public.media_grants (user_id, storage_path, expires_at)
  select p_uid, m.storage_path, now() + interval '20 minutes'
  from public.drop_media m
  where m.drop_id = p_drop
  on conflict (user_id, storage_path) do update
    set expires_at = excluded.expires_at;
$$;

revoke execute on function private.grant_drop_media(uuid, uuid) from public, anon, authenticated;

create policy drop_media_select_granted on storage.objects
  for select to authenticated
  using (
    bucket_id = 'drop-media'
    and exists (
      select 1 from public.media_grants g
      where g.user_id = (select auth.uid())
        and g.storage_path = name
        and g.expires_at > now()
    )
  );

do $detail$
declare
  v_sql text := pg_get_functiondef(
    'public.drop_detail(uuid, double precision, double precision, jsonb)'::regprocedure
  );
  v_old constant text := 'v_card.view_count := v_drop.view_count;
  end if;
  return v_card;';
  v_new constant text := 'v_card.view_count := v_drop.view_count;
  end if;
  if v_uid is not null and v_card.revealed and v_card.media is not null then
    perform private.grant_drop_media(v_drop.id, v_uid);
  end if;
  return v_card;';
begin
  if position('grant_drop_media' in v_sql) > 0 then
    return;
  end if;
  if position(v_old in v_sql) = 0 then
    raise exception 'drop_detail media grant anchor missing';
  end if;
  execute replace(v_sql, v_old, v_new);
end;
$detail$;

-- ---------------------------------------------------------------------------
-- Owner RPCs
-- ---------------------------------------------------------------------------
create function public.update_my_drop(
  p_drop_id uuid,
  p_title text,
  p_body text,
  p_attributes jsonb,
  p_alt_text text default null
)
returns public.drop_card
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_drop public.drops;
  v_category public.categories;
  v_title text := btrim(p_title);
  v_body text := nullif(btrim(p_body), '');
  v_attributes jsonb := coalesce(p_attributes, '{}'::jsonb);
  v_errors text[];
  v_alt text := nullif(left(btrim(p_alt_text), 300), '');
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (select 1 from public.profiles p where p.id = v_uid and p.deleted_at is null) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;

  select * into v_drop from public.drops d where d.id = p_drop_id for update;
  if not found or v_drop.author_id is distinct from v_uid or v_drop.source <> 'user' then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  if v_drop.deleted_at is not null or v_drop.status in ('removed', 'expired') then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;

  if v_title is null or char_length(v_title) not between 1 and 120 then
    raise exception 'invalid_title' using errcode = '22023', detail = 'title must be 1..120 characters';
  end if;
  if v_body is not null and char_length(v_body) > 2000 then
    raise exception 'invalid_body' using errcode = '22023', detail = 'description must be at most 2000 characters';
  end if;
  if jsonb_typeof(v_attributes) <> 'object' then
    raise exception 'invalid_attributes' using errcode = '22023', detail = 'attributes must be a JSON object';
  end if;

  select * into v_category from public.categories c where c.id = v_drop.category_id;
  if not extensions.jsonb_matches_schema(v_category.attributes_schema::json, v_attributes) then
    v_errors := extensions.jsonschema_validation_errors(v_category.attributes_schema::json, v_attributes::json);
    raise exception 'invalid_attributes' using errcode = '22023', detail = array_to_string(v_errors, '; ');
  end if;
  perform private.assert_content_allowed(v_category.content_filters, array[v_title, v_body]);

  update public.drops
  set title = v_title,
      body = v_body,
      attributes = v_attributes
  where id = v_drop.id
  returning * into v_drop;

  if p_alt_text is not null then
    update public.drop_media m
    set alt_text = v_alt
    where m.id = (
      select picked.id
      from public.drop_media picked
      where picked.drop_id = v_drop.id and picked.media_kind = 'image'
      order by picked.sort, picked.created_at
      limit 1
    );
  end if;

  return private.to_drop_card(v_drop, v_uid, null);
end;
$$;

create function public.delete_my_drop(p_drop_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_drop public.drops;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (select 1 from public.profiles p where p.id = v_uid and p.deleted_at is null) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;

  select * into v_drop from public.drops d where d.id = p_drop_id for update;
  if not found or v_drop.author_id is distinct from v_uid or v_drop.source <> 'user' then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  if v_drop.deleted_at is not null or v_drop.status = 'removed' then
    return;
  end if;

  update public.drops
  set status_before_delete = status,
      status = 'removed',
      removed_reason = 'author',
      deleted_at = now()
  where id = v_drop.id;
end;
$$;

create function public.restore_my_drop(p_drop_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_drop public.drops;
  v_status public.drop_status;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;

  select * into v_drop from public.drops d where d.id = p_drop_id for update;
  if not found
     or v_drop.author_id is distinct from v_uid
     or v_drop.removed_reason is distinct from 'author'
     or v_drop.deleted_at is null then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  if v_drop.deleted_at < now() - interval '10 minutes' then
    raise exception 'undo_expired' using errcode = '22023';
  end if;

  v_status := coalesce(v_drop.status_before_delete, 'active');
  if v_status not in ('active', 'confirmed', 'pending_review', 'disputed') then
    v_status := 'active';
  end if;

  update public.drops
  set status = v_status,
      status_before_delete = null,
      removed_reason = null,
      deleted_at = null
  where id = v_drop.id;
end;
$$;

create function public.my_drops()
returns table (
  id uuid,
  short_id text,
  title text,
  category_slug text,
  status public.drop_status,
  confirm_count integer,
  created_at timestamptz
)
language sql
stable
security definer
set search_path = ''
as $$
  select d.id, d.short_id, d.title, c.slug, d.status, d.confirm_count, d.created_at
  from public.drops d
  join public.categories c on c.id = d.category_id
  where d.author_id = auth.uid()
    and d.source = 'user'
    and d.deleted_at is null
    and d.status <> 'removed'
  order by d.created_at desc
  limit 100;
$$;

create function public.set_my_public_name(p_display_name text, p_handle text default null)
returns public.profiles
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_name text := btrim(p_display_name);
  v_handle text := nullif(lower(btrim(p_handle)), '');
  v_profile public.profiles;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if v_name is null or char_length(v_name) not between 1 and 50 then
    raise exception 'invalid_display_name' using errcode = '22023', detail = 'display name must be 1..50 characters';
  end if;
  if v_name ~ '[[:cntrl:]]' then
    raise exception 'invalid_display_name' using errcode = '22023';
  end if;
  if v_handle is not null and v_handle !~ '^[a-z0-9_]{3,24}$' then
    raise exception 'invalid_handle' using errcode = '22023', detail = 'handle must be 3..24 letters, numbers, or underscores';
  end if;

  begin
    update public.profiles
    set display_name = v_name,
        handle = coalesce(v_handle, handle)
    where id = v_uid and deleted_at is null
    returning * into v_profile;
    if not found then
      raise exception 'account_unavailable' using errcode = '42501';
    end if;
  exception
    when unique_violation then
      raise exception 'handle_taken' using errcode = '23505';
  end;

  return v_profile;
end;
$$;

revoke execute on function
  public.update_my_drop(uuid, text, text, jsonb, text),
  public.delete_my_drop(uuid),
  public.restore_my_drop(uuid),
  public.my_drops(),
  public.set_my_public_name(text, text)
from public, anon, authenticated;

grant execute on function
  public.update_my_drop(uuid, text, text, jsonb, text),
  public.delete_my_drop(uuid),
  public.restore_my_drop(uuid),
  public.my_drops(),
  public.set_my_public_name(text, text)
to authenticated, service_role;

-- Objects for author-deleted drops older than a day. Undo is ten minutes.
create function private.purge_author_deleted_media()
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  delete from storage.objects o
  using public.drop_media m
  join public.drops d on d.id = m.drop_id
  where o.bucket_id = m.storage_bucket
    and o.name = m.storage_path
    and d.removed_reason = 'author'
    and d.deleted_at is not null
    and d.deleted_at < now() - interval '1 day';
  get diagnostics v_count = row_count;

  delete from public.media_grants g
  where g.expires_at < now();

  return v_count;
end;
$$;

revoke execute on function private.purge_author_deleted_media() from public, anon, authenticated;

select cron.schedule(
  'xhere-purge-deleted-media',
  '17 4 * * *',
  'select private.purge_author_deleted_media()'
);
