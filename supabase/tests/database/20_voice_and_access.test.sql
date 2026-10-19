-- Voice clips (30 s, voice-clips bucket) and accessibility majority.
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test');

select ok(
  exists (select 1 from public.categories where slug = 'access_place' and active),
  'access_place is a category'
);

select ok(
  exists (
    select 1 from storage.buckets
    where id = 'voice-clips' and public = false and file_size_limit = 262144
  ),
  'voice-clips is a private 256 KB bucket'
);

set local role authenticated;
set local request.jwt.claims to '';
select throws_ok(
  $$ select public.submit_accessibility_check('cell:40.000:-100.000', 40.0, -100.0, 'yes', 'yes', 'yes') $$,
  '42501', 'not_authenticated', 'a check needs a signed-in person'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(
  public.submit_accessibility_check(
    'cell:40.000:-100.000', 40.0, -100.0, 'yes', 'yes', 'yes', 'ramp by the side door'
  ) ->> 'status',
  'accessible',
  'one yes check is accessible'
);
select is(
  (public.accessibility_summary('cell:40.000:-100.000') ->> 'my_notes'),
  'ramp by the side door',
  'the author sees their own note'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  public.submit_accessibility_check('cell:40.000:-100.000', 40.0, -100.0, 'no', 'no', 'no') ->> 'status',
  'unknown',
  'a tie is not a majority'
);
select ok(
  (public.accessibility_summary('cell:40.000:-100.000') ->> 'my_notes') is null,
  'someone else does not see the note'
);

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(
  public.submit_accessibility_check('cell:40.000:-100.000', 40.0001, -100.0001, 'yes', 'yes', 'no') ->> 'status',
  'partial',
  'two yes and one no on the restroom is partial'
);
select is(
  (public.accessibility_summary('cell:40.000:-100.000') ->> 'sample_size')::integer,
  3,
  'three people are in the sample'
);
select ok(
  (public.accessibility_summary('cell:40.000:-100.000') ->> 'last_confirmed_at') is not null,
  'the last confirmed time is the newest check'
);
select ok(
  exists (
    select 1
    from jsonb_array_elements(public.accessibility_in_view(-100.1, 39.9, -99.9, 40.1)) as item
    where item ->> 'place_key' = 'cell:40.000:-100.000'
  ),
  'the place is inside the map box'
);
select is(
  jsonb_array_length(public.accessibility_in_view(-70.0, 10.0, -69.0, 11.0)),
  0,
  'a distant box has no pins'
);

select throws_ok(
  $$ select public.submit_accessibility_check('cell:40.000:-100.000', 40.0, -100.0, 'yes', 'yes', 'yes', repeat('n', 281)) $$,
  '22023', 'invalid_note', 'notes stop at 280 characters'
);

select throws_ok(
  $$ insert into public.accessibility_checks (author_id, place_key, latitude, longitude, geog, step_free, ramp, restroom)
     values (
       auth.uid(), 'cell:1.000:1.000', 1, 1,
       extensions.st_setsrid(extensions.st_makepoint(1, 1), 4326)::extensions.geography,
       'yes', 'yes', 'yes'
     ) $$,
  '42501', 'permission denied for table accessibility_checks', 'checks are not inserted directly'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  $$ select public.create_drop(
       'note', 40.0, -100.0, 'Too long', null, '{}'::jsonb, '{}', null, null, false,
       jsonb_build_array(jsonb_build_object(
         'storage_bucket', 'voice-clips',
         'storage_path', '11111111-1111-4111-8111-111111111111/long.m4a',
         'media_kind', 'audio',
         'mime_type', 'audio/mp4',
         'byte_size', 90000,
         'duration_s', 31
       )),
       false, null) $$,
  '22023', 'voice_too_long', 'audio stops at 30 seconds'
);

select ok(
  (public.create_drop(
     'note', 40.0, -100.05, 'Short clip', null, '{}'::jsonb, '{}', null, null, false,
     jsonb_build_array(jsonb_build_object(
       'storage_bucket', 'voice-clips',
       'storage_path', '11111111-1111-4111-8111-111111111111/short.m4a',
       'media_kind', 'audio',
       'mime_type', 'audio/mp4',
       'byte_size', 80000,
       'duration_s', 12
     )),
     false, null) ->> 'category') = 'note',
  'a 12 second clip can be attached'
);

reset role;
select is(
  private.upsert_open_data_items($json$[
    {
      "source_slug": "osm-accessibility",
      "source_name": "OpenStreetMap contributors",
      "source_home": "https://www.openstreetmap.org/copyright",
      "external_id": "osm:node/1",
      "category_slug": "access_place",
      "title": "Library entrance",
      "summary": "OpenStreetMap tags wheelchair access as limited.",
      "lat": 42.100,
      "lng": -79.250,
      "source_url": "https://www.openstreetmap.org/node/1",
      "license": "ODbL 1.0",
      "attribution": "© OpenStreetMap contributors, ODbL",
      "region_id": "us-ny-test",
      "attributes": {
        "wheelchair": "limited",
        "ramp": "yes",
        "toilets_wheelchair": "yes",
        "citation": "OpenStreetMap",
        "source_url": "https://www.openstreetmap.org/node/1"
      }
    }
  ]$json$::jsonb),
  1,
  'an OSM accessibility tag imports as access_place'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(
  (public.accessibility_summary('osm:node/1') ->> 'status'),
  'partial',
  'wheelchair=limited stays partial'
);

select * from finish();
rollback;
