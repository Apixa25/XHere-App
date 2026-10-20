-- Threads, several files, vote counters, inherited hiddenness, and a safe search.
begin;
create extension if not exists pgtap with schema extensions;
select plan(39);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('55555555-5555-4555-8555-555555555555', 'voter5@example.test'),
  ('66666666-6666-4666-8666-666666666666', 'voter6@example.test'),
  ('77777777-7777-4777-8777-777777777777', 'voter7@example.test'),
  ('88888888-8888-4888-8888-888888888888', 'voter8@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'mo@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', handle = 'bob' where id = '22222222-2222-4222-8222-222222222222';
update public.profiles set tier = 'trusted', role = 'moderator', handle = 'mod' where id = '44444444-4444-4444-8444-444444444444';

select ok(
  exists (
    select 1 from pg_enum e
    join pg_type t on t.oid = e.enumtypid
    join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'public' and t.typname = 'report_target' and e.enumlabel = 'contribution'
  )
  and exists (
    select 1 from pg_enum e
    join pg_type t on t.oid = e.enumtypid
    join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'public' and t.typname = 'report_target' and e.enumlabel = 'media'
  ),
  'reports can name a contribution or one of its files'
);
select ok(
  not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'contribution_cards' and column_name = 'author_id'
  ),
  'the public card view has no author id'
);
select ok(
  has_function_privilege('authenticated', 'private.contribution_votes_tally()', 'execute')
  and not has_function_privilege('public', 'private.contribution_votes_tally()', 'execute'),
  'a signed-in vote can run the counter trigger, and the public role cannot'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  $$ select public.create_drop('historical_fact', 42.2, -79.3, 'Maple', 'Beside the path.') $$,
  'a place for a thread'
);

do $$
declare
  v_created jsonb;
begin
  perform set_config('t.drop', (select id::text from public.drops where title = 'Maple'), true);
  v_created := public.create_contribution(
    'drop',
    current_setting('t.drop')::uuid,
    'photo',
    'Two angles of the maple',
    true,
    '[
      {"mime_type":"image/jpeg","byte_size":1200,"width":800,"height":600},
      {"mime_type":"image/jpeg","byte_size":900,"width":640,"height":480}
    ]'::jsonb
  );
  perform set_config('t.photo', v_created ->> 'id', true);
  perform set_config('t.path1', v_created -> 'media' -> 0 ->> 'storage_path', true);
  perform set_config('t.path2', v_created -> 'media' -> 1 ->> 'storage_path', true);
  perform set_config('t.media', v_created -> 'media' -> 0 ->> 'id', true);
  perform public.create_contribution(
    'drop', current_setting('t.drop')::uuid, 'text', 'The bark is pale', false, '[]'::jsonb,
    (v_created ->> 'id')::uuid
  );
  perform set_config(
    't.reply',
    (select id::text from public.contributions where body = 'The bark is pale'),
    true
  );
  perform public.create_contribution('drop', current_setting('t.drop')::uuid, 'text', 'A later note', false);
  perform public.create_contribution('drop', current_setting('t.drop')::uuid, 'text', 'One more note', false);
  perform set_config(
    't.later',
    (select id::text from public.contributions where body = 'One more note'),
    true
  );
end;
$$;

