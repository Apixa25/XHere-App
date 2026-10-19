-- Public read API. Signed-out content only, plus the extra exclusions:
-- demo accounts and location_trust = reduced. service_role is the only caller.
begin;
create extension if not exists pgtap with schema extensions;
select plan(29);

insert into private.tip_lines (region_slug, name, phone, area) values
  ('test-area', 'Test tip line', '555-0100',
   extensions.st_geogfromtext('SRID=4326;POLYGON((-101 39, -99 39, -99 41, -101 41, -101 39))'));

insert into auth.users (id, email, raw_user_meta_data) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test', '{}'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test', '{"demo": true}');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'regular', handle = 'bob', is_demo = true where id = '22222222-2222-4222-8222-222222222222';

select ok(
  (select bool_and(p.prosecdef and exists (
      select 1 from unnest(coalesce(p.proconfig, '{}')) c
      where c in ('search_path=', 'search_path=""')
    ))
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where (n.nspname, p.proname) in (
     ('public', 'public_api_here'),
     ('public', 'public_api_drop'),
     ('public', 'public_api_categories'),
     ('public', 'public_api_walks'),
     ('public', 'public_api_take'),
     ('private', 'public_api_visible'),
     ('private', 'public_api_item'),
     ('private', 'public_api_walk')
   )),
  'public API functions are security definer with an empty search_path'
);
select ok(
  (select c.relrowsecurity from pg_class c where c.oid = 'private.public_api_quota'::regclass),
  'the rate-limit table has row level security'
);
select ok(
  not has_function_privilege('anon', 'public.public_api_here(double precision,double precision,integer)', 'execute')
  and not has_function_privilege('authenticated', 'public.public_api_here(double precision,double precision,integer)', 'execute')
  and not has_function_privilege('anon', 'public.public_api_drop(text)', 'execute')
  and not has_function_privilege('anon', 'public.public_api_categories()', 'execute')
  and not has_function_privilege('anon', 'public.public_api_walks(double precision,double precision)', 'execute')
  and not has_function_privilege('anon', 'public.public_api_take(text)', 'execute')
  and has_function_privilege('service_role', 'public.public_api_here(double precision,double precision,integer)', 'execute'),
  'only service_role can execute the public API functions'
);
select ok(
  not has_table_privilege('anon', 'private.public_api_quota', 'select')
  and not has_table_privilege('authenticated', 'private.public_api_quota', 'select')
  and not exists (
    select 1 from information_schema.columns
    where table_schema = 'private' and table_name = 'public_api_quota' and column_name = 'ip'
  ),
  'clients cannot read the quota table, and the address is not a column'
);

select is(
  private.upsert_open_data_items($json$[
    {
      "source_slug": "wikipedia-geo",
      "source_name": "Wikipedia",
      "source_home": "https://en.wikipedia.org/",
      "external_id": "enwiki:api1",
      "category_slug": "historical_fact",
      "title": "Test Library",
      "summary": "A public library summary.",
      "lat": 40.0,
      "lng": -100.0,
      "source_url": "https://en.wikipedia.org/wiki/Test_Library",
      "license": "CC BY-SA 4.0",
      "attribution": "Wikipedia contributors, CC BY-SA 4.0",
      "region_id": "zz-api",
      "attributes": {"citation": "Wikipedia"}
    }
  ]$json$::jsonb),
  1,
  'a public record is available to the API'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.hist', public.create_drop(
    'historical_fact', 40.0, -100.0, 'Old mill', 'SECRET_HIST_BODY'
  ) ->> 'id', true);
  perform set_config('t.anon', public.create_drop(
    'story', 40.0, -100.0001, 'Quiet story', 'SECRET_ANON_BODY', p_display_anonymous => true
  ) ->> 'id', true);
  perform set_config('t.incident', public.create_drop(
    'incident_report', 40.0, -100.0, 'Glass on the walk', 'SECRET_INCIDENT_BODY',
    jsonb_build_object('occurred_at', to_jsonb(now())),
    p_acknowledge_guidelines => true
  ) ->> 'id', true);
  perform set_config('t.reduced', public.create_drop(
    'note', 40.0, -100.0, 'Mocked bench', 'SECRET_REDUCED_BODY',
    p_integrity => '{"mock": true}'::jsonb
  ) ->> 'id', true);
  perform set_config('t.far', public.create_drop(
    'note', 40.02, -100.0, 'Far bench', 'SECRET_FAR_BODY'
  ) ->> 'id', true);
  perform set_config('t.pending', public.create_drop(
    'note', 40.0, -100.0, 'Waiting note', 'SECRET_PENDING_BODY'
  ) ->> 'id', true);
  perform set_config('t.expired', public.create_drop(
    'note', 40.0, -100.0, 'Old note', 'SECRET_EXPIRED_BODY'
  ) ->> 'id', true);
  perform set_config('t.removed', public.create_drop(
    'note', 40.0, -100.0, 'Removed note', 'SECRET_REMOVED_BODY'
  ) ->> 'id', true);
