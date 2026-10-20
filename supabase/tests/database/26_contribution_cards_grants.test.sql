-- contribution_cards is select-only and runs as the caller.
-- The author id stays in the private reader. Writes are denied.
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

select ok(
  exists (
    select 1
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relname = 'contribution_cards'
      and c.relkind = 'v'
      and c.reloptions @> array['security_invoker=true']
  ),
  'contribution_cards runs as the querying role'
);
select ok(
  not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'contribution_cards' and column_name = 'author_id'
  ),
  'the card view still has no author id'
);
select ok(
  has_table_privilege('anon', 'public.contribution_cards', 'SELECT')
  and has_table_privilege('authenticated', 'public.contribution_cards', 'SELECT')
  and has_table_privilege('service_role', 'public.contribution_cards', 'SELECT')
  and not has_table_privilege('public', 'public.contribution_cards', 'SELECT')
  and not has_table_privilege('anon', 'public.contribution_cards', 'INSERT')
  and not has_table_privilege('anon', 'public.contribution_cards', 'UPDATE')
  and not has_table_privilege('anon', 'public.contribution_cards', 'DELETE')
  and not has_table_privilege('authenticated', 'public.contribution_cards', 'INSERT')
  and not has_table_privilege('authenticated', 'public.contribution_cards', 'UPDATE')
  and not has_table_privilege('authenticated', 'public.contribution_cards', 'DELETE')
  and not has_table_privilege('service_role', 'public.contribution_cards', 'INSERT')
  and not has_table_privilege('public', 'public.contribution_cards', 'INSERT')
  and not has_table_privilege('public', 'public.contribution_cards', 'UPDATE')
  and not has_table_privilege('public', 'public.contribution_cards', 'DELETE'),
  'anon, authenticated, and service_role may only select contribution cards'
);
select ok(
  (select p.prosecdef
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'private' and p.proname = 'contribution_card_rows')
  and not ('author_id' = any (coalesce(
    (select p.proargnames
     from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'private' and p.proname = 'contribution_card_rows'),
    array[]::text[]
  )))
  and has_function_privilege('anon', 'private.contribution_card_rows()', 'execute')
  and has_function_privilege('authenticated', 'private.contribution_card_rows()', 'execute')
  and not has_function_privilege('public', 'private.contribution_card_rows()', 'execute'),
  'the card reader is private, security definer, and has no author id output'
);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice.cards@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob.cards@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'mo.cards@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', handle = 'bob' where id = '22222222-2222-4222-8222-222222222222';
update public.profiles set tier = 'trusted', role = 'moderator', handle = 'mod' where id = '44444444-4444-4444-8444-444444444444';

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  $$ select public.create_drop('historical_fact', 42.15, -79.25, 'Birch', 'By the path.') $$,
  'a place for a card'
);

do $$
begin
  perform set_config('t.drop', (select id::text from public.drops where title = 'Birch'), true);
  perform public.create_contribution(
    'drop', current_setting('t.drop')::uuid, 'text', 'A quiet birch note', true
  );
  perform set_config(
    't.note',
    (select id::text from public.contributions where body = 'A quiet birch note'),
    true
  );
end;
$$;

select is(
  (select author_handle from public.contribution_cards where id = current_setting('t.note')::uuid),
  'Anonymous',
  'the author sees Anonymous on their own card'
);
select is(
  (select is_mine from public.contribution_cards where id = current_setting('t.note')::uuid),
  true,
  'the author can tell the card is theirs without an author id'
);

reset role;
set local role anon;
set local request.jwt.claims to '{}';

select is(
  (select author_handle from public.contribution_cards where id = current_setting('t.note')::uuid),
  'Anonymous',
  'a signed-out reader sees Anonymous'
);
select is(
  (select is_mine from public.contribution_cards where id = current_setting('t.note')::uuid),
  false,
  'a signed-out reader is not the author'
);

reset role;
set local role anon;

do $$
declare
  v_cmd text;
  v_failed text := '';
begin
  foreach v_cmd in array array[
    'insert into public.contribution_cards (body) values (''nope'')',
    'update public.contribution_cards set body = ''nope''',
    'delete from public.contribution_cards'
  ] loop
    begin
      execute v_cmd;
      v_failed := v_failed || 'anon write succeeded; ';
    exception
      when insufficient_privilege or object_not_in_prerequisite_state then
        null;
      when others then
        v_failed := v_failed || 'anon ' || sqlstate || ' ' || sqlerrm || '; ';
    end;
  end loop;
  perform set_config('t.card_writes_anon', coalesce(nullif(v_failed, ''), 'none'), true);
end;
$$;

reset role;
set local role authenticated;

do $$
declare
  v_cmd text;
  v_failed text := '';
begin
  foreach v_cmd in array array[
    'insert into public.contribution_cards (body) values (''nope'')',
    'update public.contribution_cards set body = ''nope''',
    'delete from public.contribution_cards'
  ] loop
    begin
      execute v_cmd;
      v_failed := v_failed || 'authenticated write succeeded; ';
    exception
      when insufficient_privilege or object_not_in_prerequisite_state then
        null;
      when others then
        v_failed := v_failed || 'authenticated ' || sqlstate || ' ' || sqlerrm || '; ';
    end;
  end loop;
  perform set_config('t.card_writes_auth', coalesce(nullif(v_failed, ''), 'none'), true);
end;
$$;

reset role;

select is(
  current_setting('t.card_writes_anon') || ' ' || current_setting('t.card_writes_auth'),
  'none none',
  'anon and authenticated cannot insert, update, or delete cards'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select author_handle from public.contribution_cards where id = current_setting('t.note')::uuid),
  'Anonymous',
  'a neighbor sees Anonymous'
);
select is(
  (select is_mine from public.contribution_cards where id = current_setting('t.note')::uuid),
  false,
  'the card is not the neighbor''s'
);
select throws_ok(
  $$ select author_id from public.contributions $$,
  '42501',
  'permission denied for table contributions',
  'the neighbor still cannot read the author column'
);

reset role;
set local role authenticated;
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';

select is(
  public.contribution_author(current_setting('t.note')::uuid),
  '11111111-1111-4111-8111-111111111111'::uuid,
  'a moderator can still see who wrote the anonymous card'
);

reset role;

update public.contributions
set status = 'hidden'
where id = current_setting('t.note')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select is(
  (select count(*)::int from public.contribution_cards where id = current_setting('t.note')::uuid),
  0,
  'a neighbor does not see a hidden card'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select is(
  (select count(*)::int from public.contribution_cards where id = current_setting('t.note')::uuid),
  1,
  'the author still sees their hidden card'
);

reset role;

update public.contributions
set status = 'removed'
where id = current_setting('t.note')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select is(
  (select count(*)::int from public.contribution_cards where id = current_setting('t.note')::uuid),
  0,
  'a removed card is hidden from the author too'
);

select * from finish();
rollback;
