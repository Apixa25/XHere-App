-- "Still true?" confirmations: one per user, distance-checked, and status,
-- counters, and lifetime follow the category's rules.
begin;
create extension if not exists pgtap with schema extensions;
select plan(29);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ed@example.test'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'fay@example.test'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'gus@example.test');
update public.profiles set tier = 'trusted' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'regular' where id = '33333333-3333-4333-8333-333333333333';

set local role authenticated;

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.hist', public.create_drop('historical_fact', 40.0, -100.0, 'Old mill') ->> 'id', true);
  perform set_config('t.note', public.create_drop('note', 40.001, -100.0, 'Shady bench') ->> 'id', true);
  perform set_config('t.note2', public.create_drop('note', 40.002, -100.0, 'Free wifi') ->> 'id', true);
  perform set_config('t.truck', public.create_drop('food_truck', 40.003, -100.0, 'Taco truck') ->> 'id', true);
end;
$$;

-- One confirmation per user; changing your mind updates it
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (public.confirm_drop(current_setting('t.hist')::uuid, 'still_true', 40.0005, -100.0) ->> 'confirm_count')::integer,
  1,
  'a nearby confirmation counts'
);
select is(
  public.confirm_drop(current_setting('t.hist')::uuid, 'not_true', 40.0005, -100.0, 'Gone') - array['drop_id', 'expires_at', 'distance_m', 'status', 'is_confirmed', 'verdict', 'counts_for_credit', 'reasons'],
  '{"confirm_count": 0, "deny_count": 1}'::jsonb,
  'changing the verdict moves the count instead of adding one'
);
select is(
  (public.confirm_drop(current_setting('t.hist')::uuid, 'still_true', 40.0005, -100.0) ->> 'confirm_count')::integer,
  1,
  'confirming again is idempotent'
);
select is((select count(*)::integer from public.confirmations where drop_id = current_setting('t.hist')::uuid), 1, 'there is one row per user per drop');
select ok(
  (select distance_m between 50 and 60 and note is null from public.confirmations where drop_id = current_setting('t.hist')::uuid),
  'only the distance is stored, rounded to meters'
);
select is(
  (select count(*)::integer from information_schema.columns
   where table_schema = 'public' and table_name = 'confirmations' and udt_name in ('geography', 'geometry')),
  0,
  'confirmations never store the caller''s position'
);
select throws_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.01, -100.0) $$, current_setting('t.hist')),
  '22023', 'too_far', 'confirming from outside the reveal radius is rejected'
);
select throws_ok($$ select public.confirm_drop(gen_random_uuid(), 'still_true', 40.0, -100.0) $$, 'P0002', 'drop_not_found', 'unknown drops are not found');

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.0, -100.0) $$, current_setting('t.hist')),
  '42501', 'cannot_confirm_own_drop', 'authors cannot confirm their own drop'
);

-- The confirmation bar (3 for history)
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(public.confirm_drop(current_setting('t.hist')::uuid, 'still_true', 40.0, -100.0) ->> 'status', 'active', 'two of three is still active');
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select is(public.confirm_drop(current_setting('t.hist')::uuid, 'still_true', 40.0, -100.0) ->> 'status', 'confirmed', 'the third confirmation confirms it');
select ok(
  (select is_confirmed and last_confirmed_at = now() from public.drops_nearby(40.0, -100.0, 100) where id = current_setting('t.hist')::uuid),
  'confirmed drops show the badge and freshness'
);

-- Retracting a confirmation drops it back under the bar
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ delete from public.confirmations where drop_id = %L $$, current_setting('t.hist')),
  'users can retract their own confirmation'
);
reset role;
select is(
  (select status::text || '/' || confirm_count from public.drops where id = current_setting('t.hist')::uuid),
  'active/2',
  'counts and status are recomputed after a retraction'
);
set local role authenticated;

