-- Founding Locals and a time-boxed map campaign
-- (research recommendation #6, PLAN §5 badges, M11 reputation).
--
-- XHere is global. A region opts in by inserting a regions row, a
-- region_founding cap, and a campaigns row. Jamestown is a seed, not this
-- file. Imported open_data drops never count. A qualifying drop is a user
-- drop that other people can see: source user, status active or confirmed,
-- not expired, author still live, true point inside the region box.
-- pending_review, disputed, expired, and removed do not count. A personal
-- hide (hidden_drops) is not a public hide, so it still counts.
--
-- Founding Local is the first `cap` people (default 25) ordered by their
-- earliest qualifying drop. The campaign is separate: drops whose created_at
-- is inside [starts_at, ends_at).
--
-- badges / user_badges are the M11 shelf. A later nightly job can award more
-- rows from badges.rule without a new table. region_id is null for a global
-- badge and set for a regional one. meta holds evidence (rank, drop_id).

-- ---------------------------------------------------------------------------
-- People may opt out of leaderboards. The column is not client-writable.
-- The RPC is the only path, so a profile update cannot change it by accident.
-- ---------------------------------------------------------------------------
alter table public.profiles
  add column show_on_leaderboard boolean not null default true;

comment on column public.profiles.show_on_leaderboard is
  'When false, campaign leaderboards omit this person. The community total still counts their drops. A board shows a display name only, never a handle.';

-- ---------------------------------------------------------------------------
-- Catalog. Written by migrations and seeds, not by clients.
-- ---------------------------------------------------------------------------
create table public.regions (
  id text primary key check (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  name text not null check (char_length(btrim(name)) between 1 and 120),
  country text not null check (char_length(country) between 2 and 8),
  min_lng double precision not null,
  min_lat double precision not null,
  max_lng double precision not null,
  max_lat double precision not null,
  timezone text not null check (char_length(timezone) between 1 and 64),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  check (min_lng < max_lng and min_lat < max_lat),
  check (min_lat between -90 and 90 and max_lat between -90 and 90),
  check (min_lng between -180 and 180 and max_lng between -180 and 180)
);
comment on table public.regions is
  'One map region. The box is west, south, east, north. Campaigns and founding caps point here. A new city is a row, not a code change.';

create table public.badges (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique check (slug ~ '^[a-z][a-z0-9_]{1,40}$'),
  name text not null check (char_length(btrim(name)) between 1 and 60),
  description text not null check (char_length(description) between 1 and 300),
  icon text not null check (char_length(icon) between 1 and 16),
  -- global: one award per person (M11 First Drop, Truth Keeper).
  -- region: one award per person per region (Founding Local).
  scope text not null check (scope in ('global', 'region')),
  -- How a later M11 job decides the award. This migration only runs kind founding_local.
  rule jsonb not null default '{}'::jsonb check (jsonb_typeof(rule) = 'object'),
  active boolean not null default true,
  sort integer not null default 0,
  created_at timestamptz not null default now()
);
comment on table public.badges is
  'Badge catalog for M11. rule jsonb is the extension point (kind, thresholds). Founding Local is the first regional row.';

create table public.user_badges (
  user_id uuid not null references public.profiles (id) on delete cascade,
  badge_id uuid not null references public.badges (id) on delete cascade,
  region_id text references public.regions (id) on delete cascade,
  awarded_at timestamptz not null default now(),
  meta jsonb not null default '{}'::jsonb check (jsonb_typeof(meta) = 'object'),
  constraint user_badges_user_badge_region_key unique nulls not distinct (user_id, badge_id, region_id)
);
comment on table public.user_badges is
  'One award. region_id is null for a global badge. meta is evidence a later badge can extend (rank, drop_id, points). Clients read it through RPCs.';

create index user_badges_badge_idx on public.user_badges (badge_id);
create index user_badges_region_idx on public.user_badges (region_id);
create index user_badges_user_idx on public.user_badges (user_id);

create table public.region_founding (
  region_id text primary key references public.regions (id) on delete cascade,
  cap integer not null default 25 check (cap between 1 and 10000),
  updated_at timestamptz not null default now()
);
comment on table public.region_founding is
  'Opt-in. The first cap people with a qualifying drop in the region earn Founding Local. No row means no founding badge there.';

create trigger region_founding_set_updated_at before update on public.region_founding
  for each row execute function private.set_updated_at();

create table public.campaigns (
  id uuid primary key default gen_random_uuid(),
  region_id text not null references public.regions (id) on delete cascade,
  slug text not null check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  title text not null check (char_length(btrim(title)) between 1 and 80),
  description text not null check (char_length(description) between 1 and 400),
  goal_drops integer not null check (goal_drops between 1 and 1000000),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  leaderboard_size integer not null default 8 check (leaderboard_size between 1 and 50),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (region_id, slug),
  check (ends_at > starts_at)
);
comment on table public.campaigns is
  'A time-boxed goal for one region, for example 200 user drops in 14 days. open_data never counts. Dates are config.';

create index campaigns_region_idx on public.campaigns (region_id);

-- ---------------------------------------------------------------------------
-- RLS. Catalog is readable. Awards are not: a client could list every user id.
-- Boards and profile badges go through RPCs that return a display name or the caller's own rows.
-- ---------------------------------------------------------------------------
alter table public.regions enable row level security;
alter table public.badges enable row level security;
alter table public.user_badges enable row level security;
alter table public.region_founding enable row level security;
alter table public.campaigns enable row level security;

revoke all on table public.regions from anon, authenticated;
revoke all on table public.badges from anon, authenticated;
revoke all on table public.user_badges from anon, authenticated;
revoke all on table public.region_founding from anon, authenticated;
revoke all on table public.campaigns from anon, authenticated;

grant select on public.regions, public.badges, public.region_founding, public.campaigns to anon, authenticated;

create policy regions_select on public.regions
  for select to anon, authenticated
  using (active);

create policy badges_select on public.badges
  for select to anon, authenticated
  using (active);

create policy region_founding_select on public.region_founding
  for select to anon, authenticated
  using (true);

create policy campaigns_select on public.campaigns
  for select to anon, authenticated
  using (active);

-- ---------------------------------------------------------------------------
-- The founding badge. Other M11 badges are more rows in this table.
-- ---------------------------------------------------------------------------
insert into public.badges (slug, name, description, icon, scope, rule, sort)
values (
  'founding_local',
  'Founding Local',
  'One of the first people to add a drop in this region.',
  '🌱',
  'region',
  '{"kind":"founding_local","order":"first_qualifying_drop"}'::jsonb,
  10
);

-- ---------------------------------------------------------------------------
-- Helpers. private is not exposed. search_path is empty.
-- ---------------------------------------------------------------------------

-- True when this drop is a real, public contribution inside the region.
-- p_from inclusive, p_until exclusive. Null bounds mean all time (founding).
create function private.drop_counts_in_region(
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
  select p_drop.source = 'user'::public.drop_source
    and p_drop.author_id is not null
    and p_drop.status in ('active'::public.drop_status, 'confirmed'::public.drop_status)
    and (p_drop.expires_at is null or p_drop.expires_at > now())
    and (p_from is null or p_drop.created_at >= p_from)
    and (p_until is null or p_drop.created_at < p_until)
    and exists (
      select 1 from public.profiles p
      where p.id = p_drop.author_id and p.deleted_at is null
    )
    and extensions.st_x(p_drop.geog::extensions.geometry) between p_region.min_lng and p_region.max_lng
    and extensions.st_y(p_drop.geog::extensions.geometry) between p_region.min_lat and p_region.max_lat;
$$;

-- Keeps the first cap founders honest, including after a drop is removed.
create function private.refresh_founders(p_region_id text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_cap integer;
  v_badge uuid;
  v_region public.regions;
  v_winner record;
  v_ids uuid[] := '{}'::uuid[];
  v_rank integer := 0;
begin
  select b.id into v_badge from public.badges b where b.slug = 'founding_local' and b.active;
  if v_badge is null then
    return;
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('xhere.founding:' || p_region_id, 0));

  select * into v_region from public.regions r where r.id = p_region_id and r.active;
  if not found then
    delete from public.user_badges ub
    where ub.badge_id = v_badge and ub.region_id = p_region_id;
    return;
  end if;

  select f.cap into v_cap from public.region_founding f where f.region_id = p_region_id;
  if not found or v_cap is null then
    delete from public.user_badges ub
    where ub.badge_id = v_badge and ub.region_id = p_region_id;
    return;
  end if;

  for v_winner in
    select d.author_id,
           min(d.created_at) as first_at,
           (array_agg(d.id order by d.created_at, d.id))[1] as drop_id
    from public.drops d
    where private.drop_counts_in_region(d, v_region, null, null)
    group by d.author_id
    order by min(d.created_at), d.author_id
    limit v_cap
  loop
    v_rank := v_rank + 1;
    v_ids := array_append(v_ids, v_winner.author_id);
    insert into public.user_badges (user_id, badge_id, region_id, awarded_at, meta)
    values (
      v_winner.author_id,
      v_badge,
      p_region_id,
      v_winner.first_at,
      jsonb_build_object('kind', 'founding_local', 'rank', v_rank, 'drop_id', v_winner.drop_id)
    )
    on conflict (user_id, badge_id, region_id) do update
      set meta = excluded.meta
      where public.user_badges.meta is distinct from excluded.meta;
  end loop;

  delete from public.user_badges ub
  where ub.badge_id = v_badge
    and ub.region_id = p_region_id
    and not (ub.user_id = any (v_ids));
end;
$$;

create function private.sync_founding_for_drop()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_region text;
begin
  if tg_op <> 'DELETE' and new.source = 'user'::public.drop_source then
    for v_region in
      select r.id
      from public.regions r
      join public.region_founding f on f.region_id = r.id
      where r.active
        and extensions.st_x(new.geog::extensions.geometry) between r.min_lng and r.max_lng
        and extensions.st_y(new.geog::extensions.geometry) between r.min_lat and r.max_lat
    loop
      perform private.refresh_founders(v_region);
    end loop;
  end if;

  if tg_op <> 'INSERT' and old.source = 'user'::public.drop_source then
    for v_region in
      select r.id
      from public.regions r
      join public.region_founding f on f.region_id = r.id
      where r.active
        and extensions.st_x(old.geog::extensions.geometry) between r.min_lng and r.max_lng
        and extensions.st_y(old.geog::extensions.geometry) between r.min_lat and r.max_lat
    loop
      perform private.refresh_founders(v_region);
    end loop;
  end if;
  return null;
end;
$$;

create trigger drops_sync_founding
  after insert or update or delete on public.drops
  for each row execute function private.sync_founding_for_drop();

create function private.sync_founding_for_config()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    perform private.refresh_founders(old.region_id);
  else
    perform private.refresh_founders(new.region_id);
  end if;
  return null;
end;
$$;

create trigger region_founding_sync
  after insert or update or delete on public.region_founding
  for each row execute function private.sync_founding_for_config();

create function private.sync_founding_for_region()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    perform private.refresh_founders(old.id);
  elsif tg_op = 'UPDATE' and (
    new.min_lng is distinct from old.min_lng
    or new.min_lat is distinct from old.min_lat
    or new.max_lng is distinct from old.max_lng
    or new.max_lat is distinct from old.max_lat
    or new.active is distinct from old.active
  ) then
    perform private.refresh_founders(new.id);
  end if;
  return null;
end;
$$;

create trigger regions_sync_founding
  after update or delete on public.regions
  for each row execute function private.sync_founding_for_region();

-- Regional awards need a region. Global awards (M11) must not carry one.
create function private.user_badges_match_scope()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_scope text;
begin
  select b.scope into v_scope from public.badges b where b.id = new.badge_id;
  if v_scope = 'region' and new.region_id is null then
    raise exception 'region_required' using errcode = '22023';
  end if;
  if v_scope = 'global' and new.region_id is not null then
    raise exception 'region_not_allowed' using errcode = '22023';
  end if;
  return new;
end;
$$;

create trigger user_badges_match_scope
  before insert or update on public.user_badges
  for each row execute function private.user_badges_match_scope();

-- Badges to show on a drop. Anonymous drops stay anonymous: no badge there.
-- A regional badge shows only when the drop itself is inside that region.
create function private.author_badges_json(p_user uuid, p_geog extensions.geography)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
    'slug', b.slug,
    'name', b.name,
    'icon', b.icon,
    'description', b.description,
    'region_id', ub.region_id,
    'region_name', r.name,
    'rank', nullif(ub.meta ->> 'rank', '')::integer
  ) order by b.sort, b.slug, ub.region_id), '[]'::jsonb)
  from public.user_badges ub
  join public.badges b on b.id = ub.badge_id and b.active
  left join public.regions r on r.id = ub.region_id
  where ub.user_id = p_user
    and (
      ub.region_id is null
      or (
        r.active
        and extensions.st_x(p_geog::extensions.geometry) between r.min_lng and r.max_lng
        and extensions.st_y(p_geog::extensions.geometry) between r.min_lat and r.max_lat
      )
    );
