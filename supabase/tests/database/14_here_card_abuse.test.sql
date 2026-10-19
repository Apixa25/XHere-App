-- Here Card abuse: one signed-in user cannot hold the Nominatim slot or fill the place cache.
-- here_card stays granted to authenticated only. The app map is behind sign-in.
begin;
create extension if not exists pgtap with schema extensions;
select plan(36);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test');

select ok(
  not has_function_privilege('anon', 'public.here_card(double precision,double precision,double precision,double precision,integer,integer)', 'execute')
  and not has_function_privilege('anon', 'public.place_label(double precision,double precision)', 'execute')
  and not has_function_privilege('anon', 'public.remember_place_label(double precision,double precision,text,text)', 'execute')
  and not has_function_privilege('anon', 'public.claim_nominatim_slot()', 'execute'),
  'signed-out visitors still cannot open a Here Card or touch Nominatim'
);
select ok(
  has_function_privilege('authenticated', 'public.here_card(double precision,double precision,double precision,double precision,integer,integer)', 'execute')
  and has_function_privilege('authenticated', 'public.place_label(double precision,double precision)', 'execute')
  and has_function_privilege('authenticated', 'public.remember_place_label(double precision,double precision,text,text)', 'execute')
  and has_function_privilege('authenticated', 'public.claim_nominatim_slot()', 'execute')
  and has_function_privilege('service_role', 'public.claim_nominatim_slot()', 'execute')
  and not has_function_privilege('anon', 'public.here_card(double precision,double precision,double precision,double precision,integer,integer)', 'execute'),
  'here_card stays signed-in only, and the place RPCs stay available to a signed-in caller'
);
select ok(
  (select bool_and(c.relrowsecurity)
   from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where (n.nspname, c.relname) in (
     ('private', 'nominatim_user_gate'),
     ('private', 'place_label_quota')
   )),
  'RLS is enabled on the per-user Nominatim pause and the place-label quota'
);
select ok(
  not has_table_privilege('authenticated', 'private.nominatim_user_gate', 'select')
  and not has_table_privilege('authenticated', 'private.place_label_quota', 'select')
  and not has_table_privilege('authenticated', 'private.place_label_quota', 'insert')
  and not has_table_privilege('anon', 'private.nominatim_user_gate', 'select')
  and not has_table_privilege('anon', 'private.place_label_quota', 'insert'),
  'clients cannot read or write the per-user gates'
);
select ok(
  not has_function_privilege('public', 'private.place_label_text_ok(text,integer)', 'execute')
  and not has_function_privilege('anon', 'private.place_label_text_ok(text,integer)', 'execute')
  and not has_function_privilege('authenticated', 'private.place_label_text_ok(text,integer)', 'execute')
  and not has_function_privilege('public', 'private.consume_place_label_write(uuid)', 'execute')
  and not has_function_privilege('anon', 'private.consume_place_label_write(uuid)', 'execute')
  and not has_function_privilege('authenticated', 'private.consume_place_label_write(uuid)', 'execute'),
  'the place-label helpers are not executable by clients'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.claim_nominatim_slot(), true, 'bob takes the first Nominatim slot');

reset role;
update private.nominatim_gate set next_allowed_at = '-infinity'::timestamptz;
update private.nominatim_user_gate
set next_allowed_at = clock_timestamp() + interval '10 seconds'
where user_id = '22222222-2222-4222-8222-222222222222';
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.claim_nominatim_slot(), false, 'bob cannot take another slot for 10 seconds');

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(public.claim_nominatim_slot(), true, 'alice can use the slot while bob is paused');

reset role;
update private.nominatim_user_gate
set next_allowed_at = '-infinity'::timestamptz
where user_id = '11111111-1111-4111-8111-111111111111';
update private.nominatim_gate
set next_allowed_at = clock_timestamp() + interval '1100 milliseconds';
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(public.claim_nominatim_slot(), false, 'the project slot is still one lookup every 1.1 seconds');

reset role;
update private.nominatim_gate set next_allowed_at = '-infinity'::timestamptz;
update private.nominatim_user_gate
set next_allowed_at = clock_timestamp() + interval '10 seconds'
where user_id = '11111111-1111-4111-8111-111111111111';
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(public.claim_nominatim_slot(), false, 'alice cannot chain slots during her own 10 second pause');

