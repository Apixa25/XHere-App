-- M7: safety and store compliance (PLAN §4.3, §7 M7; docs/COMPLIANCE.md).
--
-- What M1 already had: report(), block_user()/unblock_user(), the
-- moderation_actions audit table, moderate_drop/resolve_report/suspend_user,
-- report auto-hide, pre-moderation per category, and delete_my_account().
-- This file closes the gaps the M7 screens need:
--
-- 1. Hide a drop for myself (hidden_drops + hide_drop/unhide_drop). Every read
--    RPC goes through private.can_view_drop, so the hide applies everywhere.
-- 2. Lists the settings screen needs: my_blocks(), my_hidden_drops(), and
--    public_profile(handle) so a profile can be reported or blocked from a drop.
-- 3. moderation_queue(): pending drops and open reports for moderators only.
-- 4. The audit log is append-only, and tier/role changes are logged however
--    they happen (app, cron, or a dashboard SQL edit).
-- 5. export_my_data(): one JSON document with the caller's own data.
-- 6. A street-address filter on incident reports (CRIME-OPEN-DATA §3:
--    "an address typed in a report is stripped by the PII filter").

-- ---------------------------------------------------------------------------
-- 1. Hide a drop for myself
-- ---------------------------------------------------------------------------
create table public.hidden_drops (
  user_id uuid not null references public.profiles (id) on delete cascade,
  drop_id uuid not null references public.drops (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, drop_id)
);
create index hidden_drops_drop_idx on public.hidden_drops (drop_id);
comment on table public.hidden_drops is 'Drops a person chose not to see. Private to that person; written by hide_drop().';

alter table public.hidden_drops enable row level security;
revoke all on table public.hidden_drops from anon, authenticated;
grant select, delete on public.hidden_drops to authenticated;

create policy hidden_drops_select_own on public.hidden_drops
  for select to authenticated
  using (user_id = auth.uid());

create policy hidden_drops_delete_own on public.hidden_drops
  for delete to authenticated
  using (user_id = auth.uid());

