-- Claimed places and time capsules. Locked story text stays out of the
-- card until the server releases it. Claim writes are the owner's, and
-- review or revoke is an admin.
begin;
create extension if not exists pgtap with schema extensions;
select plan(36);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'ada@mill.example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'cara@gmail.com'),
  ('44444444-4444-4444-8444-444444444444', 'mod@example.test'),
  ('55555555-5555-4555-8555-555555555555', 'admin@example.test'),
  ('66666666-6666-4666-8666-666666666666', 'coe@example.test');
update public.profiles set tier = 'trusted', handle = 'ada' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', handle = 'bob' where id = '22222222-2222-4222-8222-222222222222';
update public.profiles set tier = 'trusted', handle = 'cara' where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set tier = 'trusted', handle = 'mod', role = 'moderator' where id = '44444444-4444-4444-8444-444444444444';
update public.profiles set tier = 'trusted', handle = 'admin', role = 'admin' where id = '55555555-5555-4555-8555-555555555555';
update public.profiles set tier = 'trusted', handle = 'coe' where id = '66666666-6666-4666-8666-666666666666';

insert into public.drops (
  category_id, category_version, source, title, geog, display_geog, reveal_radius_m
)
select c.id, c.version, 'open_data', 'City museum',
       private.make_point(42.0, -100.0), private.make_point(42.0, -100.0), c.default_reveal_m
from public.categories c
where c.slug = 'historical_fact';

