-- create_drop: validation against the category's field schema and rules.
begin;
create extension if not exists pgtap with schema extensions;
select plan(46);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('77777777-7777-4777-8777-777777777777', 'sue@example.test'),
  ('88888888-8888-4888-8888-888888888888', 'nina@example.test');
update public.profiles set tier = 'trusted' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'regular' where id in ('33333333-3333-4333-8333-333333333333', '77777777-7777-4777-8777-777777777777');
insert into public.user_moderation (user_id, suspended_until, reason)
values ('77777777-7777-4777-8777-777777777777', now() + interval '1 day', 'test');

set local role authenticated;

-- Who may post
set local request.jwt.claims to '';
select throws_ok($$ select public.create_drop('note', 40.0, -100.0, 'Hi') $$, '42501', 'not_authenticated', 'posting needs a signed-in user');
set local request.jwt.claims to '{"sub": "77777777-7777-4777-8777-777777777777", "role": "authenticated"}';
select throws_ok($$ select public.create_drop('note', 40.0, -100.0, 'Hi') $$, '42501', 'account_suspended', 'suspended users cannot post');
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok($$ select public.create_drop('notice_board', 40.0, -100.0, 'Lost cat') $$, '42501', 'tier_too_low', 'notices need Regular tier');

-- Basic inputs
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok($$ select public.create_drop('nope', 40.0, -100.0, 'Hi') $$, '22023', 'category_not_found', 'unknown categories are rejected');
select throws_ok($$ select public.create_drop('note', 91.0, -100.0, 'Hi') $$, '22023', 'invalid_location', 'latitude must be within -90..90');
select throws_ok($$ select public.create_drop('note', 40.0, -181.0, 'Hi') $$, '22023', 'invalid_location', 'longitude must be within -180..180');
select throws_ok($$ select public.create_drop('note', 40.0, -100.0, '   ') $$, '22023', 'invalid_title', 'titles cannot be blank');
select throws_ok($$ select public.create_drop('note', 40.0, -100.0, repeat('x', 121)) $$, '22023', 'invalid_title', 'titles are at most 120 characters');
select throws_ok($$ select public.create_drop('note', 40.0, -100.0, 'Hi', repeat('x', 2001)) $$, '22023', 'invalid_body', 'bodies are at most 2000 characters');

-- Attributes against the JSON Schema
select throws_ok(
  $$ select public.create_drop('event', 40.0, -100.0, 'Concert', null, jsonb_build_object('starts_at', to_jsonb(now() + interval '1 day'))) $$,
  '22023', 'invalid_attributes', 'events require ends_at'
);
select throws_ok(
  $$ select public.create_drop('event', 40.0, -100.0, 'Concert', null, '{"starts_at": 5, "ends_at": 6}') $$,
  '22023', 'invalid_attributes', 'attribute types are enforced'
);
select throws_ok(
  $$ select public.create_drop('event', 40.0, -100.0, 'Concert', null, '{"starts_at": "tomorrow", "ends_at": "later"}') $$,
  '22023', 'invalid_attributes', 'date-time formats are enforced'
);
select throws_ok(
  $$ select public.create_drop('historical_fact', 40.0, -100.0, 'Mill', null, '{"year": 1890, "secret_field": true}') $$,
  '22023', 'invalid_attributes', 'unknown attributes are rejected'
);
select throws_ok(
  $$ select public.create_drop('hazard', 40.0, -100.0, 'Pothole', null, jsonb_build_object('occurred_at', to_jsonb(now()), 'severity', 'apocalyptic')) $$,
  '22023', 'invalid_attributes', 'enum values are enforced'
);
select throws_ok(
  $$ select public.create_drop('historical_fact', 40.0, -100.0, 'Mill', null, '[1, 2]') $$,
  '22023', 'invalid_attributes', 'attributes must be an object'
);
select throws_ok(
  $$ select public.create_drop('event', 40.0, -100.0, 'Concert', null,
       jsonb_build_object('starts_at', to_jsonb(now() + interval '2 days'), 'ends_at', to_jsonb(now() + interval '1 day'))) $$,
  '22023', 'invalid_attributes', 'events cannot end before they start'
);
do $$
declare
  v_detail text;
begin
  perform public.create_drop('event', 40.0, -100.0, 'Concert', null, '{"starts_at": "2030-01-01T10:00:00Z"}');
exception when others then
  get stacked diagnostics v_detail = pg_exception_detail;
  perform set_config('t.detail', v_detail, true);
end;
$$;
select matches(current_setting('t.detail'), 'ends_at', 'validation errors name the failing field');

