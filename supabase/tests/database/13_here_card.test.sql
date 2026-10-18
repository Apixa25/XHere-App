-- Here Card: a tapped spot, not a region. Reveal follows the viewer, not the tap.
-- Place names are a cached OpenStreetMap lookup with attribution stamped by the database.
begin;
create extension if not exists pgtap with schema extensions;
select plan(32);

insert into private.tip_lines (region_slug, name, phone, area) values
  ('test-area', 'Test tip line', '555-0100',
   extensions.st_geogfromtext('SRID=4326;POLYGON((-101 39, -99 39, -99 41, -101 41, -101 39))'));

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';

select ok(
  (select bool_and(p.prosecdef and exists (
      select 1 from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%'
    ))
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public'
     and p.proname in ('here_card', 'place_label', 'remember_place_label', 'claim_nominatim_slot')),
  'Here Card functions are security definer with an empty search_path'
);
select ok(
  (select bool_and(c.relrowsecurity)
   from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where (n.nspname, c.relname) in (('public', 'place_labels'), ('private', 'nominatim_gate'))),
  'RLS is enabled on the place cache and the Nominatim gate'
);
select ok(
  not has_table_privilege('authenticated', 'public.place_labels', 'select')
  and not has_table_privilege('authenticated', 'public.place_labels', 'insert')
  and not has_table_privilege('anon', 'public.place_labels', 'select'),
  'clients cannot read or write the place cache directly'
);
select ok(
  not has_function_privilege('anon', 'public.here_card(double precision,double precision,double precision,double precision,integer,integer)', 'execute')
  and not has_function_privilege('anon', 'public.claim_nominatim_slot()', 'execute')
  and not has_function_privilege('anon', 'public.remember_place_label(double precision,double precision,text,text)', 'execute'),
  'signed-out visitors cannot summarize a spot or fill the place cache'
);
select ok(
  has_function_privilege('authenticated', 'public.here_card(double precision,double precision,double precision,double precision,integer,integer)', 'execute')
  and has_function_privilege('authenticated', 'public.place_label(double precision,double precision)', 'execute'),
  'a signed-in person can load a Here Card and a cached place name'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.mill', public.create_drop(
    'historical_fact', 40.0, -100.0, 'Old mill', 'Burned down in 1890.', '{"year": 1890}',
    array['local']
  ) ->> 'id', true);
end;
$$;

-- Bob is about 111 km north. The tap is ~77 m from the mill. The body stays hidden.
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select not revealed and body is null and attributes is null and tags is null and media is null
      and title = 'Old mill'
      and distance_m between 70 and 85
   from public.here_card(40.0, -100.0009, 41.0, -100.0)
   where id = current_setting('t.mill')::uuid),
  'tapping next to a drop does not reveal it when the viewer is far away'
);

-- Same tap, but Bob is standing on the mill. The body opens. Distance stays the tap distance.
select ok(
  (select revealed and body = 'Burned down in 1890.' and attributes ->> 'year' = '1890'
      and tags = array['local']
      and distance_m between 70 and 85
   from public.here_card(40.0, -100.0009, 40.0, -100.0)
   where id = current_setting('t.mill')::uuid),
  'the body opens from the viewer location, and distance_m stays measured from the tap'
);

select is(
  (select d.view_count from public.drops d where d.id = current_setting('t.mill')::uuid),
  0::bigint,
  'opening a Here Card does not count a view'
);

reset role;
update public.drops set view_count = 37, confirm_count = 4 where id = current_setting('t.mill')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select view_count = 37 and confirm_count = 4
   from public.here_card(40.0, -100.0, 41.0, -100.0)
   where id = current_setting('t.mill')::uuid),
  'the card includes seen and confirmed counts'
);

select public.hide_drop(current_setting('t.mill')::uuid);
select is(
  (select count(*)::integer from public.here_card(40.0, -100.0, 41.0, -100.0)
   where id = current_setting('t.mill')::uuid),
  0,
  'a drop hidden for me is not on the card'
);
select public.unhide_drop(current_setting('t.mill')::uuid);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select public.block_user('22222222-2222-4222-8222-222222222222');
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (select count(*)::integer from public.here_card(40.0, -100.0) where id = current_setting('t.mill')::uuid),
  0,
  'a block in either direction hides that person''s drop'
);
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select public.unblock_user('22222222-2222-4222-8222-222222222222');

reset role;
update public.drops set status = 'pending_review' where id = current_setting('t.mill')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (select count(*)::integer from public.here_card(40.0, -100.0) where id = current_setting('t.mill')::uuid),
  0,
  'a drop waiting for review is hidden from everyone else'
);
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select ok(
  (select revealed and body = 'Burned down in 1890.' and is_mine
   from public.here_card(40.0, -100.0, 41.0, -100.0)
   where id = current_setting('t.mill')::uuid),
  'the author still sees their own drop in full from anywhere'
);

reset role;
update public.drops set status = 'active' where id = current_setting('t.mill')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform public.create_drop('note', 40.001, -100.0, 'Close bench', 'About 111 m north.');
  perform public.create_drop('note', 40.004, -100.0, 'Far bench', 'About 445 m north.');
  perform public.create_drop('note', -33.8688, 151.2093, 'Ferry wharf', 'Shade on the east side.');
