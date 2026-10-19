-- Everything Has a Voice.
-- An object (a bench, a mural, a door) has a 6-byte voice id that fits in one
-- legacy BLE advertising packet. Phones resolve that id to a public story.
-- Hearing is an aggregate count. There is no row for who heard which tag.
-- NFC is an optional close-range URL, not the way the object is heard.
-- Not applied to the hosted project from this repo. See supabase/README.md.

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
create type public.ble_format as enum ('eddystone_uid', 'eddystone_url', 'ibeacon', 'xhere');

create function private.new_voice_id()
returns text
language sql
volatile
set search_path = ''
as $$
  select substr(replace(gen_random_uuid()::text, '-', ''), 1, 12);
$$;

comment on function private.new_voice_id() is
  '12 hex characters, 6 bytes, the id a legacy BLE advertiser puts in one packet.';

create function private.thing_media_ok(p_media jsonb)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select p_media is not null
    and jsonb_typeof(p_media) = 'array'
    and jsonb_array_length(p_media) <= 8
    and not exists (
      select 1
      from jsonb_array_elements(p_media) as element
      where jsonb_typeof(element) <> 'object'
        or coalesce(element ->> 'url', '') !~ '^https://[^[:space:]]{1,280}$'
        or char_length(coalesce(element ->> 'alt', '')) > 140
        or element ? 'storage_path'
    );
$$;

