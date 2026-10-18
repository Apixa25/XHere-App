-- Open-data base layer (research recommendation #1, PLAN §3 / §4.1 / §5, M10).
--
-- Imported places are ordinary drops with source = open_data and author_id null,
-- so they show on the map through the existing read RPCs and never count as a
-- person's drops, points, or tier. Provenance (source, external id, license,
-- attribution, deep link) lives on open_data_items. Re-imports upsert on
-- (source_slug, external_id) and hide rows that disappeared from that snapshot.
--
-- Clients can read the provenance. They cannot write it. The importer calls
-- private.upsert_open_data_items, which is not granted to anon or authenticated.

-- ---------------------------------------------------------------------------
-- Catalog and provenance
-- ---------------------------------------------------------------------------
create table public.open_data_sources (
  slug text primary key check (slug ~ '^[a-z][a-z0-9-]{1,40}$'),
  name text not null check (char_length(btrim(name)) between 1 and 120),
  license text not null check (char_length(btrim(license)) between 1 and 200),
  attribution text not null check (char_length(btrim(attribution)) between 1 and 500),
  url text not null check (url ~ '^https://'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
comment on table public.open_data_sources is
  'One row per open-data publisher the importer is allowed to write. License and attribution are shown on every card.';

create trigger open_data_sources_set_updated_at before update on public.open_data_sources
  for each row execute function private.set_updated_at();

create table public.open_data_items (
  id uuid primary key default gen_random_uuid(),
  drop_id uuid not null unique references public.drops (id) on delete cascade,
  source_slug text not null references public.open_data_sources (slug),
  external_id text not null check (char_length(external_id) between 1 and 200),
  source_url text not null check (source_url ~ '^https://'),
  license text not null check (char_length(btrim(license)) between 1 and 200),
  attribution text not null check (char_length(btrim(attribution)) between 1 and 500),
  region_id text not null check (region_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  retrieved_at timestamptz not null default now(),
  unique (source_slug, external_id)
);
comment on table public.open_data_items is
  'Provenance for one imported place. external_id is stable per source so a re-run updates the same drop.';

create index open_data_items_region_idx on public.open_data_items (region_id, source_slug);

alter table public.open_data_sources enable row level security;
alter table public.open_data_items enable row level security;
revoke all on table public.open_data_sources, public.open_data_items from anon, authenticated;
grant select on public.open_data_sources, public.open_data_items to anon, authenticated;

create policy open_data_sources_select on public.open_data_sources
  for select to anon, authenticated using (true);
create policy open_data_items_select on public.open_data_items
  for select to anon, authenticated using (true);

-- ---------------------------------------------------------------------------
-- Provenance on the card the app already reads. Null on a user drop.
-- ---------------------------------------------------------------------------
alter type public.drop_card add attribute source public.drop_source;
alter type public.drop_card add attribute source_name text;
alter type public.drop_card add attribute source_url text;
alter type public.drop_card add attribute license text;
alter type public.drop_card add attribute attribution text;

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
  v_card.expires_at := p_drop.expires_at;
  v_card.last_confirmed_at := p_drop.last_confirmed_at;
  v_card.created_at := p_drop.created_at;
  v_card.is_mine := p_viewer is not null and p_drop.author_id is not null and p_drop.author_id = p_viewer;
  v_card.source := p_drop.source;

  if p_drop.author_id is not null
     and not (p_drop.display_anonymous and v_category.allow_anonymous_display) then
    v_card.author_handle := (select p.handle from public.profiles p where p.id = p_drop.author_id);
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

  -- Public records are not a secret. The short summary and the attribution
  -- are readable from the pin. A user drop still waits until you are close.
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

create or replace function public.drop_teaser(p_short_id text)
returns public.drop_card
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_drop public.drops;
  v_card public.drop_card;
begin
  select * into v_drop from public.drops d where d.short_id = p_short_id;
  if not found or not private.can_view_drop(v_drop, v_uid) then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;

  v_card := private.to_drop_card(v_drop, v_uid, null);
  -- Share pages keep user drops locked. A public record is already public,
  -- so its summary and attribution stay on the card.
  if v_drop.source is distinct from 'open_data' then
    v_card.revealed := false;
    v_card.body := null;
    v_card.attributes := null;
    v_card.media := null;
    v_card.tags := null;
  end if;
  return v_card;
end;
$$;

-- Return type gains `source`. CREATE OR REPLACE cannot change it.
drop function public.drops_in_view(
  double precision, double precision, double precision, double precision, text[], integer
);

create function public.drops_in_view(
  p_min_lng double precision,
  p_min_lat double precision,
  p_max_lng double precision,
  p_max_lat double precision,
  p_categories text[] default null,
  p_zoom integer default 16
)
returns table (
  kind text,
  drop_id uuid,
  short_id text,
  category_slug text,
  group_slug text,
  title text,
  lat double precision,
  lng double precision,
  point_count integer,
  category_counts jsonb,
  source text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_envelope extensions.geography;
  v_cell double precision;
begin
  perform private.make_point(p_min_lat, p_min_lng);
  perform private.make_point(p_max_lat, p_max_lng);
  if p_min_lng >= p_max_lng or p_min_lat >= p_max_lat then
    raise exception 'invalid_bbox' using errcode = '22023', detail = 'min must be below max on both axes';
  end if;
  if p_zoom is null or p_zoom not between 0 and 22 then
    raise exception 'invalid_zoom' using errcode = '22023', detail = 'zoom must be 0..22';
  end if;

  v_envelope := extensions.st_makeenvelope(p_min_lng, p_min_lat, p_max_lng, p_max_lat, 4326)::extensions.geography;

  if p_zoom >= 15 then
    return query
    select 'drop'::text, d.id, d.short_id, c.slug, g.slug, d.title,
           extensions.st_y(d.display_geog::extensions.geometry),
           extensions.st_x(d.display_geog::extensions.geometry),
           1, null::jsonb, d.source::text
    from public.drops d
    join public.categories c on c.id = d.category_id
    join public.category_groups g on g.id = c.group_id
    where extensions.st_intersects(d.display_geog, v_envelope)
      and (p_categories is null or c.slug = any (p_categories) or g.slug = any (p_categories))
      and private.can_view_drop(d, v_uid)
    order by d.created_at desc
    limit 500;
    return;
  end if;

  v_cell := 360.0 / power(2, p_zoom) / 8;

  return query
  with visible as (
    select d.id, d.short_id, d.title, c.slug as cslug, g.slug as gslug, d.source::text as source,
           extensions.st_y(d.display_geog::extensions.geometry) as y,
           extensions.st_x(d.display_geog::extensions.geometry) as x
    from public.drops d
    join public.categories c on c.id = d.category_id
    join public.category_groups g on g.id = c.group_id
    where extensions.st_intersects(d.display_geog, v_envelope)
      and (p_categories is null or c.slug = any (p_categories) or g.slug = any (p_categories))
      and private.can_view_drop(d, v_uid)
  ),
  per_category as (
    select floor(v.x / v_cell) as cx, floor(v.y / v_cell) as cy, v.cslug, count(*)::integer as n
    from visible v
    group by 1, 2, 3
  ),
  cells as (
    select pc.cx, pc.cy, jsonb_object_agg(pc.cslug, pc.n) as counts
    from per_category pc
    group by pc.cx, pc.cy
  ),
  grouped as (
    select floor(v.x / v_cell) as cx, floor(v.y / v_cell) as cy,
           count(*)::integer as n,
           avg(v.y) as y, avg(v.x) as x,
           (array_agg(v.id))[1] as id,
           (array_agg(v.short_id))[1] as short_id,
           (array_agg(v.cslug))[1] as cslug,
           (array_agg(v.gslug))[1] as gslug,
           (array_agg(v.title))[1] as title,
           (array_agg(v.source))[1] as source
    from visible v
    group by 1, 2
  )
  select case when gr.n = 1 then 'drop' else 'cluster' end,
         case when gr.n = 1 then gr.id end,
         case when gr.n = 1 then gr.short_id end,
         case when gr.n = 1 then gr.cslug end,
         case when gr.n = 1 then gr.gslug end,
         case when gr.n = 1 then gr.title end,
         gr.y, gr.x, gr.n, ce.counts,
         case when gr.n = 1 then gr.source end
  from grouped gr
  join cells ce on ce.cx = gr.cx and ce.cy = gr.cy
  order by gr.n desc
  limit 1000;
end;
$$;

revoke execute on function public.drops_in_view(
  double precision, double precision, double precision, double precision, text[], integer
) from public, anon, authenticated;

grant execute on function public.drops_in_view(
  double precision, double precision, double precision, double precision, text[], integer
) to anon, authenticated, service_role;

-- ---------------------------------------------------------------------------
-- Importer entry point. The array is the full snapshot for each
-- (source_slug, region_id) it contains. Rows from that pair that are missing
-- are marked removed so a re-run does not leave stale pins.
-- ---------------------------------------------------------------------------
create function private.upsert_open_data_items(p_items jsonb)
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
            geog, display_geog, reveal_radius_m, status
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
            'active'
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
          expires_at = null
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
      and d.status in ('active', 'confirmed')
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

revoke execute on function private.upsert_open_data_items(jsonb) from public, anon, authenticated;
grant execute on function private.upsert_open_data_items(jsonb) to service_role;

-- A public record is not something a person confirms. Confirmations are how
-- user drops earn trust, and these rows must not become a contribution.
create function private.reject_open_data_confirmation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if exists (
    select 1 from public.drops d
    where d.id = new.drop_id and d.source = 'open_data'
  ) then
    raise exception 'not_a_user_drop' using errcode = '22023',
      detail = 'public records are not user contributions';
  end if;
  return new;
end;
$$;

create trigger confirmations_reject_open_data
  before insert or update on public.confirmations
  for each row execute function private.reject_open_data_confirmation();

revoke execute on function private.reject_open_data_confirmation() from public, anon, authenticated;