$$;

create function private.campaign_json(p_campaign_id uuid, p_uid uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_campaign public.campaigns;
  v_region public.regions;
  v_profile public.profiles;
  v_count integer;
  v_mine integer := 0;
  v_board jsonb;
  v_listed boolean := false;
  v_show boolean := true;
  v_have boolean := false;
begin
  select * into v_campaign from public.campaigns c where c.id = p_campaign_id and c.active;
  if not found then
    return null;
  end if;
  if now() < v_campaign.starts_at or now() >= v_campaign.ends_at then
    return null;
  end if;
  select * into v_region from public.regions r where r.id = v_campaign.region_id and r.active;
  if not found then
    return null;
  end if;

  select count(*)::integer into v_count
  from public.drops d
  where private.drop_counts_in_region(d, v_region, v_campaign.starts_at, v_campaign.ends_at);

  if p_uid is not null then
    select * into v_profile from public.profiles p where p.id = p_uid and p.deleted_at is null;
    if found then
      v_have := true;
      v_show := v_profile.show_on_leaderboard;
      select count(*)::integer into v_mine
      from public.drops d
      where d.author_id = p_uid
        and private.drop_counts_in_region(d, v_region, v_campaign.starts_at, v_campaign.ends_at);
    end if;
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
    'display_name', s.display_name,
    'drops', s.drops
  ) order by s.drops desc, s.display_name, s.author_id), '[]'::jsonb)
  into v_board
  from (
    select p.display_name, p.id as author_id, count(*)::integer as drops
    from public.drops d
    join public.profiles p on p.id = d.author_id
    where private.drop_counts_in_region(d, v_region, v_campaign.starts_at, v_campaign.ends_at)
      and p.show_on_leaderboard
      and p.display_name is not null
      and btrim(p.display_name) <> ''
    group by p.id, p.display_name
    order by count(*) desc, p.display_name, p.id
    limit v_campaign.leaderboard_size
  ) s;

  if v_have and v_show and v_profile.display_name is not null and btrim(v_profile.display_name) <> '' then
    select exists (
      select 1 from (
        select p.id
        from public.drops d
        join public.profiles p on p.id = d.author_id
        where private.drop_counts_in_region(d, v_region, v_campaign.starts_at, v_campaign.ends_at)
          and p.show_on_leaderboard
          and p.display_name is not null
          and btrim(p.display_name) <> ''
        group by p.id, p.display_name
        order by count(*) desc, p.display_name, p.id
        limit v_campaign.leaderboard_size
      ) top
      where top.id = p_uid
    ) into v_listed;
  end if;

  return jsonb_build_object(
    'id', v_campaign.id,
    'region_id', v_region.id,
    'region_name', v_region.name,
    'slug', v_campaign.slug,
    'title', v_campaign.title,
    'description', v_campaign.description,
    'goal_drops', v_campaign.goal_drops,
    'drop_count', v_count,
    'my_drops', v_mine,
    'starts_at', v_campaign.starts_at,
    'ends_at', v_campaign.ends_at,
    'leaderboard', v_board,
    'show_on_leaderboard', v_show,
    'my_listed', v_listed
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- RPCs
-- ---------------------------------------------------------------------------

create function public.campaigns_for_map(
  p_min_lng double precision,
  p_min_lat double precision,
  p_max_lng double precision,
  p_max_lat double precision
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_rows jsonb;
begin
  if p_min_lng is null or p_min_lat is null or p_max_lng is null or p_max_lat is null
     or p_min_lng >= p_max_lng or p_min_lat >= p_max_lat
     or p_min_lat < -90 or p_max_lat > 90
     or p_min_lng < -180 or p_max_lng > 180 then
    raise exception 'invalid_location' using errcode = '22023';
  end if;

  select coalesce(jsonb_agg(s.payload order by s.ends_at, s.slug), '[]'::jsonb)
    into v_rows
  from (
    select private.campaign_json(c.id, v_uid) as payload, c.ends_at, c.slug
    from public.campaigns c
    join public.regions r on r.id = c.region_id
    where c.active and r.active
      and r.max_lng >= p_min_lng and r.min_lng <= p_max_lng
      and r.max_lat >= p_min_lat and r.min_lat <= p_max_lat
  ) s
  where s.payload is not null;
  return v_rows;
end;
$$;

-- Every live campaign. The profile has no map position, so it lists them all.
create function public.my_campaigns()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_rows jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (select 1 from public.profiles p where p.id = v_uid and p.deleted_at is null) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;

  select coalesce(jsonb_agg(s.payload order by s.ends_at, s.slug), '[]'::jsonb)
    into v_rows
  from (
    select private.campaign_json(c.id, v_uid) as payload, c.ends_at, c.slug
    from public.campaigns c
    where c.active
  ) s
  where s.payload is not null;
  return v_rows;
end;
$$;

create function public.my_badges()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_show boolean;
  v_badges jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select p.show_on_leaderboard into v_show
  from public.profiles p
  where p.id = v_uid and p.deleted_at is null;
  if not found then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
    'slug', b.slug,
    'name', b.name,
    'icon', b.icon,
    'description', b.description,
    'region_id', ub.region_id,
    'region_name', r.name,
    'rank', nullif(ub.meta ->> 'rank', '')::integer,
    'awarded_at', ub.awarded_at
  ) order by ub.awarded_at, b.sort, b.slug), '[]'::jsonb)
  into v_badges
  from public.user_badges ub
  join public.badges b on b.id = ub.badge_id and b.active
  left join public.regions r on r.id = ub.region_id
  where ub.user_id = v_uid;

  return jsonb_build_object(
    'show_on_leaderboard', v_show,
    'badges', v_badges
  );
