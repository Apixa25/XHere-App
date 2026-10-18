-- Row Level Security: cross-user reads and writes are denied.
begin;
create extension if not exists pgtap with schema extensions;
select plan(29);

-- alice (trusted), bob (new), carol (regular), adam (admin)
insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('55555555-5555-4555-8555-555555555555', 'adam@example.test');
update public.profiles set tier = 'trusted' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'regular' where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set role = 'admin' where id = '55555555-5555-4555-8555-555555555555';

set local role authenticated;

-- alice posts a drop with a photo, blocks carol, and gets reported and confirmed by carol
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.drop', public.create_drop(
    'historical_fact', 40.0, -100.0, 'Old mill', 'Burned down in 1890.',
    p_media => '[{"storage_path": "11111111-1111-4111-8111-111111111111/mill.jpg", "media_kind": "image", "mime_type": "image/jpeg"}]'
  ) ->> 'id', true);
end;
$$;

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
do $$
begin
  perform public.confirm_drop(current_setting('t.drop')::uuid, 'still_true', 40.0, -100.0);
  perform public.report('drop', current_setting('t.drop')::uuid, 'spam');
  perform public.block_user('22222222-2222-4222-8222-222222222222');
end;
$$;

-- bob tries everything against alice's data
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is_empty(
  $$ select 1 from public.drops where id = current_setting('t.drop')::uuid $$,
  'other users cannot read a drop row (true location stays private)'
);
select is_empty($$ select 1 from public.drop_media $$, 'other users cannot read media rows directly');
select is_empty($$ select 1 from public.confirmations $$, 'other users cannot read confirmations');
select is_empty($$ select 1 from public.reports $$, 'other users cannot read reports');
select is_empty($$ select 1 from public.user_blocks $$, 'other users cannot read block lists');
select is_empty($$ select 1 from public.moderation_actions $$, 'non-moderators cannot read the audit log');

do $$
declare
  n integer;
begin
  update public.drops set title = 'hacked' where id = current_setting('t.drop')::uuid;
  get diagnostics n = row_count;
  perform set_config('t.updated', n::text, true);
  delete from public.drops where id = current_setting('t.drop')::uuid;
  get diagnostics n = row_count;
  perform set_config('t.deleted', n::text, true);
  update public.profiles set display_name = 'hacked' where id = '11111111-1111-4111-8111-111111111111';
  get diagnostics n = row_count;
  perform set_config('t.profile_updated', n::text, true);
  delete from public.confirmations where user_id = '33333333-3333-4333-8333-333333333333';
  get diagnostics n = row_count;
  perform set_config('t.conf_deleted', n::text, true);
  delete from public.user_blocks where blocker_id = '33333333-3333-4333-8333-333333333333';
  get diagnostics n = row_count;
  perform set_config('t.blocks_deleted', n::text, true);
end;
$$;
select is(current_setting('t.updated'), '0', 'cannot update another user''s drop');
select is(current_setting('t.deleted'), '0', 'cannot delete another user''s drop');
select is(current_setting('t.profile_updated'), '0', 'cannot update another user''s profile');
select is(current_setting('t.conf_deleted'), '0', 'cannot delete another user''s confirmation');
select is(current_setting('t.blocks_deleted'), '0', 'cannot delete another user''s blocks');

select throws_ok(
  $$ insert into public.drops (author_id, category_id, category_version, title, geog, display_geog, reveal_radius_m)
     select '22222222-2222-4222-8222-222222222222', c.id, c.version, 'direct', 'POINT(-100 40)', 'POINT(-100 40)', 100
     from public.categories c where c.slug = 'note' $$,
  '42501', null, 'cannot insert drops directly (create_drop only)'
);
select throws_ok(
  $$ insert into public.confirmations (drop_id, user_id, verdict, distance_m)
     values (current_setting('t.drop')::uuid, '22222222-2222-4222-8222-222222222222', 'still_true', 0) $$,
  '42501', null, 'cannot insert confirmations directly (confirm_drop only)'
);
select throws_ok(
  $$ insert into public.user_blocks (blocker_id, blocked_id)
     values ('11111111-1111-4111-8111-111111111111', '33333333-3333-4333-8333-333333333333') $$,
  '42501', null, 'cannot create blocks on behalf of someone else'
);
select throws_ok(
  $$ insert into public.drop_media (drop_id, owner_id, storage_path, media_kind, mime_type)
     values (current_setting('t.drop')::uuid, '22222222-2222-4222-8222-222222222222',
             '22222222-2222-4222-8222-222222222222/x.jpg', 'image', 'image/jpeg') $$,
  '42501', null, 'cannot attach media to someone else''s drop'
);
select throws_ok(
  $$ update public.profiles set tier = 'guide' where id = '22222222-2222-4222-8222-222222222222' $$,
  '42501', null, 'cannot promote yourself'
);
select throws_ok(
  $$ insert into public.categories (group_id, slug, label, default_reveal_m, max_reveal_m)
     select id, 'sneaky', 'Sneaky', 100, 100 from public.category_groups limit 1 $$,
  '42501', null, 'non-admins cannot add categories'
);
select throws_ok(
  $$ select public.moderate_drop(current_setting('t.drop')::uuid, 'remove') $$,
  '42501', 'not_moderator', 'non-moderators cannot moderate'
);

select is(
  (select count(*)::integer from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  1,
  'signed-in users can read public profiles'
);
select lives_ok(
  $$ update public.profiles set display_name = 'Bob B' where id = '22222222-2222-4222-8222-222222222222' $$,
  'users can edit their own display name'
);

-- carol reads only her own
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(
  (select count(*)::integer from public.confirmations) + (select count(*)::integer from public.reports)
    + (select count(*)::integer from public.user_blocks),
  3,
  'users can read their own confirmations, reports, and blocks'
);

-- alice, the author, edits only what she may
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select is_empty($$ select 1 from public.reports $$, 'authors cannot see who reported their drop');
select lives_ok(
  $$ update public.drops set title = 'The old mill' where id = current_setting('t.drop')::uuid $$,
  'authors can edit their drop title'
);
select throws_ok(
  $$ update public.drops set status = 'confirmed' where id = current_setting('t.drop')::uuid $$,
  '42501', null, 'authors cannot set their own status'
);
select throws_ok(
  $$ insert into public.drop_media (drop_id, owner_id, storage_path, media_kind, mime_type, taken_here)
     values (current_setting('t.drop')::uuid, '11111111-1111-4111-8111-111111111111',
             '11111111-1111-4111-8111-111111111111/b.jpg', 'image', 'image/jpeg', true) $$,
  '42501', null, 'clients cannot mark media as taken here'
);
select throws_ok(
  $$ insert into public.drop_media (drop_id, owner_id, storage_path, media_kind, mime_type)
     values (current_setting('t.drop')::uuid, '11111111-1111-4111-8111-111111111111',
             '22222222-2222-4222-8222-222222222222/b.jpg', 'image', 'image/jpeg') $$,
  '23514', null, 'media paths must live under the owner''s folder'
);

-- admins manage the taxonomy
set local request.jwt.claims to '{"sub": "55555555-5555-4555-8555-555555555555", "role": "authenticated"}';
select lives_ok(
  $$ update public.categories set label = 'Historic fact' where slug = 'historical_fact' $$,
  'admins can edit categories'
);

-- signed-out visitors
reset role;
set local role anon;
set local request.jwt.claims to '';
select throws_ok($$ select 1 from public.profiles $$, '42501', null, 'signed-out visitors cannot read profiles');
select is((select count(*)::integer from public.categories), 30, 'signed-out visitors can read the taxonomy');

select * from finish();
rollback;
