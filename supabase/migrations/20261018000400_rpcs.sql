-- M1: RPCs (PLAN §5 "Key RPCs"). All are security definer with an empty
-- search_path and do their own authorization, because the base tables are
-- closed to clients.
--
-- Errors use a stable message slug plus an SQLSTATE that PostgREST maps to a
-- sensible HTTP status:
--   42501 (403)  not_authenticated, account_unavailable, account_suspended,
--                tier_too_low, not_moderator, cannot_confirm_own_drop, ...
--   22023 (400)  validation: invalid_location, category_not_found,
--                invalid_attributes, content_not_allowed, too_far, ...
--   P0002 (404)  drop_not_found, user_not_found, report_not_found
--   PT429 (429)  rate_limited

-- ---------------------------------------------------------------------------
-- The payload every read RPC returns. body, attributes, and media are null
-- unless the drop is revealed to the caller. lat/lng are always the display
-- (possibly blurred) position, never the true one.
-- ---------------------------------------------------------------------------
create type public.drop_card as (
  id uuid,
  short_id text,
  category_slug text,
  group_slug text,
  title text,
  lat double precision,
  lng double precision,
  location_precision_m integer,
  distance_m double precision,
  reveal_radius_m integer,
  revealed boolean,
  body text,
  attributes jsonb,
  media jsonb,
  tags text[],
  status public.drop_status,
  is_confirmed boolean,
  confirm_count integer,
  deny_count integer,
  expires_at timestamptz,
  last_confirmed_at timestamptz,
  created_at timestamptz,
  author_handle text,
  is_mine boolean
);

