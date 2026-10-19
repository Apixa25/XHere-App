-- Time Machine and "This day here".
-- XHere is global. Nothing here names a city. A region file (and the seed
-- that loads it) says which town and state a box belongs to. Sanborn sheets,
-- LoC photos, Commons photos, and Chronicling America lines are a cache so
-- the phone does not call loc.gov. A sheet is returned for the map overlay
-- only when it has at least three control points.

create table public.history_regions (
  region_id text primary key check (region_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  name text not null check (char_length(btrim(name)) between 1 and 120),
  country text not null check (country ~ '^[A-Z]{2}$'),
  town text not null check (char_length(btrim(town)) between 1 and 80),
  state text not null check (char_length(btrim(state)) between 1 and 80),
  west double precision not null,
  south double precision not null,
  east double precision not null,
  north double precision not null,
  updated_at timestamptz not null default now(),
  check (west < east and south < north),
  check (south >= -90 and north <= 90 and west >= -180 and east <= 180)
);

create table public.history_sheets (
  region_id text not null references public.history_regions (region_id) on delete cascade,
  external_id text not null,
  title text not null,
  year integer,
  date_label text not null default '',
  item_url text not null,
  iiif_service text,
  thumbnail_url text,
  sheet_index integer,
  digitized boolean not null default false,
  attribution text not null,
  license text not null,
  primary key (region_id, external_id),
  check (item_url ~ '^https://'),
  check (iiif_service is null or iiif_service ~ '^https://'),
  check (year is null or year between 1700 and 2100)
);

create table public.history_photos (
  region_id text not null references public.history_regions (region_id) on delete cascade,
  external_id text not null,
  source text not null check (source in ('loc', 'commons')),
  title text not null,
  year integer,
  date_label text not null default '',
  lat double precision,
  lng double precision,
  thumb_url text,
  image_url text,
  page_url text not null,
  creator text,
  license text not null,
  attribution text not null,
  primary key (region_id, external_id),
  check (page_url ~ '^https://'),
  check (lat is null or lat between -90 and 90),
  check (lng is null or lng between -180 and 180),
  check ((lat is null) = (lng is null)),
  check (year is null or year between 1700 and 2100)
);

create table public.history_georef (
  region_id text not null,
  external_id text not null,
  width integer not null check (width between 2 and 200000),
  height integer not null check (height between 2 and 200000),
  image_url text not null check (image_url ~ '^https://'),
  control_points jsonb not null,
  attribution text not null,
  primary key (region_id, external_id),
  foreign key (region_id, external_id)
    references public.history_sheets (region_id, external_id) on delete cascade,
  check (jsonb_typeof(control_points) = 'array')
);

create table public.history_clippings (
  region_id text not null references public.history_regions (region_id) on delete cascade,
  external_id text not null,
  terms text[] not null,
  title text not null,
  year integer not null,
  month integer not null,
  day integer not null,
  snippet text not null,
  page_url text not null,
  image_url text,
  paper text not null,
  paper_city text not null,
  paper_state text not null,
  attribution text not null,
  license text not null,
  primary key (region_id, external_id),
  check (page_url ~ '^https://'),
  check (month between 1 and 12 and day between 1 and 31),
  check (char_length(snippet) between 40 and 600),
  check (cardinality(terms) >= 1)
);

create index history_photos_region_idx on public.history_photos (region_id);
create index history_clippings_region_idx on public.history_clippings (region_id);

alter table public.history_regions enable row level security;
alter table public.history_sheets enable row level security;
alter table public.history_photos enable row level security;
alter table public.history_georef enable row level security;
alter table public.history_clippings enable row level security;

revoke all on table public.history_regions from public, anon, authenticated;
revoke all on table public.history_sheets from public, anon, authenticated;
revoke all on table public.history_photos from public, anon, authenticated;
revoke all on table public.history_georef from public, anon, authenticated;
revoke all on table public.history_clippings from public, anon, authenticated;

comment on table public.history_regions is
  'Per-region history config. The box picks the town. Not a user location trail.';
comment on table public.history_georef is
  'Control points for one Sanborn sheet. The map overlay is omitted until there are at least three.';

-- One upstream loc.gov or Wikimedia call at a time, so a refresh stays polite.
create table private.history_fetch_gate (
  id integer primary key check (id = 1),
  last_at timestamptz not null default '-infinity'
);
insert into private.history_fetch_gate (id) values (1);
alter table private.history_fetch_gate enable row level security;
revoke all on table private.history_fetch_gate from public, anon, authenticated;

create function private.upsert_history(p_doc jsonb)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_region jsonb := p_doc -> 'region';
  v_id text := v_region ->> 'region_id';
  v_sheet jsonb;
  v_photo jsonb;
  v_geo jsonb;
  v_clip jsonb;
begin
  if v_id is null or v_id !~ '^[a-z0-9]+(-[a-z0-9]+)*$' then
    raise exception 'invalid_region' using errcode = '22023';
  end if;
  if coalesce(v_region ->> 'country', '') !~ '^[A-Z]{2}$' then
    raise exception 'invalid_region' using errcode = '22023';
  end if;
  insert into public.history_regions (
    region_id, name, country, town, state, west, south, east, north
  )
  values (
    v_id,
    btrim(v_region ->> 'name'),
    v_region ->> 'country',
    btrim(v_region ->> 'town'),
    btrim(v_region ->> 'state'),
    (v_region ->> 'west')::double precision,
    (v_region ->> 'south')::double precision,
    (v_region ->> 'east')::double precision,
    (v_region ->> 'north')::double precision
  )
  on conflict (region_id) do update
    set name = excluded.name,
        country = excluded.country,
        town = excluded.town,
        state = excluded.state,
        west = excluded.west,
        south = excluded.south,
        east = excluded.east,
        north = excluded.north,
        updated_at = now();

  delete from public.history_clippings where region_id = v_id;
  delete from public.history_photos where region_id = v_id;
  delete from public.history_sheets where region_id = v_id;

  for v_sheet in select * from jsonb_array_elements(coalesce(p_doc -> 'sheets', '[]'::jsonb))
  loop
    insert into public.history_sheets (
      region_id, external_id, title, year, date_label, item_url, iiif_service,
      thumbnail_url, sheet_index, digitized, attribution, license
    )
    values (
      v_id,
      v_sheet ->> 'id',
      btrim(v_sheet ->> 'title'),
      nullif(v_sheet ->> 'year', '')::integer,
      coalesce(v_sheet ->> 'date_label', ''),
      v_sheet ->> 'item_url',
      nullif(v_sheet ->> 'iiif_service', ''),
      nullif(v_sheet ->> 'thumbnail_url', ''),
      nullif(v_sheet ->> 'sheet_index', '')::integer,
      coalesce((v_sheet ->> 'digitized')::boolean, false),
      v_sheet ->> 'attribution',
      v_sheet ->> 'license'
    );
  end loop;

  for v_photo in select * from jsonb_array_elements(coalesce(p_doc -> 'photos', '[]'::jsonb))
  loop
    insert into public.history_photos (
      region_id, external_id, source, title, year, date_label, lat, lng,
      thumb_url, image_url, page_url, creator, license, attribution
    )
    values (
      v_id,
      v_photo ->> 'id',
      v_photo ->> 'source',
      btrim(v_photo ->> 'title'),
      nullif(v_photo ->> 'year', '')::integer,
      coalesce(v_photo ->> 'date_label', ''),
      nullif(v_photo ->> 'latitude', '')::double precision,
      nullif(v_photo ->> 'longitude', '')::double precision,
      nullif(v_photo ->> 'thumb_url', ''),
      nullif(v_photo ->> 'image_url', ''),
      v_photo ->> 'page_url',
      nullif(v_photo ->> 'creator', ''),
      v_photo ->> 'license',
      v_photo ->> 'attribution'
    );
  end loop;

  for v_geo in select * from jsonb_array_elements(coalesce(p_doc -> 'georef', '[]'::jsonb))
  loop
    if jsonb_typeof(v_geo -> 'points') = 'array'
       and jsonb_array_length(v_geo -> 'points') >= 3 then
      insert into public.history_georef (
        region_id, external_id, width, height, image_url, control_points, attribution
      )
      values (
        v_id,
        v_geo ->> 'sheet_id',
        (v_geo ->> 'width')::integer,
        (v_geo ->> 'height')::integer,
        v_geo ->> 'image_url',
        v_geo -> 'points',
        v_geo ->> 'attribution'
      );
    end if;
  end loop;

  for v_clip in select * from jsonb_array_elements(coalesce(p_doc -> 'clippings', '[]'::jsonb))
  loop
    insert into public.history_clippings (
      region_id, external_id, terms, title, year, month, day, snippet,
      page_url, image_url, paper, paper_city, paper_state, attribution, license
    )
    values (
      v_id,
      v_clip ->> 'id',
      (
        select coalesce(array_agg(lower(btrim(value))), '{}')
        from jsonb_array_elements_text(v_clip -> 'terms')
        where char_length(btrim(value)) between 3 and 80
      ),
      btrim(v_clip ->> 'title'),
      (v_clip ->> 'year')::integer,
      (v_clip ->> 'month')::integer,
      (v_clip ->> 'day')::integer,
      v_clip ->> 'snippet',
      v_clip ->> 'page_url',
      nullif(v_clip ->> 'image_url', ''),
      v_clip ->> 'paper',
      v_clip ->> 'paper_city',
      v_clip ->> 'paper_state',
      v_clip ->> 'attribution',
      v_clip ->> 'license'
    );
  end loop;
end;
$$;

revoke execute on function private.upsert_history(jsonb) from public, anon, authenticated;
grant execute on function private.upsert_history(jsonb) to service_role;

create function private.claim_history_fetch()
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_ok boolean;
begin
  update private.history_fetch_gate
    set last_at = now()
    where id = 1
      and last_at <= now() - interval '2 seconds'
    returning true into v_ok;
  return coalesce(v_ok, false);
end;
$$;

revoke execute on function private.claim_history_fetch() from public, anon, authenticated;
grant execute on function private.claim_history_fetch() to service_role;

create function public.history_at(p_lat double precision, p_lng double precision)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_id text;
  v_town text;
  v_state text;
begin
  perform private.make_point(p_lat, p_lng);
  select r.region_id, r.town, r.state
    into v_id, v_town, v_state
  from public.history_regions r
  where p_lng >= r.west and p_lng <= r.east
    and p_lat >= r.south and p_lat <= r.north
  order by (r.east - r.west) * (r.north - r.south), r.region_id
  limit 1;

  return jsonb_build_object(
    'region_id', v_id,
    'town', coalesce(v_town, ''),
    'state', coalesce(v_state, ''),
    'sheets', coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', s.external_id,
        'title', s.title,
        'year', s.year,
        'date_label', s.date_label,
        'item_url', s.item_url,
        'iiif_service', s.iiif_service,
        'thumbnail_url', s.thumbnail_url,
        'sheet_index', s.sheet_index,
        'digitized', s.digitized,
        'attribution', s.attribution,
        'license', s.license
      ) order by s.year nulls last, s.sheet_index nulls last, s.title)
      from public.history_sheets s
      where s.region_id = v_id
    ), '[]'::jsonb),
    'photos', coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', p.external_id,
        'source', p.source,
        'title', p.title,
        'year', p.year,
        'date_label', p.date_label,
        'latitude', p.lat,
        'longitude', p.lng,
        'thumb_url', p.thumb_url,
        'image_url', p.image_url,
        'page_url', p.page_url,
        'creator', p.creator,
        'license', p.license,
        'attribution', p.attribution
      ) order by p.year nulls last, p.title)
      from public.history_photos p
      where p.region_id = v_id
        and (
          p.lat is null
          or extensions.st_dwithin(
            extensions.st_setsrid(extensions.st_makepoint(p.lng, p.lat), 4326)::extensions.geography,
            private.make_point(p_lat, p_lng),
            800
          )
        )
    ), '[]'::jsonb),
    'georef', coalesce((
      select jsonb_agg(jsonb_build_object(
        'sheet_id', g.external_id,
        'width', g.width,
        'height', g.height,
        'image_url', g.image_url,
        'points', g.control_points,
        'attribution', g.attribution
      ))
      from public.history_georef g
      where g.region_id = v_id
        and jsonb_array_length(g.control_points) >= 3
    ), '[]'::jsonb)
  );
