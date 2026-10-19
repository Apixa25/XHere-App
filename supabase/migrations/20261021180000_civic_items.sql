-- Town Hall (PLAN §3 open-data layers, docs/OPEN-DATA-PLAYBOOK.md, docs/TOWN-HALL.md).
--
-- Agendas, hearings, permits, and notices are public records, not drops.
-- A person cannot post one. Each region names its sources in a civic: block.
-- The importer calls private.upsert_civic_items. The phone reads the RPCs.
-- Nothing here calls a language model or a paid geocoder.

-- ---------------------------------------------------------------------------
-- Taxonomy. Four categories, one group. The map chip uses the group.
-- Suggestion skips these slugs so a typed "permit" stays a user category.
-- ---------------------------------------------------------------------------
insert into public.category_groups (slug, label, description, icon, color, sort)
values (
  'town_hall',
  'Town Hall',
  'Agendas, hearings, permits, and notices published by a public body.',
  '📋',
  '#1565C0',
  12
);

insert into public.categories (
  group_id, slug, label, description, icon, color, attributes_schema,
  min_tier_to_post, moderation_mode, sensitive, posting_notice, content_filters,
  default_reveal_m, max_reveal_m, location_precision_m,
  confirmations_to_confirm, denials_to_hide, reports_to_hide,
  allow_anonymous_display, is_builtin, sort, suggestion_hints
)
select g.id, v.slug, v.label, v.description, v.icon, v.color,
       '{"type":"object","additionalProperties":false}'::jsonb,
       'new', 'post', false,
       'Town Hall rows come from a public source. A person cannot post one.',
       '{}',
       300, 300, 0,
       3, 3, 3,
       true, true, v.sort, v.hints
from public.category_groups g
cross join (values
  ('civic_agenda', 'Agenda', 'A published meeting line from a council or committee.', '📋', '#1565C0', 1, 'townhallboard agendaitem'),
  ('civic_hearing', 'Hearing', 'A published zoning session from a public body.', '📣', '#6A1B9A', 2, 'townhallboard zoninghearing'),
  ('civic_permit', 'Permit', 'A published works approval from a public body.', '🏗️', '#E65100', 3, 'townhallboard worksapproval'),
  ('civic_notice', 'Notice', 'A published town-hall posting from a public body.', '📌', '#2E7D32', 4, 'townhallboard townhallposting')
) as v(slug, label, description, icon, color, sort, hints)
where g.slug = 'town_hall';

update public.categories
set embedding = private.hash_embed(
  replace(slug, '_', ' ') || ' ' || label || ' ' || coalesce(description, '') || ' ' || coalesce(suggestion_hints, '')
)
where slug in ('civic_agenda', 'civic_hearing', 'civic_permit', 'civic_notice');

-- People post drops. These four slugs are reserved for the importer.
create function private.reject_civic_user_drop()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_slug text;
begin
  select c.slug into v_slug from public.categories c where c.id = new.category_id;
  -- civic_issue stays a user and Open311 category. Only the Town Hall slugs are reserved.
  if v_slug in ('civic_agenda', 'civic_hearing', 'civic_permit', 'civic_notice') then
    raise exception 'civic_not_a_user_drop' using errcode = '42501',
      detail = 'Town Hall records come from a public source, not a person.';
  end if;
  return new;
end;
$$;

create trigger drops_reject_civic_user_drop
  before insert or update of category_id on public.drops
  for each row execute function private.reject_civic_user_drop();

-- Keep the suggester from offering a civic slug. The fixture set stays put.
create or replace function public.suggest_categories(
  p_text text,
  p_lat double precision default null,
  p_lng double precision default null,
  p_hour integer default null,
  p_recent text[] default '{}',
  p_limit integer default 3
)
returns table (slug text, label text, score double precision, cosine double precision)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_query extensions.vector(384);
  v_blank boolean;
  v_limit integer := least(greatest(coalesce(p_limit, 3), 1), 10);
