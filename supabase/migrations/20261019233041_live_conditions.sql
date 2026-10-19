-- Live conditions (research recommendation #5, PLAN §3 open-data layers, §4.1 source axis,
-- §5 expiry). XHere is global. Nothing in this migration names a city.
--
-- (a) region_live is optional per-region config for which free weather-alert
--     provider covers that box. The client still has a provider interface;
--     `nws` is the US National Weather Service (api.weather.gov, no key).
--     Other countries plug in as a new provider id later. `none` turns alerts off.
-- (b) civic_issue is the category an optional Open311 GeoReport v2 import uses.
--     The importer only runs where a region YAML lists the feed. expires_at on
--     an open-data item makes that pin short-lived.
-- (c) right_now is a user drop that lasts 1 hour, 4 hours, or 24 hours.
--     drops_in_view, drops_nearby, drop_detail, and here_card already hide a
--     row once expires_at has passed (private.can_view_drop). private.expire_drops
--     is already scheduled on free pg_cron (`xhere-expire-drops`, every 5 minutes)
--     and flips the status. No second job and no paid queue.

-- ---------------------------------------------------------------------------
-- Right now: Waze-style happenings. Safety group, so the chip bar stays as it is.
-- ---------------------------------------------------------------------------
insert into public.categories (
  group_id, slug, label, description, icon, color, attributes_schema,
  min_tier_to_post, moderation_mode, sensitive, posting_notice, content_filters,
  default_reveal_m, max_reveal_m, location_precision_m,
  default_ttl, max_ttl, confirm_extends_ttl,
  confirmations_to_confirm, denials_to_hide, reports_to_hide,
  allow_anonymous_display, is_builtin, sort, suggestion_hints
)
select g.id,
       'right_now',
       'Right now',
       'A short-lived condition at this spot.',
       '⏱️',
       g.color,
       '{"type":"object","additionalProperties":false,"required":["kind"],"properties":{"kind":{"enum":["closed","flooded","line","truck","hazard","other"]}}}'::jsonb,
       'new',
       'post',
       false,
       'This stays on the map for 1, 4, or 24 hours, then it disappears.',
       '{contact_info}',
       1000,
       1000,
       0,
       interval '4 hours',
       interval '24 hours',
       interval '4 hours',
       2, 2, 2,
       true,
       true,
       4,
       'queue waittime momentary rightnow'
from public.category_groups g
where g.slug = 'safety_alerts';

-- Civic issues from an open 311 / GeoReport feed. Short display life.
insert into public.categories (
  group_id, slug, label, description, icon, color, attributes_schema,
  min_tier_to_post, moderation_mode, sensitive, posting_notice, content_filters,
  default_reveal_m, max_reveal_m, location_precision_m,
  default_ttl, max_ttl,
  confirmations_to_confirm, denials_to_hide, reports_to_hide,
  allow_anonymous_display, is_builtin, sort, suggestion_hints
)
select g.id,
       'civic_issue',
       'Civic issue',
       'An open civic request published by a city.',
       '🛠️',
       g.color,
       '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string","maxLength":80},"status":{"enum":["open","closed"]},"agency":{"type":"string","maxLength":120}}}'::jsonb,
       'new',
       'post',
       false,
       'City feeds are copied as published. They leave the map when they expire.',
       '{contact_info}',
       500,
       500,
       0,
       interval '7 days',
       interval '30 days',
       3, 3, 3,
       true,
       true,
       4,
       'municipal open311 service request civicissue'
from public.category_groups g
where g.slug = 'infrastructure';

update public.categories
set embedding = private.hash_embed(
  replace(slug, '_', ' ') || ' ' || label || ' ' || coalesce(description, '') || ' ' || coalesce(suggestion_hints, '')
)
where slug in ('right_now', 'civic_issue');

-- ---------------------------------------------------------------------------
-- 1h / 4h / 24h, with slack for a slow request. Updates (a "still true")
-- may land anywhere inside the 24 hour cap; create_drop already enforces max_ttl.
-- ---------------------------------------------------------------------------
create function private.enforce_right_now_window()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_slug text;
  v_span interval;