end;
$$;

revoke execute on function public.history_at(double precision, double precision) from public, anon;
grant execute on function public.history_at(double precision, double precision) to authenticated;

create function public.clippings_at(
  p_lat double precision,
  p_lng double precision,
  p_terms text[]
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_id text;
  v_terms text[];
begin
  perform private.make_point(p_lat, p_lng);
  select r.region_id into v_id
  from public.history_regions r
  where p_lng >= r.west and p_lng <= r.east
    and p_lat >= r.south and p_lat <= r.north
  order by (r.east - r.west) * (r.north - r.south), r.region_id
  limit 1;

  select coalesce(array_agg(distinct lower(btrim(term))), '{}')
    into v_terms
  from unnest(coalesce(p_terms, '{}')) as term
  where char_length(btrim(term)) between 3 and 80;

  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', c.external_id,
      'terms', to_jsonb(c.terms),
      'title', c.title,
      'year', c.year,
      'month', c.month,
      'day', c.day,
      'snippet', c.snippet,
      'page_url', c.page_url,
      'image_url', c.image_url,
      'paper', c.paper,
      'paper_city', c.paper_city,
      'paper_state', c.paper_state,
      'attribution', c.attribution,
      'license', c.license
    ) order by c.year desc, c.month desc, c.day desc)
    from public.history_clippings c
    where c.region_id = v_id
      and exists (
        select 1
        from unnest(c.terms) as stored(term)
        where lower(btrim(stored.term)) = any (v_terms)
      )
  ), '[]'::jsonb);