end;
$$;

create function public.set_leaderboard_visibility(p_visible boolean)
returns boolean
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
  if p_visible is null then
    raise exception 'invalid_visibility' using errcode = '22023';
  end if;
  update public.profiles p
  set show_on_leaderboard = p_visible
  where p.id = v_uid and p.deleted_at is null;
  if not found then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  return p_visible;
end;
$$;

-- ---------------------------------------------------------------------------
-- Badges on the card the app already reads. Empty when the author is hidden.
-- ---------------------------------------------------------------------------
alter type public.drop_card add attribute author_badges jsonb;

create or replace function private.to_drop_card(
  p_drop public.drops,
  p_viewer uuid,
  p_point extensions.geography
)
returns public.drop_card
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_card public.drop_card;
  v_category public.categories;
  v_distance double precision;
begin
  select * into v_category from public.categories c where c.id = p_drop.category_id;

  v_card.id := p_drop.id;
  v_card.short_id := p_drop.short_id;
  v_card.category_slug := v_category.slug;
  v_card.group_slug := (select g.slug from public.category_groups g where g.id = v_category.group_id);
  v_card.title := p_drop.title;
  v_card.lat := extensions.st_y(p_drop.display_geog::extensions.geometry);
  v_card.lng := extensions.st_x(p_drop.display_geog::extensions.geometry);
  v_card.location_precision_m := v_category.location_precision_m;
  v_card.reveal_radius_m := p_drop.reveal_radius_m;
  v_card.status := p_drop.status;
  v_card.is_confirmed := p_drop.status = 'confirmed';
  v_card.confirm_count := p_drop.confirm_count;
  v_card.deny_count := p_drop.deny_count;
  v_card.view_count := p_drop.view_count;
  v_card.expires_at := p_drop.expires_at;
  v_card.last_confirmed_at := p_drop.last_confirmed_at;
  v_card.created_at := p_drop.created_at;
  v_card.is_mine := p_viewer is not null
    and p_drop.author_id is not null
    and p_drop.author_id = p_viewer;
  v_card.source := p_drop.source;
  v_card.author_badges := '[]'::jsonb;

  if p_viewer is not null then
    select c.verdict into v_card.my_verdict
    from public.confirmations c
    where c.drop_id = p_drop.id and c.user_id = p_viewer;
  end if;

  if p_drop.author_id is not null
     and not (p_drop.display_anonymous and v_category.allow_anonymous_display) then
    v_card.author_handle := (select p.handle from public.profiles p where p.id = p_drop.author_id);
    v_card.author_badges := private.author_badges_json(p_drop.author_id, p_drop.geog);
  end if;

  select s.name, i.source_url, i.license, i.attribution
    into v_card.source_name, v_card.source_url, v_card.license, v_card.attribution
  from public.open_data_items i
  join public.open_data_sources s on s.slug = i.source_slug
  where i.drop_id = p_drop.id;

  if p_point is not null then
    v_distance := extensions.st_distance(p_drop.display_geog, p_point);
    v_card.distance_m := round(v_distance::numeric, 1)::double precision;
  end if;

  v_card.revealed := p_drop.source = 'open_data'
    or v_card.is_mine
    or (v_distance is not null and v_distance <= p_drop.reveal_radius_m);

  if v_card.revealed then
    v_card.body := p_drop.body;
    v_card.attributes := p_drop.attributes;
    v_card.tags := p_drop.tags;
    v_card.media := coalesce((
      select jsonb_agg(jsonb_build_object(
          'id', m.id,
          'storage_bucket', m.storage_bucket,
          'storage_path', m.storage_path,
          'media_kind', m.media_kind,
          'mime_type', m.mime_type,
          'width', m.width,
          'height', m.height,
          'duration_s', m.duration_s,
          'taken_here', m.taken_here
        ) order by m.sort, m.created_at)
      from public.drop_media m
      where m.drop_id = p_drop.id
    ), '[]'::jsonb);
  end if;

  return v_card;