-- Lifetime rules
do $$
begin
  perform set_config('t.event', public.create_drop('event', 40.0, -100.0, 'Concert', null,
    jsonb_build_object('starts_at', to_jsonb(now() + interval '1 day'), 'ends_at', to_jsonb(now() + interval '1 day 3 hours'))) ->> 'expires_at', true);
  perform set_config('t.hist', public.create_drop('historical_fact', 40.0, -100.0, 'Old mill', 'Burned in 1890.', '{"year": 1890}') ::text, true);
  perform set_config('t.wildlife', public.create_drop('wildlife', 40.0, -100.0, 'Heron', null, '{"species": "Great blue heron"}') ->> 'expires_at', true);
  perform set_config('t.truck', public.create_drop('food_truck', 40.0, -100.0, 'Taco truck') ->> 'expires_at', true);
  perform set_config('t.construction', public.create_drop('construction', 40.0, -100.0, 'New library', null, '{"est_completion": "2027-06-01"}') ->> 'expires_at', true);
  perform set_config('t.short', public.create_drop('story', 40.0, -100.0, 'Short story', p_expires_at => now() + interval '1 day') ->> 'expires_at', true);
end;
$$;
select is(current_setting('t.event')::timestamptz, now() + interval '1 day 3 hours', 'events expire at ends_at');
select ok((current_setting('t.hist')::jsonb -> 'expires_at') = 'null'::jsonb, 'permanent categories do not expire');
select is(current_setting('t.wildlife')::timestamptz, now() + interval '7 days', 'wildlife sightings expire after 7 days');
select is(current_setting('t.truck')::timestamptz, now() + interval '6 hours', 'food trucks expire after 6 hours');
select is(current_setting('t.construction')::timestamptz, '2027-06-01'::timestamptz, 'construction expires at est_completion');
select is(current_setting('t.short')::timestamptz, now() + interval '1 day', 'authors may set a shorter lifetime');
select throws_ok(
  $$ select public.create_drop('story', 40.0, -100.0, 'Old news', p_expires_at => now() - interval '1 minute') $$,
  '22023', 'expires_in_past', 'expiry cannot be in the past'
);
select throws_ok(
  $$ select public.create_drop('wildlife', 40.0, -100.0, 'Heron', p_expires_at => now() + interval '30 days') $$,
  '22023', 'expires_too_far', 'expiry cannot exceed the category max_ttl'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  $$ select public.create_drop('event', 40.0, -100.0, 'Far-off fair', null,
       jsonb_build_object('starts_at', to_jsonb(now() + interval '59 days'), 'ends_at', to_jsonb(now() + interval '60 days'))) $$,
  '22023', 'expires_too_far', 'New users can post events at most 30 days out'
);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok(
  $$ select public.create_drop('event', 40.0, -100.0, 'Far-off fair', null,
       jsonb_build_object('starts_at', to_jsonb(now() + interval '59 days'), 'ends_at', to_jsonb(now() + interval '60 days'))) $$,
  'Regular users can post events further out'
);

-- Moderation mode: notes are reviewed first for New users only
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.create_drop('note', 40.0, -100.0, 'Nice bench') ->> 'status', 'pending_review', 'a New user''s note waits for review');
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(public.create_drop('note', 40.0, -100.0, 'Nice bench') ->> 'status', 'active', 'a Regular user''s note is live');

-- Sales: contact stays in the app, author always shown
select throws_ok(
  $$ select public.create_drop('yard_sale', 40.0, -100.0, 'Moving sale', 'Call 555-123-4567 or 555 987 6543') $$,
  '22023', 'content_not_allowed', 'phone numbers are blocked in sales'
);
select throws_ok(
  $$ select public.create_drop('yard_sale', 40.0, -100.0, 'Moving sale', 'Email me at seller@example.com') $$,
  '22023', 'content_not_allowed', 'email addresses are blocked in sales'
);
select throws_ok(
  $$ select public.create_drop('yard_sale', 40.0, -100.0, 'Moving sale', p_display_anonymous => true) $$,
  '22023', 'anonymous_not_allowed', 'sales cannot be posted anonymously'
);
select lives_ok(
  $$ select public.create_drop('yard_sale', 40.0, -100.0, 'Moving sale', 'Books from 1990-1999, all $2.',
       '{"price": 2, "currency": "USD", "contact_method": "in_app"}') $$,
  'ordinary sale text (with years and prices) is allowed'
);

