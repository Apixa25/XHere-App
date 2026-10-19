-- Live conditions: right-now expiry, civic open-data lifetime, per-region weather config.
-- No city is hard-coded in the functions. The Jamestown box is only a row this test inserts.
begin;
create extension if not exists pgtap with schema extensions;
select plan(31);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';

select ok(
  (select bool_and(p.prosecdef and exists (
      select 1 from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%'
    ))
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where (n.nspname, p.proname) in (
     ('public', 'region_live_at'),
     ('private', 'upsert_region_live'),
     ('private', 'enforce_right_now_window'),
     ('private', 'upsert_open_data_items')
   )),
  'live-condition functions are security definer with an empty search_path'
);
select ok(
  (select c.relrowsecurity from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relname = 'region_live'),
  'RLS is enabled on region_live'
);
select ok(
  has_function_privilege('anon', 'public.region_live_at(double precision,double precision)', 'execute')
  and has_function_privilege('authenticated', 'public.region_live_at(double precision,double precision)', 'execute')
  and not has_function_privilege('anon', 'private.upsert_region_live(jsonb)', 'execute')
  and not has_function_privilege('authenticated', 'private.upsert_region_live(jsonb)', 'execute')
  and (select p.proacl::text like '%service_role%'
       from pg_proc p
       join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'private' and p.proname = 'upsert_region_live'),
  'anyone can read which weather provider covers a point; only the importer role can write it'
);
select ok(
  (select default_ttl = interval '4 hours' and max_ttl = interval '24 hours'
   from public.categories where slug = 'right_now'),
  'right now defaults to 4 hours and cannot be posted past 24'
);
select ok(
  (select default_ttl = interval '7 days' and max_ttl = interval '30 days'
   from public.categories where slug = 'civic_issue'),
  'civic issues are a short-lived category'
);

