-- M11 reputation: points, tiers, badges, the spoof hook, and trust-weighted order.
-- PLAN §3 and §7. Research recommendation #7.
begin;
create extension if not exists pgtap with schema extensions;
select plan(58);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'mo@example.test'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ed@example.test'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'fay@example.test'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'gus@example.test'),
  ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'hal@example.test');
update public.profiles set tier = 'trusted' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'regular' where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set tier = 'trusted', role = 'moderator' where id = '44444444-4444-4444-8444-444444444444';

select is(
  (select row(
      confirm_first, confirm_second, confirm_third,
      view_first, view_next, view_cap,
      deny_first, deny_second, moderation_penalty,
      walk_points, quest_points,
      regular_at, trusted_at, guide_at, trusted_extra_reports,
      bonus_guide_m, bonus_trusted_m, bonus_regular_m
    )::text
   from public.reputation_rules where id = 1),
  row(8, 3, 1, 2, 1, 3, 6, 2, 15, 12, 10, 24, 80, 200, 2, 80, 40, 15)::text,
  'the public rules match the app'
);
select ok(
  (select count(*) = 5 from public.badges
   where slug in ('first_drop', 'confirmed_10', 'walk_done', 'quest_done', 'seen_around')
     and scope = 'global' and active),
  'five reputation badges sit on the existing shelf'
);

set local role anon;
select throws_ok(
  $$ select public.my_reputation() $$,
  '42501', 'permission denied for function my_reputation',
  'signed-out visitors have no reputation'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.my_reputation() ->> 'tier_label', 'New', 'a new account is labeled New');
select is(public.my_reputation() ->> 'next_label', 'Local', 'regular is shown as Local');
select is((public.my_reputation() ->> 'next_at')::integer, 24, 'Local starts at 24 points');

-- Alice posts four stories. Bob confirms them one by one.
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.a1', public.create_drop('story', 40.0, -100.0, 'A1') ->> 'id', true);
  perform set_config('t.a2', public.create_drop('story', 40.001, -100.0, 'A2') ->> 'id', true);
  perform set_config('t.a3', public.create_drop('story', 40.002, -100.0, 'A3') ->> 'id', true);
  perform set_config('t.a4', public.create_drop('story', 40.003, -100.0, 'A4') ->> 'id', true);
end;
$$;

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.0, -100.0) $$, current_setting('t.a1')),
  'bob confirms the first story'
);
reset role;
select is(
  (select points from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  8, 'the first still-true from a person is 8'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.001, -100.0) $$, current_setting('t.a2')),
  'bob confirms the second story'
);
reset role;
select is(
  (select points from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  11, 'the second still-true from the same person is 3'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.002, -100.0) $$, current_setting('t.a3')),
  'bob confirms the third story'
);
reset role;
select is(
  (select points from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  12, 'the third still-true from the same person is 1'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.003, -100.0) $$, current_setting('t.a4')),
  'bob confirms the fourth story'
);
reset role;
select is(
  (select points from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  12, 'a fourth still-true from the same person scores nothing'
);

-- The other direction of the same pair does not also score.
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
do $$
begin
  perform set_config('t.b1', public.create_drop('story', 40.01, -100.0, 'Bob story') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.01, -100.0) $$, current_setting('t.b1')),
  'alice confirms bob, which is the later direction'
);
reset role;
select is(
  (select points from public.profiles where id = '22222222-2222-4222-8222-222222222222'),
  0, 'a confirm ring scores only the earlier direction'
);
delete from public.confirmations
where user_id = '22222222-2222-4222-8222-222222222222'
  and drop_id in (
    current_setting('t.a1')::uuid, current_setting('t.a2')::uuid,
    current_setting('t.a3')::uuid, current_setting('t.a4')::uuid
  );
select is(
  (select points from public.profiles where id = '22222222-2222-4222-8222-222222222222'),
  8, 'once the earlier direction is gone, the other direction scores'
);

