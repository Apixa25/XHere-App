-- Walks and the starter quest. The catalog is region data. Progress is per person.
-- Jamestown is the pilot walk. The quest has no region, so every place offers it.
begin;
create extension if not exists pgtap with schema extensions;
select plan(31);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', handle = 'bob' where id = '22222222-2222-4222-8222-222222222222';
update public.profiles set tier = 'trusted', handle = 'carol' where id = '33333333-3333-4333-8333-333333333333';

select ok(
  (select region_id is null from public.quests where id = 'a0000000-0000-4000-8000-000000000004'),
  'the starter quest is global'
);
select is(
  (select array_agg(kind order by position) from public.quest_steps
   where quest_id = 'a0000000-0000-4000-8000-000000000004'),
  array['visit_places', 'confirm_drop', 'create_drop']::text[],
  'the starter quest is visit, confirm, and make a drop'
);

select is(
  (select count(*)::integer from public.walk_stops s
   join public.walks w on w.id = s.walk_id
   where w.region_id = 'us-ny-jamestown' and w.slug = 'downtown-history'),
  8,
  'the pilot walk has eight stops'
);
select is(
  (select array_agg(d.title order by s.position)
   from public.walk_stops s
   join public.walks w on w.id = s.walk_id
   join public.open_data_items i
     on i.source_slug = s.source_slug and i.external_id = s.external_id
   join public.drops d on d.id = i.drop_id
   where w.slug = 'downtown-history'),
  array[
    'Erie Railroad Station',
    'National Comedy Center',
    'The Wellman Building',
    'Lucille Ball Desi Arnaz Museum & Center for Comedy',
    'Reg Lenna Center for the Arts',
    'Jamestown Downtown Historic District',
    'Robert H. Jackson Center',
    'First Presbyterian Church, Jamestown, New York'
  ]::text[],
  'those stops are the public records, in walk order'
);
select ok(
  (select distance_m between 1000 and 1100 and duration_min = greatest(1, ceil(distance_m / 75.0)::integer)
   from public.walks where slug = 'downtown-history'),
  'length is the straight line between the published points, and time is that length at 75 m/min'
);

