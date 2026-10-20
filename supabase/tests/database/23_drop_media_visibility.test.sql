-- A private drop photo is readable by any signed-in person while the drop
-- is public and active. The owner is the only reader once it is hidden,
-- deleted, or still sealed inside a time capsule.
begin;
create extension if not exists pgtap with schema extensions;
select plan(22);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', handle = 'bob' where id = '22222222-2222-4222-8222-222222222222';
update public.profiles set tier = 'trusted', handle = 'cara' where id = '33333333-3333-4333-8333-333333333333';

select ok(
  exists (
    select 1 from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname = 'drop_media_select_visible'
      and qual like '%drop-media%'
  ),
  'drop-media has a select policy that can sign a photo'
);
select ok(
  has_function_privilege('authenticated', 'private.can_read_drop_media(text,text)', 'execute')
  and not has_function_privilege('anon', 'private.can_read_drop_media(text,text)', 'execute'),
  'only a signed-in session can ask the media check'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  $$ select public.create_drop(
       'historical_fact', 40.0, -100.0, 'Bench', 'By the oak.',
       p_attributes => '{"era":"1900s"}'::jsonb,
       p_media => '[{
         "storage_path": "11111111-1111-4111-8111-111111111111/bench.jpg",
         "media_kind": "image",
         "mime_type": "image/jpeg",
         "alt_text": "A wooden bench"
       }]'::jsonb
     ) $$,
  'the author can attach a photo'
);

do $$
begin
  perform set_config(
    't.drop',
    (select id::text from public.drops where title = 'Bench'),
    true
  );
end;
$$;

reset role;

insert into storage.objects (bucket_id, name, owner, owner_id)
values (
  'drop-media',
  '11111111-1111-4111-8111-111111111111/bench.jpg',
  '11111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select private.can_read_drop_media('drop-media', '11111111-1111-4111-8111-111111111111/bench.jpg')),
  true,
  'any signed-in person may read an active public photo'
);
select isnt_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'drop-media'
       and name = '11111111-1111-4111-8111-111111111111/bench.jpg' $$,
  'storage select succeeds for that neighbor, which is what signing requires'
);

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';

select lives_ok(
  format('select public.hide_drop(%L::uuid)', current_setting('t.drop')),
  'a neighbor can hide the drop'
);
select is(
  (select private.can_read_drop_media('drop-media', '11111111-1111-4111-8111-111111111111/bench.jpg')),
  false,
  'a hidden drop photo is not readable by the person who hid it'
);
select is_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'drop-media'
       and name = '11111111-1111-4111-8111-111111111111/bench.jpg' $$,
  'signing fails for the person who hid the photo'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select private.can_read_drop_media('drop-media', '11111111-1111-4111-8111-111111111111/bench.jpg')),
  true,
  'someone else can still read a drop they did not hide'
);

reset role;

update public.drops
set status = 'pending_review'
where id = current_setting('t.drop')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select private.can_read_drop_media('drop-media', '11111111-1111-4111-8111-111111111111/bench.jpg')),
  false,
  'a drop waiting for review is not a public photo'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select is(
  (select private.can_read_drop_media('drop-media', '11111111-1111-4111-8111-111111111111/bench.jpg')),
  true,
  'the owner can still read a photo that is not public yet'
);

reset role;

update public.drops
set status = 'active',
    capsule_mode = 'date',
    capsule_unlock_at = now() + interval '30 days',
    capsule_opened_at = null
where id = current_setting('t.drop')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select private.can_read_drop_media('drop-media', '11111111-1111-4111-8111-111111111111/bench.jpg')),
  false,
  'sealed capsule media stays hidden from everyone else'
);
select is_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'drop-media'
       and name = '11111111-1111-4111-8111-111111111111/bench.jpg' $$,
  'a neighbor cannot sign a sealed capsule photo'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select is(
  (select private.can_read_drop_media('drop-media', '11111111-1111-4111-8111-111111111111/bench.jpg')),
  true,
  'the owner can still read their sealed photo'
);

reset role;

update public.drops
set capsule_mode = null,
    capsule_unlock_at = null,
    status = 'active'
where id = current_setting('t.drop')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  format('select public.delete_my_drop(%L::uuid)', current_setting('t.drop')),
  'the author can soft-delete the drop'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select private.can_read_drop_media('drop-media', '11111111-1111-4111-8111-111111111111/bench.jpg')),
  false,
  'a deleted drop photo is not public'
);
select is_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'drop-media'
       and name = '11111111-1111-4111-8111-111111111111/bench.jpg' $$,
  'a neighbor cannot sign a deleted drop photo'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select isnt_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'drop-media'
       and name = '11111111-1111-4111-8111-111111111111/bench.jpg' $$,
  'the owner can still sign their deleted photo'
);

select lives_ok(
  $$ select public.create_capsule(
       'Sealed photo',
       'the sealed photo story',
       40.0,
       -100.0,
       'date',
       now() + interval '30 days',
       false,
       '[{
         "storage_path": "11111111-1111-4111-8111-111111111111/sealed.jpg",
         "media_kind": "image",
         "mime_type": "image/jpeg"
       }]'::jsonb
     ) $$,
  'a capsule can keep a photo in the private secret'
);

reset role;

insert into storage.objects (bucket_id, name, owner, owner_id)
values (
  'drop-media',
  '11111111-1111-4111-8111-111111111111/sealed.jpg',
  '11111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111'
);

select is(
  (select count(*)::integer from public.drop_media m
    where m.storage_path = '11111111-1111-4111-8111-111111111111/sealed.jpg'),
  0,
  'a sealed capsule does not copy its photo onto the drop'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'drop-media'
       and name = '11111111-1111-4111-8111-111111111111/sealed.jpg' $$,
  'a sealed capsule file stays hidden until it opens'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select isnt_empty(
  $$ select 1 from storage.objects
     where bucket_id = 'drop-media'
       and name = '11111111-1111-4111-8111-111111111111/sealed.jpg' $$,
  'the owner is the only reader of a sealed capsule file'
);
