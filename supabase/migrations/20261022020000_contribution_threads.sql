-- Threads, several files, vote counters, and place hiddenness (PLAN §0, PLAN §3, PLAN §4).
--
-- A contribution may answer another one (parent_id). It may carry up to six
-- photos and videos. edited_at is set only when the words change.
-- contributions_for pages 20 roots at a time: newest (default), oldest, or top.
-- The search text is a bound argument. It is never concatenated into SQL.
--
-- Votes stay one row per person. The same vote again removes it. The other
-- direction replaces it. A trigger keeps up_count, down_count, and score
-- (up minus down) on the row. Five downvotes, or a net of -5, hides the row
-- for the moderation queue. A downvote received is a reputation_ledger row
-- when that table is present.
--
-- A hidden, deleted, or still-sealed place hides its contributions too.
-- contribution_cards is the public face: no author id. Anonymous names are
-- the word Anonymous. Moderators still use contribution_author.

alter table public.contributions
  add column parent_id uuid references public.contributions (id) on delete cascade,
  add column edited_at timestamptz,
  add column up_count integer not null default 0 check (up_count >= 0),
  add column down_count integer not null default 0 check (down_count >= 0),
  add column score integer not null default 0;

comment on column public.contributions.parent_id is
  'The contribution this one answers. Null is a top-level post on the place.';
comment on column public.contributions.edited_at is
  'Set when the author changes the words. Vote counters do not set it.';
comment on column public.contributions.score is
  'up_count minus down_count. Maintained by the vote trigger.';

create index contributions_parent_idx
  on public.contributions (parent_id, created_at)
  where parent_id is not null;

do $$
declare
  v_name text;
begin
  select con.conname into v_name
  from pg_constraint con
  join pg_class rel on rel.oid = con.conrelid
  join pg_namespace nsp on nsp.oid = rel.relnamespace
  where nsp.nspname = 'public'
    and rel.relname = 'contributions'
    and con.contype = 'c'
    and pg_get_constraintdef(con.oid) like '%photo%';
  if v_name is not null then
    execute format('alter table public.contributions drop constraint %I', v_name);
  end if;
end;
$$;

alter table public.contributions
  add constraint contributions_kind_check
  check (kind in ('text', 'photo', 'video', 'media'));

alter table public.contribution_media
  add column ordinal smallint not null default 0;

alter table public.contribution_media
  drop constraint contribution_media_contribution_id_key;

comment on table public.contribution_media is
  'Files on one contribution. Several photos and videos are allowed. The path is two random ids, never the author id.';

update public.contributions c
set up_count = coalesce(t.up_n, 0),
    down_count = coalesce(t.down_n, 0),
    score = coalesce(t.up_n, 0) - coalesce(t.down_n, 0)
from (
  select v.contribution_id,
         count(*) filter (where v.value = 1)::integer as up_n,
         count(*) filter (where v.value = -1)::integer as down_n
  from public.contribution_votes v
  group by v.contribution_id
) t
where c.id = t.contribution_id;

-- Downvotes received. The kind list grows only when the ledger exists.
do $$
declare
  v_name text;
