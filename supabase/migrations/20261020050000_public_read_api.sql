-- Public read API (research recommendation #8, first half; PLAN §5 privacy;
-- OPEN-DATA-PLAYBOOK provenance).
--
-- Other apps and assistants ask "what's here?" over HTTP. The Edge Function
-- public-api is the only door. These functions are granted to service_role,
-- which that function uses. anon and authenticated cannot call them, so the
-- PostgREST surface cannot skip the function's per-address limit.
--
-- What a signed-out visitor already sees, and nothing past it:
--   public records (source open_data) keep their summary, with the license
--   and attribution stored on open_data_items
--   a user drop is the share-page teaser: title, category, display point,
--   and a handle only when the share page would print one
-- Coordinates are display_geog (PLAN §5). The stored geog is not selected.
--
-- Left out even when the map still shows them:
--   pending, expired, removed, disputed, and any row can_view_drop hides
--   profiles.is_demo, and auth.users raw_user_meta_data demo = true
--   drops.location_trust = 'reduced'
--
-- Rate limiting stays on the free plan: one row per hashed client address
-- in private.public_api_quota. No Redis and no paid add-on. The address
-- itself is not stored.

-- ---------------------------------------------------------------------------
-- Quota. The hash is 64 hex characters. The address is not a column.
-- ---------------------------------------------------------------------------
create table private.public_api_quota (
  ip_hash text primary key check (ip_hash ~ '^[a-f0-9]{64}$'),
  window_start timestamptz not null,
  hits integer not null check (hits >= 0)
);

comment on table private.public_api_quota is
  'Per-address window for the public read API. ip_hash is SHA-256 hex. The address is not stored. Free-plan Postgres, not a paid rate-limit product.';

alter table private.public_api_quota enable row level security;
revoke all on table private.public_api_quota from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Shared predicates and the item shape.
-- ---------------------------------------------------------------------------
create function private.public_api_share_alike(p_license text)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select coalesce(p_license, '') ~* '(odbl|cc[[:space:]]*by-sa|cc-by-sa)';
$$;

comment on function private.public_api_share_alike(text) is
  'True for ODbL and CC BY-SA, the share-alike licenses this API passes through.';

create function private.public_api_visible(p_drop public.drops)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select private.can_view_drop(p_drop, null)
    and p_drop.status in ('active'::public.drop_status, 'confirmed'::public.drop_status)
    and (p_drop.expires_at is null or p_drop.expires_at > pg_catalog.now())
    and p_drop.location_trust is distinct from 'reduced'
    and (
      p_drop.author_id is null
      or (
        not exists (
          select 1
          from public.profiles p
          where p.id = p_drop.author_id
            and (p.is_demo or p.deleted_at is not null)
        )
        and not exists (
          select 1
          from auth.users u
          where u.id = p_drop.author_id
            and coalesce(u.raw_user_meta_data ->> 'demo', '') in ('true', 't', '1')
        )
      )
    );
$$;

comment on function private.public_api_visible(public.drops) is
  'Signed-out visibility, plus the public API exclusions: demo accounts and location_trust reduced. The caller JWT is not consulted.';