-- Same rules as M1, plus the viewer's own hidden list. Authors always see their own drops.
create or replace function private.can_view_drop(p_drop public.drops, p_viewer uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select case
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

create function public.hide_drop(p_drop_id uuid)
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
  if exists (select 1 from public.hidden_drops h where h.user_id = v_uid and h.drop_id = p_drop_id) then
    return;
  end if;
  select * into v_drop from public.drops d where d.id = p_drop_id;
  if not found or not private.can_view_drop(v_drop, v_uid) then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;
  if v_drop.author_id = v_uid then
    raise exception 'cannot_hide_own_drop' using errcode = '22023';
  end if;
  insert into public.hidden_drops (user_id, drop_id)
  values (v_uid, v_drop.id)
  on conflict do nothing;
end;
$$;

create function public.unhide_drop(p_drop_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  delete from public.hidden_drops h where h.user_id = auth.uid() and h.drop_id = p_drop_id;
end;
$$;

-- ---------------------------------------------------------------------------
-- 2. Lists for settings, and a public profile lookup
-- ---------------------------------------------------------------------------
create function public.my_hidden_drops()
returns table (drop_id uuid, short_id text, title text, category_slug text, hidden_at timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select h.drop_id, d.short_id, d.title, c.slug, h.created_at
  from public.hidden_drops h
  join public.drops d on d.id = h.drop_id
  join public.categories c on c.id = d.category_id
  where h.user_id = auth.uid()
    and d.status in ('active', 'confirmed')
    and (d.expires_at is null or d.expires_at > now())
  order by h.created_at desc
  limit 500;
$$;

create function public.my_blocks()
returns table (user_id uuid, handle text, display_name text, blocked_at timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select b.blocked_id, p.handle, p.display_name, b.created_at
  from public.user_blocks b
  join public.profiles p on p.id = b.blocked_id
  where b.blocker_id = auth.uid()
  order by b.created_at desc
  limit 1000;
$$;

-- What anyone signed in may know about a handle shown on a drop. No tier, role, or area.
create function public.public_profile(p_handle text)
returns table (id uuid, handle text, display_name text, created_at timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.handle, p.display_name, p.created_at
  from public.profiles p
  where auth.uid() is not null
    and p.handle = lower(btrim(p_handle))
    and p.deleted_at is null;
$$;

-- ---------------------------------------------------------------------------
-- 3. Moderator queue: pending drops (pre-moderation or report auto-hide) and
-- every drop or profile with open reports. Sensitive categories first, then
-- oldest first. Moderators never see their own drops or their own profile here.
-- ---------------------------------------------------------------------------
create function public.moderation_queue(p_limit integer default 50)
returns table (
  kind text,
  target_id uuid,
  short_id text,
  title text,
  body text,
  category_slug text,
  status public.drop_status,
  sensitive boolean,
  subject_id uuid,
  subject_handle text,
  subject_suspended_until timestamptz,
  open_reports integer,
  reasons text[],
  latest_details text,
  report_ids uuid[],
  queued_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 200);
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;

  return query
  with open_reports as (
    select r.target_type as o_type,
           r.target_id as o_id,
           count(distinct r.reporter_id)::integer as o_count,
           array_agg(distinct r.reason::text) as o_reasons,
           (array_agg(r.details order by r.created_at desc) filter (where r.details is not null))[1] as o_details,
           array_agg(r.id order by r.created_at) as o_ids,
           min(r.created_at) as o_first
    from public.reports r
    where r.status = 'open'
    group by r.target_type, r.target_id
  ),
  items as (
    select 'drop'::text as q_kind,
           d.id as q_target,
           d.short_id as q_short,
           d.title as q_title,
           d.body as q_body,
           c.slug as q_category,
           d.status as q_status,
           c.sensitive as q_sensitive,
           d.author_id as q_subject,
           p.handle as q_handle,
           m.suspended_until as q_suspended,
           coalesce(o.o_count, 0) as q_reports,
           coalesce(o.o_reasons, '{}'::text[]) as q_reasons,
           o.o_details as q_details,
           coalesce(o.o_ids, '{}'::uuid[]) as q_report_ids,
           coalesce(o.o_first, d.updated_at) as q_at
    from public.drops d
    join public.categories c on c.id = d.category_id
    left join public.profiles p on p.id = d.author_id
    left join public.user_moderation m on m.user_id = d.author_id
    left join open_reports o on o.o_type = 'drop' and o.o_id = d.id
    where d.status in ('active', 'confirmed', 'pending_review', 'disputed')
      and (d.status = 'pending_review' or o.o_id is not null)
      and d.author_id is distinct from v_uid
      and (d.author_id is null or p.deleted_at is null)
    union all
    select 'profile'::text,
           p.id,
           null::text,
           null::text,
           null::text,
           null::text,
           null::public.drop_status,
           false,
           p.id,
           p.handle,
           m.suspended_until,
           o.o_count,
           o.o_reasons,
           o.o_details,
           o.o_ids,
           o.o_first
    from open_reports o
    join public.profiles p on p.id = o.o_id
    left join public.user_moderation m on m.user_id = p.id
    where o.o_type = 'profile'
      and p.id <> v_uid
      and p.deleted_at is null
  )
  select i.q_kind, i.q_target, i.q_short, i.q_title, i.q_body, i.q_category, i.q_status, i.q_sensitive,
         i.q_subject, i.q_handle, i.q_suspended, i.q_reports, i.q_reasons, i.q_details, i.q_report_ids, i.q_at
  from items i
  order by i.q_sensitive desc, (i.q_status = 'pending_review') desc nulls last, i.q_at asc
  limit v_limit;
end;
$$;

-- ---------------------------------------------------------------------------
-- 4. Audit log: append-only, and privilege changes are always recorded.
-- ---------------------------------------------------------------------------
-- The only permitted update is the foreign key clearing actor_id when the
-- actor's account is purged (on delete set null).
create function private.moderation_actions_append_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE'
     and old.actor_id is not null
     and new.actor_id is null
     and (to_jsonb(new) - 'actor_id') = (to_jsonb(old) - 'actor_id') then
    return new;
  end if;
  raise exception 'audit_log_is_append_only' using errcode = '42501';
end;
$$;

create trigger moderation_actions_append_only before update or delete on public.moderation_actions
  for each row execute function private.moderation_actions_append_only();

-- Tier and role are not client-writable (column grants), so a change comes from
-- the nightly job, a moderator RPC, or someone with database access. All three
-- are logged here. Callers can set xhere.audit_reason for the reason column.
create function private.profiles_audit_privileges()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_reason text := nullif(current_setting('xhere.audit_reason', true), '');
begin
  if new.tier is distinct from old.tier then
    insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
    values (auth.uid(), 'profile', new.id, 'set_tier', v_reason,
            jsonb_build_object('from', old.tier, 'to', new.tier));
  end if;
  if new.role is distinct from old.role then
    insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
    values (auth.uid(), 'profile', new.id, 'set_role', v_reason,
            jsonb_build_object('from', old.role, 'to', new.role));
  end if;
  return null;
end;
$$;

create trigger profiles_audit_privileges after update of tier, role on public.profiles
  for each row execute function private.profiles_audit_privileges();

-- Same promotions as M1. The audit row now comes from the trigger above.
create or replace function private.recompute_tiers()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  perform set_config('xhere.audit_reason', 'automatic promotion', true);
  with stats as (
    select p.id,
           p.tier,
           p.created_at,
           count(d.id) filter (where d.status <> 'removed') as live_drops,
           count(d.id) filter (where d.status <> 'removed' and d.confirm_count >= c.confirmations_to_confirm) as confirmed_drops,
           count(d.id) filter (where d.status = 'removed' and d.updated_at > now() - interval '90 days') as strikes
    from public.profiles p
    left join public.drops d on d.author_id = p.id
    left join public.categories c on c.id = d.category_id
    where p.deleted_at is null and p.tier in ('new', 'regular')
    group by p.id
  ),
  promoted as (
    update public.profiles p
    set tier = case s.tier when 'new' then 'regular'::public.user_tier else 'trusted'::public.user_tier end
    from stats s
    where p.id = s.id
      and s.strikes = 0
      and (
        (s.tier = 'new' and s.created_at <= now() - interval '7 days' and s.live_drops >= 3)
        or (s.tier = 'regular' and s.created_at <= now() - interval '30 days' and s.confirmed_drops >= 10)
      )
    returning p.id
  )
  select count(*)::integer into v_count from promoted;
  perform set_config('xhere.audit_reason', '', true);
  return v_count;
end;
$$;

-- ---------------------------------------------------------------------------
-- 5. Data export: everything this account wrote, as one JSON document.
-- The true location of the caller's own drops is included; it is their data.
-- Reports other people filed about the caller are not, because they would
-- identify the reporters. Moderator identities are not included.
-- ---------------------------------------------------------------------------
create function public.export_my_data()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.profiles;
  v_email text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  select u.email::text into v_email from auth.users u where u.id = v_uid;

  return jsonb_build_object(
    'format', 'xhere-export-v1',
    'exported_at', now(),
    'user_id', v_uid,
    'email', v_email,
    'profile', jsonb_build_object(
      'handle', v_profile.handle,
      'display_name', v_profile.display_name,
      'avatar_path', v_profile.avatar_path,
      'tier', v_profile.tier,
      'role', v_profile.role,
      'points', v_profile.points,
      'home_area', v_profile.home_area,
      'created_at', v_profile.created_at,
      'deleted_at', v_profile.deleted_at
    ),
    'suspension', (
      select jsonb_build_object('suspended_until', m.suspended_until, 'reason', m.reason)
      from public.user_moderation m
      where m.user_id = v_uid
    ),
    'drops', coalesce((
      select jsonb_agg(jsonb_build_object(
          'id', d.id,
          'short_id', d.short_id,
          'category', c.slug,
          'category_version', d.category_version,
          'title', d.title,
          'body', d.body,
          'attributes', d.attributes,
          'tags', to_jsonb(d.tags),
          'lat', extensions.st_y(d.geog::extensions.geometry),
          'lng', extensions.st_x(d.geog::extensions.geometry),
          'display_lat', extensions.st_y(d.display_geog::extensions.geometry),
          'display_lng', extensions.st_x(d.display_geog::extensions.geometry),
          'reveal_radius_m', d.reveal_radius_m,
          'status', d.status,
          'display_anonymous', d.display_anonymous,
          'expires_at', d.expires_at,
          'confirm_count', d.confirm_count,
          'deny_count', d.deny_count,
          'view_count', d.view_count,
          'removed_reason', d.removed_reason,
          'created_at', d.created_at,
          'updated_at', d.updated_at,
          'media', coalesce((
            select jsonb_agg(jsonb_build_object(
                'storage_bucket', dm.storage_bucket,
                'storage_path', dm.storage_path,
                'media_kind', dm.media_kind,
                'mime_type', dm.mime_type,
                'byte_size', dm.byte_size,
                'width', dm.width,
                'height', dm.height,
                'created_at', dm.created_at
              ) order by dm.sort, dm.created_at)
            from public.drop_media dm
            where dm.drop_id = d.id
          ), '[]'::jsonb)
        ) order by d.created_at)
      from public.drops d
      join public.categories c on c.id = d.category_id
      where d.author_id = v_uid
    ), '[]'::jsonb),
    'confirmations', coalesce((
      select jsonb_agg(jsonb_build_object(
          'drop_id', cf.drop_id,
          'short_id', d.short_id,
          'verdict', cf.verdict,
          'note', cf.note,
          'distance_m', cf.distance_m,
          'created_at', cf.created_at,
          'updated_at', cf.updated_at
        ) order by cf.created_at)
      from public.confirmations cf
      join public.drops d on d.id = cf.drop_id
      where cf.user_id = v_uid
    ), '[]'::jsonb),
    'reports_filed', coalesce((
      select jsonb_agg(jsonb_build_object(
          'id', r.id,
          'target_type', r.target_type,
          'target_id', r.target_id,
          'reason', r.reason,
          'details', r.details,
          'status', r.status,
          'created_at', r.created_at,
          'resolved_at', r.resolved_at
        ) order by r.created_at)
      from public.reports r
      where r.reporter_id = v_uid
    ), '[]'::jsonb),
    'blocks', coalesce((
      select jsonb_agg(jsonb_build_object(
          'user_id', b.blocked_id,
          'handle', p.handle,
          'created_at', b.created_at
        ) order by b.created_at)
      from public.user_blocks b
      join public.profiles p on p.id = b.blocked_id
      where b.blocker_id = v_uid
    ), '[]'::jsonb),
    'hidden_drops', coalesce((
      select jsonb_agg(jsonb_build_object('drop_id', h.drop_id, 'created_at', h.created_at) order by h.created_at)
      from public.hidden_drops h
      where h.user_id = v_uid
    ), '[]'::jsonb),
    'category_suggestions', coalesce((
      select jsonb_agg(jsonb_build_object(
          'inputs', s.inputs,
          'suggested', to_jsonb(s.suggested),
          'chosen', c.slug,
          'created_at', s.created_at
        ) order by s.created_at)
      from public.category_suggestion_log s
      left join public.categories c on c.id = s.chosen_category_id
      where s.user_id = v_uid
    ), '[]'::jsonb),
    'moderation_of_my_content', coalesce((
      select jsonb_agg(jsonb_build_object(
          'action', a.action,
          'target_type', a.target_type,
          'target_id', a.target_id,
          'reason', a.reason,
          'created_at', a.created_at
        ) order by a.created_at)
      from public.moderation_actions a
      where (a.target_type = 'profile' and a.target_id = v_uid)
         or (a.target_type = 'drop' and exists (
              select 1 from public.drops d where d.id = a.target_id and d.author_id = v_uid))
    ), '[]'::jsonb)
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- 6. Street addresses stay out of incident reports. The map already shows the
-- approximate area (~100 m), and a house number would pin a person's home.
-- ---------------------------------------------------------------------------
insert into public.content_filters (slug, description, pattern, message) values
  ('street_address',
   'A house number followed by a street name (123 Main St, 45 W 3rd Street). Incident reports describe an area, not a home.',
   '\m[0-9]{1,6}[a-z]? +([a-z0-9.''-]+ +){0,2}(street|st|avenue|ave|road|rd|boulevard|blvd|parkway|pkwy|highway|hwy)\M',
   'Leave out street addresses. The map already shows the approximate area.');

update public.categories
set content_filters = array_append(content_filters, 'street_address')
where slug = 'incident_report' and not ('street_address' = any (content_filters));

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
revoke execute on function
  private.moderation_actions_append_only(),
  private.profiles_audit_privileges()
from public;

revoke execute on function
  public.hide_drop(uuid),
  public.unhide_drop(uuid),
  public.my_hidden_drops(),
  public.my_blocks(),
  public.public_profile(text),
  public.moderation_queue(integer),
  public.export_my_data()
from public, anon, authenticated;

grant execute on function
  public.hide_drop(uuid),
  public.unhide_drop(uuid),
  public.my_hidden_drops(),
  public.my_blocks(),
  public.public_profile(text),
  public.moderation_queue(integer),
  public.export_my_data()
to authenticated, service_role;
