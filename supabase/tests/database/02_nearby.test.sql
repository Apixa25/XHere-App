-- Read paths: drops_nearby, drop_detail, drops_in_view, drop_teaser.
-- Reveal radius, expiry, blocks, moderation, and deleted accounts.
-- At latitude 40: 0.0009 deg lat ~ 100 m, 0.002 deg lng ~ 171 m.
begin;
create extension if not exists pgtap with schema extensions;
select plan(40);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'mo@example.test'),
  ('66666666-6666-4666-8666-666666666666', 'dave@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'regular' where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set tier = 'trusted', role = 'moderator' where id = '44444444-4444-4444-8444-444444444444';

set local role authenticated;

-- alice: a historical fact (150 m reveal, with a photo) and a hidden gem 171 m west (50 m reveal)
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config('t.hist', public.create_drop(
    'historical_fact', 40.0, -100.0, 'Old mill', 'Burned down in 1890.', '{"year": 1890}',
    p_media => '[{"storage_path": "11111111-1111-4111-8111-111111111111/mill.jpg", "media_kind": "image", "mime_type": "image/jpeg", "width": 800, "height": 600}]'
  ) ->> 'id', true);
  perform set_config('t.gem', public.create_drop(
    'hidden_gem', 40.0, -100.002, 'Secret garden', 'Behind the blue door.', '{"best_time": "dusk"}'
  ) ->> 'id', true);
end;
$$;

-- bob stands ~100 m north of the mill
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is((select count(*)::integer from public.drops_nearby(40.0009, -100.0, 1000)), 2, 'both drops are in range as teasers');
select is(
  (select id from public.drops_nearby(40.0009, -100.0, 1000) limit 1),
  current_setting('t.hist')::uuid,
  'results are ordered by distance'
);
select ok(
  (select revealed and body = 'Burned down in 1890.' and attributes ->> 'year' = '1890'
   from public.drops_nearby(40.0009, -100.0, 1000) where id = current_setting('t.hist')::uuid),
  'inside the reveal radius the body and attributes are returned'
);
select is(
  (select jsonb_array_length(media) from public.drops_nearby(40.0009, -100.0, 1000) where id = current_setting('t.hist')::uuid),
  1,
  'revealed drops include their media refs'
);
select ok(
  (select distance_m between 95 and 105 from public.drops_nearby(40.0009, -100.0, 1000) where id = current_setting('t.hist')::uuid),
  'distance_m is the distance to the caller in meters'
);
select ok(
  (select not revealed and body is null and attributes is null and media is null and title = 'Secret garden'
   from public.drops_nearby(40.0009, -100.0, 1000) where id = current_setting('t.gem')::uuid),
  'outside the reveal radius only the teaser (title, category, position) is returned'
);
select ok(
  (select not revealed and body is null from public.drops_nearby(40.0018, -100.0, 1000) where id = current_setting('t.hist')::uuid),
  'walking 200 m away hides the body again'
);
select is((select count(*)::integer from public.drops_nearby(40.0009, -100.0, 50)), 0, 'p_radius_m limits the search area');
select is(
  (select array_agg(category_slug) from public.drops_nearby(40.0009, -100.0, 1000, '{local_secrets}')),
  array['hidden_gem'],
  'filtering by group slug'
);
select is(
  (select array_agg(category_slug) from public.drops_nearby(40.0009, -100.0, 1000, '{historical_fact}')),
  array['historical_fact'],
  'filtering by category slug'
);
select is(
  (select author_handle from public.drops_nearby(40.0009, -100.0, 1000) where id = current_setting('t.hist')::uuid),
  'alice',
  'teasers name the author'
);
select throws_ok($$ select * from public.drops_nearby(40.0, -100.0, 0) $$, '22023', 'invalid_radius', 'radius must be positive');
select throws_ok($$ select * from public.drops_nearby(95.0, -100.0, 100) $$, '22023', 'invalid_location', 'latitude must be valid');

-- Authors always see their own drop in full
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select ok(
  (select revealed and is_mine from public.drops_nearby(40.02, -100.0, 5000) where id = current_setting('t.hist')::uuid),
  'authors see their own drops revealed from anywhere'
);
do $$
begin
  perform set_config('t.anon_story', public.create_drop(
    'story', 40.0, -100.0003, 'First kiss', 'Right here, 1972.', p_display_anonymous => true) ->> 'id', true);
  perform set_config('t.note', public.create_drop('note', 40.0, -100.001, 'Bench', 'Shady after 2 pm.') ->> 'id', true);
end;
$$;

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select author_handle is null from public.drops_nearby(40.0009, -100.0, 1000) where id = current_setting('t.anon_story')::uuid),
  'anonymous display hides the author handle'
);

