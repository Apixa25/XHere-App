-- Location integrity (M12). Soft flags only: no automatic suspension,
-- no stored trail, and a suspicious action does not earn credit.
begin;
create extension if not exists pgtap with schema extensions;
select plan(31);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('55555555-5555-4555-8555-555555555555', 'mod@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', handle = 'bob' where id = '22222222-2222-4222-8222-222222222222';
update public.profiles set tier = 'trusted', handle = 'mod', role = 'admin' where id = '55555555-5555-4555-8555-555555555555';

select ok(
  (select c.relrowsecurity from pg_class c where c.oid = 'public.location_integrity_flags'::regclass),
  'location flags have row level security'
);
select is(
  (select count(*)::integer from information_schema.columns
   where table_schema = 'public' and table_name = 'location_integrity_flags'
     and udt_name in ('geography', 'geometry')),
  0,
  'flags store reason codes, not coordinates'
);
select ok(
  not has_table_privilege('anon', 'public.location_integrity_flags', 'select')
  and not has_table_privilege('authenticated', 'public.location_integrity_flags', 'insert')
  and not has_table_privilege('authenticated', 'private.location_checkpoints', 'select'),
  'visitors cannot read flags, clients cannot insert them, and the checkpoint is private'
);
select ok(
  not has_function_privilege('anon', 'public.location_integrity_queue(integer)', 'execute')
  and not has_function_privilege('anon', 'public.counts_for_location_credit(text, uuid, uuid)', 'execute'),
  'signed-out visitors cannot read the integrity queue'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select ok(
  (public.create_drop(
      'note', 40.0, -100.0, 'Dev options', 'The plaque is still on the bench.',
      p_integrity => '{"developer_options": true}'::jsonb
   ) ->> 'counts_for_credit')::boolean
  and not exists (select 1 from public.location_integrity_flags where user_id = '11111111-1111-4111-8111-111111111111'),
  'developer options alone still count and do not raise a flag'
);

select is(
  (public.create_drop(
      'note', 40.0, -100.0, 'Mocked bench',
      p_integrity => '{"mock": true, "developer_options": true}'::jsonb
   ) ->> 'location_trust'),
  'reduced',
  'a mock fix is stored with reduced trust'
);
select ok(
  (select not counts_for_credit and location_trust = 'reduced' from public.drops where title = 'Mocked bench'),
  'a mocked drop does not count toward quests or badges'
);
select ok(
  (select reasons = array['mock_location', 'developer_options']::text[]
   from public.location_integrity_flags
   where user_id = '11111111-1111-4111-8111-111111111111' and action = 'create_drop'),
  'the flag records mock location and developer options, without a point'
);
select is(
  (select count(*)::integer from public.user_moderation where user_id = '11111111-1111-4111-8111-111111111111' and suspended_until > now()),
  0,
  'a soft flag does not suspend the account'
);

insert into public.regions (id, name, country, min_lng, min_lat, max_lng, max_lat, timezone)
values ('zz-integrity', 'Integrity box', 'ZZ', -101, 39, -99, 41, 'UTC');
select ok(
  (select private.drop_counts_in_region(d, r, null, null)
   from public.drops d
   cross join public.regions r
   where d.title = 'Dev options' and r.id = 'zz-integrity')
  and not (select private.drop_counts_in_region(d, r, null, null)
   from public.drops d
   cross join public.regions r
   where d.title = 'Mocked bench' and r.id = 'zz-integrity'),
  'a reduced-trust drop does not count toward founding or campaigns'
);

select lives_ok(
  $$ select public.create_drop('note', 41.0, -100.0, 'Pinned', p_integrity => '{"device_fix": false}'::jsonb) $$,
  'a map pin is accepted'
);
select is(
  (select round(extensions.st_y(c.geog::extensions.geometry)::numeric, 1)
   from private.location_checkpoints c
   where c.user_id = '11111111-1111-4111-8111-111111111111'),
  40.0,
  'a map pin does not become the travel baseline'
);

update private.location_checkpoints
set observed_at = now() - interval '5 seconds', expires_at = now() + interval '2 hours'
where user_id = '11111111-1111-4111-8111-111111111111';
select ok(
  (public.create_drop('note', 45.0, -100.0, 'Too soon') ->> 'counts_for_credit')::boolean,
  'a far fix inside five seconds is not impossible travel'
);

update private.location_checkpoints
set observed_at = now() - interval '1 minute', expires_at = now() + interval '2 hours'
where user_id = '11111111-1111-4111-8111-111111111111';
-- create_drop is volatile; call it once, then assert on the stored row.
do $$ begin
  perform public.create_drop('note', 50.0, -100.0, 'Too fast');
end $$;
select ok(
  not (select counts_for_credit from public.drops where title = 'Too fast')
  and exists (
    select 1 from public.location_integrity_flags
    where action = 'create_drop' and 'impossible_travel' = any (reasons)
  ),
  'a far fix a minute later is impossible travel and does not count'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  (public.confirm_drop(
      (select id from public.drops where title = 'Dev options'),
      'still_true', 40.0, -100.0, null,
      '{"accuracy_m": 5000}'::jsonb
   ) ->> 'counts_for_credit')::boolean,
  false,
  'a confirmation with a huge accuracy radius does not count'
);
select is(
  (select confirm_count from public.drops where title = 'Dev options'),
  0,
  'a flagged confirmation does not move the confirmation bar'
);
select ok(
  (public.confirm_drop(
      (select id from public.drops where title = 'Dev options'),
      'still_true', 40.0, -100.0
   ) ->> 'counts_for_credit')::boolean,
  'a later real confirmation still counts'
);
select ok(
  not (public.confirm_drop(
      (select id from public.drops where title = 'Dev options'),
      'not_true', 40.0, -100.0, null,
      '{"mock": true}'::jsonb
   ) ->> 'counts_for_credit')::boolean
  and (select verdict::text from public.confirmations
       where user_id = '22222222-2222-4222-8222-222222222222'
         and drop_id = (select id from public.drops where title = 'Dev options')) = 'still_true',
  'a later spoof does not erase a confirmation that already counted'
);
select ok(
  (public.confirm_drop(
      (select id from public.drops where title = 'Pinned'),
      'still_true', 41.0, -100.0, null,
      '{"device_at": "2020-01-01T00:00:00Z"}'::jsonb
   ) -> 'reasons') ? 'clock_skew',
  'a device clock an hour off is clock skew'
);

select is(
  (public.drop_detail(
      (select id from public.drops where title = 'Dev options'),
      40.0, -100.0,
      '{"mock": true}'::jsonb
   )).location_withheld,
  true,
  'a mocked reveal withholds the body'
);
select ok(
  (public.drop_detail(
      (select id from public.drops where title = 'Dev options'),
      40.0, -100.0
   )).body is not null,
  'a real reveal still returns the body'
);

select ok(
  (select (public.drop_detail(d.id, extensions.st_y(d.display_geog::extensions.geometry), extensions.st_x(d.display_geog::extensions.geometry), '{"mock": true}'::jsonb)).revealed
   from public.drops d where d.source = 'open_data' limit 1),
  'a public record stays readable when the fix is mocked'
);

select is(
  (public.check_in(40.0, -100.0, null, '{"mock": true}'::jsonb) ->> 'visits_added')::integer,
  0,
  'a mocked check-in does not add a place visit'
);

do $$ begin
  perform public.create_drop(
    'note', 40.0, -100.0, 'Photo here', null, '{}'::jsonb, '{}', null, null, false,
    jsonb_build_array(jsonb_build_object(
      'storage_path', '22222222-2222-4222-8222-222222222222/here.jpg',
      'media_kind', 'image',
      'mime_type', 'image/jpeg',
      'capture_lat', 40,
      'capture_lng', -100,
      'taken_here', false
    )),
    false, null
  );
end $$;
select ok(
  (select m.taken_here from public.drop_media m
   join public.drops d on d.id = m.drop_id
   where d.title = 'Photo here'),
  'a photo taken at the drop is marked taken here, and the capture point is not a column'
);
select is(
  (select count(*)::integer from information_schema.columns
   where table_schema = 'public' and table_name = 'drop_media'
     and column_name in ('capture_lat', 'capture_lng')),
  0,
  'capture coordinates are not stored'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
set local role authenticated;
select is(
  (select count(*)::integer from public.location_integrity_flags),
  (select count(*)::integer from public.location_integrity_flags where user_id = auth.uid()),
  'a person can read their own flags and not someone else''s'
);
reset role;

set local request.jwt.claims to '{"sub": "55555555-5555-4555-8555-555555555555", "role": "authenticated"}';
select ok(
  (select count(*) > 0 from public.location_integrity_queue(20) q where q.handle = 'alice'),
  'a moderator can see recent flags, with a handle and no coordinates'
);
select is(
  public.counts_for_location_credit(
    'create_drop',
    '11111111-1111-4111-8111-111111111111',
    (select id from public.drops where title = 'Dev options')
  ),
  true,
  'badges can ask whether a drop counted'
);
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  $$ select public.counts_for_location_credit('create_drop', '11111111-1111-4111-8111-111111111111', '11111111-1111-4111-8111-111111111111') $$,
  '42501', 'not_allowed', 'one person cannot ask about another person''s credit'
);

update public.location_integrity_flags set expires_at = now() - interval '1 minute';
update private.location_checkpoints set expires_at = now() - interval '1 minute';
select cmp_ok(private.purge_location_integrity(), '>', 0, 'expired flags and checkpoints are deleted');
select is(
  (select count(*)::integer from public.location_integrity_flags),
  0,
  'purge leaves no flag rows behind'
);

select * from finish();
rollback;
