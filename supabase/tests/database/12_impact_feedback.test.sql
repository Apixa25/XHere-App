-- Impact feedback: unique views, one confirm per person, batched inbox.
-- An imported public record can be seen. Confirming it is not a contribution.
begin;
create extension if not exists pgtap with schema extensions;
select plan(31);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ed@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'regular' where id = '33333333-3333-4333-8333-333333333333';

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.hist', public.create_drop('historical_fact', 40.0, -100.0, 'Old mill', 'Burned down in 1890.') ->> 'id', true);
  perform set_config('t.open', public.create_drop('historical_fact', 40.01, -100.0, 'Library marker', 'A public record.') ->> 'id', true);
end;
$$;

-- Turn the second drop into an imported public record with no author.
reset role;
update public.drops
set source = 'open_data', author_id = null
where id = current_setting('t.open')::uuid;
select is(
  (select source::text || '/' || coalesce(author_id::text, 'none') from public.drops where id = current_setting('t.open')::uuid),
  'open_data/none',
  'an imported item can have no author'
);

-- Unique signed-in views. Repeats do not add. The author does not count.
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is((select view_count from public.drop_detail(current_setting('t.hist')::uuid, 40.0005, -100.0)), 1::bigint, 'the first open counts as seen by 1');
select is((select view_count from public.drop_detail(current_setting('t.hist')::uuid, 40.0005, -100.0)), 1::bigint, 'the same person opening it again does not count twice');
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is((select view_count from public.drop_detail(current_setting('t.hist')::uuid, 40.0, -100.0)), 2::bigint, 'a second person counts');
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is((select view_count from public.drop_detail(current_setting('t.hist')::uuid, 40.0, -100.0)), 2::bigint, 'the author opening their own drop does not count');

reset role;
do $$
begin
  perform set_config('t.hist_short', (select short_id from public.drops where id = current_setting('t.hist')::uuid), true);
end;
$$;
set local role anon;
set local request.jwt.claims to '';
select lives_ok(
  format($$ select public.drop_teaser(%L) $$, current_setting('t.hist_short')),
  'a signed-out share page can still load the teaser'
);
reset role;
select is((select view_count from public.drops where id = current_setting('t.hist')::uuid), 2::bigint, 'anonymous teasers are not counted');

-- One inbox row for the day, with both viewers on it.
select is(
  (select count(*)::integer from public.impact_notices where drop_id = current_setting('t.hist')::uuid),
  1,
  'views on the same day share one inbox row'
);
select is(
  (select view_events from public.impact_notices where drop_id = current_setting('t.hist')::uuid),
  2,
  'that row totals the unique views'
);

-- Confirm is one per person. A repeat does not add another event. A denial does not either.
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (public.confirm_drop(current_setting('t.hist')::uuid, 'still_true', 40.0005, -100.0) ->> 'confirm_count')::integer,
  1,
  'confirm is one tap and counts'
);
select is(
  (select my_verdict::text from public.drop_detail(current_setting('t.hist')::uuid, 40.0005, -100.0)),
  'still_true',
  'the card remembers that you confirmed'
);
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.0005, -100.0) $$, current_setting('t.hist')),
  'confirming again is allowed and stays one row'
);
reset role;
select is(
  (select confirm_events from public.impact_notices where drop_id = current_setting('t.hist')::uuid),
  1,
  'confirming twice is still one confirm event'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'not_true', 40.0005, -100.0) $$, current_setting('t.hist')),
  'changing to not anymore is still the same confirmation row'
);
reset role;
select is(
  (select confirm_events from public.impact_notices where drop_id = current_setting('t.hist')::uuid),
  1,
  'a denial does not add a helped event'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok(
  format($$ select public.confirm_drop(%L, 'still_true', 40.0, -100.0) $$, current_setting('t.hist')),
  'a second person can confirm'
);
reset role;
select is(
  (select count(*)::integer from public.impact_notices where drop_id = current_setting('t.hist')::uuid),
  1,
  'the second confirm stays on today''s row'
);
select is(
  (select confirm_events from public.impact_notices where drop_id = current_setting('t.hist')::uuid),
  2,
  'today''s row totals both confirms'
);

-- A later UTC day is a new row, not a second ping on the same one.
update public.impact_notices
set notice_day = (timezone('utc', now()))::date - 1
where drop_id = current_setting('t.hist')::uuid;
set local role authenticated;
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select is((select view_count from public.drop_detail(current_setting('t.hist')::uuid, 40.0, -100.0)), 3::bigint, 'a new person the next day still counts once');
reset role;
select is(
  (select count(*)::integer from public.impact_notices where drop_id = current_setting('t.hist')::uuid),
  2,
  'a new day gets its own inbox row instead of another ping'
);

-- Inbox is only yours. Marking read does not touch someone else's notes.
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is((select count(*)::integer from public.my_impact_notices()), 0, 'another person does not see your inbox');
select is(public.mark_impact_notice_read(null), 0, 'marking read when you have no notes changes nothing');
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is((select count(*)::integer from public.my_impact_notices() where read_at is null), 2, 'the author sees both days, unread');
select is(public.mark_impact_notice_read(null), 2, 'one call marks every unread note');
select is((select count(*)::integer from public.my_impact_notices() where read_at is null), 0, 'they stay read');
set local request.jwt.claims to '{"role": "authenticated"}';
select throws_ok(
  $$ select * from public.my_impact_notices() $$,
  '42501',
  'not_authenticated',
  'the inbox needs a signed-in user'
);

-- Imported item: a signed-in view counts, and nobody is notified.
-- Confirming it is rejected so the pin is not that person's contribution.
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is((select view_count from public.drop_detail(current_setting('t.open')::uuid, 40.01, -100.0)), 1::bigint, 'an imported item counts a signed-in view');
select throws_ok(
  format(
    'select public.confirm_drop(%L::uuid, %L::public.confirmation_verdict, 40.01, -100.0)',
    current_setting('t.open'),
    'still_true'
  ),
  '22023',
  'not_a_user_drop',
  'confirming a public record is not a contribution'
);
reset role;
select is(
  (select count(*)::integer from public.impact_notices where drop_id = current_setting('t.open')::uuid),
  0,
  'an imported item with no author writes no inbox row'
);

select ok(
  not has_table_privilege('authenticated', 'public.drop_views', 'select')
  and not has_table_privilege('anon', 'public.drop_views', 'select')
  and not has_table_privilege('authenticated', 'public.impact_notices', 'select')
  and not has_table_privilege('authenticated', 'public.impact_notices', 'insert')
  and not has_table_privilege('anon', 'public.impact_notices', 'select'),
  'clients cannot read viewers or write inbox rows'
);
select ok(
  has_function_privilege('authenticated', 'public.my_impact_notices(integer)', 'execute')
  and not has_function_privilege('anon', 'public.my_impact_notices(integer)', 'execute')
  and not has_function_privilege('anon', 'public.mark_impact_notice_read(uuid)', 'execute'),
  'only a signed-in role can call the inbox'
);

select * from finish();
rollback;
