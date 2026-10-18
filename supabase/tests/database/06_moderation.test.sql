-- Reports, moderator actions and the audit log, suspensions, account
-- deletion, and the scheduled purge and tier jobs.
begin;
create extension if not exists pgtap with schema extensions;
select plan(41);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'mo@example.test'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ed@example.test'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'gus@example.test'),
  ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'hal@example.test');
update public.profiles set tier = 'trusted' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'regular' where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set tier = 'trusted', role = 'moderator' where id = '44444444-4444-4444-8444-444444444444';

set local role authenticated;

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.hist', public.create_drop('historical_fact', 40.0, -100.0, 'Old mill', 'Burned in 1890.') ->> 'id', true);
end;
$$;
select throws_ok(
  format($$ select public.report('drop', %L, 'spam') $$, current_setting('t.hist')),
  '22023', 'cannot_report_self', 'authors cannot report their own drop'
);

-- Reports
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
do $$
begin
  perform set_config('t.report1', public.report('drop', current_setting('t.hist')::uuid, 'misinformation', 'Wrong year')::text, true);
end;
$$;
select is(
  public.report('drop', current_setting('t.hist')::uuid, 'spam')::text,
  current_setting('t.report1'),
  'reporting the same drop twice returns the open report'
);
select throws_ok($$ select public.report('drop', gen_random_uuid(), 'spam') $$, 'P0002', 'drop_not_found', 'unknown drops cannot be reported');
select throws_ok(
  $$ select public.report('profile', '22222222-2222-4222-8222-222222222222', 'spam') $$,
  '22023', 'cannot_report_self', 'users cannot report themselves'
);
do $$
begin
  perform set_config('t.profile_report', public.report('profile', '11111111-1111-4111-8111-111111111111', 'harassment')::text, true);
end;
$$;
select isnt(current_setting('t.profile_report'), '', 'profiles can be reported');

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok(format($$ select public.report('drop', %L, 'spam') $$, current_setting('t.hist')), 'second reporter');
reset role;
select is((select status::text from public.drops where id = current_setting('t.hist')::uuid), 'active', 'two reports do not hide a history drop');
set local role authenticated;
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select lives_ok(format($$ select public.report('drop', %L, 'spam') $$, current_setting('t.hist')), 'third reporter');
reset role;
select is((select status::text from public.drops where id = current_setting('t.hist')::uuid), 'pending_review', 'three distinct reporters send it to review');
select is(
  (select count(*)::integer from public.moderation_actions where target_id = current_setting('t.hist')::uuid and action = 'auto_hide'),
  1,
  'the auto-hide is logged'
);
set local role authenticated;

-- Moderator actions
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  format($$ select public.resolve_report(%L, 'dismissed') $$, current_setting('t.report1')),
  '42501', 'not_moderator', 'non-moderators cannot resolve reports'
);
select throws_ok(
  $$ select public.suspend_user('11111111-1111-4111-8111-111111111111', now() + interval '1 day') $$,
  '42501', 'not_moderator', 'non-moderators cannot suspend users'
);

set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select is(public.moderate_drop(current_setting('t.hist')::uuid, 'approve', 'Year checks out')::text, 'active', 'approve returns the drop to active');
select is(
  (select array_agg(distinct status::text) from public.reports where target_id = current_setting('t.hist')::uuid),
  array['dismissed'],
  'approving dismisses the open reports'
);
select throws_ok(
  format($$ select public.moderate_drop(%L, 'approve') $$, current_setting('t.hist')),
  '22023', 'invalid_transition', 'only pending drops can be approved'
);
select throws_ok(
  format($$ select public.moderate_drop(%L, 'delete-everything') $$, current_setting('t.hist')),
  '22023', 'invalid_action', 'unknown actions are rejected'
);
select is(public.moderate_drop(current_setting('t.hist')::uuid, 'remove', 'Copied text')::text, 'removed', 'remove');
select is(public.moderate_drop(current_setting('t.hist')::uuid, 'restore')::text, 'active', 'restore');
select is(
  (select array_agg(action::text order by id) from public.moderation_actions
   where target_id = current_setting('t.hist')::uuid and actor_id = '44444444-4444-4444-8444-444444444444'),
  array['approve', 'remove', 'restore'],
  'moderators can read an audit trail of every action'
);
select lives_ok(
  format($$ select public.resolve_report(%L, 'dismissed', 'Not harassment') $$, current_setting('t.profile_report')),
  'moderators resolve reports'
);
select throws_ok(
  format($$ select public.resolve_report(%L, 'actioned') $$, current_setting('t.profile_report')),
  'P0002', 'report_not_found', 'a resolved report cannot be resolved again'
);
do $$
begin
  perform set_config('t.mo_drop', public.create_drop('story', 40.0, -100.0, 'Mo''s story') ->> 'id', true);