select is(
  (select kind from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.photo')::uuid),
  'photo',
  'two images stay a photo'
);
select is(
  (select jsonb_array_length(media) from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.photo')::uuid),
  2,
  'one contribution can carry two files'
);
select ok(
  current_setting('t.path1') like 'c/%'
  and current_setting('t.path2') like 'c/%'
  and current_setting('t.path1') <> current_setting('t.path2')
  and position('11111111-1111-4111-8111-111111111111' in current_setting('t.path1')) = 0
  and position('11111111-1111-4111-8111-111111111111' in current_setting('t.path2')) = 0,
  'each file path is its own id and not the author'
);
select is(
  (select parent_id from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.reply')::uuid),
  current_setting('t.photo')::uuid,
  'a reply records its parent'
);
select is(
  (select author_handle from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.photo')::uuid),
  'Anonymous',
  'an anonymous photo says Anonymous'
);
select is(
  (select edited_at from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.reply')::uuid),
  null,
  'a new reply has no edited marker'
);
select lives_ok(
  format($$ select public.edit_contribution(%L::uuid, 'The bark is pale in spring') $$, current_setting('t.reply')),
  'the author can change the words'
);
select ok(
  (select edited_at is not null from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.reply')::uuid),
  'the edit sets edited_at'
);
select is(
  (select id from public.contributions_for('drop', current_setting('t.drop')::uuid, 'oldest') limit 1),
  current_setting('t.photo')::uuid,
  'oldest puts the first post first'
);
select is(
  (select count(*)::int from public.contributions_for('drop', current_setting('t.drop')::uuid, 'oldest', 2, 0) where parent_id is null),
  2,
  'a page counts roots, and the reply rides along'
);
select is(
  (select id from public.contributions_for('drop', current_setting('t.drop')::uuid, 'oldest', 2, 2) limit 1),
  current_setting('t.later')::uuid,
  'the next page is the next root'
);
select is(
  (select count(*)::int from public.contributions_for(
    'drop', current_setting('t.drop')::uuid, 'newest', 20, 0,
    $$'; drop table public.contributions; --$$
  )),
  0,
  'a quote in the search is data, not SQL'
);
select has_table('public', 'contributions', 'the contributions table is still there');
select is(
  (select count(*)::int from public.contributions_for('drop', current_setting('t.drop')::uuid, 'newest', 20, 0, 'maple')),
  1,
  'a plain word still finds the post'
);
select ok(
  (select share_url from public.contribution_place('drop', current_setting('t.drop')::uuid)) like 'https://xhere.world/d/%',
  'a drop has a share link'
);
select throws_ok(
  $$ select author_id from public.contribution_cards $$,
  '42703',
  'author_id',
  'asking the view for an author id fails'
);
select is(
  (select author_handle from public.contribution_cards where id = current_setting('t.photo')::uuid),
  'Anonymous',
  'the view says Anonymous too'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';

select is(
  public.contribution_author(current_setting('t.photo')::uuid),
  '11111111-1111-4111-8111-111111111111'::uuid,
  'a moderator can still see who wrote the anonymous photo'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select lives_ok(
  format($$ select public.cast_contribution_vote(%L::uuid, 1) $$, current_setting('t.photo')),
  'a neighbor can upvote'
);
select lives_ok(
  format($$ select public.report('media', %L::uuid, 'spam') $$, current_setting('t.media')),
  'a file can be reported on its own'
);
select lives_ok(
  format($$ select public.cast_contribution_vote(%L::uuid, 1) $$, current_setting('t.photo')),
  'the same vote again takes it off'
);
select is(
  (select up_count from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.photo')::uuid),
  0,
  'the counter follows the removed vote'
);
select lives_ok(
  format($$ select public.cast_contribution_vote(%L::uuid, -1) $$, current_setting('t.photo')),
  'a downvote is the other direction'
);

reset role;

select is(
  (select score from public.contributions where id = current_setting('t.photo')::uuid),
  -1,
  'score is up minus down'
);
select is(
  (select count(*)::int from public.reputation_ledger where source_key = 'cdown:' || current_setting('t.photo') || ':' || '22222222-2222-4222-8222-222222222222' and kind = 'downvote' and points = -1),
  1,
  'a downvote received is one ledger row'
);

set local role authenticated;

do $$
begin
  perform set_config('request.jwt.claims', '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}', true);
  perform public.cast_contribution_vote(current_setting('t.photo')::uuid, 1);
end;
$$;

reset role;

select is(
  (select count(*)::int from public.reputation_ledger where source_key like 'cdown:' || current_setting('t.photo') || ':%'),
  0,
  'switching away from a downvote removes the ledger row'
);

set local role authenticated;

do $$
declare
  v_voter uuid;
begin
  foreach v_voter in array array[
    '22222222-2222-4222-8222-222222222222',
    '55555555-5555-4555-8555-555555555555',
    '66666666-6666-4666-8666-666666666666',
    '77777777-7777-4777-8777-777777777777',
    '88888888-8888-4888-8888-888888888888'
  ]::uuid[]
  loop
    perform set_config('request.jwt.claims', json_build_object('sub', v_voter, 'role', 'authenticated')::text, true);
    perform public.cast_contribution_vote(current_setting('t.photo')::uuid, -1);
  end loop;
end;
$$;

reset role;

select is(
  (select status from public.contributions where id = current_setting('t.photo')::uuid),
  'hidden',
  'five downvotes hide the post'
);
select is(
  (select count(*)::int from public.reputation_ledger where kind = 'downvote' and source_key like 'cdown:' || current_setting('t.photo') || ':%'),
  5,
  'each downvote is its own ledger row'
);
select ok(
  exists (
    select 1 from public.moderation_actions
    where target_type = 'contribution'
      and target_id = current_setting('t.photo')::uuid
      and action = 'auto_hide'
  ),
  'the hide is still a moderation action'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select count(*)::int from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.photo')::uuid),
  0,
  'a neighbor does not see the hidden photo'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select is(
  (select count(*)::int from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.photo')::uuid),
  1,
  'the author still sees their hidden photo'
);

reset role;

update public.drops
set capsule_mode = 'visit', capsule_opened_at = null, capsule_unlock_at = null
where id = current_setting('t.drop')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select count(*)::int from public.contributions_for('drop', current_setting('t.drop')::uuid)),
  0,
  'a sealed capsule hides every contribution from a neighbor'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select ok(
  (select count(*) > 0 from public.contributions_for('drop', current_setting('t.drop')::uuid)),
  'the capsule owner can still read the thread'
);

reset role;

update public.drops
set capsule_mode = null
where id = current_setting('t.drop')::uuid;

insert into public.hidden_drops (user_id, drop_id)
values ('22222222-2222-4222-8222-222222222222', current_setting('t.drop')::uuid);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select count(*)::int from public.contributions_for('drop', current_setting('t.drop')::uuid)),
  0,
  'a drop this person hid hides its contributions too'
);

reset role;

update public.drops set deleted_at = now() where id = current_setting('t.drop')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select is(
  (select count(*)::int from public.contributions_for('drop', current_setting('t.drop')::uuid)),
  0,
  'a deleted drop hides its contributions from the author as well'
);

select * from finish();
rollback;
