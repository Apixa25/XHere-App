-- Public profile fields, the availability check, and the avatars bucket.
-- Only the owner can write the row. A signed-in person can read the public card.
begin;
create extension if not exists pgtap with schema extensions;
select plan(31);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test');
update public.profiles
set tier = 'trusted', handle = 'alice', display_name = 'Alice'
where id = '11111111-1111-4111-8111-111111111111';
update public.profiles
set tier = 'regular', handle = 'bob', display_name = 'Bob'
where id = '22222222-2222-4222-8222-222222222222';

select ok(
  exists (
    select 1 from storage.buckets
    where id = 'avatars'
      and public
      and file_size_limit = 524288
      and allowed_mime_types @> array['image/jpeg', 'image/webp']::text[]
      and not (allowed_mime_types @> array['image/png']::text[])
  ),
  'avatars is a public 512 KB jpeg/webp bucket'
);

select ok(
  (select count(*) = 4
   from pg_policies
   where schemaname = 'storage'
     and policyname in (
       'avatars_select_public', 'avatars_insert_own', 'avatars_update_own', 'avatars_delete_own'
     )),
  'avatars has public read and owner write, update, and delete'
);

select ok(
  exists (
    select 1 from pg_policies
    where schemaname = 'storage'
      and policyname = 'avatars_select_public'
      and 'anon' = any (roles)
      and 'authenticated' = any (roles)
  ),
  'the public can read avatars'
);

select ok(
  has_column_privilege('authenticated', 'public.profiles', 'bio', 'update')
  and has_column_privilege('authenticated', 'public.profiles', 'website', 'update')
  and has_column_privilege('authenticated', 'public.profiles', 'home_area', 'update')
  and has_column_privilege('authenticated', 'public.profiles', 'avatar_path', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'tier', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'role', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'points', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'is_demo', 'update'),
  'the owner columns are bio, website, town, and picture; standing stays server-managed'
);

select ok(
  not has_function_privilege('anon', 'public.update_my_profile(text, text, text, text, text, text)', 'execute')
  and not has_function_privilege('anon', 'public.handle_available(text)', 'execute')
  and not has_function_privilege('anon', 'public.public_profile(text)', 'execute')
  and has_function_privilege('authenticated', 'public.update_my_profile(text, text, text, text, text, text)', 'execute')
  and has_function_privilege('authenticated', 'public.public_profile(text)', 'execute'),
  'profile edits and the public card require a signed-in person'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  $$ select public.update_my_profile(
    'Ada Lovelace', 'Ada_L', 'Counting engines.', 'London', 'https://example.com/ada',
    '11111111-1111-4111-8111-111111111111/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp'
  ) $$,
  'the owner can save a name, handle, bio, town, link, and picture'
);

select is(
  (select handle || '|' || display_name || '|' || bio || '|' || home_area || '|' || website
   from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  'ada_l|Ada Lovelace|Counting engines.|London|https://example.com/ada',
  'the handle is stored lowercase and the public fields are saved'
);

select ok(public.handle_available('ADA_L'), 'the owner''s own handle is available, in any case');
select ok(public.handle_available('fresh_name'), 'an unused handle is available');
select ok(not public.handle_available('bob'), 'someone else''s handle is not available');
select ok(not public.handle_available('Bo'), 'a short handle is not available');
select ok(not public.handle_available('has space'), 'a handle with a space is not available');

select throws_ok(
  $$ select public.update_my_profile('Ada', 'bob', 'x', null, null, null) $$,
  '23505', 'handle_taken', 'a taken handle is rejected'
);

select throws_ok(
  $$ select public.update_my_profile('Ada', 'ada_l', repeat('a', 161), null, null, null) $$,
  '22023', 'invalid_bio', 'a bio over 160 characters is rejected'
);

select throws_ok(
  $$ select public.update_my_profile('Ada', 'ada_l', 'ok', null, 'javascript:alert(1)', null) $$,
  '22023', 'invalid_website', 'a website must be an http or https link'
);

select throws_ok(
  $$ select public.update_my_profile(
    'Ada', 'ada_l', 'ok', null, null,
    '22222222-2222-4222-8222-222222222222/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp'
  ) $$,
  '22023', 'invalid_avatar', 'a picture path has to live in the owner''s folder'
);

select lives_ok(
  $$ select public.update_my_profile('Ada Lovelace', 'ada_l', '', '', '', '') $$,
  'blank optional fields clear the bio, town, link, and picture'
);
select is(
  (select bio is null and home_area is null and website is null and avatar_path is null
   from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  true,
  'the cleared fields are null'
);

select lives_ok(
  $$ select public.update_my_profile(
    'Ada Lovelace', 'ada_l', 'Still counting.', 'London', 'http://ada.example/notes', null
  ) $$,
  'the bio can be put back'
);

select lives_ok(
  $$ select public.set_my_public_name('Ada') $$,
  'the short name prompt still works'
);
select is(
  (select display_name || '|' || coalesce(bio, '') from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  'Ada|Still counting.',
  'picking a display name does not wipe the bio'
);

do $$
declare
  n integer;
begin
  update public.profiles set bio = 'hacked' where id = '22222222-2222-4222-8222-222222222222';
  get diagnostics n = row_count;
  perform set_config('t.other_bio', n::text, true);
end;
$$;
select is(current_setting('t.other_bio'), '0', 'row level security blocks edits to someone else');

select throws_ok(
  $$ update public.profiles set website = 'not a url' where id = auth.uid() $$,
  '23514', null, 'a direct update still has to be an http link'
);

select lives_ok(
  $$ select public.create_drop('historical_fact', 40.0, -100.0, 'Public mill', 'Open to visitors.') $$,
  'a public drop counts toward the profile'
);
select lives_ok(
  $$ select public.create_drop(
    'historical_fact', 40.0, -100.0, 'Quiet note', 'Hidden author.',
    p_display_anonymous => true
  ) $$,
  'an anonymous drop is posted'
);

reset role;
update public.profiles set is_demo = true where id = '11111111-1111-4111-8111-111111111111';

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select p.id::text from public.public_profile('ADA_L') p),
  '11111111-1111-4111-8111-111111111111',
  'the public card resolves a handle, case-insensitively, including a demo account'
);
select is(
  (select p.bio || '|' || p.home_area || '|' || p.website || '|' || p.tier::text || '|' || p.drop_count::text
   from public.public_profile('ada_l') p),
  'Still counting.|London|http://ada.example/notes|trusted|1',
  'the card has the bio, town, link, tier, and only the public drops'
);
select ok(
  (select not (to_jsonb(p) ? 'role') and not (to_jsonb(p) ? 'points') and (to_jsonb(p) -> 'badges') = '[]'::jsonb
   from public.public_profile('ada_l') p),
  'role and points stay off the public card'
);
select is(
  (select p.recent_drops -> 0 ->> 'title' from public.public_profile('ada_l') p),
  'Public mill',
  'recent drops list the public title'
);
select is_empty(
  $$ select 1 from public.public_profile('nobody_here') $$,
  'an unknown handle has no card'
);

set local role anon;
set local request.jwt.claims to '';
select throws_ok(
  $$ select 1 from public.public_profile('ada_l') $$,
  '42501',
  null,
  'a signed-out visitor cannot call the public card'
);

select * from finish();
rollback;