select ok(
  (select bool_and(p.prosecdef) and count(*) = 5
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public'
     and p.proname in ('walks_for_point', 'start_walk', 'check_in', 'my_quests', 'dismiss_quest')
     and exists (select 1 from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%')),
  'the five RPCs are security definer and pin search_path'
);

set local role anon;
set local request.jwt.claims to '';
select is(
  (select jsonb_array_length(public.walks_for_point(42.096, -79.24))),
  1,
  'a signed-out visitor sees the walk when the point is in its region'
);
select is(
  (select public.walks_for_point(42.096, -79.24) -> 0 ->> 'started'),
  'false',
  'seeing a walk does not start it'
);
select is(
  (select jsonb_array_length(public.walks_for_point(42.8864, -78.8784))),
  0,
  'a point outside the region does not return the walk'
);
select ok(
  not has_function_privilege('anon', 'public.check_in(double precision, double precision, uuid, jsonb)', 'execute')
  and not has_function_privilege('anon', 'public.start_walk(uuid)', 'execute')
  and has_function_privilege('anon', 'public.walks_for_point(double precision, double precision)', 'execute'),
  'signed-out visitors can read walks and cannot record progress'
);

reset role;
do $$
declare
  v_walk uuid;
  v_lat double precision;
  v_lng double precision;
begin
  select w.id into v_walk from public.walks w where w.slug = 'downtown-history';
  select extensions.st_y(d.display_geog::extensions.geometry),
         extensions.st_x(d.display_geog::extensions.geometry)
    into v_lat, v_lng
  from public.walk_stops s
  join public.open_data_items i on i.source_slug = s.source_slug and i.external_id = s.external_id
  join public.drops d on d.id = i.drop_id
  where s.walk_id = v_walk and s.position = 1;
  perform set_config('t.walk', v_walk::text, true);
  perform set_config('t.lat', v_lat::text, true);
  perform set_config('t.lng', v_lng::text, true);
end;
$$;

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(
  (public.check_in(current_setting('t.lat')::float8, current_setting('t.lng')::float8, current_setting('t.walk')::uuid) ->> 'walk_just_completed')::boolean,
  false,
  'standing on a stop before the walk is started does not finish it'
);
select is(
  (select count(*)::integer from public.walk_stop_reaches where walk_id = current_setting('t.walk')::uuid),
  0,
  'and it does not check the stop off'
);

select is(
  (public.start_walk(current_setting('t.walk')::uuid) ->> 'started')::boolean,
  true,
  'start_walk marks the walk as started'
);
-- The station and the comedy center are 63 m apart, inside the 150 m reveal.
-- The Wellman Building is about 227 m from the station, so it stays unchecked.
select public.check_in(
  current_setting('t.lat')::float8,
  current_setting('t.lng')::float8,
  current_setting('t.walk')::uuid
) ->> 'visit_count';
select is(
  (select array_agg(position order by position) from public.walk_stop_reaches
   where user_id = '11111111-1111-4111-8111-111111111111' and walk_id = current_setting('t.walk')::uuid),
  array[1, 2]::integer[],
  'standing at the station checks off the station and the next stop inside the reveal radius'
);
select is(
  (public.check_in(current_setting('t.lat')::float8, current_setting('t.lng')::float8, current_setting('t.walk')::uuid) ->> 'visits_added')::integer,
  0,
  'the same place does not count as a new visit'
);
select is(
  (public.check_in(current_setting('t.lat')::float8, current_setting('t.lng')::float8, current_setting('t.walk')::uuid) ->> 'walk_just_completed')::boolean,
  false,
  'two stops are not the whole walk'
);

select is(
  (select public.walks_for_point(42.8864, -78.8784) -> 0 ->> 'slug'),
  'downtown-history',
  'a started walk stays on the map a block outside its region'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (select count(*)::integer from public.walk_stop_reaches),
  0,
  'someone else cannot see those check-offs'
);
select is(
  (select jsonb_array_length(public.walks_for_point(42.8864, -78.8784))),
  0,
  'and the unfinished walk does not follow them outside the region'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
declare
  r record;
  v_result jsonb;
  v_just boolean := false;
begin
  -- Coordinates are the published points. Reading public.drops here would
  -- see nothing: authenticated has no select on that table.
  for r in
    select * from (values
      (1, 42.094509::float8, -79.244603::float8),
      (2, 42.094722::float8, -79.243889::float8),
      (3, 42.095922::float8, -79.242619::float8),
      (4, 42.09651::float8, -79.24161::float8),
      (5, 42.096689::float8, -79.239267::float8),
      (6, 42.096357::float8, -79.237379::float8),
      (7, 42.0983::float8, -79.23764::float8),
      (8, 42.098818::float8, -79.238704::float8)
    ) as v(position, lat, lng)
    order by position
  loop
    v_result := public.check_in(r.lat, r.lng, current_setting('t.walk')::uuid);
    if (v_result ->> 'walk_just_completed')::boolean then
      v_just := true;
    end if;
  end loop;
  perform set_config('t.just', v_just::text, true);
end;
$$;
select ok(current_setting('t.just')::boolean, 'walking every stop completes the walk');
select is(
  (public.check_in(current_setting('t.lat')::float8, current_setting('t.lng')::float8, current_setting('t.walk')::uuid) ->> 'walk_just_completed')::boolean,
  false,
  'finishing it again does not celebrate a second time'
);
select is(
  (select jsonb_array_length(public.walks_for_point(42.8864, -78.8784))),
  0,
  'a finished walk does not follow you outside its region'
);

reset role;
select set_config('t.anydrop', (select id::text from public.drops limit 1), true);
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  format(
    $$ insert into public.place_visits (user_id, drop_id) values ('11111111-1111-4111-8111-111111111111', %L) $$,
    current_setting('t.anydrop')
  ),
  '42501',
  null,
  'a client cannot insert a visit'
);
select throws_ok(
  $$ insert into public.walk_stop_reaches (user_id, walk_id, position)
     values ('11111111-1111-4111-8111-111111111111', current_setting('t.walk')::uuid, 1) $$,
  '42501',
  null,
  'a client cannot insert a check-off'
);

-- Quest counts. Carol has not stood downtown, so her starter quest starts at zero.
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(public.create_drop('note', 41.10, -100.0, 'Mine', 'My own note.') ->> 'category', 'note', 'carol can post');
select is(
  (public.check_in(41.10, -100.0) ->> 'visits_added')::integer,
  0,
  'your own drop is not a place you visited'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select set_config('t.hidden', (public.create_drop('historical_fact', 41.20, -100.0, 'Hidden mill', 'A mill.') ->> 'id'), true);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
do $$
begin
  perform public.hide_drop(current_setting('t.hidden')::uuid);
end;
$$;
select is(
  (public.check_in(41.20, -100.0) ->> 'visits_added')::integer,
  0,
  'a hidden drop is not a visit'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select set_config('t.stone', (public.create_drop('historical_fact', 41.30, -100.0, 'Corner stone', 'A stone.') ->> 'id'), true);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select public.confirm_drop(current_setting('t.stone')::uuid, 'not_true', 41.30, -100.0);
select is(
  (select (step ->> 'progress')::integer
   from jsonb_array_elements(public.my_quests() -> 0 -> 'steps') step
   where step ->> 'kind' = 'confirm_drop'),
  0,
  'not anymore does not count as a confirm'
);
select public.confirm_drop(current_setting('t.stone')::uuid, 'still_true', 41.30, -100.0);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select public.create_drop('note', 41.00, -100.0, 'Bench one', 'A bench.');
select public.create_drop('note', 41.01, -100.0, 'Bench two', 'A bench.');
select public.create_drop('note', 41.02, -100.0, 'Bench three', 'A bench.');
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select public.check_in(41.00, -100.0);
select public.check_in(41.01, -100.0);
select public.check_in(41.02, -100.0);
select public.create_drop('note', 41.40, -100.0, 'First drop', 'Carol''s drop.');
select is(
  (public.my_quests() -> 0 ->> 'completed')::boolean,
  true,
  'three visits, one confirm, and one drop finish the starter quest'
);
do $$
begin
  perform public.dismiss_quest('a0000000-0000-4000-8000-000000000004');
end;
$$;
select is(
  (select jsonb_array_length(public.my_quests())),
  0,
  'dismissing the quest hides it'
);
reset role;
select is(
  (select count(*)::integer from public.place_visits where user_id = '33333333-3333-4333-8333-333333333333'),
  3,
  'dismissing does not erase the visits'
);

select * from finish();
rollback;
