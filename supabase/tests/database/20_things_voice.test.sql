-- Objects that speak. Owners edit. Everyone reads a public story.
-- Hearing is a counter. The quota table does not remember which tag was heard.
begin;
create extension if not exists pgtap with schema extensions;
select plan(35);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('10000000-0000-4000-8000-000000000001', 'r1@example.test'),
  ('10000000-0000-4000-8000-000000000002', 'r2@example.test'),
  ('10000000-0000-4000-8000-000000000003', 'r3@example.test'),
  ('10000000-0000-4000-8000-000000000004', 'r4@example.test'),
  ('10000000-0000-4000-8000-000000000005', 'r5@example.test');

select ok(
  (select c.relrowsecurity from pg_class c where c.oid = 'public.things'::regclass)
  and (select c.relrowsecurity from pg_class c where c.oid = 'public.ble_tags'::regclass)
  and (select c.relrowsecurity from pg_class c where c.oid = 'public.nfc_tags'::regclass)
  and (select c.relrowsecurity from pg_class c where c.oid = 'public.thing_reports'::regclass)
  and (select c.relrowsecurity from pg_class c where c.oid = 'private.thing_rpc_quota'::regclass),
  'things, tags, reports, and the quota table have row level security'
);
select ok(
  not has_column_privilege('anon', 'public.things', 'owner_id', 'SELECT')
  and not has_column_privilege('authenticated', 'public.things', 'owner_id', 'SELECT')
  and not has_column_privilege('anon', 'public.things', 'fixed_geog', 'SELECT')
  and has_column_privilege('anon', 'public.things', 'story', 'SELECT')
  and not has_table_privilege('anon', 'public.ble_tags', 'SELECT')
  and not has_table_privilege('authenticated', 'private.thing_rpc_quota', 'SELECT'),
  'clients can read a public story and cannot read the owner, the raw point, or the tags'
);
select ok(
  not exists (
    select 1 from information_schema.columns
    where table_schema = 'private' and table_name = 'thing_rpc_quota'
      and column_name in ('thing_id', 'voice_id', 'adv_key', 'key', 'reporter_id')
  ),
  'the rate-limit row is a caller and a count, not a tag'
);
select ok(
  not has_function_privilege('anon', 'public.public_api_thing(text)', 'execute')
  and not has_function_privilege('authenticated', 'public.public_api_thing(text)', 'execute')
  and has_function_privilege('service_role', 'public.public_api_thing(text)', 'execute')
  and has_function_privilege('authenticated', 'public.resolve_things(text[])', 'execute')
  and not has_function_privilege('anon', 'public.resolve_things(text[])', 'execute'),
  'the open lookup is service_role only, and resolve is signed-in only'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select throws_ok(
  $$ select public.create_thing('Bench', 'A story.', 'incident_report') $$,
  '22023', 'category_not_allowed', 'a sensitive category cannot be an object voice'
);

do $$
declare
  v jsonb;
begin
  v := public.create_thing(
    p_title => 'Bench',
    p_story => 'It remembers the mural.',
    p_category_slug => 'note',
    p_place_name => 'The park',
    p_lat => 42.09504,
    p_lng => -79.24412
  );
  perform set_config('t.thing', v ->> 'id', true);
  perform set_config('t.voice', v ->> 'voice_id', true);
  perform set_config('t.quiet', public.create_thing('Quiet chair', 'Not on the air.', 'note', p_visibility => 'unlisted') ->> 'voice_id', true);
end;
$$;

select ok(current_setting('t.voice') ~ '^[0-9a-f]{12}$', 'the voice id is 12 hex characters, six bytes');
select is(
  (select title from public.things where id = current_setting('t.thing')::uuid),
  'Bench',
  'the owner can read the row just created'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (select count(*)::integer from public.things where voice_id = current_setting('t.voice')),
  1,
  'another person can read a public thing'
);
select is(
  (select count(*)::integer from public.things where voice_id = current_setting('t.quiet')),
  0,
  'an unlisted thing is not public'
);
do $$
declare
  n integer;
begin
  update public.things set title = 'Stolen' where id = current_setting('t.thing')::uuid;
  get diagnostics n = row_count;
  perform set_config('t.stolen', n::text, true);
end;
$$;
select is(current_setting('t.stolen'), '0', 'another person cannot edit the story');

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select lives_ok(
  $$ update public.things set title = 'Renamed bench' where id = current_setting('t.thing')::uuid $$,
  'the owner can rename the thing'
);
select throws_ok(
  format('update public.things set seen_count = 9 where id = %L::uuid', current_setting('t.thing')),
  '42501', null, 'the owner cannot write the heard counter'
);
select is(
  (select seen_count from public.things where id = current_setting('t.thing')::uuid),
  0::bigint,
  'renaming did not change the counter'
);

select is(
  (select public.resolve_things(array['nope', current_setting('t.quiet')]) -> 'things'),
  '[]'::jsonb,
  'resolve skips junk and unlisted things'
);
select is(
  (select seen_count from public.things where id = current_setting('t.thing')::uuid),
  0::bigint,
  'resolve does not count as a hearing'
);
select is(
  (select jsonb_array_length(public.resolve_things(array[current_setting('t.voice')]) -> 'things')),
  1,
  'resolve returns the public thing for its voice id'
);
select is(
  (public.hear_things(array['xhere:' || current_setting('t.voice')]) ->> 'counted')::integer,
  1,
  'hear counts one public thing'
);
select is(
  (select seen_count from public.things where id = current_setting('t.thing')::uuid),
  1::bigint,
  'the counter went up without a listener column'
);

select lives_ok(
  $$ select public.bind_ble_tag(current_setting('t.thing')::uuid, 'ibeacon:11111111-1111-4111-8111-111111111111:7:8') $$,
  'the owner can bind a beacon the phone heard'
);
select is(
  (select jsonb_array_length(public.resolve_things(array['ibeacon:11111111-1111-4111-8111-111111111111:7:8']) -> 'things')),
  1,
  'the bound iBeacon resolves to the same public thing'
);
select throws_ok(
  $$ select public.resolve_things('{}'::text[]) $$,
  '22023', 'invalid_keys', 'an empty key list is rejected'
);

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
do $$
begin
  for i in 1..15 loop
    perform public.resolve_things(array['aaaaaaaaaaaa']);
  end loop;
end;
$$;
select throws_ok(
  $$ select public.resolve_things(array['aaaaaaaaaaaa']) $$,
  'PT429', 'rate_limited', 'resolve allows 15 calls a minute'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  format('select public.bind_ble_tag(%L::uuid, ''ibeacon:11111111-1111-4111-8111-111111111111:1:1'')', current_setting('t.thing')),
  '42501', 'not_owner', 'only the owner binds a beacon'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  format('select public.report_thing(%L::uuid, ''spam'')', current_setting('t.thing')),
  '22023', 'cannot_report_self', 'the owner cannot flag their own object'
);

set local request.jwt.claims to '{"sub": "10000000-0000-4000-8000-000000000001", "role": "authenticated"}';
select lives_ok(format('select public.report_thing(%L::uuid, ''spam'')', current_setting('t.thing')), 'flag 1');
set local request.jwt.claims to '{"sub": "10000000-0000-4000-8000-000000000002", "role": "authenticated"}';
select lives_ok(format('select public.report_thing(%L::uuid, ''harassment'')', current_setting('t.thing')), 'flag 2');
set local request.jwt.claims to '{"sub": "10000000-0000-4000-8000-000000000003", "role": "authenticated"}';
select lives_ok(format('select public.report_thing(%L::uuid, ''hate'')', current_setting('t.thing')), 'flag 3');
set local request.jwt.claims to '{"sub": "10000000-0000-4000-8000-000000000004", "role": "authenticated"}';
select lives_ok(format('select public.report_thing(%L::uuid, ''other'')', current_setting('t.thing')), 'flag 4');
set local request.jwt.claims to '{"sub": "10000000-0000-4000-8000-000000000005", "role": "authenticated"}';
select lives_ok(format('select public.report_thing(%L::uuid, ''misinformation'')', current_setting('t.thing')), 'flag 5');
select is(
  (select status from public.things where id = current_setting('t.thing')::uuid),
  'removed',
  'five distinct flags remove the object from public reads'
);

reset role;
set local role service_role;
select is_null(
  public.public_api_thing(current_setting('t.quiet')) ->> 'title',
  'the open lookup hides an unlisted thing'
);
select is_null(public.public_api_thing(current_setting('t.voice')), 'a removed thing is not on the open lookup');

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
declare
  v jsonb;
begin
  v := public.create_thing('Mural', 'The wall tells it.', 'street_art', p_lat => 42.1, p_lng => -79.2);
  perform set_config('t.mural', v ->> 'voice_id', true);
end;
$$;

reset role;
set local role service_role;
select is(public.public_api_thing(current_setting('t.mural')) ->> 'title', 'Mural', 'the open lookup returns the story');
select ok(
  not (public.public_api_thing(current_setting('t.mural')) ? 'owner_id')
  and public.public_api_thing(current_setting('t.mural')) ->> 'license' = 'xhere-thing'
  and public.public_api_thing(current_setting('t.mural')) ->> 'story' = 'The wall tells it.',
  'the open lookup has the story and the license, and not the owner'
);
select throws_ok(
  $$ select public.public_api_thing('not-a-voice') $$,
  '22023', 'invalid_id', 'a bad id is rejected'
);

select * from finish();
rollback;