end;
$$;

revoke execute on function public.clippings_at(double precision, double precision, text[]) from public, anon;
grant execute on function public.clippings_at(double precision, double precision, text[]) to authenticated;

-- The history Edge Function calls these with the service role. PostgREST only
-- exposes public. anon and signed-in clients cannot refresh or replace the cache.
create function public.history_upsert(p_doc jsonb)
returns void
language sql
volatile
security definer
set search_path = ''
as $$ select private.upsert_history(p_doc); $$;

revoke execute on function public.history_upsert(jsonb) from public, anon, authenticated;
grant execute on function public.history_upsert(jsonb) to service_role;

create function public.history_claim_fetch()
returns boolean
language sql
volatile
security definer
set search_path = ''
as $$ select private.claim_history_fetch(); $$;

revoke execute on function public.history_claim_fetch() from public, anon, authenticated;
grant execute on function public.history_claim_fetch() to service_role;

create function public.history_bundle(p_region_id text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_row public.history_regions;
begin
  if p_region_id is null or p_region_id !~ '^[a-z0-9]+(-[a-z0-9]+)*$' then
    raise exception 'invalid_region' using errcode = '22023';
  end if;
  select * into v_row from public.history_regions where region_id = p_region_id;
  if not found then
    return 'null'::jsonb;
  end if;
  return jsonb_build_object(
    'region_id', v_row.region_id,
    'name', v_row.name,
    'country', v_row.country,
    'town', v_row.town,
    'state', v_row.state,
    'west', v_row.west,
    'south', v_row.south,
    'east', v_row.east,
    'north', v_row.north,
    'updated_at', v_row.updated_at,
    'sheets', coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', s.external_id,
        'title', s.title,
        'year', s.year,
        'date_label', s.date_label,
        'item_url', s.item_url,
        'iiif_service', s.iiif_service,
        'thumbnail_url', s.thumbnail_url,
        'sheet_index', s.sheet_index,
        'digitized', s.digitized,
        'attribution', s.attribution,
        'license', s.license
      ))
      from public.history_sheets s
      where s.region_id = v_row.region_id
    ), '[]'::jsonb),
    'photos', coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', p.external_id,
        'source', p.source,
        'title', p.title,
        'year', p.year,
        'date_label', p.date_label,
        'latitude', p.lat,
        'longitude', p.lng,
        'thumb_url', p.thumb_url,
        'image_url', p.image_url,
        'page_url', p.page_url,
        'creator', p.creator,
        'license', p.license,
        'attribution', p.attribution
      ))
      from public.history_photos p
      where p.region_id = v_row.region_id
    ), '[]'::jsonb),
    'georef', coalesce((
      select jsonb_agg(jsonb_build_object(
        'sheet_id', g.external_id,
        'width', g.width,
        'height', g.height,
        'image_url', g.image_url,
        'points', g.control_points,
        'attribution', g.attribution
      ))
      from public.history_georef g
      where g.region_id = v_row.region_id
    ), '[]'::jsonb),
    'clippings', coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', c.external_id,
        'terms', to_jsonb(c.terms),
        'title', c.title,
        'year', c.year,
        'month', c.month,
        'day', c.day,
        'snippet', c.snippet,
        'page_url', c.page_url,
        'image_url', c.image_url,
        'paper', c.paper,
        'paper_city', c.paper_city,
        'paper_state', c.paper_state,
        'attribution', c.attribution,
        'license', c.license
      ))
      from public.history_clippings c
      where c.region_id = v_row.region_id
    ), '[]'::jsonb)
  );
end;
$$;

revoke execute on function public.history_bundle(text) from public, anon, authenticated;
grant execute on function public.history_bundle(text) to service_role;
