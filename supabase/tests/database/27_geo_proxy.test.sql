-- geo-proxy cache and the shared Nominatim / Overpass budgets.
-- Clients cannot read the tables or call the functions. service_role can.
begin;
create extension if not exists pgtap with schema extensions;
select plan(15);

select ok(
  (select bool_and(p.prosecdef and exists (
      select 1 from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%'
    ))
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public'
     and p.proname in ('geo_upstream_endpoint', 'geo_cache_read', 'geo_cache_write', 'geo_claim_upstream')),
  'geo proxy functions are security definer with a locked search_path'
);

select ok(
  (select bool_and(c.relrowsecurity)
   from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'private'
     and c.relname in ('geo_cache', 'geo_upstream', 'geo_budget')),
  'RLS is enabled on the geo cache, the upstream table, and the Overpass budget'
);

select ok(
  not has_table_privilege('anon', 'private.geo_cache', 'select')
  and not has_table_privilege('authenticated', 'private.geo_cache', 'select')
  and not has_table_privilege('authenticated', 'private.geo_upstream', 'select')
  and not has_table_privilege('authenticated', 'private.geo_upstream', 'update')
  and not has_table_privilege('anon', 'private.geo_budget', 'select'),
  'clients cannot read or edit the geo cache or the upstream hosts'
);

select ok(
  not has_function_privilege('anon', 'public.geo_claim_upstream(text)', 'execute')
  and not has_function_privilege('authenticated', 'public.geo_claim_upstream(text)', 'execute')
  and not has_function_privilege('authenticated', 'public.geo_cache_read(text)', 'execute')
  and not has_function_privilege('authenticated', 'public.geo_cache_write(text,text,jsonb,integer)', 'execute')
  and not has_function_privilege('anon', 'public.geo_upstream_endpoint(text)', 'execute')
  and has_function_privilege('service_role', 'public.geo_claim_upstream(text)', 'execute')
  and has_function_privilege('service_role', 'public.geo_cache_write(text,text,jsonb,integer)', 'execute')
  and has_function_privilege('service_role', 'public.geo_upstream_endpoint(text)', 'execute'),
  'only the edge function role can use the geo proxy SQL'
);

select ok(
  public.geo_upstream_endpoint('nominatim') like 'https://%',
  'the seeded Nominatim host is https'
);
select ok(
  public.geo_upstream_endpoint('overpass') like 'https://%',
  'the seeded Overpass host is https'
);

update private.geo_upstream
set endpoint = 'https://mirror.example.test/reverse'
where kind = 'nominatim';
select is(
  public.geo_upstream_endpoint('nominatim'),
  'https://mirror.example.test/reverse',
  'changing the config row switches the upstream without an app release'
);
update private.geo_upstream
set endpoint = 'https://nominatim.openstreetmap.org/reverse'
where kind = 'nominatim';

select is(
  public.geo_cache_write('nominatim:51.5:-0.12', 'nominatim', '{"name":"Bridge"}'::jsonb, 3600),
  true,
  'a normal answer is stored'
);
select is(
  public.geo_cache_read('nominatim:51.5:-0.12') -> 'body' ->> 'name',
  'Bridge',
  'the cached body is the upstream JSON'
);
select is(
  (public.geo_cache_read('nominatim:51.5:-0.12') ->> 'fresh')::boolean,
  true,
  'a row inside its TTL is fresh'
);
select is(
  public.geo_cache_read('nominatim:missing') is null,
  true,
  'a missing key reads as null'
);
select is(
  public.geo_cache_write('nope', 'nominatim', '{"name":"x"}'::jsonb, 3600),
  false,
  'a short key is refused'
);

select is(public.geo_claim_upstream('nominatim'), true, 'the first Nominatim claim in this window is allowed');
select is(public.geo_claim_upstream('nominatim'), false, 'Nominatim stays at one request per 1.1 seconds');

update private.geo_budget
set window_day = (clock_timestamp() at time zone 'utc')::date,
    window_count = 90,
    next_allowed_at = '-infinity'
where kind = 'overpass';
select is(
  public.geo_claim_upstream('overpass'),
  false,
  'Overpass stops at 90 requests for the UTC day'
);

select * from finish();
rollback;