create function private.public_api_item(
  p_drop public.drops,
  p_distance_m double precision
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_category public.categories;
  v_group_slug text;
  v_group_label text;
  v_source_name text;
  v_source_url text;
  v_license text;
  v_attribution text;
  v_source_slug text;
  v_external text;
  v_public boolean;
  v_handle text;
  v_summary text;
begin
  select * into v_category from public.categories c where c.id = p_drop.category_id;
  if not found then
    return null;
  end if;
  select g.slug, g.label into v_group_slug, v_group_label
  from public.category_groups g
  where g.id = v_category.group_id;

  v_public := p_drop.source = 'open_data';
  if v_public then
    select s.name, i.source_url, i.license, i.attribution, i.source_slug, i.external_id
      into v_source_name, v_source_url, v_license, v_attribution, v_source_slug, v_external
    from public.open_data_items i
    join public.open_data_sources s on s.slug = i.source_slug
    where i.drop_id = p_drop.id;
    -- A public record with no provenance is not published.
    if v_license is null or btrim(v_license) = '' or v_attribution is null or btrim(v_attribution) = '' then
      return null;
    end if;
    v_summary := p_drop.body;
  else
    v_license := 'xhere-teaser';
    v_attribution := 'XHere user teaser. Hidden text is not included.';
    v_summary := null;
    -- The share page prints a handle only when the category is not sensitive
    -- and the drop is not anonymous.
    if p_drop.author_id is not null
       and not v_category.sensitive
       and not (p_drop.display_anonymous and v_category.allow_anonymous_display) then
      select p.handle into v_handle
      from public.profiles p
      where p.id = p_drop.author_id and p.deleted_at is null and not p.is_demo;
      if v_handle is not null then
        v_attribution := format(
          'Teaser posted on XHere by @%s. Hidden text is not included.',
          v_handle
        );
      end if;
    end if;
  end if;

  return jsonb_build_object(
    'id', p_drop.id,
    'short_id', p_drop.short_id,
    'kind', case when v_public then 'public_record' else 'user_teaser' end,
    'title', p_drop.title,
    'category_slug', v_category.slug,
    'category_label', v_category.label,
    'group_slug', v_group_slug,
    'group_label', v_group_label,
    'lat', extensions.st_y(p_drop.display_geog::extensions.geometry),
    'lon', extensions.st_x(p_drop.display_geog::extensions.geometry),
    'approximate', v_category.sensitive or v_category.location_precision_m > 0,
    'location_precision_m', v_category.location_precision_m,
    'reveal_radius_m', p_drop.reveal_radius_m,
    'distance_m', case
      when p_distance_m is null then null
      else round(p_distance_m::numeric, 1)
    end,
    'status', p_drop.status,
    'summary', v_summary,
    'source_slug', v_source_slug,
    'source_name', v_source_name,
    'source_url', v_source_url,
    'external_id', v_external,
    'author_handle', v_handle,
    'attribution', v_attribution,
    'license', v_license,
    'share_alike', private.public_api_share_alike(v_license)
  );
end;
$$;

comment on function private.public_api_item(public.drops, double precision) is
  'One public API item. User drops have a null summary. Coordinates come from display_geog. Author id, media, attributes, and the exact geog are not selected.';

-- ---------------------------------------------------------------------------
-- What's here. Radius is capped at 1 km, the same ceiling as here_card.
-- ---------------------------------------------------------------------------
create function public.public_api_here(
  p_lat double precision,
  p_lon double precision,
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
  v_items jsonb := '[]'::jsonb;
  v_drop public.drops;
  v_item jsonb;
  v_live jsonb;
begin
  if p_radius_m is null or p_radius_m not between 1 and 1000 then
    raise exception 'invalid_radius' using errcode = '22023',
      detail = 'radius must be 1..1000 m';
  end if;
  v_point := private.make_point(p_lat, p_lon);

  for v_drop in
    select d.*
    from public.drops d
    where extensions.st_dwithin(d.display_geog, v_point, p_radius_m)
      and private.public_api_visible(d)
    order by extensions.st_distance(d.display_geog, v_point), d.created_at desc
    limit 40
  loop
    v_item := private.public_api_item(
      v_drop,
      extensions.st_distance(v_drop.display_geog, v_point)
    );
    if v_item is not null then
      v_items := v_items || jsonb_build_array(v_item);
    end if;
  end loop;

  select jsonb_build_object(
    'region_id', r.region_id,
    'name', r.name,
    'country', r.country,
    'weather_provider', r.weather_provider,
    'attribution', 'XHere region configuration. This API does not copy weather-alert text.',
    'license', 'xhere-config',
    'share_alike', false
  ) into v_live
  from public.region_live_at(p_lat, p_lon) as r;

  return jsonb_build_object(
    'lat', p_lat,
    'lon', p_lon,
    'radius_m', p_radius_m,
    'items', v_items,
    'live', v_live
  );
end;
$$;

comment on function public.public_api_here(double precision, double precision, integer) is
  'Public records and user teasers within radius meters of a point. Max radius 1000. Max 40 items. Does not read the caller JWT and does not store the point.';

create function public.public_api_drop(p_id text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_drop public.drops;
  v_item jsonb;
begin
  if p_id is null
     or char_length(p_id) > 36
     or (
       p_id !~ '^[A-Za-z0-9_-]{12}$'
       and p_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
     ) then
    raise exception 'invalid_id' using errcode = '22023',
      detail = 'id must be a 12-character short id or a uuid';
  end if;

  if p_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select * into v_drop from public.drops d where d.id = p_id::uuid;
  else
    select * into v_drop from public.drops d where d.short_id = p_id;
  end if;

  if not found or not private.public_api_visible(v_drop) then
    return null;
  end if;
  v_item := private.public_api_item(v_drop, null);
  return v_item;
end;
$$;

comment on function public.public_api_drop(text) is
  'One public item by short id or uuid. Missing, hidden, pending, expired, demo, and reduced-trust rows all return null.';

create function public.public_api_categories()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'groups', coalesce((
      select jsonb_agg(jsonb_build_object(
        'slug', g.slug,
        'label', g.label,
        'description', g.description,
        'icon', g.icon,
        'attribution', 'XHere category list',
        'license', 'xhere-catalog',
        'share_alike', false
      ) order by g.sort, g.slug)
      from public.category_groups g
      where g.active
    ), '[]'::jsonb),
    'categories', coalesce((
      select jsonb_agg(jsonb_build_object(
        'slug', c.slug,
        'label', c.label,
        'description', c.description,
        'icon', c.icon,
        'group_slug', g.slug,
        'group_label', g.label,
        'sensitive', c.sensitive,
        'default_reveal_m', c.default_reveal_m,
        'location_precision_m', c.location_precision_m,
        'attribution', 'XHere category list',
        'license', 'xhere-catalog',
        'share_alike', false
      ) order by g.sort, c.sort, c.slug)
      from public.categories c
      join public.category_groups g on g.id = c.group_id
      where c.active and g.active
    ), '[]'::jsonb)
  );
$$;

comment on function public.public_api_categories() is
  'Active groups and categories: labels and reveal rules a signed-out client can infer. Embeddings, content-filter patterns, and moderation settings are not included.';

create function private.public_api_walk(p_walk_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_walk public.walks;
  v_stops jsonb := '[]'::jsonb;
  v_position integer;
  v_drop_id uuid;
  v_drop public.drops;
  v_item jsonb;
  v_licenses text[] := '{}'::text[];
  v_attributions text[] := '{}'::text[];
  v_share boolean := false;
  v_license text;
begin
  select * into v_walk from public.walks w where w.id = p_walk_id and w.active;
  if not found then
    return null;
  end if;

  for v_position, v_drop_id in
    select s.position, d.id
    from public.walk_stops s
    join public.open_data_items i
      on i.source_slug = s.source_slug and i.external_id = s.external_id
    join public.drops d on d.id = i.drop_id
    where s.walk_id = p_walk_id
    order by s.position
  loop
    select * into v_drop from public.drops d where d.id = v_drop_id;
    if not found or not private.public_api_visible(v_drop) then
      continue;
    end if;
    v_item := private.public_api_item(v_drop, null);
    if v_item is null then
      continue;
    end if;
    v_stops := v_stops || jsonb_build_array(
      v_item || jsonb_build_object('position', v_position)
    );
    v_licenses := v_licenses || (v_item ->> 'license');
    v_attributions := v_attributions || (v_item ->> 'attribution');
    v_share := v_share or coalesce((v_item ->> 'share_alike')::boolean, false);
  end loop;

  if jsonb_array_length(v_stops) = 0 then
    return null;
  end if;

  if (select count(distinct lic) from unnest(v_licenses) as lic) = 1 then
    v_license := v_licenses[1];
  else
    v_license := 'mixed';
  end if;

  return jsonb_build_object(
    'id', v_walk.id,
    'region_id', v_walk.region_id,
    'slug', v_walk.slug,
    'title', v_walk.title,
    'description', v_walk.description,
    'distance_m', v_walk.distance_m,
    'duration_min', v_walk.duration_min,
    'attribution', 'XHere walk. ' || array_to_string(v_attributions, ' · '),
    'license', v_license,
    'share_alike', v_share,
    'stops', v_stops
  );
end;
$$;

comment on function private.public_api_walk(uuid) is
  'One walk and the stops that are still public records. Personal progress is not included.';

create function public.public_api_walks(
  p_lat double precision,
  p_lon double precision
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_rows jsonb;
begin
  perform private.make_point(p_lat, p_lon);
  select coalesce(jsonb_agg(s.payload order by s.slug), '[]'::jsonb)
    into v_rows
  from (
    select private.public_api_walk(w.id) as payload, w.slug
    from public.walks w
    where w.active
      and p_lon >= w.min_lng and p_lon <= w.max_lng
      and p_lat >= w.min_lat and p_lat <= w.max_lat
  ) s
  where s.payload is not null;
  return jsonb_build_object(
    'lat', p_lat,
    'lon', p_lon,
    'walks', v_rows
  );
end;
$$;

comment on function public.public_api_walks(double precision, double precision) is
  'Active walks whose box contains the point. Started walks outside the box are personal state and are not added.';

-- ---------------------------------------------------------------------------
-- 30 requests per 60 seconds per hashed address. One row, replaced in place.
-- ---------------------------------------------------------------------------
create function public.public_api_take(p_ip_hash text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_limit constant integer := 30;
  v_hits integer;
  v_start timestamptz;
  v_retry integer;
begin
  if p_ip_hash is null or p_ip_hash !~ '^[a-f0-9]{64}$' then
    raise exception 'invalid_client' using errcode = '22023',
      detail = 'client hash must be 64 hex characters';
  end if;

  insert into private.public_api_quota (ip_hash, window_start, hits)
  values (p_ip_hash, pg_catalog.clock_timestamp(), 1)
  on conflict (ip_hash) do update
    set hits = case
          when private.public_api_quota.window_start <= pg_catalog.clock_timestamp() - interval '60 seconds' then 1
          else private.public_api_quota.hits + 1
        end,
        window_start = case
          when private.public_api_quota.window_start <= pg_catalog.clock_timestamp() - interval '60 seconds'
            then pg_catalog.clock_timestamp()
          else private.public_api_quota.window_start
        end
  returning hits, window_start into v_hits, v_start;

  delete from private.public_api_quota as stale
  where stale.window_start < pg_catalog.clock_timestamp() - interval '2 minutes'
    and stale.ip_hash <> p_ip_hash;

  v_retry := greatest(
    1,
    ceil(extract(epoch from (v_start + interval '60 seconds' - pg_catalog.clock_timestamp())))::integer
  );

  return jsonb_build_object(
    'allowed', v_hits <= v_limit,
    'limit', v_limit,
    'remaining', greatest(v_limit - v_hits, 0),
    'retry_after_seconds', v_retry
  );
end;
$$;

comment on function public.public_api_take(text) is
  'Consumes one public-API request for a SHA-256 client hash. 30 requests per 60 seconds. Does not store the address.';

-- ---------------------------------------------------------------------------
-- Grants. service_role only. The Edge Function holds that key.
-- ---------------------------------------------------------------------------
revoke execute on function
  private.public_api_share_alike(text),
  private.public_api_visible(public.drops),
  private.public_api_item(public.drops, double precision),
  private.public_api_walk(uuid)
from public, anon, authenticated;

revoke execute on function
  public.public_api_here(double precision, double precision, integer),
  public.public_api_drop(text),
  public.public_api_categories(),
  public.public_api_walks(double precision, double precision),
  public.public_api_take(text)
from public, anon, authenticated;

grant execute on function
  public.public_api_here(double precision, double precision, integer),
  public.public_api_drop(text),
  public.public_api_categories(),
  public.public_api_walks(double precision, double precision),
  public.public_api_take(text)
to service_role;