-- Not anymore, then back to still true.
set local role authenticated;
set local request.jwt.claims to '{"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "role": "authenticated"}';
do $$
begin
  perform set_config('t.fay', public.create_drop('story', 40.02, -100.0, 'Fay story') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'not_true', 40.02, -100.0) $$, current_setting('t.fay')),
  'carol says not anymore'
);
reset role;
select is(
  (select points from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  -6, 'the first not-anymore is minus 6'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.02, -100.0) $$, current_setting('t.fay')),
  'carol changes it to still true'
);
reset role;
select is(
  (select points from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  8, 'switching back removes the penalty and scores the still-true'
);

-- A unique view scores. The author's own open does not. A spoofed confirm does not.
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.drop_detail(%L, 40.02, -100.0) $$, current_setting('t.fay')),
  'bob opens fay''s story'
);
reset role;
select is(
  (select points from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  10, 'the first unique view is 2'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "role": "authenticated"}';
select lives_ok(
  format($$ select public.drop_detail(%L, 40.02, -100.0) $$, current_setting('t.fay')),
  'fay opens her own story'
);
reset role;
select is(
  (select points from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  10, 'an author opening their own drop does not score'
);
update public.confirmations
set spoof_flag = true
where drop_id = current_setting('t.fay')::uuid
  and user_id = '33333333-3333-4333-8333-333333333333';
select is(
  (select points from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  2, 'a spoofed still-true is left out'
);
select ok(
  (select left_out is true from public.reputation_ledger
   where source_key = 'confirm:' || current_setting('t.fay') || ':' || '33333333-3333-4333-8333-333333333333'),
  'the ledger keeps the spoofed row so clearing the flag can restore it'
);
update public.confirmations
set spoof_flag = false
where drop_id = current_setting('t.fay')::uuid
  and user_id = '33333333-3333-4333-8333-333333333333';
select is(
  (select points from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  10, 'a reviewed flag (false) counts again'
);

-- Imported public records never score.
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.record', public.create_drop('story', 40.03, -100.0, 'Soon a record') ->> 'id', true);
end;
$$;
reset role;
update public.drops
set source = 'open_data', author_id = null
where id = current_setting('t.record')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.drop_detail(%L, 40.03, -100.0) $$, current_setting('t.record')),
  'bob opens a public record'
);
reset role;
select is(
  (select count(*)::integer from public.reputation_ledger where drop_id = current_setting('t.record')::uuid),
  0, 'a public record writes no reputation rows'
);

-- Removal claws the drop's points back and subtracts 15. Restore puts them back.
set local role authenticated;
set local request.jwt.claims to '{"sub": "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "role": "authenticated"}';
do $$
begin
  perform set_config('t.gus', public.create_drop('story', 40.04, -100.0, 'Gus story') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.04, -100.0) $$, current_setting('t.gus')),
  'bob confirms gus'
);
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select lives_ok(
  format($$ select public.moderate_drop(%L, 'remove', 'Spam') $$, current_setting('t.gus')),
  'a moderator removes gus''s story'
);
reset role;
select is(
  (select points from public.profiles where id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'),
  -15, 'removal takes the confirm back and subtracts 15'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select lives_ok(
  format($$ select public.moderate_drop(%L, 'restore') $$, current_setting('t.gus')),
  'the moderator restores it'
);
reset role;
select is(
  (select points from public.profiles where id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'),
  8, 'restoring the drop puts the confirm back and drops the penalty'
);

-- A finished walk scores once and earns the badge.
insert into public.walks (
  id, region_id, slug, title, description, distance_m, duration_min,
  min_lng, min_lat, max_lng, max_lat
) values (
  'b0000000-0000-4000-8000-000000000016', 'zz-rep', 'rep-walk', 'A walk', 'A short walk.',
  100, 10, -101, 39, -99, 41
);
insert into public.walk_progress (user_id, walk_id, completed_at)
values ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'b0000000-0000-4000-8000-000000000016', now());
select is(
  (select points from public.profiles where id = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'),
  12, 'finishing a walk is 12'
);
select ok(
  exists (
    select 1 from public.user_badges ub
    join public.badges b on b.id = ub.badge_id
    where ub.user_id = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd' and b.slug = 'walk_done'
  ),
  'finishing a walk awards Walked it'
);

-- A one-step quest scores, and the first drop badge is awarded. Two confirms reach Local.
insert into public.quests (id, slug, title, description)
values ('b0000000-0000-4000-8000-000000000017', 'rep-quest', 'One drop', 'Post one drop.');
insert into public.quest_steps (quest_id, position, kind, title, target_count)
values ('b0000000-0000-4000-8000-000000000017', 1, 'create_drop', 'Post one', 1);
set local role authenticated;
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
do $$
begin
  perform set_config('t.ed', public.create_drop('story', 40.05, -100.0, 'Ed story') ->> 'id', true);
end;
$$;
reset role;
select is(
  (select points from public.profiles where id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  10, 'finishing a quest is 10'
);
select is(
  (select array_agg(b.slug order by b.slug)
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where ub.user_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  array['first_drop', 'quest_done'],
  'the first drop and the finished quest are on the shelf'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.05, -100.0) $$, current_setting('t.ed')),
  'bob confirms ed'
);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.05, -100.0) $$, current_setting('t.ed')),
  'carol confirms ed'
);
reset role;
select is(
  (select tier::text from public.profiles where id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  'regular', '24 points promotes an unpinned account to Local immediately'
);

-- A removal in the last 90 days blocks the nightly promotion. A clean score does not.
update public.quests set active = false where slug = 'rep-quest';
set local role authenticated;
set local request.jwt.claims to '{"sub": "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "role": "authenticated"}';
do $$
begin
  perform set_config('t.hal', public.create_drop('story', 40.06, -100.0, 'Hal story') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select lives_ok(
  format($$ select public.moderate_drop(%L, 'remove', 'Spam') $$, current_setting('t.hal')),
  'a moderator removes hal''s story'
);
reset role;
select is(
  (select points from public.profiles where id = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'),
  -3, 'the walk points stay and the removal subtracts 15'
);
update public.profiles set points = 24
where id in ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd');
do $$ begin perform private.recompute_tiers(); end $$;
select is(
  (select array_agg(p.id::text || '=' || p.tier::text order by p.id::text)
   from public.profiles p
   where p.id in ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd')),
  array[
    'cccccccc-cccc-4ccc-8ccc-cccccccccccc=regular',
    'dddddddd-dddd-4ddd-8ddd-dddddddddddd=new'
  ],
  'the nightly job promotes a clean score and leaves a recent removal at New'
);

select ok(
  not has_table_privilege('authenticated', 'public.reputation_ledger', 'select')
  and not has_table_privilege('anon', 'public.reputation_ledger', 'select')
  and not has_column_privilege('authenticated', 'public.confirmations', 'spoof_flag', 'update')
  and not has_column_privilege('authenticated', 'public.drop_views', 'spoof_flag', 'update')
  and not has_column_privilege('authenticated', 'public.walk_progress', 'spoof_flag', 'update')
  and not has_column_privilege('authenticated', 'public.quest_completions', 'spoof_flag', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'tier_pin', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'points', 'update'),
  'clients cannot read the ledger or set spoof flags, the pin, or points'
);

-- Trust-weighted order. Alice is Trusted. Bob is New. Her drop is about 30 m farther.
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
do $$
begin
  perform set_config('t.near_new', public.create_drop('story', 41.0, -100.0, 'Near new') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.near_trusted', public.create_drop('story', 41.00027, -100.0, 'Farther trusted') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(
  (select id from public.drops_nearby(41.0, -100.0, 500) limit 1),
  current_setting('t.near_trusted')::uuid,
  'a Trusted drop about 30 m farther ranks ahead of a New one'
);
select is(
  (select author_tier::text from public.drops_nearby(41.0, -100.0, 500)
   where id = current_setting('t.near_trusted')::uuid),
  'trusted', 'the card carries the author tier'
);

-- Demo accounts do not earn, and their confirms do not move anyone else.
reset role;
insert into auth.users (id, email) values
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'iris@example.test');
update public.profiles set is_demo = true where id = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
set local role authenticated;
set local request.jwt.claims to '{"sub": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "role": "authenticated"}';
select lives_ok(
  $$ select public.create_drop('story', 40.2, -100.0, 'Demo story') $$,
  'a demo account can still post'
);
reset role;
select is(
  (select points from public.profiles where id = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'),
  0, 'a demo account earns no points for a drop'
);
select is(
  (select count(*) from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where ub.user_id = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
     and b.slug = 'first_drop'),
  0::bigint, 'a demo account does not take the first-drop badge'
);
do $$
begin
  perform set_config(
    't.alice_pts',
    (select points::text from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
    true
  );
end;
$$;
set local role authenticated;
set local request.jwt.claims to '{"sub": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.0, -100.0) $$, current_setting('t.a1')),
  'a demo account can still confirm'
);
reset role;
select is(
  (select points from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  current_setting('t.alice_pts')::integer,
  'a demo confirm does not add reputation'
);

-- Marking an account demo removes its score. Clearing the flag puts it back.
do $$
begin
  perform set_config(
    't.fay_pts',
    (select points::text from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
    true
  );
end;
$$;
update public.profiles set is_demo = true where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
select is(
  (select points from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  0, 'marking an account demo removes its reputation'
);
select is(
  (select count(*) from public.reputation_ledger
   where user_id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
      or actor_id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  0::bigint, 'a demo account has no ledger rows'
);
select is(
  (select count(*) from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where ub.user_id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
     and b.slug = 'first_drop'),
  0::bigint, 'a demo account loses the reputation badges'
);
update public.profiles set is_demo = false where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
select is(
  (select points from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  current_setting('t.fay_pts')::integer,
  'clearing the demo flag puts the score back'
);
select is(
  (select count(*) from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where ub.user_id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
     and b.slug = 'first_drop'),
  1::bigint, 'clearing the demo flag puts the first-drop badge back'
);

select * from finish();
rollback;