end;
$$;

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
do $$
begin
  perform set_config('t.demo', public.create_drop(
    'note', 40.0, -100.0, 'Demo note', 'SECRET_DEMO_BODY'
  ) ->> 'id', true);
end;
$$;

reset role;
update public.drops set status = 'pending_review' where id = current_setting('t.pending')::uuid;
update public.drops set status = 'expired', expires_at = now() - interval '1 minute' where id = current_setting('t.expired')::uuid;
update public.drops set status = 'removed' where id = current_setting('t.removed')::uuid;

insert into public.region_live (region_id, name, country, west, south, east, north, weather_provider)
values ('zz-api', 'API test', 'ZZ', -101, 39, -99, 41, 'none');

insert into public.walks (id, region_id, slug, title, description, distance_m, duration_min, min_lng, min_lat, max_lng, max_lat)
values (
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'zz-api', 'api-walk', 'API walk',
  'A walk used by the public API test.', 100, 10, -100.01, 39.99, -99.99, 40.01
);
insert into public.walk_stops (walk_id, position, source_slug, external_id)
values ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 1, 'wikipedia-geo', 'enwiki:api1');

set local role service_role;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "service_role"}';

select ok(
  (select (item ->> 'kind') = 'public_record'
      and item ->> 'summary' = 'A public library summary.'
      and item ->> 'license' = 'CC BY-SA 4.0'
      and item ->> 'attribution' = 'Wikipedia contributors, CC BY-SA 4.0'
      and (item ->> 'share_alike')::boolean
      and item ->> 'author_handle' is null
   from jsonb_array_elements(public.public_api_here(40.0, -100.0, 300) -> 'items') item
   where item ->> 'title' = 'Test Library'),
  'a public record keeps its summary, license, and attribution'
);
select ok(
  (select item ->> 'kind' = 'user_teaser'
      and item ->> 'summary' is null
      and item ->> 'author_handle' = 'alice'
      and item ->> 'license' = 'xhere-teaser'
      and not (item ->> 'share_alike')::boolean
      and not (item ? 'body')
      and not (item ? 'attributes')
      and not (item ? 'media')
      and not (item ? 'geog')
      and not (item ? 'author_id')
      and not (item ? 'location_trust')
   from jsonb_array_elements(public.public_api_here(40.0, -100.0, 300) -> 'items') item
   where item ->> 'title' = 'Old mill'),
  'a user drop is a teaser even when the caller JWT is the author'
);
select ok(
  (select item ->> 'author_handle' is null
   from jsonb_array_elements(public.public_api_here(40.0, -100.0, 300) -> 'items') item
   where item ->> 'title' = 'Quiet story'),
  'an anonymous drop does not name the author'
);
select ok(
  (select (item ->> 'approximate')::boolean
      and item ->> 'author_handle' is null
      and item ->> 'summary' is null
      and abs((item ->> 'lat')::float8 - extensions.st_y(d.display_geog::extensions.geometry)) < 1e-8
      and abs((item ->> 'lon')::float8 - extensions.st_x(d.display_geog::extensions.geometry)) < 1e-8
      and not extensions.st_equals(d.geog::extensions.geometry, d.display_geog::extensions.geometry)
      and abs((item ->> 'lat')::float8 - extensions.st_y(d.geog::extensions.geometry)) > 1e-6
   from jsonb_array_elements(public.public_api_here(40.0, -100.0, 1000) -> 'items') item
   join public.drops d on d.id = (item ->> 'id')::uuid
   where d.id = current_setting('t.incident')::uuid),
  'a blurred incident uses the display point, not the stored point, and hides the author'
);
select is(
  (select count(*)::integer
   from jsonb_array_elements(public.public_api_here(40.0, -100.0, 1000) -> 'items') item
   where item ->> 'title' in ('Mocked bench', 'Demo note', 'Waiting note', 'Old note', 'Removed note', 'Far bench')),
  0,
  'reduced, demo, pending, expired, removed, and out-of-radius drops are absent'
);
select ok(
  position('SECRET_' in public.public_api_here(40.0, -100.0, 1000)::text) = 0,
  'hidden text is not in the payload'
);
select ok(
  exists (
    select 1 from public.drops_in_view(-100.01, 39.99, -99.99, 40.01, null, 16) v
    where v.title in ('Mocked bench', 'Demo note')
  ),
  'the map RPC still lists demo and reduced drops; only this API withholds them'
);
select is(
  (select public.public_api_here(40.0, -100.0, 300) -> 'live' ->> 'region_id'),
  'zz-api',
  'the signed-out live region is included, without weather-alert text'
);
select throws_ok(
  $$ select public.public_api_here(40.0, -100.0, 1001) $$,
  '22023', 'invalid_radius', 'radius above 1000 m is rejected'
);
select throws_ok(
  $$ select public.public_api_here(95.0, -100.0, 300) $$,
  '22023', 'invalid_location', 'latitude must be valid'
);