select ok(
  (select paid = false and active from public.place_plans where slug = 'free'),
  'the only plan is free and billing is not turned on'
);
select ok(
  not has_table_privilege('authenticated', 'private.capsule_secrets', 'select')
  and not has_table_privilege('anon', 'private.capsule_secrets', 'select')
  and not has_function_privilege('anon', 'public.create_capsule(text,text,double precision,double precision,text,timestamptz,boolean,jsonb,jsonb)', 'execute')
  and not has_function_privilege('anon', 'public.claim_review_queue(integer)', 'execute'),
  'locked stories and claim review are not granted to visitors'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select public.create_capsule(
  'Sealed visit', 'the sealed visit story', 40.0, -100.0, 'visit',
  p_oral_history => true
);
select is(
  (select body from public.drops where title = 'Sealed visit'),
  null,
  'a locked capsule stores no story on the drop'
);
select is(
  (select capsule_locked and body is null and revealed = false
   from public.drops_nearby(40.0, -100.0, 200) where title = 'Sealed visit'),
  true,
  'nearby hides the story even for the author standing on it'
);
select is(
  (select body is null and capsule_locked
   from public.drop_detail((select id from public.drops where title = 'Sealed visit'), 40.0, -100.0)),
  true,
  'the author is not the next person who opens a visit capsule'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (select body is null and capsule_locked
   from public.drop_detail((select id from public.drops where title = 'Sealed visit'), 40.02, -100.0)),
  true,
  'standing far away does not open a visit capsule'
);
select is(
  (select body is null and capsule_locked
   from public.drop_detail(
     (select id from public.drops where title = 'Sealed visit'),
     40.0, -100.0,
     '{"mock": true}'::jsonb
   )),
  true,
  'a mocked visit does not open the capsule'
);
select ok(
  (select 'mock_location' = any (reasons)
   from public.location_integrity_flags
   where user_id = '22222222-2222-4222-8222-222222222222' and action = 'open_capsule'),
  'a mocked visit is flagged and the story stays sealed'
);
select is(
  (select body from public.drop_detail((select id from public.drops where title = 'Sealed visit'), 40.0, -100.0)),
  'the sealed visit story',
  'the next person within 30 m with a trusted fix can read it'
);
select is(
  (select count(*)::integer from private.capsule_secrets s
   join public.drops d on d.id = s.drop_id where d.title = 'Sealed visit'),
  0,
  'the private copy is removed once the capsule opens'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(
  (select capsule_opens from public.my_impact_notices() where title = 'Sealed visit'),
  1,
  'the author inbox says the capsule was opened'
);
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select public.drop_detail((select id from public.drops where title = 'Sealed visit'), 40.0, -100.0);
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(
  (select capsule_opens from public.my_impact_notices() where title = 'Sealed visit'),
  1,
  'opening it again does not notify the author twice'
);

select public.create_capsule(
  'Sealed later', 'not yet', 41.0, -100.0, 'date', now() + interval '7 days'
);
select is(
  (select capsule_locked and body is null
   from public.drop_detail((select id from public.drops where title = 'Sealed later'), 41.0, -100.0)),
  true,
  'a future date stays sealed on the spot'
);
select public.create_capsule(
  'Already due', 'the dated story', 41.0, -100.0, 'date', now() - interval '1 minute'
);
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (select body is null and capsule_locked = false
   from public.drop_detail((select id from public.drops where title = 'Already due'), 41.02, -100.0)),
  true,
  'a due capsule opens, and someone outside the reveal radius still cannot read it'
);
select is(
  (select body from public.drops_nearby(41.0, -100.0, 200) where title = 'Already due'),
  'the dated story',
  'someone inside the reveal radius can read a capsule after its date'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  $$ select public.start_place_claim(
       (select id from public.drops where title = 'City museum'),
       'https://www.mill.example.test', 42.001, -100.0) $$,
  '22023', 'too_far', 'a claim has to be made within 50 m'
);
select throws_ok(
  $$ select public.start_place_claim(
       (select id from public.drops where title = 'City museum'),
       'https://www.mill.example.test', 42.0, -100.0, '{"mock": true}'::jsonb) $$,
  '22023', 'location_not_trusted', 'a mocked fix cannot claim a place'
);
select is(
  (select count(*)::integer from public.place_claims),
  0,
  'a failed on-site check does not leave a claim'
);

select public.create_user_place('Mill', 40.0, -100.0, 'https://www.mill.example.test');
select public.start_place_claim(
  (select id from public.drops where title = 'Mill'),
  'https://www.mill.example.test', 40.0, -100.0
);
select ok(
  (select (result ->> 'mailed')::boolean = false
      and result ->> 'status' = 'pending_proof'
      and not (result ? 'code')
   from (
     select public.request_claim_postcard(
       (select id from public.place_claims where place_id = (select id from public.places where name = 'Mill'))
     ) as result
   ) s),
  'postcard verification is recorded and not mailed'
);
select is(
  (select status from public.place_claims where place_id = (select id from public.places where name = 'Mill')),
  'pending_proof',
  'asking for a postcard does not activate the claim'
);

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select public.create_user_place('Gmail shop', 39.0, -100.0, 'https://gmail.com');
select public.start_place_claim(
  (select id from public.drops where title = 'Gmail shop'),
  'https://gmail.com', 39.0, -100.0
);
select throws_ok(
  $$ select public.prove_claim_email(
       (select id from public.place_claims
        where place_id = (select id from public.places where name = 'Gmail shop'))
     ) $$,
  '22023', 'email_domain_mismatch', 'a free-mail domain does not prove a place'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is(
  (public.prove_claim_email(
     (select id from public.place_claims where place_id = (select id from public.places where name = 'Mill'))
   ) ->> 'status'),
  'active',
  'the signed-in email domain activates the claim'
);
select is(
  (select official and place_name = 'Mill'
   from public.drops_nearby(40.0, -100.0, 50) where title = 'Mill'),
  true,
  'a claimed place shows the Official badge'
);
select throws_ok(
  $$ select public.start_place_claim(
       (select id from public.drops where title = 'Mill'),
       'https://www.mill.example.test', 40.0, -100.0) $$,
  '22023', 'place_already_claimed', 'a place has one open claim'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select public.create_drop('note', 40.0, -100.0, 'Neighbor note', 'The door sticks.');
select throws_ok(
  $$ select public.post_place_announcement(
       (select id from public.places where name = 'Mill'),
       'Tonight', 'Live music', now() + interval '2 days', 40.0, -100.0) $$,
  '42501', 'not_owner', 'only the owner team can post a special'
);
select throws_ok(
  $$ select public.reply_as_owner(
       (select id from public.drops where title = 'Neighbor note'),
       'We will look at the door.') $$,
  '42501', 'not_owner', 'a neighbor cannot reply as the owner'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select public.set_place_hours(
  (select id from public.places where name = 'Mill'),
  '[{"day":1,"open":"09:00","close":"17:00"}]'::jsonb,
  'Open Mondays.'
);
select public.post_place_announcement(
  (select id from public.places where name = 'Mill'),
  'Tonight', 'Live music', now() + interval '2 days', 40.0, -100.0
);
select ok(
  (select source = 'official' and body = 'Live music'
   from public.drops_nearby(40.0, -100.0, 50) where title = 'Tonight'),
  'an official special is readable while the claim is active'
);
select is(
  (select (official_at ->> 'badge') = 'Official' and official_at ->> 'plan_slug' = 'free'
   from (select public.official_at(40.0, -100.0) as official_at) s),
  true,
  'the Here Card can load the official hours for this spot'
);
select public.reply_as_owner(
  (select id from public.drops where title = 'Neighbor note'),
  'We will look at the door.'
);
select is(
  (select public.owner_replies_for_drop((select id from public.drops where title = 'Neighbor note')) -> 0 ->> 'body'),
  'We will look at the door.',
  'the owner reply is on the drop'
);

select public.invite_place_coowner((select id from public.places where name = 'Mill'), 'coe');
set local request.jwt.claims to '{"sub": "66666666-6666-4666-8666-666666666666", "role": "authenticated"}';
select public.accept_place_invite((select id from public.places where name = 'Mill'));
select public.reply_as_owner(
  (select id from public.drops where title = 'Neighbor note'),
  'A co-owner can answer too.'
);
select is(
  (select count(*)::integer from public.place_teams where status = 'active' and place_id = (select id from public.places where name = 'Mill')),
  1,
  'co-owners join the one active team'
);

set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select throws_ok(
  $$ select public.claim_review_queue(10) $$,
  '42501', 'not_admin', 'a moderator is not the claim admin'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select public.start_place_claim(
  (select id from public.drops where title = 'City museum'),
  'https://museum.example.test', 42.0, -100.0
);
select public.submit_claim_for_review(
  (select cl.id from public.place_claims cl
   join public.places pl on pl.id = cl.place_id where pl.name = 'City museum'),
  'I run the desk.'
);
select throws_ok(
  $$ select public.review_place_claim(
       (select cl.id from public.place_claims cl
        join public.places pl on pl.id = cl.place_id where pl.name = 'City museum'),
       true, null) $$,
  '42501', 'not_admin', 'the claimant cannot approve their own review'
);

set local request.jwt.claims to '{"sub": "55555555-5555-4555-8555-555555555555", "role": "authenticated"}';
select ok(
  (select public.claim_review_queue(20) @> '[{"kind":"claim","place_name":"City museum"}]'::jsonb),
  'the admin queue lists the place waiting for review'
);
select public.review_place_claim(
  (select cl.id from public.place_claims cl
   join public.places pl on pl.id = cl.place_id where pl.name = 'City museum'),
  true, 'desk confirmed'
);
select ok(
  (select source = 'open_data' from public.drops where title = 'City museum')
  and (select official from public.drops_nearby(42.0, -100.0, 50) where title = 'City museum'),
  'an open-data place stays a public record and gains the badge'
);

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select public.dispute_place_claim(
  (select id from public.places where name = 'Mill'),
  'Wrong owner', 'The mill is the family next door.'
);
set local request.jwt.claims to '{"sub": "55555555-5555-4555-8555-555555555555", "role": "authenticated"}';
select public.resolve_claim_dispute(
  (select id from public.claim_disputes where reason = 'Wrong owner'),
  true
);
select is(
  (select status from public.place_claims cl
   join public.places pl on pl.id = cl.place_id
   where pl.name = 'Mill' and cl.status = 'active'),
  'active',
  'closing a dispute does not revoke the claim'
);
select public.revoke_place_claim(
  (select cl.id from public.place_claims cl
   join public.places pl on pl.id = cl.place_id
   where pl.name = 'City museum' and cl.status = 'active'),
  'The desk asked us to undo it.'
);
select is(
  (select public.official_at(42.0, -100.0)),
  null,
  'revoke takes the Official badge off the place'
);

select * from finish();
rollback;