end;
$$;

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select count(*) = 1 from public.here_card(40.0, -100.0) where title = 'Close bench')
  and (select count(*) = 0 from public.here_card(40.0, -100.0) where title = 'Far bench'),
  'the default 300 m radius keeps the summary to this spot'
);
select ok(
  (select not revealed and body is null and title = 'Ferry wharf'
   from public.here_card(-33.8688, 151.2093)
   where title = 'Ferry wharf'),
  'a spot in Sydney uses the same rules, and stays locked without a viewer location'
);

select throws_ok(
  $$ select * from public.here_card(40.0, -100.0, null, null, 10) $$,
  '22023', 'invalid_radius', 'the radius is a short walk, 25 to 1000 m'
);
select throws_ok(
  $$ select * from public.here_card(95.0, -100.0) $$,
  '22023', 'invalid_location', 'the tap has to be a real coordinate'
);
select throws_ok(
  $$ select * from public.here_card(40.0, -100.0, 40.0, null) $$,
  '22023', 'invalid_location', 'viewer coordinates are a pair or omitted'
);

-- Public record, readable from the other side of the world, with its citation.
reset role;
select private.upsert_open_data_items($json$[
  {
    "source_slug": "wikipedia-geo",
    "source_name": "Wikipedia",
    "source_home": "https://en.wikipedia.org/",
    "external_id": "enwiki:paris",
    "category_slug": "historical_fact",
    "title": "A Paris library",
    "summary": "Wikipedia article. Open the source for what is written there.",
    "lat": 48.8566,
    "lng": 2.3522,
    "source_url": "https://en.wikipedia.org/wiki/Example",
    "license": "CC BY-SA 4.0",
    "attribution": "Wikipedia contributors, CC BY-SA 4.0",
    "region_id": "fr-idf",
    "attributes": {
      "source_url": "https://en.wikipedia.org/wiki/Example",
      "citation": "Wikipedia"
    }
  }
]$json$::jsonb);
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select revealed and body is not null and source = 'open_data' and is_mine = false
      and author_handle is null
      and source_name = 'Wikipedia'
      and source_url = 'https://en.wikipedia.org/wiki/Example'
      and attribution = 'Wikipedia contributors, CC BY-SA 4.0'
   from public.here_card(48.8566, 2.3522, 0, 0)
   where title = 'A Paris library'),
  'a public record stays readable, with attribution, wherever the viewer is'
);

-- Incident reports stay on the blurred pin. Bob is nowhere near it.
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.incident', public.create_drop(
    'incident_report', 40.00037, -100.00041, 'Car break-in', 'A car window was smashed and a bag was taken.',
    jsonb_build_object('occurred_at', to_jsonb(now() - interval '2 hours'), 'severity', 'med', 'ongoing', false),
    p_acknowledge_guidelines => true
  ) ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select h.body is null and not h.revealed
      and h.lat = extensions.st_y(d.display_geog::extensions.geometry)
      and h.lng = extensions.st_x(d.display_geog::extensions.geometry)
      and not extensions.st_equals(d.geog::extensions.geometry, d.display_geog::extensions.geometry)
   from public.here_card(40.00037, -100.00041, 0, 0) h
   join public.drops d on d.id = h.id
   where h.id = current_setting('t.incident')::uuid),
  'an incident card uses the blurred pin and hides the body when the viewer is far'
);

-- Place names. The cell is 0.001°. Attribution is not taken from the client.
select is(
  (select count(*)::integer from public.place_label(51.5, -0.12)),
  0,
  'a cell with no lookup yet has no name'
);
select lives_ok(
  $$ select public.remember_place_label(51.5, -0.12, 'Westminster Bridge', 'London') $$,
  'a signed-in person can cache a place name'
);
select is(
  (select name from public.place_label(51.5004, -0.1204)),
  'Westminster Bridge',
  'a nearby tap in the same 0.001 degree cell reuses the cached name'
);
select is(
  (select count(*)::integer from public.place_label(51.5005, -0.12)),
  0,
  'the next cell over is a different cache row'
);
select is(
  (select attribution || '|' || license || '|' || provider from public.place_label(51.5, -0.12)),
  '© OpenStreetMap contributors|ODbL-1.0|nominatim',
  'the database stamps OpenStreetMap attribution'
);
select public.remember_place_label(51.5, -0.12, 'Not the real name', 'nope');
select is(
  (select name from public.place_label(51.5, -0.12)),
  'Westminster Bridge',
  'a fresh cache row is kept for 90 days'
);
reset role;
update public.place_labels
set fetched_at = now() - interval '91 days'
where cell_lat = round(51.5::numeric, 3) and cell_lng = round((-0.12)::numeric, 3);
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select public.remember_place_label(51.5, -0.12, 'Westminster Bridge Road', 'London');
select is(
  (select name from public.place_label(51.5, -0.12)),
  'Westminster Bridge Road',
  'a label older than 90 days can be refreshed'
);
select throws_ok(
  $$ select public.remember_place_label(10, 20, '   ', null) $$,
  '22023', 'invalid_place', 'a blank place name is rejected'
);

set local request.jwt.claims to '{"role": "authenticated"}';
select throws_ok(
  $$ select public.remember_place_label(10, 20, 'Somewhere', null) $$,
  '42501', 'not_authenticated', 'caching a place name needs a signed-in user'
);
select throws_ok(
  $$ select public.claim_nominatim_slot() $$,
  '42501', 'not_authenticated', 'the Nominatim slot needs a signed-in user'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.claim_nominatim_slot(), true, 'the first caller in this window may geocode once');
select is(public.claim_nominatim_slot(), false, 'the next caller within 1.1 seconds has to wait');

select * from finish();
rollback;