begin
  v_query := private.hash_embed(coalesce(p_text, ''));
  select not exists (
    select 1
    from regexp_split_to_table(
      lower(replace(replace(coalesce(p_text, ''), '_', ' '), '-', ' ')),
      '[^a-z0-9]+'
    ) as tok
    where char_length(tok) >= 2
  ) into v_blank;

  return query
  with scored as (
    select c.slug, c.label,
           case when v_blank then 0::float8
                else (1 - (c.embedding operator(extensions.<=>) v_query))::float8
           end as cosine,
           coalesce(n.n, 0) as nearby_n,
           (c.slug = any(coalesce(p_recent, '{}'))) as recent,
           case
             when p_hour is null then 0::float8
             when c.slug in ('food_truck', 'market', 'eatery_tip') and (p_hour between 11 and 14 or p_hour between 17 and 20) then 1::float8
             when c.slug in ('event', 'popup', 'music_spot') and p_hour between 16 and 22 then 1::float8
             when c.slug in ('yard_sale', 'free_stuff', 'for_sale') and p_hour between 8 and 16 then 1::float8
             when c.slug = 'wildlife' and (p_hour between 5 and 9 or p_hour between 17 and 20) then 1::float8
             else 0::float8
           end as time_boost
    from public.categories c
    left join public.nearby_category_counts(p_lat, p_lng, 500) n on n.slug = c.slug
    where c.active
      and c.embedding is not null
      and c.slug not in ('civic_agenda', 'civic_hearing', 'civic_permit', 'civic_notice')
  ),
  best as (
    select coalesce(max(scored.cosine), 0) as best_cosine,
           coalesce(max(scored.nearby_n), 1) as max_nearby
    from scored
  )
  select scored.slug, scored.label,
         (scored.cosine
           + 0.18 * (case when best.max_nearby > 0 then scored.nearby_n::float8 / best.max_nearby else 0 end)
           + 0.12 * (case when scored.recent then 1 else 0 end)
           + 0.08 * scored.time_boost)::double precision as score,
         scored.cosine::double precision
  from scored
  cross join best
  order by
    case when (v_blank or best.best_cosine < 0.02) and scored.slug = 'note' then 0 else 1 end,
    (scored.cosine
      + 0.18 * (case when best.max_nearby > 0 then scored.nearby_n::float8 / best.max_nearby else 0 end)
      + 0.12 * (case when scored.recent then 1 else 0 end)
      + 0.08 * scored.time_boost) desc,
    scored.slug
  limit v_limit;
end;
$$;