end;
$$;

-- The caller's own awards travel with the data export. Nothing public is added.
create or replace function public.export_my_data()
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
      'show_on_leaderboard', v_profile.show_on_leaderboard,
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
    'badges', coalesce((
      select jsonb_agg(jsonb_build_object(
          'slug', b.slug,
          'name', b.name,
          'region_id', ub.region_id,
          'awarded_at', ub.awarded_at,
          'rank', ub.meta -> 'rank'
        ) order by ub.awarded_at)
      from public.user_badges ub
      join public.badges b on b.id = ub.badge_id
      where ub.user_id = v_uid
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
-- Grants
-- ---------------------------------------------------------------------------
revoke execute on function
  private.drop_counts_in_region(public.drops, public.regions, timestamptz, timestamptz),
  private.refresh_founders(text),
  private.sync_founding_for_drop(),
  private.sync_founding_for_config(),
  private.sync_founding_for_region(),
  private.user_badges_match_scope(),
  private.author_badges_json(uuid, extensions.geography),
  private.campaign_json(uuid, uuid)
from public, anon, authenticated;

revoke execute on function
  public.campaigns_for_map(double precision, double precision, double precision, double precision),
  public.my_campaigns(),
  public.my_badges(),
  public.set_leaderboard_visibility(boolean)
from public, anon, authenticated;

grant execute on function
  public.campaigns_for_map(double precision, double precision, double precision, double precision)
to anon, authenticated, service_role;

grant execute on function
  public.my_campaigns(),
  public.my_badges(),
  public.set_leaderboard_visibility(boolean)
to authenticated, service_role;
