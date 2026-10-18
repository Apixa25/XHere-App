-- M6: outside the reveal radius the body (and tags, attributes, media) is never
-- in the payload; confirm_drop rejects a caller outside range; counters and
-- status follow the category bars. Also the two gaps fixed in
-- 20261018000800_reveal_payload.sql.
-- At latitude 40: 0.0009 deg lat ~ 100 m, 0.0018 deg ~ 200 m.
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ed@example.test'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'fay@example.test'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'gus@example.test'),
  ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'hia@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.hist', public.create_drop(
    'historical_fact', 40.0, -100.0, 'Old mill', 'SECRET_BODY_SENTINEL',
    '{"year": 1890}', array['secret-tag', 'mill'],
    p_media => '[{"storage_path": "11111111-1111-4111-8111-111111111111/secret-photo.jpg", "media_kind": "image", "mime_type": "image/jpeg"}]'
  ) ->> 'id', true);
  perform set_config('t.note', public.create_drop('note', 40.002, -100.0, 'Shady bench', 'Bring a book.') ->> 'id', true);
  perform set_config('t.note2', public.create_drop('note', 40.003, -100.0, 'Free wifi', 'Ask at the counter.') ->> 'id', true);
  perform set_config('t.hazard', public.create_drop(
    'hazard', 40.004, -100.0, 'Ice on the walk', 'Slippery after dark.',
    jsonb_build_object('occurred_at', to_jsonb(now()))
  ) ->> 'id', true);
end;
$$;

-- ~200 m north of a 150 m historical fact.
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select not revealed
      and body is null
      and attributes is null
      and media is null
      and tags is null
      and (x::text) !~ 'SECRET_BODY_SENTINEL'
      and (x::text) !~ 'secret-tag'
      and (x::text) !~ 'secret-photo'
      and (x::text) !~ '1890'
   from public.drop_detail(current_setting('t.hist')::uuid, 40.0018, -100.0) x),
  'outside the reveal radius the body is never in the payload'
);
select ok(
  (select not revealed and body is null and tags is null and (x::text) !~ 'SECRET_BODY_SENTINEL'
   from public.drops_nearby(40.0018, -100.0, 1000) x where id = current_setting('t.hist')::uuid),
  'drops_nearby hides the body outside the radius too'
);

select ok(
  (select revealed
      and body = 'SECRET_BODY_SENTINEL'
      and attributes ->> 'year' = '1890'
      and 'secret-tag' = any (tags)
      and media -> 0 ->> 'storage_path' like '%secret-photo.jpg'
   from public.drop_detail(current_setting('t.hist')::uuid, 40.0005, -100.0)),
  'inside the radius the body, tags, attributes, and media are returned'
);

-- Share teasers never reveal, including for the author.
select ok(
  (select not revealed and body is null and tags is null and (x::text) !~ 'SECRET_BODY_SENTINEL'
   from public.drop_teaser((select short_id from public.drops where id = current_setting('t.hist')::uuid)) x),
  'a signed-in reader gets a teaser with no body'
);
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select ok(
  (select not revealed and body is null and tags is null
   from public.drop_teaser((select short_id from public.drops where id = current_setting('t.hist')::uuid))),
  'the author''s share teaser does not include the body either'
);

-- Confirm only from within max(50 m, reveal radius). History is 150 m.
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.01, -100.0) $$, current_setting('t.hist')),
  '22023', 'too_far', 'confirming from outside the reveal radius is rejected'
);

-- History bar is 3.
select is(
  public.confirm_drop(current_setting('t.hist')::uuid, 'still_true', 40.0005, -100.0) ->> 'status',
  'active',
  'one of three history confirmations stays active'
);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.0, -100.0) $$, current_setting('t.hist')),
  'the second history confirmation is accepted'
);
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select is(
  public.confirm_drop(current_setting('t.hist')::uuid, 'still_true', 40.0, -100.0) ->> 'status',
  'confirmed',
  'the third history confirmation sets the confirmed badge'
);

-- Notes: 7 days unless confirmed twice, then permanent.
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (r ->> 'status') = 'active' and (r ->> 'expires_at')::timestamptz = now() + interval '7 days',
  'one confirmation leaves a note active and expiring in 7 days'
)
from (select public.confirm_drop(current_setting('t.note')::uuid, 'still_true', 40.002, -100.0) as r) s;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(
  public.confirm_drop(current_setting('t.note')::uuid, 'still_true', 40.002, -100.0) - array['drop_id', 'confirm_count', 'deny_count', 'distance_m', 'verdict', 'is_confirmed'],
  '{"status": "confirmed", "expires_at": null}'::jsonb,
  'two confirmations make a note permanent and confirmed'
);

-- Three denials dispute a note, and it leaves the map.
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'not_true', 40.003, -100.0) $$, current_setting('t.note2')),
  'the first denial is stored'
);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'not_true', 40.003, -100.0) $$, current_setting('t.note2')),
  'the second denial is stored'
);
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select is(
  public.confirm_drop(current_setting('t.note2')::uuid, 'not_true', 40.003, -100.0) ->> 'status',
  'disputed',
  'three denials dispute a note'
);

-- Hazard: a confirmation extends a short expiry by 24 h, up to the 72 h cap. A denial does not.
reset role;
update public.drops set expires_at = now() + interval '1 hour' where id = current_setting('t.hazard')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "role": "authenticated"}';
select is(
  (public.confirm_drop(current_setting('t.hazard')::uuid, 'still_true', 40.004, -100.0) ->> 'expires_at')::timestamptz,
  now() + interval '24 hours',
  'confirming a hazard extends it by 24 hours'
);
reset role;
update public.drops set expires_at = now() + interval '1 hour' where id = current_setting('t.hazard')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "role": "authenticated"}';
select is(
  (public.confirm_drop(current_setting('t.hazard')::uuid, 'not_true', 40.004, -100.0) ->> 'expires_at')::timestamptz,
  now() + interval '1 hour',
  'denying a hazard does not extend it'
);

-- Changing your mind does not take a second hourly slot. A different drop does.
reset role;
update public.tier_limits set confirmations_per_hour = 1 where tier = 'new';
set local role authenticated;
set local request.jwt.claims to '{"sub": "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.0, -100.0) $$, current_setting('t.hist')),
  'the first confirmation in the hour is allowed'
);
select lives_ok(
  format($$ select public.confirm_drop(%L, 'not_true', 40.0, -100.0) $$, current_setting('t.hist')),
  'updating that same confirmation does not spend another slot'
);
select throws_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.004, -100.0) $$, current_setting('t.hazard')),
  'PT429', 'rate_limited', 'a second drop in the same hour is still rate limited'
);

select * from finish();
rollback;
