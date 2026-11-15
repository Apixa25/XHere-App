-- Geo proxy cache (PLAN §2). The phone calls the geo-proxy Edge Function.
-- This database does not call Nominatim or Overpass. The function does,
-- through the hosts in private.geo_upstream (or GEO_NOMINATIM_URL /
-- GEO_OVERPASS_URL on the function). Change a row to switch a mirror.
-- No app update. No city. No user id on the cache.
--
-- Nominatim: one upstream request every 1.1 seconds for the whole project,
-- using the same private.nominatim_gate older builds already share.
-- Overpass: at most 90 upstream requests per UTC day, and not more than one
-- every 2 seconds. A busy slot returns the cached body, or nothing.

create table private.geo_cache (
  cache_key text primary key check (char_length(cache_key) between 8 and 200),
  kind text not null check (kind in ('nominatim', 'overpass')),
  body jsonb not null,
  fetched_at timestamptz not null default now(),
  expires_at timestamptz not null
);
comment on table private.geo_cache is
  'Shared Nominatim and Overpass answers. Rounded keys so neighboring phones reuse one row. No user id.';

alter table private.geo_cache enable row level security;
revoke all on table private.geo_cache from public, anon, authenticated;

create table private.geo_upstream (
  kind text primary key check (kind in ('nominatim', 'overpass')),
  endpoint text not null check (endpoint ~ '^https://[^[:space:]@]+'),
  updated_at timestamptz not null default now()
);
comment on table private.geo_upstream is
  'Upstream host for geo-proxy. An operator can point this at another public mirror without shipping an app update. Env vars on the function win when they are set.';

insert into private.geo_upstream (kind, endpoint) values
  ('nominatim', 'https://nominatim.openstreetmap.org/reverse'),
  ('overpass', 'https://overpass-api.de/api/interpreter');

alter table private.geo_upstream enable row level security;
revoke all on table private.geo_upstream from public, anon, authenticated;

create table private.geo_budget (
  kind text primary key check (kind = 'overpass'),
  next_allowed_at timestamptz not null default '-infinity',
  window_day date,
  window_count integer not null default 0 check (window_count >= 0)
);
comment on table private.geo_budget is
  'Overpass budget for the whole project: 90 requests per UTC day, at least 2 seconds apart.';

insert into private.geo_budget (kind) values ('overpass');

alter table private.geo_budget enable row level security;
revoke all on table private.geo_budget from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- service_role only. The Edge Function calls these. Clients cannot.
-- ---------------------------------------------------------------------------
create function public.geo_upstream_endpoint(p_kind text)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select u.endpoint
  from private.geo_upstream u
  where u.kind = p_kind
    and u.endpoint ~ '^https://'
    and position('@' in u.endpoint) = 0
  limit 1;
$$;

create function public.geo_cache_read(p_key text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_body jsonb;
  v_expires timestamptz;
begin
  if p_key is null or char_length(p_key) < 8 or char_length(p_key) > 200 then
    return null;
  end if;
  select c.body, c.expires_at into v_body, v_expires
  from private.geo_cache c
  where c.cache_key = p_key;
  if not found then
    return null;
  end if;
  return jsonb_build_object('body', v_body, 'fresh', v_expires > now());
end;
$$;

create function public.geo_cache_write(
  p_key text,
  p_kind text,
  p_body jsonb,
  p_ttl_seconds integer
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_key is null or char_length(p_key) < 8 or char_length(p_key) > 200 then
    return false;
  end if;
  if p_kind is null or p_kind not in ('nominatim', 'overpass') then
    return false;
  end if;
  if p_body is null or octet_length(p_body::text) > 262144 then
    return false;
  end if;
  if p_ttl_seconds is null or p_ttl_seconds < 60 or p_ttl_seconds > 7776000 then
    return false;
  end if;
  insert into private.geo_cache (cache_key, kind, body, fetched_at, expires_at)
  values (
    p_key,
    p_kind,
    p_body,
    now(),
    now() + make_interval(secs => p_ttl_seconds)
  )
  on conflict (cache_key) do update
    set kind = excluded.kind,
        body = excluded.body,
        fetched_at = excluded.fetched_at,
        expires_at = excluded.expires_at;
  return true;
end;
$$;

create function public.geo_claim_upstream(p_kind text)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_next timestamptz;
  v_day date;
  v_count integer;
  v_today date := (clock_timestamp() at time zone 'utc')::date;
begin
  if p_kind = 'nominatim' then
    select g.next_allowed_at into v_next
    from private.nominatim_gate g
    where g.id = 1
    for update;
    if not found or v_next > clock_timestamp() then
      return false;
    end if;
    update private.nominatim_gate
    set next_allowed_at = clock_timestamp() + interval '1100 milliseconds'
    where id = 1;
    return true;
  elsif p_kind = 'overpass' then
    select b.next_allowed_at, b.window_day, b.window_count
      into v_next, v_day, v_count
    from private.geo_budget b
    where b.kind = 'overpass'
    for update;
    if not found then
      return false;
    end if;
    if v_day is distinct from v_today then
      v_day := v_today;
      v_count := 0;
    end if;
    if v_count >= 90 or v_next > clock_timestamp() then
      update private.geo_budget
      set window_day = v_day,
          window_count = v_count
      where kind = 'overpass';
      return false;
    end if;
    update private.geo_budget
    set next_allowed_at = clock_timestamp() + interval '2 seconds',
        window_day = v_day,
        window_count = v_count + 1
    where kind = 'overpass';
    return true;
  end if;
  return false;
end;
$$;

comment on function public.geo_upstream_endpoint(text) is
  'Https upstream for geo-proxy. service_role only. Env on the function overrides this.';
comment on function public.geo_cache_read(text) is
  'Cached geo-proxy body and whether it is still inside the TTL. service_role only.';
comment on function public.geo_cache_write(text, text, jsonb, integer) is
  'Stores one geo-proxy answer. Rejects oversized bodies and TTLs outside 1 minute..90 days. service_role only.';
comment on function public.geo_claim_upstream(text) is
  'True when geo-proxy may call that upstream once. Nominatim shares private.nominatim_gate (1.1 seconds). Overpass is 90 per UTC day and 2 seconds apart. A false claim means return cache or nothing.';

revoke execute on function
  public.geo_upstream_endpoint(text),
  public.geo_cache_read(text),
  public.geo_cache_write(text, text, jsonb, integer),
  public.geo_claim_upstream(text)
from public, anon, authenticated;

grant execute on function
  public.geo_upstream_endpoint(text),
  public.geo_cache_read(text),
  public.geo_cache_write(text, text, jsonb, integer),
  public.geo_claim_upstream(text)
to service_role;