begin
  if to_regclass('public.reputation_ledger') is null then
    return;
  end if;
  select con.conname into v_name
  from pg_constraint con
  join pg_class rel on rel.oid = con.conrelid
  join pg_namespace nsp on nsp.oid = rel.relnamespace
  where nsp.nspname = 'public'
    and rel.relname = 'reputation_ledger'
    and con.contype = 'c'
    and pg_get_constraintdef(con.oid) like '%quest%';
  if v_name is not null then
    execute format('alter table public.reputation_ledger drop constraint %I', v_name);
    execute $sql$
      alter table public.reputation_ledger
      add constraint reputation_ledger_kind_check
      check (kind in ('confirm', 'view', 'deny', 'moderation', 'walk', 'quest', 'downvote'))
    $sql$;
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Place hiddenness. A sealed capsule, a deleted drop, or a drop this person
-- hid takes the contributions with it.
-- ---------------------------------------------------------------------------
create or replace function private.contribution_target_visible(p_kind text, p_id uuid, p_viewer uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_drop public.drops;
  v_thing public.things;
  v_civic public.civic_items;
begin
  if p_kind in ('drop', 'marker') then
    select * into v_drop from public.drops d where d.id = p_id;
    if not found then
      return false;
    end if;
    if p_kind = 'marker' and not exists (
      select 1 from public.categories c
      where c.id = v_drop.category_id and c.slug = 'historical_marker'
    ) then
      return false;
    end if;
    if not private.can_view_drop(v_drop, p_viewer) then
      return false;
    end if;
    if v_drop.capsule_mode is not null
       and v_drop.capsule_opened_at is null
       and v_drop.author_id is distinct from p_viewer then
      return false;
    end if;
    return true;
  elsif p_kind = 'open_data' then
    select d.* into v_drop
    from public.open_data_items i
    join public.drops d on d.id = i.drop_id
    where i.id = p_id;
    if not found or not private.can_view_drop(v_drop, p_viewer) then
      return false;
    end if;
    if v_drop.capsule_mode is not null
       and v_drop.capsule_opened_at is null
       and v_drop.author_id is distinct from p_viewer then
      return false;
    end if;
    return true;
  elsif p_kind = 'thing' then
    select * into v_thing from public.things t where t.id = p_id;
    if not found or v_thing.status <> 'active' then
      return false;
    end if;
    if v_thing.visibility = 'public' then
      return true;
    end if;
    return p_viewer is not null and v_thing.owner_id = p_viewer;
  elsif p_kind = 'civic' then
    select * into v_civic from public.civic_items i where i.id = p_id;
    if not found or v_civic.status <> 'active' then
      return false;
    end if;
    if v_civic.expires_at is not null and v_civic.expires_at <= now() then
      return false;
    end if;
    return true;
  end if;
  return false;
end;
$$;

create or replace function private.contribution_readable_id(p_id uuid, p_viewer uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_row public.contributions;
begin
  select * into v_row from public.contributions c where c.id = p_id;
  if not found or v_row.status = 'removed' then
    return false;
  end if;
  if not private.contribution_target_visible(v_row.target_kind, v_row.target_id, p_viewer) then
    return false;
  end if;
  if p_viewer is not null and v_row.author_id = p_viewer then
    return v_row.status in ('active', 'pending_review', 'hidden');
  end if;
  if v_row.status <> 'active' then
    return false;
  end if;
  if exists (
    select 1 from public.profiles p
    where p.id = v_row.author_id and p.deleted_at is not null
  ) then
    return false;
  end if;
  if p_viewer is not null and private.is_blocked_between(p_viewer, v_row.author_id) then
    return false;
  end if;
  return true;
end;
$$;

create or replace function private.contribution_votes_guard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_author uuid;
  v_kind text;
  v_status text;
begin
  select c.author_id, c.kind, c.status into v_author, v_kind, v_status
  from public.contributions c
  where c.id = new.contribution_id;
  if v_author is null then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  if v_author = new.voter_id then
    raise exception 'cannot_vote_self' using errcode = '22023';
  end if;
  if v_kind not in ('photo', 'video', 'media') then
    raise exception 'votes_are_for_media' using errcode = '22023';
  end if;
  if v_status <> 'active' then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  return new;
end;
$$;

create function private.contribution_votes_tally()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id uuid := coalesce(new.contribution_id, old.contribution_id);
  v_voter uuid := coalesce(new.voter_id, old.voter_id);
  v_up integer := 0;
  v_down integer := 0;
  v_author uuid;
  v_status text;
  v_target_kind text;
  v_target uuid;
  v_drop uuid;
  v_key text;
begin
  select count(*) filter (where v.value = 1)::integer,
         count(*) filter (where v.value = -1)::integer
    into v_up, v_down
  from public.contribution_votes v
  where v.contribution_id = v_id;

  update public.contributions c
  set up_count = v_up,
      down_count = v_down,
      score = v_up - v_down
  where c.id = v_id
  returning c.author_id, c.status, c.target_kind, c.target_id
    into v_author, v_status, v_target_kind, v_target;

  if v_status = 'active' and (v_down >= 5 or (v_up - v_down) <= -5) then
    update public.contributions set status = 'hidden' where id = v_id;
    insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
    values (
      null,
      'contribution',
      v_id,
      'auto_hide',
      'downvote threshold',
      jsonb_build_object('up', v_up, 'down', v_down)
    );
  end if;

  if to_regclass('public.reputation_ledger') is not null and v_author is not null and v_voter is not null then
    v_key := 'cdown:' || v_id::text || ':' || v_voter::text;
    v_drop := case when v_target_kind in ('drop', 'marker') then v_target else null end;
    if tg_op = 'DELETE' or (tg_op = 'UPDATE' and old.value = -1 and new.value is distinct from -1) then
      delete from public.reputation_ledger where source_key = 'cdown:' || v_id::text || ':' || old.voter_id::text;
    end if;
    if (tg_op = 'INSERT' and new.value = -1)
       or (tg_op = 'UPDATE' and new.value = -1 and old.value is distinct from -1) then
      insert into public.reputation_ledger (user_id, actor_id, drop_id, kind, source_key, points)
      values (v_author, v_voter, v_drop, 'downvote', v_key, -1)
      on conflict (source_key) do nothing;
    end if;
  end if;
  return null;
end;
$$;

comment on function private.contribution_votes_tally() is
  'Keeps up, down, and score aligned with the vote rows. Hides a post at 5 downs or a net of -5, and records a downvote in the reputation ledger when that table exists.';

revoke all on function private.contribution_votes_tally() from public, anon, authenticated;
grant execute on function private.contribution_votes_tally() to authenticated;

create trigger contribution_votes_tally
  after insert or update or delete on public.contribution_votes
  for each row execute function private.contribution_votes_tally();

-- ---------------------------------------------------------------------------
-- Public list. Anonymous rows say Anonymous and never include an author id.
-- Keyword search uses the bound p_query argument only.
-- ---------------------------------------------------------------------------
drop function public.contribution_place(text, uuid);
drop function public.contributions_for(text, uuid, text);
drop function public.create_contribution(text, uuid, text, text, boolean, jsonb);

create function public.contribution_place(p_target_kind text, p_target_id uuid)
returns table (anonymous_allowed boolean, visible boolean, share_url text)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_short text;
begin
  if not private.contribution_target_visible(p_target_kind, p_target_id, auth.uid())
     and not exists (
       select 1 from public.drops d where p_target_kind in ('drop', 'marker') and d.id = p_target_id
     )
     and not exists (
       select 1 from public.open_data_items i where p_target_kind = 'open_data' and i.id = p_target_id
     )
     and not exists (
       select 1 from public.things t where p_target_kind = 'thing' and t.id = p_target_id
     )
     and not exists (
       select 1 from public.civic_items i where p_target_kind = 'civic' and i.id = p_target_id
     ) then
    raise exception 'place_not_found' using errcode = 'P0002';
  end if;
  if p_target_kind in ('drop', 'marker') then
    select d.short_id into v_short from public.drops d where d.id = p_target_id;
  elsif p_target_kind = 'open_data' then
    select d.short_id into v_short
    from public.open_data_items i
    join public.drops d on d.id = i.drop_id
    where i.id = p_target_id;
  end if;
  anonymous_allowed := private.anonymous_display_allowed(p_target_kind, p_target_id);
  visible := private.contribution_target_visible(p_target_kind, p_target_id, auth.uid());
  share_url := case when v_short is null then null else 'https://xhere.world/d/' || v_short end;
  return next;
end;
$$;

comment on function public.contribution_place(text, uuid) is
  'Whether this place can show an anonymous contribution, and the public share link when the place is a drop.';

create function public.contributions_for(
  p_target_kind text,
  p_target_id uuid,
  p_sort text default 'newest',
  p_limit integer default 20,
  p_offset integer default 0,
  p_query text default null
)
returns table (
  id uuid,
  target_kind text,
  target_id uuid,
  kind text,
  body text,
  created_at timestamptz,
  live boolean,
  up_count integer,
  down_count integer,
  score double precision,
  my_vote smallint,
  author_handle text,
  anonymous boolean,
  is_mine boolean,
  collapsed boolean,
  media jsonb,
  parent_id uuid,
  edited_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_sort text := coalesce(p_sort, 'newest');
  v_limit integer := least(greatest(coalesce(p_limit, 20), 1), 20);
  v_offset integer := greatest(coalesce(p_offset, 0), 0);
  v_query text := nullif(lower(btrim(coalesce(p_query, ''))), '');
begin
  if v_sort not in ('newest', 'oldest', 'top') then
    raise exception 'invalid_sort' using errcode = '22023';
  end if;
  if v_query is not null and char_length(v_query) > 80 then
    raise exception 'invalid_query' using errcode = '22023';
  end if;
  if p_target_kind is null or p_target_id is null then
    raise exception 'place_not_found' using errcode = 'P0002';
  end if;

  return query
  with recursive visible as (
    select c.*
    from public.contributions c
    where c.target_kind = p_target_kind
      and c.target_id = p_target_id
      and private.contribution_readable_id(c.id, v_uid)
      and (
        v_query is null
        or position(v_query in lower(coalesce(c.body, ''))) > 0
      )
  ),
  roots as (
    select v.id,
           row_number() over (
             order by
               case when v_sort = 'top' then private.wilson_lower(v.up_count, v.down_count) end desc nulls last,
               case when v_sort = 'oldest' then v.created_at end asc nulls last,
               case when v_sort <> 'oldest' then v.created_at end desc nulls last,
               v.id
           ) as n
    from visible v
    where v.parent_id is null
       or not exists (select 1 from visible parent where parent.id = v.parent_id)
  ),
  page as (
    select r.id
    from roots r
    where r.n > v_offset and r.n <= v_offset + v_limit
  ),
  tree as (
    select p.id, p.id as root_id
    from page p
    union all
    select child.id, t.root_id
    from visible child
    join tree t on child.parent_id = t.id
  )
  select c.id,
         c.target_kind,
         c.target_id,
         c.kind,
         c.body,
         c.created_at,
         c.created_at > now() - interval '24 hours',
         c.up_count,
         c.down_count,
         private.wilson_lower(c.up_count, c.down_count),
         mine.value,
         case when c.display_anonymous then 'Anonymous' else p.handle end,
         c.display_anonymous,
         v_uid is not null and c.author_id = v_uid,
         private.contribution_collapsed(c.up_count, c.down_count),
         coalesce((
           select jsonb_agg(jsonb_build_object(
             'id', m.id,
             'storage_bucket', m.storage_bucket,
             'storage_path', m.storage_path,
             'thumb_path', m.thumb_path,
             'mime_type', m.mime_type,
             'byte_size', m.byte_size,
             'width', m.width,
             'height', m.height,
             'duration_s', m.duration_s,
             'alt_text', m.alt_text
           ) order by m.ordinal, m.id)
           from public.contribution_media m
           where m.contribution_id = c.id
         ), '[]'::jsonb),
         c.parent_id,
         c.edited_at
  from public.contributions c
  join tree t on t.id = c.id
  join roots r on r.id = t.root_id
  left join public.profiles p on p.id = c.author_id and not c.display_anonymous
  left join public.contribution_votes mine
    on mine.contribution_id = c.id and v_uid is not null and mine.voter_id = v_uid
  order by r.n, (c.parent_id is not null), c.created_at, c.id;
end;
$$;

comment on function public.contributions_for(text, uuid, text, integer, integer, text) is
  'One page of community posts. Newest, oldest, or top. Anonymous handles are the word Anonymous. p_query is a bound search string, not SQL.';

create view public.contribution_cards
with (security_invoker = false) as
select c.id,
       c.target_kind,
       c.target_id,
       c.parent_id,
       c.kind,
       c.body,
       c.created_at,
       c.edited_at,
       c.up_count,
       c.down_count,
       c.score,
       case when c.display_anonymous then 'Anonymous' else p.handle end as author_handle,
       c.display_anonymous as anonymous,
       (select auth.uid()) is not null and c.author_id = (select auth.uid()) as is_mine
from public.contributions c
left join public.profiles p on p.id = c.author_id and not c.display_anonymous
where private.contribution_readable_id(c.id, (select auth.uid()));

comment on view public.contribution_cards is
  'Public rows for a contribution. author_id is not a column. Anonymous posts show Anonymous. Moderators use contribution_author.';

revoke all on table public.contribution_cards from public;
grant select on table public.contribution_cards to anon, authenticated;

-- ---------------------------------------------------------------------------
-- Writes
-- ---------------------------------------------------------------------------
create function public.create_contribution(
  p_target_kind text,
  p_target_id uuid,
  p_kind text,
  p_body text default null,
  p_display_anonymous boolean default false,
  p_media jsonb default '[]'::jsonb,
  p_parent_id uuid default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.profiles;
  v_parent public.contributions;
  v_limit integer;
  v_recent integer;
  v_depth integer;
  v_body text := nullif(btrim(p_body), '');
  v_mode public.moderation_mode := 'post'::public.moderation_mode;
  v_status text := 'active';
  v_id uuid := gen_random_uuid();
  v_store_kind text;
  v_item jsonb;
  v_mime text;
  v_ext text;
  v_media_id uuid;
  v_path text;
  v_thumb text;
  v_bytes integer;
  v_width integer;
  v_height integer;
  v_duration numeric;
  v_alt text;
  v_thumb_bytes integer;
  v_ordinal smallint := 0;
  v_images integer := 0;
  v_videos integer := 0;
  v_slots jsonb := '[]'::jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if exists (
    select 1 from public.user_moderation m
    where m.user_id = v_uid and m.suspended_until > now()
  ) then
    raise exception 'account_suspended' using errcode = '42501';
  end if;
  if not private.contribution_target_visible(p_target_kind, p_target_id, v_uid) then
    raise exception 'place_not_found' using errcode = 'P0002';
  end if;
  if p_parent_id is not null then
    select * into v_parent from public.contributions c where c.id = p_parent_id;
    if not found
       or v_parent.target_kind <> p_target_kind
       or v_parent.target_id <> p_target_id
       or not private.contribution_readable_id(p_parent_id, v_uid) then
      raise exception 'contribution_not_found' using errcode = 'P0002';
    end if;
    with recursive chain as (
      select c.id, c.parent_id, 1 as depth
      from public.contributions c
      where c.id = p_parent_id
      union all
      select parent.id, parent.parent_id, chain.depth + 1
      from public.contributions parent
      join chain on parent.id = chain.parent_id
      where chain.depth < 8
    )
    select max(chain.depth) into v_depth from chain;
    if coalesce(v_depth, 1) >= 8 then
      raise exception 'thread_too_deep' using errcode = '22023';
    end if;
  end if;
  if coalesce(p_display_anonymous, false) and not private.anonymous_display_allowed(p_target_kind, p_target_id) then
    raise exception 'anonymous_not_allowed' using errcode = '22023';
  end if;
  if p_kind not in ('text', 'photo', 'video', 'media') then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_kind = 'text' and (v_body is null or char_length(v_body) > 500) then
    raise exception 'invalid_contribution' using errcode = '22023', detail = 'text is 1..500 characters';
  end if;
  if p_kind <> 'text' and v_body is not null and char_length(v_body) > 500 then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_media is null or jsonb_typeof(p_media) <> 'array' or jsonb_array_length(p_media) > 6 then
    raise exception 'invalid_contribution' using errcode = '22023', detail = 'at most 6 files';
  end if;
  if p_kind = 'text' and jsonb_array_length(p_media) <> 0 then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_kind <> 'text' and jsonb_array_length(p_media) < 1 then
    raise exception 'invalid_contribution' using errcode = '22023', detail = 'a photo or video needs a file';
  end if;

  for v_item in select e.value from jsonb_array_elements(p_media) as e(value)
  loop
    v_mime := v_item ->> 'mime_type';
    if v_mime like 'image/%' then
      v_images := v_images + 1;
    elsif v_mime like 'video/%' then
      v_videos := v_videos + 1;
    end if;
  end loop;
  if p_kind = 'photo' and v_videos > 0 then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_kind = 'video' and v_images > 0 then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_kind = 'text' then
    v_store_kind := 'text';
  elsif v_images > 0 and v_videos > 0 then
    v_store_kind := 'media';
  elsif v_videos > 0 then
    v_store_kind := 'video';
  else
    v_store_kind := 'photo';
  end if;

  select tl.contributions_per_day into v_limit
  from public.tier_limits tl
  where tl.tier = v_profile.tier;
  select count(*) into v_recent
  from public.contributions c
  where c.author_id = v_uid and c.created_at > now() - interval '1 day';
  if v_recent >= coalesce(v_limit, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s contributions per day', v_profile.tier, coalesce(v_limit, 0));
  end if;

  if p_target_kind in ('drop', 'marker', 'open_data') then
    select c.moderation_mode into v_mode
    from public.drops d
    join public.categories c on c.id = d.category_id
    left join public.open_data_items i on i.drop_id = d.id
    where (p_target_kind in ('drop', 'marker') and d.id = p_target_id)
       or (p_target_kind = 'open_data' and i.id = p_target_id);
  elsif p_target_kind = 'thing' then
    select coalesce(c.moderation_mode, 'post'::public.moderation_mode) into v_mode
    from public.things t
    left join public.categories c on c.slug = t.category_slug
    where t.id = p_target_id;
  end if;
  if v_mode = 'pre' or (v_mode = 'pre_for_new' and v_profile.tier = 'new') then
    v_status := 'pending_review';
  end if;

  insert into public.contributions (
    id, target_kind, target_id, parent_id, author_id, display_anonymous, kind, body, status
  ) values (
    v_id, p_target_kind, p_target_id, p_parent_id, v_uid,
    coalesce(p_display_anonymous, false), v_store_kind, v_body, v_status
  );

  if v_store_kind <> 'text' then
    for v_item in select e.value from jsonb_array_elements(p_media) as e(value)
    loop
      v_ordinal := v_ordinal + 1;
      v_mime := v_item ->> 'mime_type';
      v_ext := private.contribution_extension(v_mime);
      v_bytes := (v_item ->> 'byte_size')::integer;
      v_width := nullif(v_item ->> 'width', '')::integer;
      v_height := nullif(v_item ->> 'height', '')::integer;
      v_duration := nullif(v_item ->> 'duration_s', '')::numeric;
      v_alt := nullif(btrim(v_item ->> 'alt_text'), '');
      v_thumb_bytes := nullif(v_item ->> 'thumb_byte_size', '')::integer;
      if v_ext is null or v_bytes is null then
        raise exception 'invalid_contribution' using errcode = '22023';
      end if;
      if v_mime like 'image/%' and v_bytes > 512000 then
        raise exception 'invalid_contribution' using errcode = '22023', detail = 'photos are at most 500 KB';
      end if;
      if v_mime like 'video/%' and (v_bytes > 8388608 or v_duration is null or v_duration > 30) then
        raise exception 'invalid_contribution' using errcode = '22023', detail = 'videos are at most 30 seconds and 8 MB';
      end if;
      if v_mime like 'video/%' and (v_width is null or v_height is null or greatest(v_width, v_height) > 1280) then
        raise exception 'invalid_contribution' using errcode = '22023', detail = 'videos are at most 720p';
      end if;
      if v_mime like 'image/%' and v_width is not null and v_height is not null and greatest(v_width, v_height) > 1600 then
        raise exception 'invalid_contribution' using errcode = '22023', detail = 'photos are at most 1600 px';
      end if;
      if v_alt is not null and char_length(v_alt) > 300 then
        raise exception 'invalid_contribution' using errcode = '22023';
      end if;
      v_media_id := gen_random_uuid();
      v_path := 'c/' || v_id::text || '/' || v_media_id::text || '.' || v_ext;
      if v_mime like 'video/%' then
        if v_thumb_bytes is null or v_thumb_bytes < 1 or v_thumb_bytes > 512000 then
          raise exception 'invalid_contribution' using errcode = '22023', detail = 'a video needs a thumbnail';
        end if;
        v_thumb := 'c/' || v_id::text || '/' || v_media_id::text || '.thumb.jpg';
      else
        v_thumb := null;
      end if;
      if position(v_uid::text in v_path) > 0 or (v_thumb is not null and position(v_uid::text in v_thumb) > 0) then
        raise exception 'invalid_contribution' using errcode = '22023';
      end if;
      insert into public.contribution_media (
        id, contribution_id, storage_path, thumb_path, mime_type, byte_size, width, height, duration_s, alt_text, ordinal
      ) values (
        v_media_id, v_id, v_path, v_thumb, v_mime, v_bytes, v_width, v_height, v_duration, v_alt, v_ordinal
      );
      v_slots := v_slots || jsonb_build_array(jsonb_build_object(
        'id', v_media_id,
        'storage_bucket', 'contribution-media',
        'storage_path', v_path,
        'thumb_path', v_thumb,
        'mime_type', v_mime
      ));
    end loop;
  end if;

  return jsonb_build_object(
    'id', v_id,
    'status', v_status,
    'anonymous', coalesce(p_display_anonymous, false),
    'media', v_slots
  );
end;
$$;

comment on function public.create_contribution(text, uuid, text, text, boolean, jsonb, uuid) is
  'Adds a contribution, optionally as a reply, and reserves non-identifying storage paths. The return value has no author id.';

create function public.edit_contribution(p_id uuid, p_body text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_row public.contributions;
  v_body text := nullif(btrim(coalesce(p_body, '')), '');
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_row from public.contributions c where c.id = p_id and c.author_id = v_uid and c.status <> 'removed';
  if not found then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  if v_row.kind = 'text' and (v_body is null or char_length(v_body) > 500) then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if v_body is not null and char_length(v_body) > 500 then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  update public.contributions
  set body = v_body, edited_at = now()
  where id = p_id;
end;
$$;

comment on function public.edit_contribution(uuid, text) is
  'The author changes the words. edited_at is the marker. The author id stays off the return.';

create or replace function public.cast_contribution_vote(p_id uuid, p_value integer)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_recent integer;
  v_current smallint;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_value is null or p_value not in (-1, 0, 1) then
    raise exception 'invalid_vote' using errcode = '22023';
  end if;
  if not private.contribution_readable_id(p_id, v_uid) then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  select v.value into v_current
  from public.contribution_votes v
  where v.contribution_id = p_id and v.voter_id = v_uid;
  if p_value = 0 or v_current = p_value::smallint then
    delete from public.contribution_votes v
    where v.contribution_id = p_id and v.voter_id = v_uid;
    return;
  end if;
  if v_current is null then
    select count(*) into v_recent
    from public.contribution_votes v
    where v.voter_id = v_uid and v.created_at > now() - interval '1 hour';
    if v_recent >= 120 then
      raise exception 'rate_limited' using errcode = 'PT429',
        detail = '120 new votes per hour';
    end if;
  end if;
  insert into public.contribution_votes (contribution_id, voter_id, value)
  values (p_id, v_uid, p_value::smallint)
  on conflict (contribution_id, voter_id) do update
    set value = excluded.value, updated_at = now();
end;
$$;

comment on function public.cast_contribution_vote(uuid, integer) is
  'One vote per person. Repeating the same vote removes it. The other direction switches it. 0 removes it. The author cannot vote.';

create or replace function public.live_places_in_view(
  p_min_lng double precision,
  p_min_lat double precision,
  p_max_lng double precision,
  p_max_lat double precision
)
returns table (
  target_kind text,
  target_id uuid,
  lat double precision,
  lng double precision
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_envelope extensions.geography;
begin
  perform private.make_point(p_min_lat, p_min_lng);
  perform private.make_point(p_max_lat, p_max_lng);
  if p_min_lng >= p_max_lng or p_min_lat >= p_max_lat then
    raise exception 'invalid_bbox' using errcode = '22023';
  end if;
  v_envelope := extensions.st_makeenvelope(p_min_lng, p_min_lat, p_max_lng, p_max_lat, 4326)::extensions.geography;

  return query
  with live as (
    select c.target_kind as k,
           c.target_id as i,
           private.contribution_point(c.target_kind, c.target_id) as geog
    from public.contributions c
    where c.status = 'active'
      and c.kind in ('photo', 'video', 'media')
      and c.created_at > now() - interval '24 hours'
      and private.contribution_readable_id(c.id, v_uid)
  )
  select distinct l.k, l.i,
         extensions.st_y(l.geog::extensions.geometry),
         extensions.st_x(l.geog::extensions.geometry)
  from live l
  where l.geog is not null
    and extensions.st_intersects(l.geog, v_envelope)
  limit 500;
end;
$$;

revoke all on function public.contribution_place(text, uuid) from public;
revoke all on function public.contributions_for(text, uuid, text, integer, integer, text) from public;
revoke all on function public.create_contribution(text, uuid, text, text, boolean, jsonb, uuid) from public;
revoke all on function public.edit_contribution(uuid, text) from public;

grant execute on function public.contribution_place(text, uuid) to anon, authenticated;
grant execute on function public.contributions_for(text, uuid, text, integer, integer, text) to anon, authenticated;
grant execute on function public.create_contribution(text, uuid, text, text, boolean, jsonb, uuid) to authenticated;
grant execute on function public.edit_contribution(uuid, text) to authenticated;

-- New enum value. report() learns 'media' in the next migration.
alter type public.report_target add value if not exists 'media';