-- Reveal radius: authors may tighten, never widen
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  $$ select public.create_drop('historical_fact', 40.0, -100.0, 'Mill', p_reveal_radius_m => 151) $$,
  '22023', 'invalid_reveal_radius', 'reveal radius cannot exceed the category max'
);
select throws_ok(
  $$ select public.create_drop('historical_fact', 40.0, -100.0, 'Mill', p_reveal_radius_m => 5) $$,
  '22023', 'invalid_reveal_radius', 'reveal radius has a 10 m floor'
);
select is((public.create_drop('historical_fact', 40.0, -100.0, 'Mill gate', p_reveal_radius_m => 60) ->> 'reveal_radius_m')::integer, 60, 'reveal radius can be tightened');

-- Tags
do $$
begin
  perform set_config('t.tagged', public.create_drop('story', 40.0, -100.0, 'Tagged', p_tags => '{History,history, Old-Mill }') ->> 'id', true);
end;
$$;
select is(
  (select d.tags from public.drops d where d.id = current_setting('t.tagged')::uuid),
  array['history', 'old-mill'],
  'tags are trimmed, lowercased, and de-duplicated'
);
select throws_ok($$ select public.create_drop('story', 40.0, -100.0, 'Tagged', p_tags => '{"bad tag!"}') $$, '22023', 'invalid_tags', 'tags have a restricted alphabet');
select throws_ok(
  $$ select public.create_drop('story', 40.0, -100.0, 'Tagged', p_tags => '{a,b,c,d,e,f,g,h,i,j,k}') $$,
  '22023', 'invalid_tags', 'at most 10 tags'
);

-- Media refs (any content type, files in the author's own Storage folder)
select throws_ok(
  $$ select public.create_drop('story', 40.0, -100.0, 'Clip', p_media => '[{"storage_path": "someone-else/a.mp4", "media_kind": "video", "mime_type": "video/mp4"}]') $$,
  '22023', 'invalid_media', 'media must live in the author''s folder'
);
select throws_ok(
  $$ select public.create_drop('story', 40.0, -100.0, 'Clip', p_media => '[{"storage_path": "11111111-1111-4111-8111-111111111111/a.mp4", "media_kind": "hologram", "mime_type": "video/mp4"}]') $$,
  '22023', 'invalid_media', 'media kinds are checked'
);
do $$
begin
  perform set_config('t.mixed', public.create_drop('story', 40.0, -100.0, 'Mixed media', p_media =>
    '[{"storage_path": "11111111-1111-4111-8111-111111111111/a.jpg", "media_kind": "image", "mime_type": "image/jpeg"},
      {"storage_path": "11111111-1111-4111-8111-111111111111/b.m4a", "media_kind": "audio", "mime_type": "audio/mp4", "duration_s": 12.5},
      {"storage_path": "11111111-1111-4111-8111-111111111111/c.pdf", "media_kind": "file", "mime_type": "application/pdf"}]') ->> 'id', true);
end;
$$;
select is(
  (select array_agg(m.media_kind::text || ':' || m.sort order by m.sort) from public.drop_media m
   where m.drop_id = current_setting('t.mixed')::uuid),
  array['image:0', 'audio:1', 'file:2'],
  'images, audio, and files are stored as ordered media refs'
);

-- Bookkeeping: category version, exact location for unblurred categories, duplicate hint
select ok(
  (select d.category_version = c.version and extensions.st_equals(d.geog::extensions.geometry, d.display_geog::extensions.geometry)
   from public.drops d join public.categories c on c.id = d.category_id
   where d.id = (current_setting('t.hist')::jsonb ->> 'id')::uuid),
  'drops record the category version, and unblurred categories keep the exact point'
);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select ok(
  (public.create_drop('historical_fact', 40.00005, -100.0, 'Old mill ruins') -> 'possible_duplicates')
    ? (current_setting('t.hist')::jsonb ->> 'short_id'),
  'similar drops nearby come back as a duplicate hint'
);

-- Rate limit: New tier allows 3 drops per hour
set local request.jwt.claims to '{"sub": "88888888-8888-4888-8888-888888888888", "role": "authenticated"}';
select lives_ok(
  $$ select public.create_drop('story', 40.0, -100.0, 'One'), public.create_drop('story', 40.0, -100.0, 'Two'), public.create_drop('story', 40.0, -100.0, 'Three') $$,
  'three drops in an hour are fine for a New user'
);
select throws_ok($$ select public.create_drop('story', 40.0, -100.0, 'Four') $$, 'PT429', 'rate_limited', 'the fourth is rate limited');

select * from finish();
rollback;