end;
$$;
select throws_ok(
  format($$ select public.moderate_drop(%L, 'remove') $$, current_setting('t.mo_drop')),
  '42501', 'cannot_moderate_own_drop', 'moderators cannot act on their own drops'
);
select throws_ok(
  $$ select public.suspend_user('44444444-4444-4444-8444-444444444444', now() + interval '1 day') $$,
  '42501', 'cannot_moderate_self', 'moderators cannot suspend themselves'
);

-- Removed drops are frozen for their author
select lives_ok(format($$ select public.moderate_drop(%L, 'remove', 'Spam') $$, current_setting('t.hist')), 'remove again');
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
declare
  n integer;
begin
  update public.drops set title = 'Edited after removal' where id = current_setting('t.hist')::uuid;
  get diagnostics n = row_count;
  perform set_config('t.edited', n::text, true);
end;
$$;
select is(current_setting('t.edited'), '0', 'authors cannot edit a removed drop');

-- Suspension
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select lives_ok(
  $$ select public.suspend_user('22222222-2222-4222-8222-222222222222', now() + interval '3 days', 'Spam') $$,
  'moderators suspend users'
);
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok($$ select public.create_drop('story', 40.0, -100.0, 'Hi') $$, '42501', 'account_suspended', 'suspended users cannot post');
select is((select count(*)::integer from public.user_moderation), 1, 'users can see their own suspension');
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is((select count(*)::integer from public.user_moderation), 0, 'other users cannot see suspensions');
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select lives_ok($$ select public.suspend_user('22222222-2222-4222-8222-222222222222', null, 'Appeal granted') $$, 'lifting a suspension');
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok($$ select public.create_drop('story', 40.0, -100.0, 'Back again') $$, 'users can post again once unsuspended');

-- Account deletion: soft delete now, purge after 30 days
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
do $$
begin
  perform set_config('t.carol_drop', public.create_drop('story', 40.0, -100.0, 'Carol''s story') ->> 'id', true);
  perform public.delete_my_account();
end;
$$;
select ok(
  (select handle like 'deleted_%' and display_name is null and deleted_at is not null from public.profiles where id = '33333333-3333-4333-8333-333333333333'),
  'deleting an account scrubs the profile right away'
);
select throws_ok($$ select public.create_drop('story', 40.0, -100.0, 'Still here?') $$, '42501', 'account_unavailable', 'deleted accounts cannot post');
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is_empty($$ select 1 from public.profiles where id = '33333333-3333-4333-8333-333333333333' $$, 'deleted profiles are hidden from others');

reset role;
select is(private.purge_deleted_accounts(), 0, 'accounts deleted today are kept for the grace period');
update public.profiles set deleted_at = now() - interval '31 days' where id = '33333333-3333-4333-8333-333333333333';
select is(private.purge_deleted_accounts(), 1, 'accounts deleted over 30 days ago are purged');
select ok(
  not exists (select 1 from auth.users where id = '33333333-3333-4333-8333-333333333333')
  and not exists (select 1 from public.drops where id = current_setting('t.carol_drop')::uuid),
  'the purge removes the auth user and their drops'
);

-- Nightly tier recompute: New -> Regular after 7 days and 3 drops, unless they have a strike
update public.tier_limits set drops_per_hour = 10 where tier = 'new';
set local role authenticated;
set local request.jwt.claims to '{"sub": "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "role": "authenticated"}';
select lives_ok(
  $$ select public.create_drop('story', 40.0, -100.0, 'G1'), public.create_drop('story', 40.0, -100.0, 'G2'),
            public.create_drop('story', 40.0, -100.0, 'G3') $$,
  'gus posts three drops'
);
set local request.jwt.claims to '{"sub": "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "role": "authenticated"}';
do $$
begin
  perform public.create_drop('story', 40.0, -100.0, 'H1');
  perform public.create_drop('story', 40.0, -100.0, 'H2');
  perform public.create_drop('story', 40.0, -100.0, 'H3');
  perform set_config('t.hal_bad', public.create_drop('story', 40.0, -100.0, 'H4') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select lives_ok(format($$ select public.moderate_drop(%L, 'remove', 'Spam') $$, current_setting('t.hal_bad')), 'one of hal''s drops is removed');
reset role;
update public.profiles set created_at = now() - interval '8 days'
  where id in ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd');
select ok(private.recompute_tiers() >= 1, 'the tier job promotes eligible users');
select is(
  (select array_agg(handle_tier order by handle_tier) from (
     select p.id::text || '=' || p.tier::text as handle_tier from public.profiles p
     where p.id in ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd')) x),
  array['cccccccc-cccc-4ccc-8ccc-cccccccccccc=regular', 'dddddddd-dddd-4ddd-8ddd-dddddddddddd=new'],
  'a clean record is promoted; a recent removal blocks promotion'
);

select * from finish();
rollback;