select ok(
  (select public.public_api_drop(d.short_id) ->> 'summary' is null
      and public.public_api_drop(d.short_id) ->> 'title' = 'Old mill'
      and public.public_api_drop(d.id::text) ->> 'short_id' = d.short_id
   from public.drops d where d.id = current_setting('t.hist')::uuid),
  'a drop can be read by short id or uuid, still as a teaser'
);
select is(
  public.public_api_drop('000000000000'),
  null,
  'an unknown short id is null, the same answer as a hidden row'
);
select is(
  public.public_api_drop(current_setting('t.reduced')),
  null,
  'a reduced-trust drop is null by id'
);
select is(
  public.public_api_drop(current_setting('t.demo')),
  null,
  'a demo drop is null by id'
);
select is(
  public.public_api_drop(current_setting('t.pending')),
  null,
  'a pending drop is null by id'
);

select ok(
  (select cat ->> 'slug' = 'historical_fact'
      and cat ->> 'license' = 'xhere-catalog'
      and cat ->> 'attribution' = 'XHere category list'
      and not (cat ? 'embedding')
   from jsonb_array_elements(public.public_api_categories() -> 'categories') cat
   where cat ->> 'slug' = 'historical_fact'),
  'categories are the public labels, with attribution'
);
select ok(
  position('person_descriptors' in public.public_api_categories()::text) = 0
  and position('embedding' in public.public_api_categories()::text) = 0,
  'category payloads omit filter patterns and embeddings'
);

select ok(
  (select walk ->> 'slug' = 'api-walk'
      and walk ->> 'license' = 'CC BY-SA 4.0'
      and (walk ->> 'share_alike')::boolean
      and not (walk ? 'started')
      and (walk -> 'stops' -> 0 ->> 'summary') = 'A public library summary.'
      and not (walk -> 'stops' -> 0 ? 'reached')
   from jsonb_array_elements(public.public_api_walks(40.0, -100.0) -> 'walks') walk),
  'a walk in the box includes the public stop and no personal progress'
);

reset role;
update public.drops d
set location_trust = 'reduced'
from public.open_data_items i
where i.drop_id = d.id and i.external_id = 'enwiki:api1';
set local role service_role;
select is(
  (select count(*)::integer from jsonb_array_elements(public.public_api_walks(40.0, -100.0) -> 'walks')),
  0,
  'a walk whose only stop is reduced-trust is omitted'
);
reset role;
update public.drops d
set location_trust = 'standard'
from public.open_data_items i
where i.drop_id = d.id and i.external_id = 'enwiki:api1';

set local role service_role;
select is(
  (public.public_api_take(repeat('ab', 32)) ->> 'allowed')::boolean,
  true,
  'the first request in a window is allowed'
);
reset role;
update private.public_api_quota
set hits = 30
where ip_hash = repeat('ab', 32);
set local role service_role;
select is(
  (public.public_api_take(repeat('ab', 32)) ->> 'allowed')::boolean,
  false,
  'the 31st request in the same minute is refused'
);
reset role;
update private.public_api_quota
set hits = 30, window_start = clock_timestamp() - interval '2 minutes'
where ip_hash = repeat('ab', 32);
set local role service_role;
select is(
  (public.public_api_take(repeat('ab', 32)) ->> 'remaining')::integer,
  29,
  'an expired window starts over'
);
select throws_ok(
  $$ select public.public_api_take('not-a-hash') $$,
  '22023', 'invalid_client', 'a raw address is not accepted as the quota key'
);

set local role anon;
select throws_ok(
  $$ select public.public_api_here(40.0, -100.0, 300) $$,
  '42501', 'permission denied for function public_api_here',
  'a signed-out role cannot call the SQL directly'
);

select * from finish();
rollback;