begin
  if tg_op <> 'INSERT' then
    return new;
  end if;
  select c.slug into v_slug from public.categories c where c.id = new.category_id;
  if v_slug is distinct from 'right_now' then
    return new;
  end if;
  if new.expires_at is null then
    raise exception 'invalid_right_now_ttl' using errcode = '22023',
      detail = 'right now drops last 1 hour, 4 hours, or 24 hours';
  end if;
  v_span := new.expires_at - now();
  if v_span between interval '50 minutes' and interval '70 minutes'
     or v_span between interval '3 hours 40 minutes' and interval '4 hours 20 minutes'
     or v_span between interval '23 hours 40 minutes' and interval '24 hours 20 minutes' then
    return new;
  end if;
  raise exception 'invalid_right_now_ttl' using errcode = '22023',
    detail = 'right now drops last 1 hour, 4 hours, or 24 hours';
end;
$$;

create trigger drops_enforce_right_now
  before insert on public.drops
  for each row execute function private.enforce_right_now_window();

revoke execute on function private.enforce_right_now_window() from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Per-region live config. Public to read (no secrets). Writes go through
-- private.upsert_region_live, granted to service_role only.
-- ---------------------------------------------------------------------------
create table public.region_live (
  region_id text primary key check (region_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  name text not null check (char_length(btrim(name)) between 1 and 120),
  country text not null check (country ~ '^[A-Z]{2}$'),
  west double precision not null,
  south double precision not null,
  east double precision not null,
  north double precision not null,
  weather_provider text check (weather_provider is null or weather_provider in ('nws', 'none')),
  updated_at timestamptz not null default now(),
  check (west < east and south < north),
  check (south >= -90 and north <= 90 and west >= -180 and east <= 180)
);
comment on table public.region_live is
  'Optional per-region live-layer config. weather_provider nws uses api.weather.gov. none turns official alerts off. Not a user location trail.';

alter table public.region_live enable row level security;
revoke all on table public.region_live from public, anon, authenticated;
grant select on table public.region_live to anon, authenticated;

create policy region_live_select on public.region_live
  for select to anon, authenticated
  using (true);

create function private.upsert_region_live(p_row jsonb)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_id text := p_row ->> 'region_id';
  v_provider text := nullif(p_row ->> 'weather_provider', '');
  v_name text := btrim(p_row ->> 'name');
begin
  if v_id is null or v_id !~ '^[a-z0-9]+(-[a-z0-9]+)*$' then
    raise exception 'invalid_region' using errcode = '22023', detail = coalesce(v_id, 'missing');
  end if;
  if v_provider is not null and v_provider not in ('nws', 'none') then
    raise exception 'invalid_weather_provider' using errcode = '22023', detail = v_provider;
  end if;
  if coalesce(p_row ->> 'country', '') !~ '^[A-Z]{2}$' then
    raise exception 'invalid_region' using errcode = '22023', detail = 'country';
  end if;
  if v_name is null or char_length(v_name) > 120 then
    raise exception 'invalid_region' using errcode = '22023', detail = 'name';
  end if;
  insert into public.region_live (region_id, name, country, west, south, east, north, weather_provider)
  values (
    v_id,
    v_name,
    p_row ->> 'country',
    (p_row ->> 'west')::double precision,
    (p_row ->> 'south')::double precision,
    (p_row ->> 'east')::double precision,
    (p_row ->> 'north')::double precision,
    v_provider
  )
  on conflict (region_id) do update
    set name = excluded.name,
        country = excluded.country,
        west = excluded.west,
        south = excluded.south,
        east = excluded.east,
        north = excluded.north,
        weather_provider = excluded.weather_provider,
        updated_at = now();
end;
$$;

revoke execute on function private.upsert_region_live(jsonb) from public, anon, authenticated;
grant execute on function private.upsert_region_live(jsonb) to service_role;

create function public.region_live_at(
  p_lat double precision,
  p_lng double precision
)
returns table (
  region_id text,
  name text,
  country text,
  weather_provider text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform private.make_point(p_lat, p_lng);
  return query
  select r.region_id, r.name, r.country, r.weather_provider
  from public.region_live r
  where p_lng >= r.west and p_lng <= r.east
    and p_lat >= r.south and p_lat <= r.north
  order by (r.east - r.west) * (r.north - r.south), r.region_id
  limit 1;
end;
$$;

comment on function public.region_live_at(double precision, double precision) is
  'The smallest configured region whose box contains this point, if any. Weather provider id is nws, none, or null.';

revoke execute on function public.region_live_at(double precision, double precision) from public;
grant execute on function public.region_live_at(double precision, double precision) to anon, authenticated, service_role;

-- ---------------------------------------------------------------------------
-- Open-data items may carry expires_at. Missing means permanent, which is
-- what the history import already writes. A civic feed sets a short lifetime.
-- ---------------------------------------------------------------------------
create or replace function private.upsert_open_data_items(p_items jsonb)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_item jsonb;
  v_count integer := 0;
  v_category public.categories;
  v_drop_id uuid;
  v_geog extensions.geography;
  v_title text;
  v_summary text;
  v_attrs jsonb;
  v_slug text;
  v_external text;
  v_url text;
  v_attempt integer;
  v_expires timestamptz;
begin
  if jsonb_typeof(p_items) is distinct from 'array' then
    raise exception 'invalid_items' using errcode = '22023', detail = 'expected a JSON array';
  end if;
  if jsonb_array_length(p_items) > 5000 then
    raise exception 'invalid_items' using errcode = '22023', detail = 'at most 5000 items per call';
  end if;

  for v_item in select value from jsonb_array_elements(p_items) as element(value)
  loop
    v_slug := v_item ->> 'source_slug';
    v_external := v_item ->> 'external_id';
    v_url := v_item ->> 'source_url';
    v_title := btrim(v_item ->> 'title');
    v_summary := nullif(btrim(v_item ->> 'summary'), '');
    v_attrs := coalesce(v_item -> 'attributes', '{}'::jsonb);
    v_expires := null;

    if v_slug is null or v_slug !~ '^[a-z][a-z0-9-]{1,40}$' then
      raise exception 'invalid_source' using errcode = '22023', detail = coalesce(v_slug, 'missing');
    end if;
    if v_slug ~ '(hmdb|atlas-obscura|atlasobscura)'
       or coalesce(v_url, '') ~* 'hmdb\.org|atlasobscura\.com' then
      raise exception 'license_not_allowed' using errcode = '22023',
        detail = 'HMdb and Atlas Obscura are link-only';
    end if;
    if v_external is null or char_length(v_external) not between 1 and 200 then
      raise exception 'invalid_external_id' using errcode = '22023', detail = coalesce(v_external, 'missing');
    end if;
    if v_url is null or v_url !~ '^https://' or char_length(v_url) > 500 then
      raise exception 'invalid_source_url' using errcode = '22023', detail = coalesce(v_url, 'missing');
    end if;
    if nullif(btrim(v_item ->> 'license'), '') is null
       or nullif(btrim(v_item ->> 'attribution'), '') is null
       or nullif(btrim(v_item ->> 'source_name'), '') is null then
      raise exception 'missing_provenance' using errcode = '22023', detail = v_external;
    end if;
    if v_item ->> 'region_id' is null or v_item ->> 'region_id' !~ '^[a-z0-9]+(-[a-z0-9]+)*$' then
      raise exception 'invalid_region' using errcode = '22023', detail = coalesce(v_item ->> 'region_id', 'missing');
    end if;
    if char_length(v_title) not between 1 and 120 then
      raise exception 'invalid_title' using errcode = '22023', detail = v_external;
    end if;
    if v_summary is not null and char_length(v_summary) > 2000 then
      raise exception 'invalid_summary' using errcode = '22023', detail = v_external;
    end if;
    if jsonb_typeof(v_attrs) is distinct from 'object' then
      raise exception 'invalid_attributes' using errcode = '22023', detail = v_external;
    end if;
    if nullif(v_item ->> 'expires_at', '') is not null then
      begin
        v_expires := (v_item ->> 'expires_at')::timestamptz;
      exception
        when invalid_datetime_format or datetime_field_overflow or invalid_text_representation then
          raise exception 'invalid_expires' using errcode = '22023', detail = v_external;
      end;
    end if;

    select * into v_category
    from public.categories c
    where c.slug = v_item ->> 'category_slug' and c.active;
    if not found then
      raise exception 'category_not_found' using errcode = '22023', detail = coalesce(v_item ->> 'category_slug', 'missing');
    end if;
    if not extensions.jsonb_matches_schema(v_category.attributes_schema::json, v_attrs) then
      raise exception 'invalid_attributes' using errcode = '22023', detail = v_external;
    end if;

    v_geog := private.make_point((v_item ->> 'lat')::double precision, (v_item ->> 'lng')::double precision);

    insert into public.open_data_sources (slug, name, license, attribution, url)
    values (
      v_slug,
      btrim(v_item ->> 'source_name'),
      btrim(v_item ->> 'license'),
      btrim(v_item ->> 'attribution'),
      coalesce(v_item ->> 'source_home', v_url)
    )
    on conflict (slug) do update
      set name = excluded.name,
          license = excluded.license,
          attribution = excluded.attribution,
          url = excluded.url;

    select i.drop_id into v_drop_id
    from public.open_data_items i
    where i.source_slug = v_slug and i.external_id = v_external;

    if v_drop_id is null then
      v_attempt := 0;
      loop
        v_attempt := v_attempt + 1;
        begin
          insert into public.drops (
            author_id, category_id, category_version, source, title, body, attributes,
            geog, display_geog, reveal_radius_m, status, expires_at
          ) values (
            null,
            v_category.id,
            v_category.version,
            'open_data',
            v_title,
            v_summary,
            v_attrs,
            v_geog,
            private.fuzz_point(v_geog, v_category.location_precision_m),
            v_category.default_reveal_m,
            'active',
            v_expires
          )
          returning id into v_drop_id;
          exit;
        exception when unique_violation then
          if v_attempt >= 5 then
            raise;
          end if;
        end;
      end loop;

      insert into public.open_data_items (
        drop_id, source_slug, external_id, source_url, license, attribution, region_id
      ) values (
        v_drop_id, v_slug, v_external, v_url,
        btrim(v_item ->> 'license'), btrim(v_item ->> 'attribution'), v_item ->> 'region_id'
      );
    else
      update public.drops d
      set category_id = v_category.id,
          category_version = v_category.version,
          source = 'open_data',
          author_id = null,
          title = v_title,
          body = v_summary,
          attributes = v_attrs,
          geog = v_geog,
          display_geog = private.fuzz_point(v_geog, v_category.location_precision_m),
          reveal_radius_m = v_category.default_reveal_m,
          status = 'active',
          expires_at = v_expires
      where d.id = v_drop_id;

      update public.open_data_items i
      set source_url = v_url,
          license = btrim(v_item ->> 'license'),
          attribution = btrim(v_item ->> 'attribution'),
          region_id = v_item ->> 'region_id',
          retrieved_at = now()
      where i.drop_id = v_drop_id;
    end if;

    v_count := v_count + 1;
  end loop;

  if v_count > 0 then
    update public.drops d
    set status = 'removed'
    from public.open_data_items i
    where i.drop_id = d.id
      and d.source = 'open_data'
      and d.status in ('active', 'confirmed', 'expired')
      and (i.source_slug, i.region_id) in (
        select item ->> 'source_slug', item ->> 'region_id'
        from jsonb_array_elements(p_items) as batch(item)
      )
      and not exists (
        select 1
        from jsonb_array_elements(p_items) as batch(item)
        where item ->> 'source_slug' = i.source_slug
          and item ->> 'region_id' = i.region_id
          and item ->> 'external_id' = i.external_id
      );
  end if;

  return v_count;
end;
$$;
