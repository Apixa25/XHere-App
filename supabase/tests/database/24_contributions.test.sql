-- Community contributions: who can see a file, who is named, and who can vote.
begin;
create extension if not exists pgtap with schema extensions;
select plan(34);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'mo@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', handle = 'bob' where id = '22222222-2222-4222-8222-222222222222';
update public.profiles set tier = 'regular', handle = 'cara' where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set tier = 'trusted', role = 'moderator', handle = 'mod' where id = '44444444-4444-4444-8444-444444444444';

select ok(
  exists (
    select 1 from storage.buckets
    where id = 'contribution-media' and public = false
  ),
  'contribution-media is a private bucket'
);
select ok(
  exists (
    select 1 from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname = 'contribution_media_select_visible'
      and qual like '%contribution-media%'
  ),
  'contribution files have a select policy so signing can succeed'
);
select ok(
  has_function_privilege('authenticated', 'private.can_read_contribution_media(text,text)', 'execute')
  and not has_function_privilege('anon', 'private.can_read_contribution_media(text,text)', 'execute'),
  'only a signed-in session can ask the contribution media check'
);
select ok(
  not ('author_id' = any (p.proargnames)),
  'the public list has no author id column'
)
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public' and p.proname = 'contributions_for';

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  $$ select public.create_drop('historical_fact', 42.1, -79.2, 'Oak', 'By the river.') $$,
  'a place to attach notes to'
);

do $$
begin
  perform set_config(
    't.drop',
    (select id::text from public.drops where title = 'Oak'),
    true
  );
end;
$$;

select lives_ok(
  format(
    $$ select public.create_contribution('drop', %L::uuid, 'text', 'The oak is older than the street.', true) $$,
    current_setting('t.drop')
  ),
  'a note can be anonymous when the category allows it'
);

do $$
begin
  perform set_config(
    't.note',
    (select id::text from public.contributions where body like 'The oak%'),
    true
  );
end;
$$;