-- ---------------------------------------------------------------------------
-- Records
-- ---------------------------------------------------------------------------
create table public.civic_items (
  id uuid primary key default gen_random_uuid(),
  region_id text not null check (region_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  source_slug text not null check (source_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  external_id text not null check (char_length(external_id) between 1 and 180),
  item_type text not null check (item_type in ('agenda_item', 'hearing', 'permit', 'notice')),
  title text not null check (char_length(btrim(title)) between 1 and 160),
  summary text check (summary is null or char_length(summary) <= 400),
  occurs_on date,
  body_name text check (body_name is null or char_length(body_name) <= 120),
  source_url text not null check (source_url ~ '^https://' and char_length(source_url) <= 500),
  address_text text check (address_text is null or char_length(address_text) <= 200),
  geom extensions.geography(Point, 4326),
  attribution text not null check (char_length(btrim(attribution)) between 1 and 300),
  license text not null check (char_length(btrim(license)) between 1 and 200),
  status text not null default 'active' check (status in ('active', 'archived')),
  expires_at timestamptz,
  created_at timestamptz not null default pg_catalog.now(),
  updated_at timestamptz not null default pg_catalog.now(),
  unique (region_id, source_slug, external_id)
);
comment on table public.civic_items is
  'Public agendas, hearings, permits, and notices. Not a person''s drop. Geometry may be null when no address matched.';

create index civic_items_geom_idx on public.civic_items using gist (geom);
create index civic_items_region_idx on public.civic_items (region_id, status, occurs_on);

create trigger civic_items_set_updated_at before update on public.civic_items
  for each row execute function private.set_updated_at();

create table public.civic_follows (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  region_id text not null check (region_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  address_key text not null check (char_length(address_key) between 3 and 200),
  address_text text not null check (char_length(btrim(address_text)) between 1 and 200),
  created_at timestamptz not null default pg_catalog.now(),
  unique (user_id, region_id, address_key)
);
comment on table public.civic_follows is
  'An address a person asked to hear about, inside one region. The key is the comparable street form.';

create index civic_follows_region_idx on public.civic_follows (region_id, address_key);

create table public.civic_notices (
  id uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references public.profiles (id) on delete cascade,
  civic_item_id uuid not null references public.civic_items (id) on delete cascade,
  read_at timestamptz,
  created_at timestamptz not null default pg_catalog.now(),
  unique (recipient_id, civic_item_id)
);
comment on table public.civic_notices is
  'Inbox row when a followed address matches a new Town Hall record. Shown with impact notices.';

create index civic_notices_recipient_idx on public.civic_notices (recipient_id, created_at desc);
create index civic_notices_item_idx on public.civic_notices (civic_item_id);

alter table public.civic_items enable row level security;
alter table public.civic_follows enable row level security;
alter table public.civic_notices enable row level security;
revoke all on table public.civic_items, public.civic_follows, public.civic_notices from public, anon, authenticated;
grant select on public.civic_items to anon, authenticated;

create policy civic_items_select_active on public.civic_items
  for select to anon, authenticated
  using (status = 'active' and (expires_at is null or expires_at > pg_catalog.now()));

-- Follows and notices are read through security-definer RPCs. No client policy.

-- ---------------------------------------------------------------------------
-- Address keys. Same rules as Civic.addressKey and import_civic.address_key.
-- ---------------------------------------------------------------------------
create function private.civic_address_key(p_value text)
returns text
language plpgsql
stable
set search_path = ''
as $$
declare
  v text;
  v_token text;
  v_out text[] := array[]::text[];
begin
  if p_value is null or btrim(p_value) = '' then
    return '';
  end if;
  v := pg_catalog.lower(pg_catalog.replace(p_value, '&', ' and '));
  v := pg_catalog.regexp_replace(v, '[^a-z0-9]+', ' ', 'g');
  v := ' ' || v || ' ';
  v := pg_catalog.replace(v, ' street ', ' st ');
  v := pg_catalog.replace(v, ' avenue ', ' ave ');
  v := pg_catalog.replace(v, ' road ', ' rd ');
  v := pg_catalog.replace(v, ' boulevard ', ' blvd ');
  v := pg_catalog.replace(v, ' drive ', ' dr ');
  v := pg_catalog.replace(v, ' lane ', ' ln ');
  v := pg_catalog.replace(v, ' court ', ' ct ');
  v := pg_catalog.replace(v, ' place ', ' pl ');
  v := pg_catalog.replace(v, ' parkway ', ' pkwy ');
  v := pg_catalog.replace(v, ' highway ', ' hwy ');
  v := pg_catalog.replace(v, ' terrace ', ' ter ');
  v := pg_catalog.replace(v, ' circle ', ' cir ');
  v := pg_catalog.replace(v, ' trail ', ' trl ');
  v := pg_catalog.replace(v, ' first ', ' 1st ');
  v := pg_catalog.replace(v, ' second ', ' 2nd ');
  v := pg_catalog.replace(v, ' third ', ' 3rd ');
  v := pg_catalog.replace(v, ' new york ', ' ');
  v := pg_catalog.replace(v, ' ny ', ' ');
  v := pg_catalog.replace(v, ' usa ', ' ');
  for v_token in
    select tok from pg_catalog.regexp_split_to_table(pg_catalog.btrim(v), '\s+') as tok
  loop
    v_token := case v_token
      when 'e' then 'east'
      when 'w' then 'west'
      when 'n' then 'north'
      when 's' then 'south'
      else v_token
    end;
    if v_token <> '' then
      v_out := v_out || v_token;
    end if;
  end loop;
  if pg_catalog.cardinality(v_out) >= 1 and v_out[pg_catalog.cardinality(v_out)] ~ '^\d{5}$' then
    v_out := v_out[1:pg_catalog.cardinality(v_out) - 1];
  end if;
  return pg_catalog.array_to_string(v_out, ' ');
end;
$$;

create function private.civic_address_match(p_follow text, p_item text)
returns boolean
language sql
stable
set search_path = ''
as $$
  select private.civic_address_key(p_follow) <> ''
    and private.civic_address_key(p_item) <> ''
    and pg_catalog.cardinality(pg_catalog.regexp_split_to_array(private.civic_address_key(p_follow), ' ')) >= 2
    and pg_catalog.strpos(
      ' ' || private.civic_address_key(p_item) || ' ',
      ' ' || private.civic_address_key(p_follow) || ' '
    ) > 0;
$$;

create function private.civic_item_json(p_row public.civic_items, p_distance integer)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'id', p_row.id,
    'region_id', p_row.region_id,
    'item_type', p_row.item_type,
    'title', p_row.title,
    'summary', p_row.summary,
    'occurs_on', p_row.occurs_on,
    'body_name', p_row.body_name,
    'source_url', p_row.source_url,
    'address_text', p_row.address_text,
    'lat', case when p_row.geom is null then null else extensions.st_y(p_row.geom::extensions.geometry) end,
    'lng', case when p_row.geom is null then null else extensions.st_x(p_row.geom::extensions.geometry) end,
    'attribution', p_row.attribution,
    'license', p_row.license,
    'expires_at', p_row.expires_at,
    'source_slug', p_row.source_slug,
    'distance_m', p_distance
  );
$$;

create function private.civic_notify_followers(p_item public.civic_items)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.civic_notices (recipient_id, civic_item_id)
  select f.user_id, p_item.id
  from public.civic_follows f
  where f.region_id = p_item.region_id
    and private.civic_address_match(f.address_text, p_item.address_text)
  on conflict (recipient_id, civic_item_id) do nothing;
end;
$$;

create function private.upsert_civic_items(p_items jsonb)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_row jsonb;
  v_region text;
  v_source text;
  v_external text;
  v_type text;
  v_title text;
  v_summary text;
  v_occurs date;
  v_body text;
  v_url text;
  v_address text;
  v_lat double precision;
  v_lng double precision;
  v_attr text;
  v_license text;
  v_expires timestamptz;
  v_geom extensions.geography;
  v_existed boolean;
  v_item public.civic_items;
  v_count integer := 0;
begin
  if p_items is null or pg_catalog.jsonb_typeof(p_items) <> 'array' then
    raise exception 'invalid_civic_items' using errcode = '22023', detail = 'items must be a JSON array';
  end if;
  for v_row in select value from pg_catalog.jsonb_array_elements(p_items) as value
  loop
    v_region := v_row ->> 'region_id';
    v_source := v_row ->> 'source_slug';
    v_external := v_row ->> 'external_id';
    v_type := v_row ->> 'item_type';
    v_title := pg_catalog.btrim(v_row ->> 'title');
    v_summary := nullif(pg_catalog.btrim(v_row ->> 'summary'), '');
    v_body := nullif(pg_catalog.btrim(v_row ->> 'body_name'), '');
    v_url := v_row ->> 'source_url';
    v_address := nullif(pg_catalog.btrim(v_row ->> 'address_text'), '');
    v_attr := pg_catalog.btrim(v_row ->> 'attribution');
    v_license := pg_catalog.btrim(v_row ->> 'license');
    if v_row ->> 'occurs_on' is null or v_row ->> 'occurs_on' = '' then
      v_occurs := null;
    else
      v_occurs := (v_row ->> 'occurs_on')::date;
    end if;
    if v_row ->> 'expires_at' is null or v_row ->> 'expires_at' = '' then
      v_expires := null;
    else
      v_expires := (v_row ->> 'expires_at')::timestamptz;
    end if;
    if v_row ->> 'lat' is null or v_row ->> 'lat' = 'null' or v_row ->> 'lng' is null or v_row ->> 'lng' = 'null' then
      v_lat := null;
      v_lng := null;
      v_geom := null;
    else
      v_lat := (v_row ->> 'lat')::double precision;
      v_lng := (v_row ->> 'lng')::double precision;
      v_geom := private.make_point(v_lat, v_lng);
    end if;
    if v_region is null or v_source is null or v_external is null or v_type is null
       or v_title is null or v_url is null or v_attr is null or v_license is null
       or v_type not in ('agenda_item', 'hearing', 'permit', 'notice')
       or v_url !~ '^https://'
       or char_length(v_title) > 160
       or char_length(v_external) > 180 then
      raise exception 'invalid_civic_item' using errcode = '22023', detail = coalesce(v_external, v_title, 'row');
    end if;
    select exists (
      select 1 from public.civic_items i
      where i.region_id = v_region and i.source_slug = v_source and i.external_id = v_external
    ) into v_existed;
    insert into public.civic_items as i (
      region_id, source_slug, external_id, item_type, title, summary, occurs_on,
      body_name, source_url, address_text, geom, attribution, license, status, expires_at
    ) values (
      v_region, v_source, v_external, v_type, v_title, v_summary, v_occurs,
      v_body, v_url, v_address, v_geom, v_attr, v_license, 'active', v_expires
    )
    on conflict (region_id, source_slug, external_id) do update
      set item_type = excluded.item_type,
          title = excluded.title,
          summary = excluded.summary,
          occurs_on = excluded.occurs_on,
          body_name = excluded.body_name,
          source_url = excluded.source_url,
          address_text = excluded.address_text,
          geom = excluded.geom,
          attribution = excluded.attribution,
          license = excluded.license,
          status = 'active',
          expires_at = excluded.expires_at
    returning * into v_item;
    if not v_existed then
      perform private.civic_notify_followers(v_item);
    end if;
    v_count := v_count + 1;
  end loop;

  update public.civic_items i
  set status = 'archived'
  where i.status = 'active'
    and exists (
      select 1 from pg_catalog.jsonb_array_elements(p_items) e
      where e ->> 'region_id' = i.region_id and e ->> 'source_slug' = i.source_slug
    )
    and not exists (
      select 1 from pg_catalog.jsonb_array_elements(p_items) e
      where e ->> 'region_id' = i.region_id
        and e ->> 'source_slug' = i.source_slug
        and e ->> 'external_id' = i.external_id
    );
  return v_count;
end;
$$;

create function private.archive_civic_items()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  update public.civic_items
  set status = 'archived'
  where status = 'active'
    and expires_at is not null
    and expires_at <= pg_catalog.now();
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

-- ---------------------------------------------------------------------------
-- Reads. Security definer so a null-geometry row in the same region is included
-- even when the select policy is the only direct path. Callers still only see
-- active, unexpired rows.
-- ---------------------------------------------------------------------------
create function public.civic_items_near(
  p_lat double precision,
  p_lng double precision,
  p_radius_m integer default 300
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_point extensions.geography;
  v_radius integer := least(greatest(coalesce(p_radius_m, 300), 1), 2000);
  v_today date := (pg_catalog.now() at time zone 'utc')::date;
begin
  if p_lat is null or p_lng is null then
    return '[]'::jsonb;
  end if;
  v_point := private.make_point(p_lat, p_lng);
  return coalesce((
    select pg_catalog.jsonb_agg(row_json order by (row_json ->> 'distance_m')::int, row_json ->> 'occurs_on', row_json ->> 'title')
    from (
      select private.civic_item_json(i, round(extensions.st_distance(i.geom, v_point))::integer) as row_json
      from public.civic_items i
      where i.status = 'active'
        and (i.expires_at is null or i.expires_at > pg_catalog.now())
        and i.geom is not null
        and extensions.st_dwithin(i.geom, v_point, v_radius)
        and (
          i.item_type in ('permit', 'notice')
          or i.occurs_on is null
          or i.occurs_on >= v_today
        )
    ) s
  ), '[]'::jsonb);
end;
$$;

create function public.civic_items_in_view(
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
  v_box extensions.geometry;
begin
  if p_min_lng is null or p_min_lat is null or p_max_lng is null or p_max_lat is null
     or p_min_lng >= p_max_lng or p_min_lat >= p_max_lat then
    return '[]'::jsonb;
  end if;
  v_box := extensions.st_makeenvelope(p_min_lng, p_min_lat, p_max_lng, p_max_lat, 4326);
  return coalesce((
    select pg_catalog.jsonb_agg(private.civic_item_json(i, null) order by i.occurs_on nulls last, i.title)
    from public.civic_items i
    where i.status = 'active'
      and (i.expires_at is null or i.expires_at > pg_catalog.now())
      and i.geom is not null
      and extensions.st_intersects(i.geom::extensions.geometry, v_box)
  ), '[]'::jsonb);
end;
$$;

create function public.civic_upcoming(
  p_lat double precision,
  p_lng double precision,
  p_radius_m integer default 25000
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_point extensions.geography;
  v_radius integer := least(greatest(coalesce(p_radius_m, 25000), 1), 50000);
  v_today date := (pg_catalog.now() at time zone 'utc')::date;
begin
  if p_lat is null or p_lng is null then
    return '[]'::jsonb;
  end if;
  v_point := private.make_point(p_lat, p_lng);
  return coalesce((
    select pg_catalog.jsonb_agg(row_json order by row_json ->> 'occurs_on', row_json ->> 'title')
    from (
      select private.civic_item_json(
        i,
        case when i.geom is null then null else round(extensions.st_distance(i.geom, v_point))::integer end
      ) as row_json
      from public.civic_items i
      where i.status = 'active'
        and (i.expires_at is null or i.expires_at > pg_catalog.now())
        and i.item_type <> 'permit'
        and (
          i.item_type = 'notice'
          or i.occurs_on is null
          or i.occurs_on >= v_today
        )
        and i.region_id in (
          select near.region_id
          from public.civic_items near
          where near.geom is not null
            and near.status = 'active'
            and extensions.st_dwithin(near.geom, v_point, v_radius)
        )
    ) s
  ), '[]'::jsonb);
end;
$$;

create function public.follow_civic_address(p_region_id text, p_address text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_key text;
  v_text text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_region_id is null or p_region_id !~ '^[a-z0-9]+(-[a-z0-9]+)*$' then
    raise exception 'invalid_region' using errcode = '22023';
  end if;
  v_text := pg_catalog.left(pg_catalog.btrim(coalesce(p_address, '')), 200);
  v_key := private.civic_address_key(v_text);
  if pg_catalog.cardinality(pg_catalog.regexp_split_to_array(v_key, ' ')) < 2 then
    raise exception 'invalid_address' using errcode = '22023', detail = 'an address needs a street, not one word';
  end if;
  if (
    select count(*) from public.civic_follows f where f.user_id = v_uid
  ) >= 30 and not exists (
    select 1 from public.civic_follows f
    where f.user_id = v_uid and f.region_id = p_region_id and f.address_key = v_key
  ) then
    raise exception 'civic_follow_limit' using errcode = '22023', detail = '30 addresses';
  end if;
  insert into public.civic_follows (user_id, region_id, address_key, address_text)
  values (v_uid, p_region_id, v_key, v_text)
  on conflict (user_id, region_id, address_key) do update
    set address_text = excluded.address_text;
  insert into public.civic_notices (recipient_id, civic_item_id)
  select v_uid, i.id
  from public.civic_items i
  where i.region_id = p_region_id
    and i.status = 'active'
    and (i.expires_at is null or i.expires_at > pg_catalog.now())
    and private.civic_address_match(v_text, i.address_text)
  on conflict (recipient_id, civic_item_id) do nothing;
  return pg_catalog.jsonb_build_object('region_id', p_region_id, 'address_key', v_key, 'address_text', v_text);
end;
$$;

create function public.unfollow_civic_address(p_region_id text, p_address text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  delete from public.civic_follows
  where user_id = v_uid
    and region_id = p_region_id
    and address_key = private.civic_address_key(p_address);
end;
$$;

create function public.my_civic_notices(p_limit integer default 40)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_limit integer := least(greatest(coalesce(p_limit, 40), 1), 100);
begin
  if v_uid is null then
    return '[]'::jsonb;
  end if;
  return coalesce((
    select pg_catalog.jsonb_agg(row_json order by row_json ->> 'created_at' desc)
    from (
      select pg_catalog.jsonb_build_object(
        'id', n.id,
        'title', i.title,
        'address_text', i.address_text,
        'source_url', i.source_url,
        'read_at', n.read_at,
        'created_at', n.created_at
      ) as row_json
      from public.civic_notices n
      join public.civic_items i on i.id = n.civic_item_id
      where n.recipient_id = v_uid
      order by n.created_at desc
      limit v_limit
    ) s
  ), '[]'::jsonb);
end;
$$;

create function public.mark_civic_notice_read(p_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  update public.civic_notices
  set read_at = pg_catalog.now()
  where id = p_id and recipient_id = v_uid and read_at is null;
end;
$$;

revoke all on function private.reject_civic_user_drop() from public, anon, authenticated;
revoke all on function private.civic_address_key(text) from public, anon, authenticated;
revoke all on function private.civic_address_match(text, text) from public, anon, authenticated;
revoke all on function private.civic_item_json(public.civic_items, integer) from public, anon, authenticated;
revoke all on function private.civic_notify_followers(public.civic_items) from public, anon, authenticated;
revoke all on function private.upsert_civic_items(jsonb) from public, anon, authenticated;
revoke all on function private.archive_civic_items() from public, anon, authenticated;

revoke all on function
  public.civic_items_near(double precision, double precision, integer),
  public.civic_items_in_view(double precision, double precision, double precision, double precision),
  public.civic_upcoming(double precision, double precision, integer),
  public.follow_civic_address(text, text),
  public.unfollow_civic_address(text, text),
  public.my_civic_notices(integer),
  public.mark_civic_notice_read(uuid)
from public;

grant execute on function
  public.civic_items_near(double precision, double precision, integer),
  public.civic_items_in_view(double precision, double precision, double precision, double precision),
  public.civic_upcoming(double precision, double precision, integer)
to anon, authenticated, service_role;

grant execute on function
  public.follow_civic_address(text, text),
  public.unfollow_civic_address(text, text),
  public.my_civic_notices(integer),
  public.mark_civic_notice_read(uuid)
to authenticated, service_role;

select cron.schedule(
  'xhere-archive-civic',
  '11 4 * * *',
  $$select private.archive_civic_items()$$
);
