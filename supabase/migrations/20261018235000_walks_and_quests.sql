-- Walks and a starter quest (research recommendation #4, PLAN §3 quests, M14 pulled forward).
--
-- XHere is global. A walk is an ordered route of stops for one region. Each stop
-- names a place the way the open-data snapshot does (source_slug, external_id).
-- It does not copy history. The Jamestown route is a seed, not this migration.
-- A quest with a null region_id is for every region. The starter quest is that.
--
-- Progress is server-side. A check-in uses the caller's point the same way a
-- reveal does (distance to display_geog <= reveal_radius_m) and then discards
-- the point. Rows store which drop or stop, never a location trail.
--
-- Client RPCs are security definer in public, with an empty search_path, the
-- same shape as the other RPCs PostgREST exposes. Helpers stay in private.

-- ---------------------------------------------------------------------------
-- Catalog. Written by migrations and seeds, not by clients.
-- ---------------------------------------------------------------------------
create table public.walks (
  id uuid primary key default gen_random_uuid(),
  region_id text not null check (char_length(region_id) between 1 and 80),
  slug text not null check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  title text not null check (char_length(title) between 1 and 120),
  description text not null check (char_length(description) between 1 and 600),
  distance_m integer not null check (distance_m between 1 and 200000),
  duration_min integer not null check (duration_min between 1 and 1440),
  min_lng double precision not null,
  min_lat double precision not null,
  max_lng double precision not null,
  max_lat double precision not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (region_id, slug),
  check (min_lng < max_lng and min_lat < max_lat),
  check (min_lat between -90 and 90 and max_lat between -90 and 90),
  check (min_lng between -180 and 180 and max_lng between -180 and 180)
);
comment on table public.walks is
  'An ordered walk for one region. The box is how the map decides the walk is here. Distance is the straight line between stops, not a routed path.';

create table public.walk_stops (
  walk_id uuid not null references public.walks (id) on delete cascade,
  position integer not null check (position between 1 and 100),
  source_slug text not null check (char_length(source_slug) between 1 and 80),
  external_id text not null check (char_length(external_id) between 1 and 200),
  primary key (walk_id, position),
  unique (walk_id, source_slug, external_id)
);
comment on table public.walk_stops is
  'One stop, in order. It names an imported place by source and external id. The drop is resolved when the walk is read.';

create index walk_stops_place_idx on public.walk_stops (source_slug, external_id);
create index walks_region_idx on public.walks (region_id);

-- ---------------------------------------------------------------------------
-- Progress. No coordinates. Writes go through the RPCs.
-- ---------------------------------------------------------------------------
create table public.walk_progress (
  user_id uuid not null references public.profiles (id) on delete cascade,
  walk_id uuid not null references public.walks (id) on delete cascade,
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  primary key (user_id, walk_id)
);
create index walk_progress_walk_idx on public.walk_progress (walk_id);
comment on table public.walk_progress is
  'The caller started this walk. completed_at is set when every visible stop has been reached. No path is stored.';

create table public.walk_stop_reaches (
  user_id uuid not null references public.profiles (id) on delete cascade,
  walk_id uuid not null,
  position integer not null,
  reached_at timestamptz not null default now(),
  primary key (user_id, walk_id, position),
  foreign key (walk_id, position) references public.walk_stops (walk_id, position) on delete cascade
);
create index walk_stop_reaches_stop_idx on public.walk_stop_reaches (walk_id, position);
comment on table public.walk_stop_reaches is
  'A stop the caller stood inside the reveal radius of. The point they sent is not kept.';

create table public.place_visits (
  user_id uuid not null references public.profiles (id) on delete cascade,
  drop_id uuid not null references public.drops (id) on delete cascade,
  visited_at timestamptz not null default now(),
  primary key (user_id, drop_id)
);
create index place_visits_drop_idx on public.place_visits (drop_id);
comment on table public.place_visits is
  'A place the caller was inside the reveal radius of, for quests. One row per person per drop. Not a location trail.';

create table public.quests (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  region_id text,
  title text not null check (char_length(title) between 1 and 120),
  description text not null check (char_length(description) between 1 and 600),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  check (region_id is null or char_length(region_id) between 1 and 80)
);
comment on table public.quests is
  'A checklist any region can offer. region_id null means every region. The starter quest is the global one.';

create table public.quest_steps (
  quest_id uuid not null references public.quests (id) on delete cascade,
  position integer not null check (position between 1 and 20),
  kind text not null check (kind in ('visit_places', 'confirm_drop', 'create_drop')),
  target_count integer not null check (target_count between 1 and 100),
  title text not null check (char_length(title) between 1 and 120),
  primary key (quest_id, position)
);
comment on table public.quest_steps is
  'visit_places counts place_visits. confirm_drop counts still-accurate confirmations. create_drop counts the caller''s own user drops.';

create table public.quest_dismissals (
  user_id uuid not null references public.profiles (id) on delete cascade,
  quest_id uuid not null references public.quests (id) on delete cascade,
  dismissed_at timestamptz not null default now(),
  primary key (user_id, quest_id)
);
create index quest_dismissals_quest_idx on public.quest_dismissals (quest_id);
comment on table public.quest_dismissals is
  'The caller hid this quest. Progress is still derived from visits, confirmations, and drops.';

-- ---------------------------------------------------------------------------
-- RLS. Catalog is readable. Progress is readable only by the person it is about.
-- Nothing here is inserted by a client grant.
-- ---------------------------------------------------------------------------
alter table public.walks enable row level security;
alter table public.walk_stops enable row level security;
alter table public.walk_progress enable row level security;
alter table public.walk_stop_reaches enable row level security;
alter table public.place_visits enable row level security;
alter table public.quests enable row level security;
alter table public.quest_steps enable row level security;
alter table public.quest_dismissals enable row level security;

revoke all on table public.walks from anon, authenticated;
revoke all on table public.walk_stops from anon, authenticated;
revoke all on table public.walk_progress from anon, authenticated;
revoke all on table public.walk_stop_reaches from anon, authenticated;
revoke all on table public.place_visits from anon, authenticated;
revoke all on table public.quests from anon, authenticated;
revoke all on table public.quest_steps from anon, authenticated;
revoke all on table public.quest_dismissals from anon, authenticated;

grant select on public.walks, public.walk_stops, public.quests, public.quest_steps to anon, authenticated;
grant select on public.walk_progress, public.walk_stop_reaches, public.place_visits, public.quest_dismissals to authenticated;

create policy walks_select on public.walks
  for select to anon, authenticated
  using (active);

create policy walk_stops_select on public.walk_stops
  for select to anon, authenticated
  using (exists (
    select 1 from public.walks w where w.id = walk_id and w.active
  ));

create policy quests_select on public.quests
  for select to anon, authenticated
  using (active);

create policy quest_steps_select on public.quest_steps
  for select to anon, authenticated
  using (exists (
    select 1 from public.quests q where q.id = quest_id and q.active
  ));

create policy walk_progress_select_own on public.walk_progress
  for select to authenticated
  using (user_id = (select auth.uid()));

create policy walk_stop_reaches_select_own on public.walk_stop_reaches
  for select to authenticated
  using (user_id = (select auth.uid()));

create policy place_visits_select_own on public.place_visits
  for select to authenticated
  using (user_id = (select auth.uid()));

create policy quest_dismissals_select_own on public.quest_dismissals
  for select to authenticated
  using (user_id = (select auth.uid()));

-- ---------------------------------------------------------------------------
-- Helpers. private is not exposed. search_path is empty.
-- ---------------------------------------------------------------------------

-- One walk, with stops that resolve to a drop the caller is allowed to see.
-- A missing import or a hidden drop is left off the route.
create function private.walk_json(
  p_walk_id uuid,
  p_uid uuid,
  p_point extensions.geography
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', w.id,
    'region_id', w.region_id,
    'slug', w.slug,
    'title', w.title,
    'description', w.description,
    'distance_m', w.distance_m,
    'duration_min', w.duration_min,
    'started', exists (
      select 1 from public.walk_progress p
      where p.walk_id = w.id and p.user_id = p_uid
    ),
    'completed', exists (
      select 1 from public.walk_progress p
      where p.walk_id = w.id and p.user_id = p_uid and p.completed_at is not null
    ),
    'stops', coalesce((
      select jsonb_agg(jsonb_build_object(
        'position', s.position,
        'title', d.title,
        'summary', d.body,
        'lat', extensions.st_y(d.display_geog::extensions.geometry),
        'lng', extensions.st_x(d.display_geog::extensions.geometry),
        'reveal_radius_m', d.reveal_radius_m,
        'short_id', d.short_id,
        'drop_id', d.id,
        'reached', exists (
          select 1 from public.walk_stop_reaches r
          where r.user_id = p_uid and r.walk_id = s.walk_id and r.position = s.position
        ),
        'distance_m', case
          when p_point is null then null
          else round(extensions.st_distance(d.display_geog, p_point)::numeric, 1)
        end,
        'available', true
      ) order by s.position)
      from public.walk_stops s
      join public.open_data_items i
        on i.source_slug = s.source_slug and i.external_id = s.external_id
      join public.drops d on d.id = i.drop_id
      where s.walk_id = w.id
        and private.can_view_drop(d, p_uid)
    ), '[]'::jsonb)
  )
  from public.walks w
  where w.id = p_walk_id;
$$;

-- How far the caller is on one kind of step. Counts, not coordinates.
create function private.quest_step_progress(p_uid uuid, p_kind text)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select case p_kind
    when 'visit_places' then (
      select count(*)::integer from public.place_visits v where v.user_id = p_uid
    )
    when 'confirm_drop' then (
      select count(*)::integer from public.confirmations c
      where c.user_id = p_uid and c.verdict = 'still_true'
    )
    when 'create_drop' then (
      select count(*)::integer from public.drops d
      where d.author_id = p_uid and d.source = 'user' and d.status <> 'removed'
    )
    else 0
  end;
$$;

create function private.quests_json(p_uid uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
    'id', q.id,
    'slug', q.slug,
    'title', q.title,
    'description', q.description,
    'completed', not exists (
      select 1 from public.quest_steps s
      where s.quest_id = q.id
        and private.quest_step_progress(p_uid, s.kind) < s.target_count
    ),
    'steps', coalesce((
      select jsonb_agg(jsonb_build_object(
        'position', s.position,
        'kind', s.kind,
        'title', s.title,
        'target_count', s.target_count,
        'progress', private.quest_step_progress(p_uid, s.kind)
      ) order by s.position)
      from public.quest_steps s
      where s.quest_id = q.id
    ), '[]'::jsonb)
  ) order by q.slug), '[]'::jsonb)
  from public.quests q
  where q.active
    and q.region_id is null
    and not exists (
      select 1 from public.quest_dismissals d
      where d.user_id = p_uid and d.quest_id = q.id
    );