-- Expiry: hidden as soon as expires_at passes, even before the cron job runs
select ok(exists (select 1 from public.drops_nearby(40.0009, -100.0, 1000) where id = current_setting('t.note')::uuid), 'a fresh note is visible');
reset role;
update public.drops set expires_at = now() - interval '1 second' where id = current_setting('t.note')::uuid;
set local role authenticated;
select ok(not exists (select 1 from public.drops_nearby(40.0009, -100.0, 1000) where id = current_setting('t.note')::uuid), 'an expired drop is hidden');
reset role;
select ok(private.expire_drops() >= 1, 'the expiry job marks expired drops');
select is((select status::text from public.drops where id = current_setting('t.note')::uuid), 'expired', 'the drop status becomes expired');
set local role authenticated;

-- Blocks, both directions
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
do $$
begin
  perform set_config('t.carol_art', public.create_drop('street_art', 40.0, -100.0005, 'Mural', 'Big blue whale.') ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
do $$
begin
  perform set_config('t.bob_story', public.create_drop('story', 40.0001, -100.0, 'My first job', 'Paper route.') ->> 'id', true);
end;
$$;
select ok(exists (select 1 from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.carol_art')::uuid), 'bob sees carol''s drop');
select lives_ok($$ select public.block_user('33333333-3333-4333-8333-333333333333') $$, 'bob blocks carol');
select ok(not exists (select 1 from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.carol_art')::uuid), 'a blocker no longer sees the blocked user''s drops');
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select ok(not exists (select 1 from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.bob_story')::uuid), 'the blocked user no longer sees the blocker''s drops');
select throws_ok(
  format($$ select public.drop_detail(%L, 40.0, -100.0) $$, current_setting('t.bob_story')),
  'P0002', 'drop_not_found', 'drop_detail also respects blocks'
);
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok($$ select public.unblock_user('33333333-3333-4333-8333-333333333333') $$, 'bob unblocks carol');
select ok(exists (select 1 from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.carol_art')::uuid), 'unblocking restores visibility');

-- Moderation state: pending drops are hidden from everyone but the author until approved
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
do $$
begin
  perform set_config('t.pending', public.create_drop(
    'incident_report', 40.0002, -100.0002, 'Bike stolen', 'Lock was cut at the rack.',
    jsonb_build_object('occurred_at', to_jsonb(now() - interval '1 hour')), p_acknowledge_guidelines => true) ->> 'id', true);
end;
$$;
select is(
  (select status::text from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.pending')::uuid),
  'pending_review',
  'the author sees their pending drop'
);
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(not exists (select 1 from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.pending')::uuid), 'others do not see pending drops');
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select is(public.moderate_drop(current_setting('t.pending')::uuid, 'approve')::text, 'active', 'a moderator approves it');
select is(public.moderate_drop(current_setting('t.carol_art')::uuid, 'remove', 'test')::text, 'removed', 'a moderator removes another drop');
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(exists (select 1 from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.pending')::uuid), 'approved drops become visible');
select ok(not exists (select 1 from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.carol_art')::uuid), 'removed drops are hidden');

-- Deleted accounts: their drops disappear immediately
set local request.jwt.claims to '{"sub": "66666666-6666-4666-8666-666666666666", "role": "authenticated"}';
do $$
begin
  perform set_config('t.dave', public.create_drop('viewpoint', 40.0, -100.0, 'Sunset spot', 'Faces west.') ->> 'id', true);
  perform public.delete_my_account();
end;
$$;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(not exists (select 1 from public.drops_nearby(40.0, -100.0, 1000) where id = current_setting('t.dave')::uuid), 'drops from deleted accounts are hidden');

-- drop_detail
select ok(
  (select not revealed and body is null from public.drop_detail(current_setting('t.hist')::uuid, 40.0018, -100.0)),
  'drop_detail outside the radius returns a teaser'
);
select ok(
  (select revealed and body is not null from public.drop_detail(current_setting('t.hist')::uuid, 40.0009, -100.0)),
  'drop_detail inside the radius returns the body'
);
reset role;
select is((select view_count from public.drops where id = current_setting('t.hist')::uuid), 2::bigint, 'drop_detail counts views');
set local role authenticated;

-- Map viewport and share teasers
select ok(
  pg_get_function_result('public.drops_in_view(double precision,double precision,double precision,double precision,text[],integer)'::regprocedure) !~ '\m(body|attributes|media)\M',
  'drops_in_view cannot return body, attributes, or media'
);
select is(
  (select sum(point_count)::integer from public.drops_in_view(-100.01, 39.99, -99.99, 40.01, null, 5)),
  (select count(*)::integer from public.drops_in_view(-100.01, 39.99, -99.99, 40.01, null, 16)),
  'clusters at low zoom account for every visible drop'
);
select throws_ok(
  $$ select * from public.drops_in_view(-99.0, 40.0, -100.0, 41.0) $$,
  '22023', 'invalid_bbox', 'bounding boxes must be ordered'
);

reset role;
do $$
begin
  perform set_config('t.hist_short', (select short_id from public.drops where id = current_setting('t.hist')::uuid), true);
end;
$$;
set local role anon;
set local request.jwt.claims to '';
select ok(
  (select not revealed and body is null and title = 'Old mill'
   from public.drop_teaser(current_setting('t.hist_short'))),
  'share teasers never reveal the body'
);

select * from finish();
rollback;