reset role;
update private.nominatim_gate set next_allowed_at = '-infinity'::timestamptz;
update private.nominatim_user_gate set next_allowed_at = '-infinity'::timestamptz;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.claim_nominatim_slot(), true, 'bob can take a slot again once his pause is over');
reset role;
update private.nominatim_gate
set next_allowed_at = clock_timestamp() + interval '1100 milliseconds';
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(public.claim_nominatim_slot(), false, 'alice waits when the project slot is busy');
reset role;
update private.nominatim_gate set next_allowed_at = '-infinity'::timestamptz;
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(
  public.claim_nominatim_slot(),
  true,
  'a refused claim does not start the caller''s own pause'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  $$ select public.remember_place_label(95, 0, 'Pole', null) $$,
  '22023', 'invalid_location', 'a place label still needs a real coordinate'
);
select throws_ok(
  $$ select public.remember_place_label(1, 2, '   ', null) $$,
  '22023', 'invalid_place', 'a blank place name is rejected'
);
select throws_ok(
  format('select public.remember_place_label(1, 2, %L, null)', repeat('A', 161)),
  '22023', 'invalid_place', 'a place name longer than 160 characters is rejected'
);
select throws_ok(
  format('select public.remember_place_label(1, 2, %L, null)', 'Bridge' || chr(10)),
  '22023', 'invalid_place', 'a control character in a place name is rejected'
);
select throws_ok(
  $$ select public.remember_place_label(1, 2, '---', null) $$,
  '22023', 'invalid_place', 'a place name with no letter or number is rejected'
);
select throws_ok(
  $$ select public.remember_place_label(1, 2, '<script>alert(1)</script>', null) $$,
  '22023', 'invalid_place', 'markup in a place name is rejected'
);
select throws_ok(
  $$ select public.remember_place_label(1, 2, 'See https://evil.example', null) $$,
  '22023', 'invalid_place', 'a link in a place name is rejected'
);
select throws_ok(
  format('select public.remember_place_label(1, 2, %L, null)', 'Bridge' || chr(8203)),
  '22023', 'invalid_place', 'a zero-width character in a place name is rejected'
);
select throws_ok(
  format('select public.remember_place_label(1, 2, %L, %L)', 'Bridge', repeat('B', 301)),
  '22023', 'invalid_place', 'an address longer than 300 characters is rejected'
);
select throws_ok(
  $$ select public.remember_place_label(1, 2, 'Bridge', 'Town <b>') $$,
  '22023', 'invalid_place', 'markup in an address is rejected'
);

select lives_ok(
  format(
    'select public.remember_place_label(-23.5505, -46.6333, %L, %L)',
    repeat('S', 160),
    $addr$O'Brien Street, São Paulo$addr$
  ),
  'a 160 character name and an accented address are accepted'
);
select is(
  (select name from public.place_label(-23.5514, -46.6333)),
  repeat('S', 160),
  'the same 0.001 degree cell is what a nearby coordinate reads'
);
select is(
  (select count(*)::integer from public.place_label(-23.5515, -46.6333)),
  0,
  'the next 0.001 degree cell is a different cache row'
);
select is(
  (select attribution || '|' || license || '|' || provider from public.place_label(-23.5505, -46.6333)),
  '© OpenStreetMap contributors|ODbL-1.0|nominatim',
  'attribution is still stamped by the database'
);

reset role;
select ok(
  (select cell_lat = round((-23.5505)::numeric, 3)
      and cell_lng = round((-46.6333)::numeric, 3)
      and cell_lat = -23.551
      and cell_lng = -46.633
   from public.place_labels
   where name = repeat('S', 160)),
  'the stored key is the 0.001 degree grid of the given coordinates'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  $$ select public.remember_place_label(-23.5505, -46.6333, 'Not the real name', 'nope') $$,
  'repeating a fresh cell does not count as another write'
);
select is(
  (select name from public.place_label(-23.5505, -46.6333)),
  repeat('S', 160),
  'the first writer still wins inside 90 days'
);
reset role;
select ok(
  (select writes_in_window = 1 and next_allowed_at > clock_timestamp()
   from private.place_label_quota
   where user_id = '22222222-2222-4222-8222-222222222222'),
  'a repeat of a fresh cell does not burn a write, and the pause is running'
);
update private.place_label_quota
set next_allowed_at = clock_timestamp() + interval '10 seconds'
where user_id = '22222222-2222-4222-8222-222222222222';

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  $$ select public.remember_place_label(10.0, 20.0, 'Second Place', 'Town') $$,
  'PT429', 'rate_limited', 'a second new cell inside 10 seconds is refused'
);

reset role;
update private.place_label_quota
set writes_in_window = 30,
    next_allowed_at = '-infinity'::timestamptz,
    window_started_at = clock_timestamp()
where user_id = '22222222-2222-4222-8222-222222222222';
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  $$ select public.remember_place_label(10.0, 20.0, 'Second Place', 'Town') $$,
  'PT429', 'rate_limited', '30 mutating place labels an hour is the cap'
);

reset role;
update private.place_label_quota
set writes_in_window = 0,
    next_allowed_at = '-infinity'::timestamptz,
    window_started_at = clock_timestamp()
where user_id = '22222222-2222-4222-8222-222222222222';
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  $$ select public.remember_place_label(10.0, 20.0, 'Second Place', 'Town') $$,
  'a write is accepted again once the pause and the hourly window are clear'
);
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select lives_ok(
  format('select public.remember_place_label(-23.5505, -46.6333, %L, null)', repeat('Z', 20)),
  'another user can try the same fresh cell'
);
select is(
  (select name from public.place_label(-23.5505, -46.6333)),
  repeat('S', 160),
  'another user cannot replace a fresh place label'
);

select * from finish();
rollback;