$$;

-- Places the caller is inside the reveal radius of. Own drops do not count.
-- The point is not written.
create function private.record_place_visits(p_uid uuid, p_point extensions.geography)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_added integer;
begin
  insert into public.place_visits (user_id, drop_id)
  select p_uid, d.id
  from public.drops d
  where extensions.st_dwithin(d.display_geog, p_point, 50000)
    and extensions.st_distance(d.display_geog, p_point) <= d.reveal_radius_m
    and d.author_id is distinct from p_uid
    and private.can_view_drop(d, p_uid)
  on conflict (user_id, drop_id) do nothing;
  get diagnostics v_added = row_count;
  return v_added;
end;
$$;

-- Check off stops on a walk the caller already started. Returns true only on
-- the call that completes the walk.
create function private.mark_walk_stops(
  p_uid uuid,
  p_walk_id uuid,
  p_point extensions.geography
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_total integer;
  v_reached integer;
begin
  if not exists (
    select 1 from public.walk_progress p
    where p.user_id = p_uid and p.walk_id = p_walk_id
  ) then
    return false;
  end if;

  insert into public.walk_stop_reaches (user_id, walk_id, position)
  select p_uid, s.walk_id, s.position
  from public.walk_stops s
  join public.open_data_items i
    on i.source_slug = s.source_slug and i.external_id = s.external_id
  join public.drops d on d.id = i.drop_id
  where s.walk_id = p_walk_id
    and private.can_view_drop(d, p_uid)
    and extensions.st_distance(d.display_geog, p_point) <= d.reveal_radius_m
  on conflict (user_id, walk_id, position) do nothing;

  select count(*)::integer into v_total
  from public.walk_stops s
  join public.open_data_items i
    on i.source_slug = s.source_slug and i.external_id = s.external_id
  join public.drops d on d.id = i.drop_id
  where s.walk_id = p_walk_id
    and private.can_view_drop(d, p_uid);

  select count(*)::integer into v_reached
  from public.walk_stop_reaches r
  where r.user_id = p_uid
    and r.walk_id = p_walk_id
    and exists (
      select 1
      from public.walk_stops s
      join public.open_data_items i
        on i.source_slug = s.source_slug and i.external_id = s.external_id
      join public.drops d on d.id = i.drop_id
      where s.walk_id = r.walk_id
        and s.position = r.position
        and private.can_view_drop(d, p_uid)
    );

  if v_total > 0 and v_reached = v_total then
    update public.walk_progress p
    set completed_at = now()
    where p.user_id = p_uid
      and p.walk_id = p_walk_id
      and p.completed_at is null;
    return found;
  end if;
  return false;
end;
$$;

-- ---------------------------------------------------------------------------
-- RPCs
-- ---------------------------------------------------------------------------

-- Walks whose box contains this point, plus a walk the caller has started and
-- not finished (so guidance continues a block outside the box).
create function public.walks_for_point(p_lat double precision, p_lng double precision)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_point extensions.geography;
  v_rows jsonb;
begin
  v_point := private.make_point(p_lat, p_lng);
  select coalesce(jsonb_agg(s.payload order by s.open_walk desc, s.slug), '[]'::jsonb)
    into v_rows
  from (
    select private.walk_json(w.id, v_uid, v_point) as payload,
           w.slug,
           exists (
             select 1 from public.walk_progress p
             where p.user_id = v_uid and p.walk_id = w.id and p.completed_at is null
           ) as open_walk
    from public.walks w
    where w.active
      and (
        (p_lng >= w.min_lng and p_lng <= w.max_lng and p_lat >= w.min_lat and p_lat <= w.max_lat)
        or exists (
          select 1 from public.walk_progress p
          where p.user_id = v_uid and p.walk_id = w.id and p.completed_at is null
        )
      )
  ) s
  where s.payload is not null
    and jsonb_typeof(s.payload -> 'stops') = 'array'
    and jsonb_array_length(s.payload -> 'stops') > 0;
  return v_rows;
end;
$$;

create function public.start_walk(p_walk_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_walk public.walks;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (select 1 from public.profiles p where p.id = v_uid and p.deleted_at is null) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;

  select * into v_walk from public.walks w where w.id = p_walk_id and w.active;
  if not found then
    raise exception 'walk_not_found' using errcode = 'P0002';
  end if;

  insert into public.walk_progress (user_id, walk_id)
  values (v_uid, v_walk.id)
  on conflict (user_id, walk_id) do nothing;

  return private.walk_json(v_walk.id, v_uid, null);
end;
$$;

-- Stand here. Records visits and, when a started walk is passed, checks off
-- stops inside the reveal radius. Does not store p_lat/p_lng.
create function public.check_in(
  p_lat double precision,
  p_lng double precision,
  p_walk_id uuid default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_point extensions.geography;
  v_added integer;
  v_count integer;
  v_just boolean := false;
  v_walk jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (select 1 from public.profiles p where p.id = v_uid and p.deleted_at is null) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;

  v_point := private.make_point(p_lat, p_lng);
  v_added := private.record_place_visits(v_uid, v_point);
  select count(*)::integer into v_count from public.place_visits v where v.user_id = v_uid;

  if p_walk_id is not null then
    if not exists (select 1 from public.walks w where w.id = p_walk_id and w.active) then
      raise exception 'walk_not_found' using errcode = 'P0002';
    end if;
    v_just := private.mark_walk_stops(v_uid, p_walk_id, v_point);
    v_walk := private.walk_json(p_walk_id, v_uid, v_point);
  end if;

  return jsonb_build_object(
    'visit_count', v_count,
    'visits_added', v_added,
    'quests', private.quests_json(v_uid),
    'walk', v_walk,
    'walk_just_completed', v_just
  );
end;
$$;

create function public.my_quests()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  return private.quests_json(v_uid);
end;
$$;

create function public.dismiss_quest(p_quest_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (select 1 from public.quests q where q.id = p_quest_id and q.active) then
    raise exception 'quest_not_found' using errcode = 'P0002';
  end if;
  insert into public.quest_dismissals (user_id, quest_id)
  values (v_uid, p_quest_id)
  on conflict (user_id, quest_id) do nothing;
end;
$$;

-- ---------------------------------------------------------------------------
-- The starter quest. Global: region_id is null, so every region offers it.
-- ---------------------------------------------------------------------------
insert into public.quests (id, slug, region_id, title, description)
values (
  'a0000000-0000-4000-8000-000000000004',
  'starter',
  null,
  'Start here',
  'Visit 3 places, confirm 1 drop, and make your first drop.'
);

insert into public.quest_steps (quest_id, position, kind, title, target_count)
values
  ('a0000000-0000-4000-8000-000000000004', 1, 'visit_places', 'Visit 3 places', 3),
  ('a0000000-0000-4000-8000-000000000004', 2, 'confirm_drop', 'Confirm 1 drop', 1),
  ('a0000000-0000-4000-8000-000000000004', 3, 'create_drop', 'Make your first drop', 1);

-- ---------------------------------------------------------------------------
-- Grants. The map can read a walk while signed out. Progress needs an account.
-- ---------------------------------------------------------------------------
revoke execute on function
  private.walk_json(uuid, uuid, extensions.geography),
  private.quest_step_progress(uuid, text),
  private.quests_json(uuid),
  private.record_place_visits(uuid, extensions.geography),
  private.mark_walk_stops(uuid, uuid, extensions.geography)
from public, anon, authenticated;

revoke execute on function
  public.walks_for_point(double precision, double precision),
  public.start_walk(uuid),
  public.check_in(double precision, double precision, uuid),
  public.my_quests(),
  public.dismiss_quest(uuid)
from public, anon, authenticated;

grant execute on function
  public.walks_for_point(double precision, double precision)
to anon, authenticated, service_role;

grant execute on function
  public.start_walk(uuid),
  public.check_in(double precision, double precision, uuid),
  public.my_quests(),
  public.dismiss_quest(uuid)
to authenticated, service_role;