select is(
  (select author_handle from public.contributions_for('drop', current_setting('t.drop')::uuid, 'newest') where id = current_setting('t.note')::uuid),
  null,
  'the author does not see their own name on an anonymous note'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select author_handle from public.contributions_for('drop', current_setting('t.drop')::uuid, 'newest') where id = current_setting('t.note')::uuid),
  null,
  'another person does not see the anonymous author'
);
select is(
  (select is_mine from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.note')::uuid),
  false,
  'the note is not theirs'
);
select throws_ok(
  format($$ select public.contribution_author(%L::uuid) $$, current_setting('t.note')),
  '42501',
  'not_moderator',
  'a neighbor cannot ask who wrote an anonymous note'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';

select is(
  public.contribution_author(current_setting('t.note')::uuid),
  '11111111-1111-4111-8111-111111111111'::uuid,
  'a moderator can see the recorded author'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select ok(
  has_column_privilege('authenticated', 'public.contributions', 'id', 'SELECT')
  and not has_column_privilege('authenticated', 'public.contributions', 'author_id', 'SELECT'),
  'signed-in users may read a contribution but not its author id'
);
select throws_ok(
  $$ select author_id from public.contributions $$,
  '42501',
  'permission denied for table contributions',
  'reading the author column is denied'
);

do $$
declare
  v_created jsonb;
begin
  v_created := public.create_contribution(
    'drop',
    current_setting('t.drop')::uuid,
    'photo',
    'Bench in the shade',
    false,
    '[{"mime_type":"image/jpeg","byte_size":1200,"width":800,"height":600,"alt_text":"A bench"}]'::jsonb
  );
  perform set_config('t.photo', v_created ->> 'id', true);
  perform set_config('t.path', v_created -> 'media' -> 0 ->> 'storage_path', true);
end;
$$;

select ok(
  current_setting('t.photo') is not null and current_setting('t.path') like 'c/%',
  'a photo contribution reserves a storage path'
);

select ok(
  current_setting('t.path') like 'c/%'
  and position('11111111-1111-4111-8111-111111111111' in current_setting('t.path')) = 0,
  'the storage path does not contain the author id'
);

reset role;

insert into storage.objects (bucket_id, name, owner, owner_id)
values (
  'contribution-media',
  current_setting('t.path'),
  '11111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111'
);

select is(
  (select owner_id from storage.objects where name = current_setting('t.path')),
  null,
  'the object row does not keep the uploader id'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select private.can_read_contribution_media('contribution-media', current_setting('t.path'))),
  true,
  'any signed-in person may read an active public photo'
);
select isnt_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'contribution-media' and name = current_setting('t.path') $$,
  'the select policy matches the photo, which is what signing needs'
);

select throws_ok(
  format($$ select public.cast_contribution_vote(%L::uuid, 1) $$, current_setting('t.note')),
  '22023',
  'votes_are_for_media',
  'a text note is not voted on'
);

select lives_ok(
  format($$ select public.cast_contribution_vote(%L::uuid, 1) $$, current_setting('t.photo')),
  'someone else can upvote a photo'
);
select lives_ok(
  format($$ select public.cast_contribution_vote(%L::uuid, -1) $$, current_setting('t.photo')),
  'the same person can change that vote'
);
select is(
  (select my_vote from public.contributions_for('drop', current_setting('t.drop')::uuid, 'top') where id = current_setting('t.photo')::uuid),
  -1::smallint,
  'the changed vote is a downvote'
);
select lives_ok(
  format($$ select public.cast_contribution_vote(%L::uuid, 0) $$, current_setting('t.photo')),
  'a vote can be taken back'
);
select is(
  (select my_vote from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.photo')::uuid),
  null,
  'taking a vote back leaves no vote'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select throws_ok(
  format($$ select public.cast_contribution_vote(%L::uuid, 1) $$, current_setting('t.photo')),
  '22023',
  'cannot_vote_self',
  'the author cannot vote on their own photo'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select public.cast_contribution_vote(current_setting('t.photo')::uuid, 1);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select public.cast_contribution_vote(current_setting('t.photo')::uuid, 1);

select throws_ok(
  format(
    $$ insert into public.contribution_votes (contribution_id, voter_id, value)
       values (%L::uuid, '33333333-3333-4333-8333-333333333333', -1) $$,
    current_setting('t.photo')
  ),
  '23505',
  'duplicate key value violates unique constraint "contribution_votes_pkey"',
  'one vote row per person'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  $$ select public.create_drop('yard_sale', 42.1, -79.2, 'Table', 'Saturday morning.') $$,
  'a sale is a place whose author is always shown'
);

select throws_ok(
  format(
    $$ select public.create_contribution('drop', %L::uuid, 'text', 'Still there', true) $$,
    (select id from public.drops where title = 'Table')
  ),
  '22023',
  'anonymous_not_allowed',
  'a sale cannot take an anonymous contribution'
);

select lives_ok(
  $$ select public.create_drop('historical_marker', 42.11, -79.21, 'Plaque', '1891.',
       p_attributes => '{"year":1891}'::jsonb) $$,
  'a historical marker is its own place kind'
);

select lives_ok(
  format(
    $$ select public.create_contribution('marker', %L::uuid, 'text', 'The plaque faces the river.', false) $$,
    (select id from public.drops where title = 'Plaque')
  ),
  'a marker accepts a contribution'
);

select throws_ok(
  format(
    $$ select public.create_contribution('marker', %L::uuid, 'text', 'Not a plaque', false) $$,
    current_setting('t.drop')
  ),
  'P0002',
  'place_not_found',
  'marker is only for historical markers'
);

select is(
  (select live from public.contributions_for('drop', current_setting('t.drop')::uuid) where id = current_setting('t.photo')::uuid),
  true,
  'a photo from this hour wears the live flag'
);

reset role;

update public.contributions
set status = 'removed'
where id = current_setting('t.photo')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select private.can_read_contribution_media('contribution-media', current_setting('t.path'))),
  false,
  'a removed photo is not readable'
);
select is_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'contribution-media' and name = current_setting('t.path') $$,
  'the select policy hides a removed photo from signing'
);

select * from finish();
rollback;