select throws_ok(
  $$ insert into public.drops (
       author_id, category_id, category_version, title, geog, display_geog, reveal_radius_m
     )
     select '11111111-1111-4111-8111-111111111111', c.id, c.version, 'Bare',
            extensions.st_geogfromtext('SRID=4326;POINT(-100 40)'),
            extensions.st_geogfromtext('SRID=4326;POINT(-100 40)'),
            1000
     from public.categories c where c.slug = 'right_now' $$,
  '22023', 'invalid_right_now_ttl', 'a right now drop needs a 1, 4, or 24 hour expiry'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select throws_ok(
  $$ insert into public.region_live (region_id, name, country, west, south, east, north, weather_provider)
     values ('us-example', 'Example', 'US', -1, -1, 1, 1, 'nws') $$,
  '42501',
  'permission denied for table region_live',
  'signed-in people cannot write region config'
);

do $$
begin
  perform set_config('t.hour', public.create_drop(
    'right_now', 40.0, -100.0, 'Line at the door', null, '{"kind":"line"}',
    p_expires_at => now() + interval '1 hour'
  ) ->> 'id', true);
  perform set_config('t.four', public.create_drop(
    'right_now', 40.0, -100.0, 'Four', null, '{"kind":"hazard"}',
    p_expires_at => now() + interval '4 hours'
  ) ->> 'id', true);
  perform set_config('t.day', public.create_drop(
    'right_now', 40.0, -100.0, 'Day', null, '{"kind":"other"}',
    p_expires_at => now() + interval '24 hours'
  ) ->> 'id', true);
  perform set_config('t.usual', public.create_drop(
    'right_now', 40.0, -100.0, 'Usual', null, '{"kind":"closed"}'
  ) ->> 'id', true);
end;
$$;

select ok(
  (select expires_at between now() + interval '50 minutes' and now() + interval '70 minutes'
   from public.here_card(40.0, -100.0) where id = current_setting('t.hour')::uuid),
  'one hour is accepted'
);
select ok(
  (select expires_at between now() + interval '3 hours 40 minutes' and now() + interval '4 hours 20 minutes'
   from public.here_card(40.0, -100.0) where id = current_setting('t.four')::uuid),
  'four hours is accepted'
);
select ok(
  (select expires_at between now() + interval '23 hours 40 minutes' and now() + interval '24 hours 20 minutes'
   from public.here_card(40.0, -100.0) where id = current_setting('t.day')::uuid),
  '24 hours is accepted'
);
select ok(
  (select expires_at between now() + interval '3 hours 40 minutes' and now() + interval '4 hours 20 minutes'
   from public.here_card(40.0, -100.0) where id = current_setting('t.usual')::uuid),
  'omitting an expiry uses the 4 hour default'
);

select throws_ok(
  $$ select public.create_drop('right_now', 40.0, -100.0, 'Soon', null, '{"kind":"line"}', p_expires_at => now() + interval '30 minutes') $$,
  '22023', 'invalid_right_now_ttl', '30 minutes is not a right now lifetime'
);
select throws_ok(
  $$ select public.create_drop('right_now', 40.0, -100.0, 'Two', null, '{"kind":"line"}', p_expires_at => now() + interval '2 hours') $$,
  '22023', 'invalid_right_now_ttl', '2 hours is not a right now lifetime'
);
select throws_ok(
  $$ select public.create_drop('right_now', 40.0, -100.0, 'Later', null, '{"kind":"line"}', p_expires_at => now() + interval '48 hours') $$,
  '22023', 'expires_too_far', '48 hours is past the category cap'
);
select throws_ok(
  $$ select public.create_drop('right_now', 40.0, -100.0, 'What', null, '{}'::jsonb, p_expires_at => now() + interval '1 hour') $$,
  '22023', 'invalid_attributes', 'kind is required'
);

reset role;
update public.drops set expires_at = now() - interval '1 minute' where id = current_setting('t.hour')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(
  (select count(*)::integer from public.here_card(40.0, -100.0) where id = current_setting('t.hour')::uuid),
  0,
  'an expired right now drop leaves the Here Card before the cleanup job'
);
select is(
  (select count(*)::integer from public.drops_in_view(-100.01, 39.99, -99.99, 40.01, null, 16)
   where drop_id = current_setting('t.hour')::uuid),
  0,
  'an expired right now drop leaves the map before the cleanup job'
);
reset role;
select is(
  (select status::text from public.drops where id = current_setting('t.hour')::uuid),
  'active',
  'the row stays active until the existing free cleanup job'
);
select ok(private.expire_drops() >= 1, 'private.expire_drops is the free cleanup, already on pg_cron');
select is(
  (select status::text from public.drops where id = current_setting('t.hour')::uuid),
  'expired',
  'the cleanup job marks the row expired'
);

select is(
  private.upsert_open_data_items($json$[
    {
      "source_slug": "open311-example",
      "source_name": "Example city",
      "source_home": "https://example.com/open311",
      "external_id": "open311:1",
      "category_slug": "civic_issue",
      "title": "Pothole",
      "summary": "Open civic request. Status: open.",
      "description": "Alice lives at 1 Main",
      "lat": 42.09,
      "lng": -79.24,
      "source_url": "https://example.com/open311/requests/1.json",
      "license": "CC0 1.0",
      "attribution": "Example city open data",
      "region_id": "us-example",
      "attributes": {"service": "Pothole", "status": "open"},
      "expires_at": "2099-01-01T00:00:00Z"
    },
    {
      "source_slug": "open311-example",
      "source_name": "Example city",
      "source_home": "https://example.com/open311",
      "external_id": "open311:2",
      "category_slug": "civic_issue",
      "title": "Old light",
      "summary": "Open civic request. Status: open.",
      "lat": 42.09,
      "lng": -79.24,
      "source_url": "https://example.com/open311/requests/2.json",
      "license": "CC0 1.0",
      "attribution": "Example city open data",
      "region_id": "us-example",
      "attributes": {"service": "Streetlight", "status": "open"},
      "expires_at": "2000-01-01T00:00:00Z"
    }
  ]$json$::jsonb),
  2,
  'a civic feed can set expires_at'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select ok(
  (select revealed and body = 'Open civic request. Status: open.'
      and attributes ->> 'service' = 'Pothole'
      and attributes ->> 'status' = 'open'
      and source = 'open_data'
      and is_mine = false
      and position('Alice' in coalesce(body, '') || title) = 0
   from public.here_card(42.09, -79.24) where title = 'Pothole'),
  'an open civic request is on the Here Card without a person name from the feed'
);
select is(
  (select count(*)::integer from public.here_card(42.09, -79.24) where title = 'Old light'),
  0,
  'a civic request past its expiry is hidden'
);

reset role;
select is(
  private.upsert_open_data_items($json$[
    {
      "source_slug": "open311-example",
      "source_name": "Example city",
      "source_home": "https://example.com/open311",
      "external_id": "open311:3",
      "category_slug": "civic_issue",
      "title": "Keep",
      "summary": "Open civic request. Status: open.",
      "lat": 42.091,
      "lng": -79.241,
      "source_url": "https://example.com/open311/requests/3.json",
      "license": "CC0 1.0",
      "attribution": "Example city open data",
      "region_id": "us-example",
      "attributes": {"service": "Keep", "status": "open"},
      "expires_at": "2099-06-01T00:00:00Z"
    }
  ]$json$::jsonb),
  1,
  'first civic write'
);
select private.upsert_open_data_items($json$[
  {
    "source_slug": "open311-example",
    "source_name": "Example city",
    "source_home": "https://example.com/open311",
    "external_id": "open311:3",
    "category_slug": "civic_issue",
    "title": "Keep",
    "summary": "Open civic request. Status: open.",
    "lat": 42.091,
    "lng": -79.241,
    "source_url": "https://example.com/open311/requests/3.json",
    "license": "CC0 1.0",
    "attribution": "Example city open data",
    "region_id": "us-example",
    "attributes": {"service": "Keep", "status": "open"}
  }
]$json$::jsonb);
select ok(
  (select d.expires_at is null
   from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   where i.external_id = 'open311:3'),
  'leaving expires_at out keeps a history import permanent'
);

select private.upsert_region_live($json${
  "region_id": "us-ny-jamestown",
  "name": "Jamestown, New York, USA",
  "country": "US",
  "west": -79.32,
  "south": 42.05,
  "east": -79.18,
  "north": 42.14,
  "weather_provider": "nws"
}$json$::jsonb);
select private.upsert_region_live($json${
  "region_id": "xx-wide",
  "name": "Wide",
  "country": "ZZ",
  "west": -10,
  "south": -10,
  "east": 10,
  "north": 10,
  "weather_provider": "nws"
}$json$::jsonb);
select private.upsert_region_live($json${
  "region_id": "xx-quiet",
  "name": "Quiet",
  "country": "ZZ",
  "west": 1,
  "south": 1,
  "east": 2,
  "north": 2,
  "weather_provider": "none"
}$json$::jsonb);

set local role anon;
select is(
  (select weather_provider from public.region_live_at(42.09, -79.24)),
  'nws',
  'a point in the pilot box uses the National Weather Service'
);
select is(
  (select weather_provider from public.region_live_at(1.5, 1.5)),
  'none',
  'the smallest box wins, and none turns alerts off'
);
select is(
  (select weather_provider from public.region_live_at(5.0, 5.0)),
  'nws',
  'a point in only the wider box uses that box'
);
select is(
  (select count(*)::integer from public.region_live_at(48.8566, 2.3522)),
  0,
  'a point outside every configured box returns no row'
);
select ok(
  (select count(*) >= 1 from public.region_live),
  'signed-out visitors can read region config'
);

reset role;
select throws_ok(
  $$ select private.upsert_region_live('{"region_id":"zz-paid","name":"Paid","country":"US","west":0,"south":0,"east":1,"north":1,"weather_provider":"paid"}'::jsonb) $$,
  '22023', 'invalid_weather_provider', 'only free provider ids are stored'
);

select * from finish();
rollback;