create function private.to_drop_card(
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
  v_card.tags := p_drop.tags;
  v_card.status := p_drop.status;
  v_card.is_confirmed := p_drop.status = 'confirmed';
  v_card.confirm_count := p_drop.confirm_count;
  v_card.deny_count := p_drop.deny_count;
  v_card.expires_at := p_drop.expires_at;
  v_card.last_confirmed_at := p_drop.last_confirmed_at;
  v_card.created_at := p_drop.created_at;
  v_card.is_mine := p_viewer is not null and p_drop.author_id is not distinct from p_viewer;

  if p_drop.author_id is not null
     and not (p_drop.display_anonymous and v_category.allow_anonymous_display) then
    v_card.author_handle := (select p.handle from public.profiles p where p.id = p_drop.author_id);
  end if;

  if p_point is not null then
    v_distance := extensions.st_distance(p_drop.display_geog, p_point);
    v_card.distance_m := round(v_distance::numeric, 1)::double precision;
  end if;

  v_card.revealed := v_card.is_mine or (v_distance is not null and v_distance <= p_drop.reveal_radius_m);

  if v_card.revealed then
    v_card.body := p_drop.body;
    v_card.attributes := p_drop.attributes;
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

-- ---------------------------------------------------------------------------
-- drops_nearby: teasers for everything within p_radius_m of the caller, and
-- the full body where the caller is inside the drop's reveal radius.
-- p_categories accepts category slugs and/or group slugs.
-- ---------------------------------------------------------------------------
create function public.drops_nearby(
  p_lat double precision,
  p_lng double precision,
  p_radius_m integer default 1000,
  p_categories text[] default null,
  p_limit integer default 200
)
returns setof public.drop_card
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_point extensions.geography := private.make_point(p_lat, p_lng);
  v_uid uuid := auth.uid();
begin
  if p_radius_m is null or p_radius_m not between 1 and 50000 then
    raise exception 'invalid_radius' using errcode = '22023', detail = 'radius must be 1..50000 m';
  end if;

  return query
  with hits as (
    select d as drop_row, extensions.st_distance(d.display_geog, v_point) as dist
    from public.drops d
    join public.categories c on c.id = d.category_id
    join public.category_groups g on g.id = c.group_id
    where extensions.st_dwithin(d.display_geog, v_point, p_radius_m)
      and (p_categories is null or c.slug = any (p_categories) or g.slug = any (p_categories))
      and private.can_view_drop(d, v_uid)
    order by dist, d.created_at desc
    limit least(greatest(coalesce(p_limit, 200), 1), 500)
  )
  select card.*
  from hits
  cross join lateral private.to_drop_card(hits.drop_row, v_uid, v_point) as card
  order by hits.dist, card.created_at desc;
end;
$$;

-- ---------------------------------------------------------------------------
-- drops_in_view: map viewport. Teasers at zoom >= 15, grid clusters below.
-- Never returns body or attributes. Bounding boxes may not cross the antimeridian.
-- ---------------------------------------------------------------------------
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
  category_counts jsonb
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
           1, null::jsonb
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

  -- Roughly 1/8 of a 256 px tile per cell at this zoom.
  v_cell := 360.0 / power(2, p_zoom) / 8;

  return query
  with visible as (
    select d.id, d.short_id, d.title, c.slug as cslug, g.slug as gslug,
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
           (array_agg(v.title))[1] as title
    from visible v
    group by 1, 2
  )
  select case when gr.n = 1 then 'drop' else 'cluster' end,
         case when gr.n = 1 then gr.id end,
         case when gr.n = 1 then gr.short_id end,
         case when gr.n = 1 then gr.cslug end,
         case when gr.n = 1 then gr.gslug end,
         case when gr.n = 1 then gr.title end,
         gr.y, gr.x, gr.n, ce.counts
  from grouped gr
  join cells ce on ce.cx = gr.cx and ce.cy = gr.cy
  order by gr.n desc
  limit 1000;
end;
$$;

-- ---------------------------------------------------------------------------
-- drop_detail: one drop, revealed only inside its radius. Counts a view.
-- ---------------------------------------------------------------------------
create function public.drop_detail(
  p_drop_id uuid,
  p_lat double precision default null,
  p_lng double precision default null
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
  v_point extensions.geography;
begin
  select * into v_drop from public.drops d where d.id = p_drop_id;
  if not found or not private.can_view_drop(v_drop, v_uid) then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;

  if p_lat is not null or p_lng is not null then
    v_point := private.make_point(p_lat, p_lng);
  end if;

  if v_drop.author_id is distinct from v_uid then
    update public.drops set view_count = view_count + 1 where id = v_drop.id;
  end if;

  return private.to_drop_card(v_drop, v_uid, v_point);
end;
$$;

-- drop_teaser: share-link preview (xhere.world/d/{short_id}). Never reveals.
create function public.drop_teaser(p_short_id text)
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
  if not v_card.is_mine then
    v_card.revealed := false;
    v_card.body := null;
    v_card.attributes := null;
    v_card.media := null;
  end if;
  return v_card;
end;
$$;

-- ---------------------------------------------------------------------------
-- create_drop: the only way to insert a drop. Applies every category rule.
-- ---------------------------------------------------------------------------
create function public.create_drop(
  p_category text,
  p_lat double precision,
  p_lng double precision,
  p_title text,
  p_body text default null,
  p_attributes jsonb default '{}'::jsonb,
  p_tags text[] default '{}',
  p_reveal_radius_m integer default null,
  p_expires_at timestamptz default null,
  p_display_anonymous boolean default false,
  p_media jsonb default '[]'::jsonb,
  p_acknowledge_guidelines boolean default false
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
  v_category public.categories;
  v_limits public.tier_limits;
  v_point extensions.geography;
  v_display extensions.geography;
  v_title text := btrim(coalesce(p_title, ''));
  v_body text := nullif(btrim(coalesce(p_body, '')), '');
  v_attributes jsonb := coalesce(p_attributes, '{}'::jsonb);
  v_media jsonb := coalesce(p_media, '[]'::jsonb);
  v_tags text[];
  v_reveal integer;
  v_cap interval;
  v_expires timestamptz;
  v_explicit_expiry boolean := false;
  v_status public.drop_status;
  v_errors text[];
  v_last_hour integer;
  v_last_day integer;
  v_drop public.drops;
  v_duplicates jsonb;
  v_item jsonb;
begin
  -- Who is posting
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if exists (select 1 from public.user_moderation m where m.user_id = v_uid and m.suspended_until > now()) then
    raise exception 'account_suspended' using errcode = '42501';
  end if;

  -- Category and who may post in it
  select * into v_category from public.categories c where c.slug = p_category and c.active;
  if not found then
    raise exception 'category_not_found' using errcode = '22023', detail = coalesce(p_category, 'null');
  end if;
  if v_profile.tier < v_category.min_tier_to_post then
    raise exception 'tier_too_low' using errcode = '42501',
      detail = format('%s requires tier %s', v_category.slug, v_category.min_tier_to_post);
  end if;
  if v_category.sensitive and not coalesce(p_acknowledge_guidelines, false) then
    raise exception 'guidelines_not_acknowledged' using errcode = '22023', hint = v_category.posting_notice;
  end if;

  -- Place, text, tags
  v_point := private.make_point(p_lat, p_lng);

  if char_length(v_title) not between 1 and 120 then
    raise exception 'invalid_title' using errcode = '22023', detail = 'title must be 1..120 characters';
  end if;
  if char_length(v_body) > 2000 then
    raise exception 'invalid_body' using errcode = '22023', detail = 'body must be at most 2000 characters';
  end if;

  select coalesce(array_agg(distinct lower(btrim(t))), '{}') into v_tags
  from unnest(coalesce(p_tags, '{}')) as t
  where btrim(t) <> '';
  if cardinality(v_tags) > 10
     or exists (select 1 from unnest(v_tags) as t where t !~ '^[a-z0-9][a-z0-9_-]{0,31}$') then
    raise exception 'invalid_tags' using errcode = '22023',
      detail = 'at most 10 tags of 1..32 characters: a-z, 0-9, _ and -';
  end if;

  -- Attributes against the category's JSON Schema, plus cross-field checks
  if jsonb_typeof(v_attributes) <> 'object' then
    raise exception 'invalid_attributes' using errcode = '22023', detail = 'attributes must be a JSON object';
  end if;
  if not extensions.jsonb_matches_schema(v_category.attributes_schema::json, v_attributes) then
    v_errors := extensions.jsonschema_validation_errors(v_category.attributes_schema::json, v_attributes::json);
    raise exception 'invalid_attributes' using errcode = '22023', detail = array_to_string(v_errors, '; ');
  end if;
  if v_attributes ? 'starts_at' and v_attributes ? 'ends_at'
     and (v_attributes ->> 'ends_at')::timestamptz < (v_attributes ->> 'starts_at')::timestamptz then
    raise exception 'invalid_attributes' using errcode = '22023', detail = 'ends_at is before starts_at';
  end if;
  if v_attributes ? 'occurred_at' then
    if (v_attributes ->> 'occurred_at')::timestamptz > now() + interval '5 minutes' then
      raise exception 'invalid_attributes' using errcode = '22023', detail = 'occurred_at is in the future';
    end if;
    if v_category.max_ttl is not null
       and (v_attributes ->> 'occurred_at')::timestamptz < now() - v_category.max_ttl then
      raise exception 'invalid_attributes' using errcode = '22023',
        detail = format('occurred_at is older than %s', v_category.max_ttl);
    end if;
  end if;

  perform private.assert_content_allowed(v_category.content_filters, array[v_title, v_body]);

  if coalesce(p_display_anonymous, false) and not v_category.allow_anonymous_display then
    raise exception 'anonymous_not_allowed' using errcode = '22023', detail = v_category.slug;
  end if;

  -- Reveal radius: authors may tighten it, never widen it past the category max
  v_reveal := coalesce(p_reveal_radius_m, v_category.default_reveal_m);
  if v_reveal < greatest(10, v_category.location_precision_m) or v_reveal > v_category.max_reveal_m then
    raise exception 'invalid_reveal_radius' using errcode = '22023',
      detail = format('allowed %s..%s m', greatest(10, v_category.location_precision_m), v_category.max_reveal_m);
  end if;

  -- Lifetime
  v_cap := case
    when v_profile.tier = 'new' and v_category.max_ttl_new is not null then v_category.max_ttl_new
    else v_category.max_ttl
  end;
  if v_category.expires_at_attribute is not null and v_attributes ? v_category.expires_at_attribute then
    v_expires := (v_attributes ->> v_category.expires_at_attribute)::timestamptz;
    v_explicit_expiry := true;
  end if;
  if p_expires_at is not null then
    v_expires := least(coalesce(v_expires, p_expires_at), p_expires_at);
    v_explicit_expiry := true;
  end if;
  if v_expires is null and v_category.default_ttl is not null then
    v_expires := now() + v_category.default_ttl;
  end if;
  if v_explicit_expiry and v_expires <= now() then
    raise exception 'expires_in_past' using errcode = '22023';
  end if;
  if v_cap is not null then
    if v_expires is null then
      v_expires := now() + v_cap;
    elsif v_expires > now() + v_cap then
      if v_explicit_expiry then
        raise exception 'expires_too_far' using errcode = '22023',
          detail = format('%s allows at most %s ahead', v_category.slug, v_cap);
      end if;
      v_expires := now() + v_cap;
    end if;
  end if;

  -- Media refs: objects pointing at the caller's own Storage folder
  if jsonb_typeof(v_media) <> 'array' then
    raise exception 'invalid_media' using errcode = '22023', detail = 'media must be a JSON array';
  end if;
  for v_item in select value from jsonb_array_elements(v_media) loop
    if jsonb_typeof(v_item) <> 'object'
       or coalesce(v_item ->> 'storage_path', '') = ''
       or split_part(v_item ->> 'storage_path', '/', 1) <> v_uid::text
       or coalesce(v_item ->> 'mime_type', '') = ''
       or not (coalesce(v_item ->> 'media_kind', '') = any (enum_range(null::public.media_kind)::text[])) then
      raise exception 'invalid_media' using errcode = '22023',
        detail = 'each item needs media_kind, mime_type, and a storage_path under "<your user id>/"';
    end if;
  end loop;
  if v_category.requires_photo
     and not exists (select 1 from jsonb_array_elements(v_media) e where e.value ->> 'media_kind' = 'image') then
    raise exception 'photo_required' using errcode = '22023', detail = v_category.slug;
  end if;

  -- Moderation and rate limits
  v_status := case
    when private.needs_premod(v_category, v_profile.tier) then 'pending_review'::public.drop_status
    else 'active'::public.drop_status
  end;

  perform pg_advisory_xact_lock(hashtextextended('xhere.create_drop:' || v_uid::text, 0));
  select * into v_limits from public.tier_limits tl where tl.tier = v_profile.tier;
  select count(*) filter (where d.created_at > now() - interval '1 hour'), count(*)
    into v_last_hour, v_last_day
  from public.drops d
  where d.author_id = v_uid and d.created_at > now() - interval '1 day';
  if v_last_hour >= coalesce(v_limits.drops_per_hour, 0) or v_last_day >= coalesce(v_limits.drops_per_day, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s drops per hour and %s per day',
        v_profile.tier, coalesce(v_limits.drops_per_hour, 0), coalesce(v_limits.drops_per_day, 0));
  end if;

  -- Blur, duplicate hint, insert
  v_display := private.fuzz_point(v_point, v_category.location_precision_m);

  select coalesce(jsonb_agg(x.short_id), '[]'::jsonb) into v_duplicates
  from (
    select d.short_id
    from public.drops d
    where d.category_id = v_category.id
      and extensions.st_dwithin(d.display_geog, v_display, 50)
      and extensions.similarity(d.title, v_title) >= 0.3
      and private.can_view_drop(d, v_uid)
    order by d.created_at desc
    limit 5
  ) as x;

  insert into public.drops (
    author_id, category_id, category_version, source, title, body, attributes, tags,
    geog, display_geog, reveal_radius_m, status, display_anonymous, expires_at
  ) values (
    v_uid, v_category.id, v_category.version, 'user', v_title, v_body, v_attributes, v_tags,
    v_point, v_display, v_reveal, v_status, coalesce(p_display_anonymous, false), v_expires
  )
  returning * into v_drop;

  insert into public.drop_media (
    drop_id, owner_id, storage_bucket, storage_path, media_kind, mime_type,
    byte_size, width, height, duration_s, sort
  )
  select v_drop.id, v_uid,
         coalesce(e.value ->> 'storage_bucket', 'drop-media'),
         e.value ->> 'storage_path',
         (e.value ->> 'media_kind')::public.media_kind,
         e.value ->> 'mime_type',
         (e.value ->> 'byte_size')::bigint,
         (e.value ->> 'width')::integer,
         (e.value ->> 'height')::integer,
         (e.value ->> 'duration_s')::numeric,
         (e.ord - 1)::smallint
  from jsonb_array_elements(v_media) with ordinality as e(value, ord);

  return jsonb_build_object(
    'id', v_drop.id,
    'short_id', v_drop.short_id,
    'status', v_drop.status,
    'category', v_category.slug,
    'category_version', v_drop.category_version,
    'reveal_radius_m', v_drop.reveal_radius_m,
    'expires_at', v_drop.expires_at,
    'lat', extensions.st_y(v_drop.display_geog::extensions.geometry),
    'lng', extensions.st_x(v_drop.display_geog::extensions.geometry),
    'location_precision_m', v_category.location_precision_m,
    'posting_notice', v_category.posting_notice,
    'possible_duplicates', v_duplicates
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- confirm_drop: "Still true?" from someone who is actually there.
-- ---------------------------------------------------------------------------
create function public.confirm_drop(
  p_drop_id uuid,
  p_verdict public.confirmation_verdict,
  p_lat double precision,
  p_lng double precision,
  p_note text default null
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
  v_drop public.drops;
  v_point extensions.geography;
  v_distance double precision;
  v_limit integer;
  v_recent integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if exists (select 1 from public.user_moderation m where m.user_id = v_uid and m.suspended_until > now()) then
    raise exception 'account_suspended' using errcode = '42501';
  end if;
  if p_verdict is null then
    raise exception 'invalid_verdict' using errcode = '22023';
  end if;
  if char_length(p_note) > 280 then
    raise exception 'invalid_note' using errcode = '22023', detail = 'note must be at most 280 characters';
  end if;

  select * into v_drop from public.drops d where d.id = p_drop_id for update;
  if not found or not private.can_view_drop(v_drop, v_uid) or v_drop.status not in ('active', 'confirmed') then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;
  if v_drop.author_id = v_uid then
    raise exception 'cannot_confirm_own_drop' using errcode = '42501';
  end if;

  v_point := private.make_point(p_lat, p_lng);
  v_distance := extensions.st_distance(v_drop.display_geog, v_point);
  if v_distance > greatest(50, v_drop.reveal_radius_m) then
    raise exception 'too_far' using errcode = '22023',
      detail = format('%s m away; must be within %s m', round(v_distance::numeric), greatest(50, v_drop.reveal_radius_m));
  end if;

  select tl.confirmations_per_hour into v_limit from public.tier_limits tl where tl.tier = v_profile.tier;
  select count(*) into v_recent
  from public.confirmations c
  where c.user_id = v_uid and c.updated_at > now() - interval '1 hour';
  if v_recent >= coalesce(v_limit, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s confirmations per hour', v_profile.tier, coalesce(v_limit, 0));
  end if;

  insert into public.confirmations (drop_id, user_id, verdict, note, distance_m)
  values (v_drop.id, v_uid, p_verdict, nullif(btrim(p_note), ''), round(v_distance)::integer)
  on conflict (drop_id, user_id) do update
    set verdict = excluded.verdict, note = excluded.note, distance_m = excluded.distance_m;

  if p_verdict = 'still_true' then
    update public.drops d
    set last_confirmed_at = now(),
        expires_at = case
          when c.confirm_extends_ttl is not null and d.expires_at is not null then
            least(greatest(d.expires_at, now() + c.confirm_extends_ttl),
                  coalesce(now() + c.max_ttl, 'infinity'::timestamptz))
          else d.expires_at
        end
    from public.categories c
    where d.id = v_drop.id and c.id = d.category_id;
  end if;

  select * into v_drop from public.drops d where d.id = p_drop_id;
  return jsonb_build_object(
    'drop_id', v_drop.id,
    'verdict', p_verdict,
    'status', v_drop.status,
    'is_confirmed', v_drop.status = 'confirmed',
    'confirm_count', v_drop.confirm_count,
    'deny_count', v_drop.deny_count,
    'expires_at', v_drop.expires_at,
    'distance_m', round(v_distance)::integer
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- report: flag a drop or a profile. Idempotent per reporter and target.
-- Enough distinct reporters send a drop back to review (category reports_to_hide).
-- ---------------------------------------------------------------------------
create function public.report(
  p_target_type public.report_target,
  p_target_id uuid,
  p_reason public.report_reason,
  p_details text default null
)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.profiles;
  v_drop public.drops;
  v_report_id uuid;
  v_limit integer;
  v_recent integer;
  v_open_reporters integer;
  v_threshold integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if p_reason is null or p_target_id is null or p_target_type is null then
    raise exception 'invalid_report' using errcode = '22023';
  end if;
  if char_length(p_details) > 1000 then
    raise exception 'invalid_report' using errcode = '22023', detail = 'details must be at most 1000 characters';
  end if;

  if p_target_type = 'drop' then
    select * into v_drop from public.drops d where d.id = p_target_id;
    if not found or not private.can_view_drop(v_drop, v_uid) then
      raise exception 'drop_not_found' using errcode = 'P0002';
    end if;
    if v_drop.author_id = v_uid then
      raise exception 'cannot_report_self' using errcode = '22023';
    end if;
  else
    if p_target_id = v_uid then
      raise exception 'cannot_report_self' using errcode = '22023';
    end if;
    if not exists (select 1 from public.profiles p where p.id = p_target_id and p.deleted_at is null) then
      raise exception 'user_not_found' using errcode = 'P0002';
    end if;
  end if;

  select r.id into v_report_id
  from public.reports r
  where r.reporter_id = v_uid and r.target_type = p_target_type and r.target_id = p_target_id and r.status = 'open';
  if found then
    return v_report_id;
  end if;

  select tl.reports_per_day into v_limit from public.tier_limits tl where tl.tier = v_profile.tier;
  select count(*) into v_recent
  from public.reports r
  where r.reporter_id = v_uid and r.created_at > now() - interval '1 day';
  if v_recent >= coalesce(v_limit, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s reports per day', v_profile.tier, coalesce(v_limit, 0));
  end if;

  insert into public.reports (target_type, target_id, reporter_id, reason, details)
  values (p_target_type, p_target_id, v_uid, p_reason, nullif(btrim(p_details), ''))
  returning id into v_report_id;

  if p_target_type = 'drop' and v_drop.status in ('active', 'confirmed') then
    select c.reports_to_hide into v_threshold from public.categories c where c.id = v_drop.category_id;
    select count(distinct r.reporter_id) into v_open_reporters
    from public.reports r
    where r.target_type = 'drop' and r.target_id = v_drop.id and r.status = 'open';
    if v_open_reporters >= v_threshold then
      update public.drops set status = 'pending_review' where id = v_drop.id;
      insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
      values (null, 'drop', v_drop.id, 'auto_hide', 'report threshold reached',
              jsonb_build_object('open_reporters', v_open_reporters, 'threshold', v_threshold));
    end if;
  end if;

  return v_report_id;
end;
$$;

-- ---------------------------------------------------------------------------
-- Blocks
-- ---------------------------------------------------------------------------
create function public.block_user(p_user_id uuid)
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
  if p_user_id is null or p_user_id = v_uid then
    raise exception 'cannot_block_self' using errcode = '22023';
  end if;
  if not exists (select 1 from public.profiles p where p.id = p_user_id) then
    raise exception 'user_not_found' using errcode = 'P0002';
  end if;
  insert into public.user_blocks (blocker_id, blocked_id)
  values (v_uid, p_user_id)
  on conflict do nothing;
end;
$$;

create function public.unblock_user(p_user_id uuid)
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
  delete from public.user_blocks where blocker_id = auth.uid() and blocked_id = p_user_id;
end;
$$;

-- ---------------------------------------------------------------------------
-- delete_my_account: soft delete now (content hidden immediately, profile
-- scrubbed); private.purge_deleted_accounts hard-deletes after 30 days.
-- ---------------------------------------------------------------------------
create function public.delete_my_account()
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
  update public.profiles
  set deleted_at = now(),
      handle = 'deleted_' || substr(md5(id::text), 1, 16),
      display_name = null,
      avatar_path = null,
      home_area = null
  where id = v_uid and deleted_at is null;
  delete from public.user_blocks where blocker_id = v_uid;
end;
$$;

-- ---------------------------------------------------------------------------
-- Moderation (role moderator or admin)
-- ---------------------------------------------------------------------------
create function public.moderate_drop(
  p_drop_id uuid,
  p_action text,
  p_reason text default null
)
returns public.drop_status
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_drop public.drops;
  v_action public.moderation_action_type;
  v_report_status public.report_status;
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;

  select * into v_drop from public.drops d where d.id = p_drop_id for update;
  if not found then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;
  if v_drop.author_id = v_uid then
    raise exception 'cannot_moderate_own_drop' using errcode = '42501';
  end if;

  case p_action
    when 'approve' then
      if v_drop.status <> 'pending_review' then
        raise exception 'invalid_transition' using errcode = '22023',
          detail = format('approve needs pending_review, drop is %s', v_drop.status);
      end if;
      update public.drops set status = 'active' where id = v_drop.id;
      perform private.refresh_drop_confirmations(v_drop.id);
      v_action := 'approve';
      v_report_status := 'dismissed';
    when 'restore' then
      if v_drop.status not in ('removed', 'disputed') then
        raise exception 'invalid_transition' using errcode = '22023',
          detail = format('restore needs removed or disputed, drop is %s', v_drop.status);
      end if;
      update public.drops set status = 'active', removed_reason = null where id = v_drop.id;
      v_action := 'restore';
      v_report_status := 'dismissed';
    when 'remove' then
      if v_drop.status = 'removed' then
        raise exception 'invalid_transition' using errcode = '22023', detail = 'drop is already removed';
      end if;
      update public.drops set status = 'removed', removed_reason = coalesce(p_reason, 'removed by moderator')
      where id = v_drop.id;
      v_action := case
        when v_drop.status = 'pending_review' then 'reject'::public.moderation_action_type
        else 'remove'::public.moderation_action_type
      end;
      v_report_status := 'actioned';
    else
      raise exception 'invalid_action' using errcode = '22023', detail = 'use approve, restore, or remove';
  end case;

  update public.reports
  set status = v_report_status, resolved_by = v_uid, resolved_at = now(), resolution_note = p_reason
  where target_type = 'drop' and target_id = v_drop.id and status = 'open';

  insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
  values (v_uid, 'drop', v_drop.id, v_action, p_reason, jsonb_build_object('from_status', v_drop.status));

  return (select d.status from public.drops d where d.id = v_drop.id);
end;
$$;

create function public.resolve_report(
  p_report_id uuid,
  p_status public.report_status,
  p_note text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;
  if p_status is null or p_status = 'open' then
    raise exception 'invalid_status' using errcode = '22023', detail = 'use actioned or dismissed';
  end if;

  update public.reports
  set status = p_status, resolved_by = v_uid, resolved_at = now(), resolution_note = p_note
  where id = p_report_id and status = 'open';
  if not found then
    raise exception 'report_not_found' using errcode = 'P0002';
  end if;

  insert into public.moderation_actions (actor_id, target_type, target_id, action, reason)
  values (v_uid, 'report', p_report_id,
          case when p_status = 'actioned' then 'action_report' else 'dismiss_report' end::public.moderation_action_type,
          p_note);
end;
$$;

-- p_until null lifts a suspension.
create function public.suspend_user(
  p_user_id uuid,
  p_until timestamptz,
  p_reason text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;
  if p_user_id = v_uid then
    raise exception 'cannot_moderate_self' using errcode = '42501';
  end if;
  if not exists (select 1 from public.profiles p where p.id = p_user_id) then
    raise exception 'user_not_found' using errcode = 'P0002';
  end if;

  insert into public.user_moderation (user_id, suspended_until, reason, updated_by, updated_at)
  values (p_user_id, p_until, p_reason, v_uid, now())
  on conflict (user_id) do update
    set suspended_until = excluded.suspended_until, reason = excluded.reason,
        updated_by = excluded.updated_by, updated_at = excluded.updated_at;

  insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
  values (v_uid, 'profile', p_user_id,
          case when p_until is null then 'unsuspend_user' else 'suspend_user' end::public.moderation_action_type,
          p_reason, jsonb_build_object('until', p_until));
end;
$$;

-- ---------------------------------------------------------------------------
-- Grants. Signed-out visitors get the map viewport and share-link teasers.
-- Reveal, posting, confirming, reporting, and blocking need an account.
-- ---------------------------------------------------------------------------
revoke execute on function
  public.drops_nearby(double precision, double precision, integer, text[], integer),
  public.drops_in_view(double precision, double precision, double precision, double precision, text[], integer),
  public.drop_detail(uuid, double precision, double precision),
  public.drop_teaser(text),
  public.create_drop(text, double precision, double precision, text, text, jsonb, text[], integer, timestamptz, boolean, jsonb, boolean),
  public.confirm_drop(uuid, public.confirmation_verdict, double precision, double precision, text),
  public.report(public.report_target, uuid, public.report_reason, text),
  public.block_user(uuid),
  public.unblock_user(uuid),
  public.delete_my_account(),
  public.moderate_drop(uuid, text, text),
  public.resolve_report(uuid, public.report_status, text),
  public.suspend_user(uuid, timestamptz, text)
from public, anon, authenticated;

grant execute on function
  public.drops_in_view(double precision, double precision, double precision, double precision, text[], integer),
  public.drop_teaser(text)
to anon;

grant execute on function
  public.drops_nearby(double precision, double precision, integer, text[], integer),
  public.drops_in_view(double precision, double precision, double precision, double precision, text[], integer),
  public.drop_detail(uuid, double precision, double precision),
  public.drop_teaser(text),
  public.create_drop(text, double precision, double precision, text, text, jsonb, text[], integer, timestamptz, boolean, jsonb, boolean),
  public.confirm_drop(uuid, public.confirmation_verdict, double precision, double precision, text),
  public.report(public.report_target, uuid, public.report_reason, text),
  public.block_user(uuid),
  public.unblock_user(uuid),
  public.delete_my_account(),
  public.moderate_drop(uuid, text, text),
  public.resolve_report(uuid, public.report_status, text),
  public.suspend_user(uuid, timestamptz, text)
to authenticated, service_role;