create table public.things (
  id uuid primary key default gen_random_uuid(),
  voice_id text not null unique default private.new_voice_id() check (voice_id ~ '^[0-9a-f]{12}$'),
  owner_id uuid not null references public.profiles (id) on delete cascade,
  drop_id uuid references public.drops (id) on delete set null,
  place_name text check (place_name is null or char_length(btrim(place_name)) between 1 and 120),
  category_slug text references public.categories (slug) on delete set null,
  title text not null check (char_length(btrim(title)) between 1 and 80),
  story text not null check (char_length(story) between 1 and 2000),
  media jsonb not null default '[]'::jsonb check (private.thing_media_ok(media)),
  fixed_lat double precision,
  fixed_lng double precision,
  fixed_geog extensions.geography(Point, 4326),
  visibility text not null default 'public' check (visibility in ('public', 'unlisted')),
  status text not null default 'active' check (status in ('active', 'removed')),
  seen_count bigint not null default 0 check (seen_count >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint things_location_complete check (
    (fixed_lat is null and fixed_lng is null and fixed_geog is null)
    or (fixed_lat is not null and fixed_lng is not null and fixed_geog is not null)
  )
);

comment on table public.things is
  'A physical object with a story. voice_id is the 6-byte BLE id. seen_count is an aggregate and is not a list of listeners. owner_id is not granted to clients.';

comment on column public.things.seen_count is
  'Aggregate times a public resolve was followed by hear_things. The caller is not stored with the tag.';

create index things_owner_idx on public.things (owner_id);
create index things_drop_idx on public.things (drop_id);
create index things_category_idx on public.things (category_slug);
create index things_geog_idx on public.things using gist (fixed_geog);

create table public.ble_tags (
  id uuid primary key default gen_random_uuid(),
  thing_id uuid not null references public.things (id) on delete cascade,
  format public.ble_format not null,
  adv_key text not null unique check (char_length(adv_key) between 8 and 180),
  namespace text,
  instance text,
  url text,
  ibeacon_uuid uuid,
  major integer,
  minor integer,
  voice_id text,
  created_by uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now()
);

comment on table public.ble_tags is
  'A BLE advertisement bound to a thing: Eddystone-UID, Eddystone-URL, iBeacon, or the compact XHere service data. adv_key is the resolver lookup.';

create index ble_tags_thing_idx on public.ble_tags (thing_id);
create index ble_tags_created_by_idx on public.ble_tags (created_by);

create table public.nfc_tags (
  id uuid primary key default gen_random_uuid(),
  thing_id uuid not null references public.things (id) on delete cascade,
  ndef_url text not null check (ndef_url ~ '^https://xhere\.world/t/[0-9a-f]{12}$'),
  created_by uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now()
);

comment on table public.nfc_tags is
  'Optional NFC sticker. The NDEF message also carries xhere://. NFC is close range and is not the broadcast.';

create index nfc_tags_thing_idx on public.nfc_tags (thing_id);
create index nfc_tags_created_by_idx on public.nfc_tags (created_by);

create table public.thing_reports (
  id uuid primary key default gen_random_uuid(),
  thing_id uuid not null references public.things (id) on delete cascade,
  reporter_id uuid not null references public.profiles (id) on delete cascade,
  reason public.report_reason not null,
  details text check (details is null or char_length(details) <= 1000),
  status public.report_status not null default 'open',
  created_at timestamptz not null default now()
);

comment on table public.thing_reports is
  'Flags on an object story. Five distinct open reporters remove it from public reads. Not a sighting log.';

create unique index thing_reports_one_open on public.thing_reports (thing_id, reporter_id) where status = 'open';
create index thing_reports_thing_idx on public.thing_reports (thing_id);
create index thing_reports_reporter_idx on public.thing_reports (reporter_id);

-- One row per caller and kind. Hits and a window. No tag id, no voice id.
create table private.thing_rpc_quota (
  user_id uuid not null,
  kind text not null check (kind in ('resolve', 'hear')),
  window_start timestamptz not null,
  hits integer not null check (hits >= 0),
  primary key (user_id, kind)
);

comment on table private.thing_rpc_quota is
  'Rate limit for resolve_things and hear_things. user_id, kind, window, hits. The keys that were heard are not stored.';

alter table private.thing_rpc_quota enable row level security;
revoke all on table private.thing_rpc_quota from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Keys and cards
-- ---------------------------------------------------------------------------
create function private.normalize_thing_key(p_raw text)
returns text
language plpgsql
immutable
set search_path = ''
as $$
declare
  v text := lower(btrim(coalesce(p_raw, '')));
  v_major integer;
  v_minor integer;
  v_uuid text;
begin
  if v = '' or char_length(v) > 180 then
    return null;
  end if;
  if v ~ '^[0-9a-f]{12}$' then
    return 'xhere:' || v;
  end if;
  if v ~ '^xhere:[0-9a-f]{12}$' then
    return v;
  end if;
  if v ~ '^uid:[0-9a-f]{20}:[0-9a-f]{12}$' then
    return v;
  end if;
  if v ~ '^url:xhere://t/[0-9a-f]{12}$' then
    return v;
  end if;
  if v ~ '^url:https://xhere\.world/t/[0-9a-f]{12}$' then
    return v;
  end if;
  if v ~ '^ibeacon:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9]{1,5}:[0-9]{1,5}$' then
    v_uuid := split_part(v, ':', 2);
    v_major := split_part(v, ':', 3)::integer;
    v_minor := split_part(v, ':', 4)::integer;
    if v_major > 65535 or v_minor > 65535 then
      return null;
    end if;
    return 'ibeacon:' || v_uuid || ':' || v_major::text || ':' || v_minor::text;
  end if;
  if v ~ '^url:https?://[a-z0-9._~:/?#\[\]@!$&''()*+,;=%-]{1,160}$' then
    return v;
  end if;
  return null;
end;
$$;

comment on function private.normalize_thing_key(text) is
  'Canonical BLE lookup key: xhere, Eddystone-UID, Eddystone-URL, or iBeacon. Anything else is ignored.';

create function private.thing_for_key(p_key text)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select t.id
  from public.things t
  where t.status = 'active'
    and t.visibility = 'public'
    and (
      (p_key ~ '^xhere:[0-9a-f]{12}$' and t.voice_id = split_part(p_key, ':', 2))
      or (p_key ~ '^uid:58484552450100000001:[0-9a-f]{12}$' and t.voice_id = split_part(p_key, ':', 3))
      or (p_key ~ '^url:https://xhere\.world/t/[0-9a-f]{12}$' and t.voice_id = substring(p_key from '[0-9a-f]{12}$'))
      or (p_key ~ '^url:xhere://t/[0-9a-f]{12}$' and t.voice_id = substring(p_key from '[0-9a-f]{12}$'))
      or exists (
        select 1 from public.ble_tags b
        where b.thing_id = t.id and b.adv_key = p_key
      )
    )
  limit 1;
$$;

comment on function private.thing_for_key(text) is
  'Public active thing for a broadcast key. The XHere namespace and the https/xhere URLs map onto voice_id. Unlisted and removed things are not returned.';

create function private.thing_json(p_thing public.things, p_mine boolean)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_label text;
begin
  if p_thing.category_slug is not null then
    select c.label into v_label from public.categories c where c.slug = p_thing.category_slug;
  end if;
  return jsonb_build_object(
    'id', p_thing.id,
    'voice_id', p_thing.voice_id,
    'title', p_thing.title,
    'story', p_thing.story,
    'category_slug', p_thing.category_slug,
    'category_label', v_label,
    'place_name', p_thing.place_name,
    'drop_id', p_thing.drop_id,
    'lat', p_thing.fixed_lat,
    'lon', p_thing.fixed_lng,
    'media', p_thing.media,
    'seen_count', p_thing.seen_count,
    'is_mine', p_mine,
    'url', 'https://xhere.world/t/' || p_thing.voice_id,
    'status', p_thing.status,
    'visibility', p_thing.visibility
  );
end;
$$;

create function private.thing_take(
  p_kind text,
  p_limit integer,
  p_window interval,
  p_cost integer
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_hits integer;
  v_start timestamptz;
  v_next integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_cost < 1 then
    return true;
  end if;

  insert into private.thing_rpc_quota as q (user_id, kind, window_start, hits)
  values (v_uid, p_kind, pg_catalog.clock_timestamp(), 0)
  on conflict (user_id, kind) do nothing;

  select q.hits, q.window_start into v_hits, v_start
  from private.thing_rpc_quota q
  where q.user_id = v_uid and q.kind = p_kind
  for update;

  if v_start <= pg_catalog.clock_timestamp() - p_window then
    v_hits := 0;
    v_start := pg_catalog.clock_timestamp();
  end if;
  v_next := v_hits + p_cost;
  if v_next > p_limit then
    return false;
  end if;

  update private.thing_rpc_quota q
  set hits = v_next, window_start = v_start
  where q.user_id = v_uid and q.kind = p_kind;
  return true;
end;
$$;

comment on function private.thing_take(text, integer, interval, integer) is
  'Consumes p_cost hits for this caller. False when the window would be exceeded. Does not store which keys were asked.';

-- ---------------------------------------------------------------------------
-- Row protection. Direct updates cannot move the counter, the id, or the owner.
-- security definer functions run as the owner, so hear_things can still count.
-- ---------------------------------------------------------------------------
create function private.protect_thing_row()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' and current_user in ('anon', 'authenticated', 'authenticator') then
    new.id := old.id;
    new.voice_id := old.voice_id;
    new.owner_id := old.owner_id;
    new.seen_count := old.seen_count;
    new.status := old.status;
    new.created_at := old.created_at;
    if new.fixed_lat is distinct from old.fixed_lat or new.fixed_lng is distinct from old.fixed_lng then
      if new.fixed_lat is null or new.fixed_lng is null then
        new.fixed_lat := null;
        new.fixed_lng := null;
        new.fixed_geog := null;
      else
        new.fixed_lat := round(new.fixed_lat::numeric, 5);
        new.fixed_lng := round(new.fixed_lng::numeric, 5);
        new.fixed_geog := private.make_point(new.fixed_lat, new.fixed_lng);
      end if;
    end if;
  end if;
  return new;
end;
$$;

create trigger things_protect
  before update on public.things
  for each row execute function private.protect_thing_row();

create trigger things_touch
  before update on public.things
  for each row execute function private.set_updated_at();

-- ---------------------------------------------------------------------------
-- Shared checks for create and update
-- ---------------------------------------------------------------------------
create function private.thing_prepare(
  p_title text,
  p_story text,
  p_category_slug text,
  p_drop_id uuid,
  p_place_name text,
  p_lat double precision,
  p_lng double precision,
  p_media jsonb,
  p_visibility text,
  p_uid uuid
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_drop public.drops;
  v_sensitive boolean;
  v_active boolean;
  v_geog extensions.geography;
  v_lat double precision;
  v_lng double precision;
  v_media jsonb := coalesce(p_media, '[]'::jsonb);
  v_place text := nullif(btrim(coalesce(p_place_name, '')), '');
  v_category text := nullif(btrim(coalesce(p_category_slug, '')), '');
  v_visibility text := coalesce(nullif(btrim(coalesce(p_visibility, '')), ''), 'public');
begin
  if p_title is null or char_length(btrim(p_title)) not between 1 and 80 or position(chr(0) in p_title) > 0 then
    raise exception 'invalid_title' using errcode = '22023';
  end if;
  if p_story is null or char_length(p_story) not between 1 and 2000 or position(chr(0) in p_story) > 0 then
    raise exception 'invalid_story' using errcode = '22023';
  end if;
  if v_visibility not in ('public', 'unlisted') then
    raise exception 'invalid_visibility' using errcode = '22023';
  end if;
  if not private.thing_media_ok(v_media) then
    raise exception 'invalid_media' using errcode = '22023';
  end if;
  if v_place is not null and char_length(v_place) > 120 then
    raise exception 'invalid_place' using errcode = '22023';
  end if;
  if v_category is not null then
    select c.sensitive, c.active into v_sensitive, v_active
    from public.categories c where c.slug = v_category;
    if not found or not v_active then
      raise exception 'invalid_category' using errcode = '22023';
    end if;
    if v_sensitive then
      raise exception 'category_not_allowed' using errcode = '22023';
    end if;
  end if;
  if p_drop_id is not null then
    select * into v_drop from public.drops d where d.id = p_drop_id;
    if not found or not private.can_view_drop(v_drop, p_uid) then
      raise exception 'drop_not_found' using errcode = 'P0002';
    end if;
  end if;
  if p_lat is null and p_lng is null then
    v_lat := null;
    v_lng := null;
    v_geog := null;
  elsif p_lat is null or p_lng is null then
    raise exception 'invalid_location' using errcode = '22023';
  else
    v_lat := round(p_lat::numeric, 5);
    v_lng := round(p_lng::numeric, 5);
    v_geog := private.make_point(v_lat, v_lng);
  end if;
  return jsonb_build_object(
    'title', btrim(p_title),
    'story', p_story,
    'category_slug', v_category,
    'drop_id', p_drop_id,
    'place_name', v_place,
    'lat', v_lat,
    'lng', v_lng,
    'media', v_media,
    'visibility', v_visibility
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- Client RPCs
-- ---------------------------------------------------------------------------
create function public.create_thing(
  p_title text,
  p_story text,
  p_category_slug text default null,
  p_drop_id uuid default null,
  p_place_name text default null,
  p_lat double precision default null,
  p_lng double precision default null,
  p_media jsonb default '[]'::jsonb,
  p_visibility text default 'public'
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
  v_ready jsonb;
  v_thing public.things;
  v_voice text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  v_ready := private.thing_prepare(
    p_title, p_story, p_category_slug, p_drop_id, p_place_name, p_lat, p_lng, p_media, p_visibility, v_uid
  );
  v_voice := private.new_voice_id();
  insert into public.things (
    voice_id, owner_id, drop_id, place_name, category_slug, title, story, media,
    fixed_lat, fixed_lng, fixed_geog, visibility
  ) values (
    v_voice,
    v_uid,
    nullif(v_ready ->> 'drop_id', '')::uuid,
    v_ready ->> 'place_name',
    v_ready ->> 'category_slug',
    v_ready ->> 'title',
    v_ready ->> 'story',
    v_ready -> 'media',
    (v_ready ->> 'lat')::double precision,
    (v_ready ->> 'lng')::double precision,
    case when v_ready ->> 'lat' is null then null else private.make_point((v_ready ->> 'lat')::double precision, (v_ready ->> 'lng')::double precision) end,
    v_ready ->> 'visibility'
  )
  returning * into v_thing;

  insert into public.ble_tags (thing_id, format, adv_key, voice_id, created_by)
  values (v_thing.id, 'xhere', 'xhere:' || v_thing.voice_id, v_thing.voice_id, v_uid);

  return private.thing_json(v_thing, true);
end;
$$;

comment on function public.create_thing(text, text, text, uuid, text, double precision, double precision, jsonb, text) is
  'Creates an object at an optional fixed point and binds its compact XHere BLE id. A missing point is allowed: indoors the broadcast is the location.';

create function public.update_thing(
  p_thing_id uuid,
  p_title text,
  p_story text,
  p_category_slug text default null,
  p_drop_id uuid default null,
  p_place_name text default null,
  p_lat double precision default null,
  p_lng double precision default null,
  p_media jsonb default '[]'::jsonb,
  p_visibility text default 'public'
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_ready jsonb;
  v_thing public.things;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_thing from public.things t where t.id = p_thing_id;
  if not found then
    raise exception 'thing_not_found' using errcode = 'P0002';
  end if;
  if v_thing.owner_id <> v_uid then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  v_ready := private.thing_prepare(
    p_title, p_story, p_category_slug, p_drop_id, p_place_name, p_lat, p_lng, p_media, p_visibility, v_uid
  );
  update public.things t set
    title = v_ready ->> 'title',
    story = v_ready ->> 'story',
    category_slug = v_ready ->> 'category_slug',
    drop_id = nullif(v_ready ->> 'drop_id', '')::uuid,
    place_name = v_ready ->> 'place_name',
    media = v_ready -> 'media',
    visibility = v_ready ->> 'visibility',
    fixed_lat = (v_ready ->> 'lat')::double precision,
    fixed_lng = (v_ready ->> 'lng')::double precision,
    fixed_geog = case
      when v_ready ->> 'lat' is null then null
      else private.make_point((v_ready ->> 'lat')::double precision, (v_ready ->> 'lng')::double precision)
    end
  where t.id = p_thing_id
  returning * into v_thing;
  return private.thing_json(v_thing, true);
end;
$$;

create function public.my_things()
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
  select coalesce(jsonb_agg(private.thing_json(t, true) order by t.created_at desc), '[]'::jsonb)
    into v_rows
  from public.things t
  where t.owner_id = v_uid;
  return jsonb_build_object('things', v_rows);
end;
$$;

create function public.resolve_things(p_keys text[])
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_raw text;
  v_key text;
  v_id uuid;
  v_thing public.things;
  v_out jsonb := '[]'::jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_keys is null or cardinality(p_keys) < 1 or cardinality(p_keys) > 24 then
    raise exception 'invalid_keys' using errcode = '22023';
  end if;
  if not private.thing_take('resolve', 15, interval '60 seconds', 1) then
    raise exception 'rate_limited' using errcode = 'PT429';
  end if;

  for v_raw in select distinct k from unnest(p_keys) as u(k) loop
    v_key := private.normalize_thing_key(v_raw);
    if v_key is null then
      continue;
    end if;
    v_id := private.thing_for_key(v_key);
    if v_id is null then
      continue;
    end if;
    select * into v_thing from public.things t where t.id = v_id;
    v_out := v_out || jsonb_build_array(jsonb_build_object(
      'key', v_key,
      'thing', private.thing_json(v_thing, v_thing.owner_id = v_uid)
    ));
  end loop;
  return jsonb_build_object('things', v_out);
end;
$$;

comment on function public.resolve_things(text[]) is
  'Public things for broadcast keys. 15 calls per 60 seconds. Does not store the keys or the caller together. Unlisted and removed things are omitted.';

create function public.hear_things(p_keys text[])
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_raw text;
  v_key text;
  v_id uuid;
  v_ids uuid[] := '{}';
  v_count integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_keys is null or cardinality(p_keys) < 1 or cardinality(p_keys) > 24 then
    raise exception 'invalid_keys' using errcode = '22023';
  end if;

  for v_raw in select distinct k from unnest(p_keys) as u(k) loop
    v_key := private.normalize_thing_key(v_raw);
    if v_key is null then
      continue;
    end if;
    v_id := private.thing_for_key(v_key);
    if v_id is not null and not v_id = any(v_ids) then
      v_ids := v_ids || v_id;
    end if;
  end loop;

  if not private.thing_take('hear', 48, interval '10 minutes', greatest(cardinality(v_ids), 1)) then
    raise exception 'rate_limited' using errcode = 'PT429';
  end if;

  update public.things t
  set seen_count = t.seen_count + 1
  where t.id = any(v_ids);
  v_count := cardinality(v_ids);
  return jsonb_build_object('counted', v_count);
end;
$$;

comment on function public.hear_things(text[]) is
  'Adds one to seen_count for each public thing. 48 increments per 10 minutes. The quota row has no tag id. Nothing records which account heard which object.';

create function public.bind_ble_tag(p_thing_id uuid, p_key text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_thing public.things;
  v_key text := private.normalize_thing_key(p_key);
  v_voice text;
  v_format public.ble_format;
  v_namespace text;
  v_instance text;
  v_url text;
  v_uuid uuid;
  v_major integer;
  v_minor integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if v_key is null then
    raise exception 'invalid_key' using errcode = '22023';
  end if;
  select * into v_thing from public.things t where t.id = p_thing_id;
  if not found then
    raise exception 'thing_not_found' using errcode = 'P0002';
  end if;
  if v_thing.owner_id <> v_uid then
    raise exception 'not_owner' using errcode = '42501';
  end if;

  if v_key ~ '^xhere:' or v_key ~ '^uid:58484552450100000001:' or v_key ~ '^url:https://xhere\.world/t/' or v_key ~ '^url:xhere://t/' then
    v_voice := substring(v_key from '[0-9a-f]{12}$');
    if v_voice is distinct from v_thing.voice_id then
      raise exception 'tag_in_use' using errcode = '22023';
    end if;
  end if;

  if v_key ~ '^xhere:' then
    v_format := 'xhere';
    v_voice := split_part(v_key, ':', 2);
  elsif v_key ~ '^uid:' then
    v_format := 'eddystone_uid';
    v_namespace := split_part(v_key, ':', 2);
    v_instance := split_part(v_key, ':', 3);
    v_voice := null;
  elsif v_key ~ '^url:' then
    v_format := 'eddystone_url';
    v_url := substr(v_key, 5);
    v_voice := null;
  else
    v_format := 'ibeacon';
    v_uuid := split_part(v_key, ':', 2)::uuid;
    v_major := split_part(v_key, ':', 3)::integer;
    v_minor := split_part(v_key, ':', 4)::integer;
    v_voice := null;
  end if;

  insert into public.ble_tags (
    thing_id, format, adv_key, namespace, instance, url, ibeacon_uuid, major, minor, voice_id, created_by
  ) values (
    p_thing_id, v_format, v_key, v_namespace, v_instance, v_url, v_uuid, v_major, v_minor, v_voice, v_uid
  )
  on conflict (adv_key) do nothing;

  if not exists (
    select 1 from public.ble_tags b where b.adv_key = v_key and b.thing_id = p_thing_id
  ) then
    raise exception 'tag_in_use' using errcode = '22023';
  end if;
end;
$$;

comment on function public.bind_ble_tag(uuid, text) is
  'Binds an existing beacon the phone heard to an object the caller owns. The compact id created with the thing is already bound.';

create function public.bind_nfc(p_thing_id uuid)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_thing public.things;
  v_url text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_thing from public.things t where t.id = p_thing_id;
  if not found then
    raise exception 'thing_not_found' using errcode = 'P0002';
  end if;
  if v_thing.owner_id <> v_uid then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  v_url := 'https://xhere.world/t/' || v_thing.voice_id;
  insert into public.nfc_tags (thing_id, ndef_url, created_by)
  values (p_thing_id, v_url, v_uid);
  return v_url;
end;
$$;

comment on function public.bind_nfc(uuid) is
  'Records an optional NFC sticker. The phone writes https and xhere:// NDEF records. This does not make the object audible.';

create function public.report_thing(
  p_thing_id uuid,
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
  v_thing public.things;
  v_report_id uuid;
  v_limit integer;
  v_recent integer;
  v_open integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if p_reason is null or p_thing_id is null then
    raise exception 'invalid_report' using errcode = '22023';
  end if;
  if char_length(p_details) > 1000 then
    raise exception 'invalid_report' using errcode = '22023';
  end if;
  select * into v_thing from public.things t where t.id = p_thing_id;
  if not found or v_thing.status <> 'active' or (v_thing.visibility <> 'public' and v_thing.owner_id <> v_uid) then
    raise exception 'thing_not_found' using errcode = 'P0002';
  end if;
  if v_thing.owner_id = v_uid then
    raise exception 'cannot_report_self' using errcode = '22023';
  end if;

  select r.id into v_report_id
  from public.thing_reports r
  where r.thing_id = p_thing_id and r.reporter_id = v_uid and r.status = 'open';
  if found then
    return v_report_id;
  end if;

  select tl.reports_per_day into v_limit from public.tier_limits tl where tl.tier = v_profile.tier;
  select count(*) into v_recent from (
    select r.id from public.reports r
    where r.reporter_id = v_uid and r.created_at > pg_catalog.now() - interval '1 day'
    union all
    select tr.id from public.thing_reports tr
    where tr.reporter_id = v_uid and tr.created_at > pg_catalog.now() - interval '1 day'
  ) recent;
  if v_recent >= coalesce(v_limit, 0) then
    raise exception 'rate_limited' using errcode = 'PT429';
  end if;

  insert into public.thing_reports (thing_id, reporter_id, reason, details)
  values (p_thing_id, v_uid, p_reason, nullif(btrim(p_details), ''))
  returning id into v_report_id;

  select count(distinct r.reporter_id) into v_open
  from public.thing_reports r
  where r.thing_id = p_thing_id and r.status = 'open';
  if v_open >= 5 and v_thing.status = 'active' then
    update public.things set status = 'removed' where id = p_thing_id;
  end if;
  return v_report_id;
end;
$$;

comment on function public.report_thing(uuid, public.report_reason, text) is
  'Flags an object story. Five distinct open reports remove it from public reads. Idempotent while the previous flag is open.';

-- ---------------------------------------------------------------------------
-- Open resolver used only by the public-api Edge Function.
-- ---------------------------------------------------------------------------
create function public.public_api_thing(p_id text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_thing public.things;
  v_label text;
  v_media jsonb;
begin
  if p_id is null or char_length(p_id) > 180 then
    raise exception 'invalid_id' using errcode = '22023';
  end if;
  if p_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    select * into v_thing from public.things t where t.id = p_id::uuid;
  elsif p_id ~ '^[0-9a-f]{12}$' then
    select * into v_thing from public.things t where t.voice_id = p_id;
  else
    raise exception 'invalid_id' using errcode = '22023',
      detail = 'id must be a 12-hex voice id or a uuid';
  end if;
  if not found or v_thing.status <> 'active' or v_thing.visibility <> 'public' then
    return null;
  end if;
  if v_thing.category_slug is not null then
    select c.label into v_label from public.categories c where c.slug = v_thing.category_slug;
  end if;
  select coalesce(jsonb_agg(jsonb_build_object('url', element ->> 'url', 'alt', element ->> 'alt')), '[]'::jsonb)
    into v_media
  from jsonb_array_elements(v_thing.media) as element;
  return jsonb_build_object(
    'id', v_thing.id,
    'voice_id', v_thing.voice_id,
    'title', v_thing.title,
    'story', v_thing.story,
    'category_slug', v_thing.category_slug,
    'category_label', v_label,
    'place_name', v_thing.place_name,
    'lat', v_thing.fixed_lat,
    'lon', v_thing.fixed_lng,
    'media', v_media,
    'seen_count', v_thing.seen_count,
    'url', 'https://xhere.world/t/' || v_thing.voice_id,
    'attribution', 'Story posted on XHere. This API does not say who heard the object.',
    'license', 'xhere-thing',
    'share_alike', false
  );
end;
$$;

comment on function public.public_api_thing(text) is
  'Read-only public story for a voice id or uuid. No owner id. service_role only, via the public-api function.';

-- ---------------------------------------------------------------------------
-- RLS. Owners edit the story columns. Everyone reads a public active row.
-- The counter, the voice id, and the owner are not client-writable.
-- Tag tables have no client policies; the RPCs are the door.
-- ---------------------------------------------------------------------------
alter table public.things enable row level security;
alter table public.ble_tags enable row level security;
alter table public.nfc_tags enable row level security;
alter table public.thing_reports enable row level security;

revoke all on table public.things from public, anon, authenticated;
revoke all on table public.ble_tags from public, anon, authenticated;
revoke all on table public.nfc_tags from public, anon, authenticated;
revoke all on table public.thing_reports from public, anon, authenticated;

grant select (
  id, voice_id, drop_id, place_name, category_slug, title, story, media,
  visibility, status, seen_count, created_at, updated_at, fixed_lat, fixed_lng
) on public.things to anon, authenticated;

grant update (
  title, story, category_slug, place_name, drop_id, media, visibility, fixed_lat, fixed_lng
) on public.things to authenticated;

grant delete on public.things to authenticated;

create policy things_select on public.things
  for select
  using (
    (status = 'active' and visibility = 'public')
    or owner_id = (select auth.uid())
  );

create policy things_update on public.things
  for update
  using (owner_id = (select auth.uid()))
  with check (owner_id = (select auth.uid()));

create policy things_delete on public.things
  for delete
  using (owner_id = (select auth.uid()));

revoke execute on function
  private.new_voice_id(),
  private.thing_media_ok(jsonb),
  private.normalize_thing_key(text),
  private.thing_for_key(text),
  private.thing_json(public.things, boolean),
  private.thing_take(text, integer, interval, integer),
  private.protect_thing_row(),
  private.thing_prepare(text, text, text, uuid, text, double precision, double precision, jsonb, text, uuid)
from public, anon, authenticated;

revoke execute on function
  public.create_thing(text, text, text, uuid, text, double precision, double precision, jsonb, text),
  public.update_thing(uuid, text, text, text, uuid, text, double precision, double precision, jsonb, text),
  public.my_things(),
  public.resolve_things(text[]),
  public.hear_things(text[]),
  public.bind_ble_tag(uuid, text),
  public.bind_nfc(uuid),
  public.report_thing(uuid, public.report_reason, text),
  public.public_api_thing(text)
from public, anon, authenticated;

grant execute on function
  public.create_thing(text, text, text, uuid, text, double precision, double precision, jsonb, text),
  public.update_thing(uuid, text, text, text, uuid, text, double precision, double precision, jsonb, text),
  public.my_things(),
  public.resolve_things(text[]),
  public.hear_things(text[]),
  public.bind_ble_tag(uuid, text),
  public.bind_nfc(uuid),
  public.report_thing(uuid, public.report_reason, text)
to authenticated;

grant execute on function public.public_api_thing(text) to service_role;