-- Notes: 7 days unless confirmed twice, then permanent
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (public.confirm_drop(current_setting('t.note')::uuid, 'still_true', 40.001, -100.0) ->> 'expires_at')::timestamptz = now() + interval '7 days',
  'an unconfirmed note still expires in 7 days'
);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(
  public.confirm_drop(current_setting('t.note')::uuid, 'still_true', 40.001, -100.0) - array['drop_id', 'confirm_count', 'deny_count', 'distance_m', 'verdict', 'is_confirmed', 'counts_for_credit', 'reasons'],
  '{"status": "confirmed", "expires_at": null}'::jsonb,
  'two confirmations make a note permanent'
);

-- Denials hide a drop (denials_to_hide is 3 for notes)
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.confirm_drop(current_setting('t.note2')::uuid, 'not_true', 40.002, -100.0) ->> 'status', 'active', 'one denial is not enough');
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(public.confirm_drop(current_setting('t.note2')::uuid, 'not_true', 40.002, -100.0) ->> 'status', 'active', 'two denials are not enough');
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select is(public.confirm_drop(current_setting('t.note2')::uuid, 'not_true', 40.002, -100.0) ->> 'status', 'disputed', 'three denials dispute it');
set local request.jwt.claims to '{"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "role": "authenticated"}';
select ok(not exists (select 1 from public.drops_nearby(40.002, -100.0, 100) where id = current_setting('t.note2')::uuid), 'disputed drops are hidden');
select throws_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.002, -100.0) $$, current_setting('t.note2')),
  'P0002', 'drop_not_found', 'hidden drops cannot be confirmed'
);

-- Food trucks: each "still here" renews them to at least 6 h from now (never shortening)
reset role;
update public.drops set expires_at = now() + interval '1 hour' where id = current_setting('t.truck')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (public.confirm_drop(current_setting('t.truck')::uuid, 'still_true', 40.003, -100.0) ->> 'expires_at')::timestamptz,
  now() + interval '6 hours',
  'a confirmation renews a food truck for 6 h'
);
reset role;
update public.drops set expires_at = now() + interval '23 hours' where id = current_setting('t.truck')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(
  (public.confirm_drop(current_setting('t.truck')::uuid, 'still_true', 40.003, -100.0) ->> 'expires_at')::timestamptz,
  now() + interval '23 hours',
  'a renewal never shortens a later expiry'
);
reset role;
update public.drops set expires_at = now() + interval '1 hour' where id = current_setting('t.truck')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select is(
  (public.confirm_drop(current_setting('t.truck')::uuid, 'not_true', 40.003, -100.0) ->> 'expires_at')::timestamptz,
  now() + interval '1 hour',
  'a denial does not renew it'
);

-- Pending and blocked drops cannot be confirmed
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
do $$
begin
  perform set_config('t.pending', public.create_drop('note', 40.004, -100.0, 'New user note') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select throws_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.004, -100.0) $$, current_setting('t.pending')),
  'P0002', 'drop_not_found', 'pending drops cannot be confirmed'
);
set local request.jwt.claims to '{"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "role": "authenticated"}';
select lives_ok($$ select public.block_user('11111111-1111-4111-8111-111111111111') $$, 'fay blocks alice');
select throws_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.0, -100.0) $$, current_setting('t.hist')),
  'P0002', 'drop_not_found', 'drops from blocked users cannot be confirmed'
);

-- Rate limit on confirmations
reset role;
update public.tier_limits set confirmations_per_hour = 1 where tier = 'new';
set local role authenticated;
set local request.jwt.claims to '{"sub": "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "role": "authenticated"}';
select lives_ok(format($$ select public.confirm_drop(%L, 'still_true', 40.0, -100.0) $$, current_setting('t.hist')), 'first confirmation in the hour');
select throws_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.001, -100.0) $$, current_setting('t.note')),
  'PT429', 'rate_limited', 'confirmations are rate limited per tier'
);

select * from finish();
rollback;
