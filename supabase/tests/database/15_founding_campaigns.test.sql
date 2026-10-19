-- Founding Locals and a time-boxed map campaign.
-- The rules are global. Jamestown is the seeded pilot, not a special case.
begin;
create extension if not exists pgtap with schema extensions;
select plan(43);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'eve@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'frank@example.test'),
  ('55555555-5555-4555-8555-555555555555', 'carol@example.test'),
  ('66666666-6666-4666-8666-666666666666', 'dave@example.test'),
  ('77777777-7777-4777-8777-777777777777', 'blank@example.test');

update public.profiles set tier = 'trusted', handle = 'secret_handle_alice', display_name = 'Alice'
  where id = '11111111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', handle = 'bob', display_name = 'Bob'
  where id = '22222222-2222-4222-8222-222222222222';
update public.profiles set tier = 'trusted', handle = 'eve', display_name = 'Eve'
  where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set tier = 'trusted', handle = 'frank', display_name = 'Frank'
  where id = '44444444-4444-4444-8444-444444444444';
update public.profiles set tier = 'trusted', handle = 'carol', display_name = 'Carol'
  where id = '55555555-5555-4555-8555-555555555555';
update public.profiles set tier = 'trusted', handle = 'dave', display_name = 'Dave'
  where id = '66666666-6666-4666-8666-666666666666';
update public.profiles set tier = 'trusted', handle = 'blank_handle', display_name = null
  where id = '77777777-7777-4777-8777-777777777777';

select ok(
  (select bool_and(p.prosecdef) and count(*) = 4
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public'
     and p.proname in ('campaigns_for_map', 'my_campaigns', 'my_badges', 'set_leaderboard_visibility')
     and exists (
       select 1 from unnest(coalesce(p.proconfig, '{}')) c
       where c in ('search_path=', 'search_path=""')
     )),
  'the campaign RPCs are security definer and pin an empty search_path'
);

select ok(
  not has_function_privilege('public', 'private.refresh_founders(text)', 'execute')
  and not has_function_privilege('anon', 'private.refresh_founders(text)', 'execute')
  and not has_function_privilege('authenticated', 'private.campaign_json(uuid, uuid)', 'execute')
  and not has_function_privilege('anon', 'private.author_badges_json(uuid, extensions.geography)', 'execute'),
  'founding helpers are not callable by clients'
);

select ok(
  (select c.relrowsecurity
   from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relname = 'user_badges'),
  'user_badges has RLS and no client policy'
);

set local role anon;
select throws_ok(
  $$ select * from public.user_badges $$,
  '42501', null,
  'a signed-out visitor cannot read the award table'
);
select ok(
  has_table_privilege('anon', 'public.campaigns', 'select'),
  'the campaign catalog is readable'
);
reset role;

select is(
  (select goal_drops from public.campaigns where region_id = 'us-ny-jamestown' and slug = 'map-jamestown'),
  200,
  'the Jamestown pilot goal is config'
);
select is(
  (select cap from public.region_founding where region_id = 'us-ny-jamestown'),
  25,
  'the Jamestown founding cap is config'
);
select ok(
  (select ends_at = starts_at + interval '14 days'
      and now() >= starts_at and now() < ends_at
   from public.campaigns where slug = 'map-jamestown'),
  'the pilot clock starts when the seed is applied and runs 14 days'
);
select is(
  (select public.campaigns_for_map(-79.32, 42.05, -79.18, 42.14) -> 0 ->> 'slug'),
  'map-jamestown',
  'the Jamestown box returns the pilot campaign'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  $$ update public.profiles set show_on_leaderboard = false where id = auth.uid() $$,
  '42501', null,
  'the leaderboard flag is not a client-writable column'
);
select is(
  public.set_leaderboard_visibility(false),
  false,
  'the RPC is the way to opt out'
);
select is(
  (public.my_badges() ->> 'show_on_leaderboard')::boolean,
  false,
  'my_badges reports the switch'
);
select is(public.set_leaderboard_visibility(true), true, 'opting back in');
reset role;

insert into public.regions (id, name, country, min_lng, min_lat, max_lng, max_lat, timezone)
values ('zz-testbox', 'Test box', 'ZZ', 9, 9, 11, 11, 'UTC');
insert into public.region_founding (region_id, cap) values ('zz-testbox', 2);
insert into public.campaigns (region_id, slug, title, description, goal_drops, starts_at, ends_at, leaderboard_size)
values (
  'zz-testbox', 'map-testbox', 'Map the box', 'User drops only.', 5,
  now() - interval '1 hour', now() + interval '14 days', 2
);

create function public._test_plant(
  p_author uuid,
  p_title text,
  p_at timestamptz,
  p_status public.drop_status default 'active',
  p_source public.drop_source default 'user',
  p_lng double precision default 10,
  p_lat double precision default 10
) returns uuid
language plpgsql
set search_path = public, extensions
as $$
declare
  v_id uuid;
begin
  insert into public.drops (
    author_id, category_id, category_version, title, geog, display_geog,
    reveal_radius_m, status, source, created_at
  )
  select
    case when p_source = 'user' then p_author else null end,
    c.id, c.version, p_title,
    format('POINT(%s %s)', p_lng, p_lat)::extensions.geography,
    format('POINT(%s %s)', p_lng, p_lat)::extensions.geography,
    100, p_status, p_source, p_at
  from public.categories c
  where c.slug = 'note'
  returning id into v_id;
  return v_id;
end;
$$;

do $$ begin
  perform public._test_plant('11111111-1111-4111-8111-111111111111', 'Alice first', now() - interval '40 minutes');
  perform public._test_plant('22222222-2222-4222-8222-222222222222', 'Bob', now() - interval '30 minutes');
  perform public._test_plant('33333333-3333-4333-8333-333333333333', 'Eve', now() - interval '20 minutes');
end $$;

select is(
  (select array_agg(ub.user_id order by (ub.meta ->> 'rank')::integer)
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-testbox'),
  array['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222']::uuid[],
  'the first two people earn Founding Local'
);

do $$ begin
  perform public._test_plant('11111111-1111-4111-8111-111111111111', 'Alice again', now() - interval '19 minutes');
end $$;
select is(
  (select count(*)::integer from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-testbox'),
  2,
  'a second drop does not take another founding slot'
);
select is(
  (select (public.campaigns_for_map(9, 9, 11, 11) -> 0 ->> 'drop_count')::integer),
  4,
  'the campaign counts every qualifying drop inside the window'
);
select is(
  public.campaigns_for_map(9, 9, 11, 11) -> 0 -> 'leaderboard',
  '[{"display_name":"Alice","drops":2},{"display_name":"Bob","drops":1}]'::jsonb,
  'the board is display name and count'
);
select ok(
  position('secret_handle_alice' in public.campaigns_for_map(9, 9, 11, 11)::text) = 0,
  'a handle never appears on the board'
);

do $$ begin
  perform public._test_plant(null, 'Imported hall', now() - interval '18 minutes', 'active', 'open_data');
end $$;
select is(
  (select (public.campaigns_for_map(9, 9, 11, 11) -> 0 ->> 'drop_count')::integer),
  4,
  'an imported public record does not count'
);
do $$ begin
  perform public._test_plant('22222222-2222-4222-8222-222222222222', 'Bob far away', now() - interval '17 minutes', 'active', 'user', 50, 10);
end $$;
select is(
  (select (public.campaigns_for_map(9, 9, 11, 11) -> 0 ->> 'drop_count')::integer),
  4,
  'a drop outside the region box does not count'
);

do $$ begin
  perform public._test_plant('44444444-4444-4444-8444-444444444444', 'Frank waiting', now() - interval '35 minutes', 'pending_review');
end $$;
select is(
  (select array_agg(ub.user_id order by (ub.meta ->> 'rank')::integer)
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-testbox'),
  array['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222']::uuid[],
  'a drop waiting for review is not a founding drop yet'
);
update public.drops set status = 'active'
 where author_id = '44444444-4444-4444-8444-444444444444' and title = 'Frank waiting';
select is(
  (select array_agg(ub.user_id order by (ub.meta ->> 'rank')::integer)
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-testbox'),
  array['11111111-1111-4111-8111-111111111111', '44444444-4444-4444-8444-444444444444']::uuid[],
  'once it is public, the earlier person takes the slot'
);

update public.drops set status = 'removed'
 where author_id = '11111111-1111-4111-8111-111111111111';
select is(
  (select array_agg(ub.user_id order by (ub.meta ->> 'rank')::integer)
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-testbox'),
  array['44444444-4444-4444-8444-444444444444', '22222222-2222-4222-8222-222222222222']::uuid[],
  'losing the only qualifying drop gives the place to the next person'
);
select is(
  (select (public.campaigns_for_map(9, 9, 11, 11) -> 0 ->> 'drop_count')::integer),
  3,
  'removed drops leave the campaign total'
);

do $$ begin
  perform public._test_plant('55555555-5555-4555-8555-555555555555', 'Carol early', now() - interval '2 days');
end $$;
select is(
  (select array_agg(ub.user_id order by (ub.meta ->> 'rank')::integer)
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-testbox'),
  array['55555555-5555-4555-8555-555555555555', '44444444-4444-4444-8444-444444444444']::uuid[],
  'founding is all-time, so a drop from before the campaign still earns it'
);
select is(
  (select (public.campaigns_for_map(9, 9, 11, 11) -> 0 ->> 'drop_count')::integer),
  3,
  'that same drop is outside the campaign window'
);

do $$ begin
  perform public._test_plant(
    '66666666-6666-4666-8666-666666666666',
    'Dave at the bell',
    (select ends_at from public.campaigns where slug = 'map-testbox')
  );
end $$;
select is(
  (select (public.campaigns_for_map(9, 9, 11, 11) -> 0 ->> 'drop_count')::integer),
  3,
  'a drop at the end instant does not count'
);
select is(
  (select count(*)::integer from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-testbox'
     and ub.user_id = '66666666-6666-4666-8666-666666666666'),
  0,
  'and it does not bump someone out of the first two'
);

do $$ begin
  perform public._test_plant('77777777-7777-4777-8777-777777777777', 'Blank one', now() - interval '10 minutes');
  perform public._test_plant('77777777-7777-4777-8777-777777777777', 'Blank two', now() - interval '9 minutes');
  perform public._test_plant('77777777-7777-4777-8777-777777777777', 'Blank three', now() - interval '8 minutes');
end $$;
select is(
  (select (public.campaigns_for_map(9, 9, 11, 11) -> 0 ->> 'drop_count')::integer),
  6,
  'a blank display name still adds to the total'
);
select is(
  public.campaigns_for_map(9, 9, 11, 11) -> 0 -> 'leaderboard',
  '[{"display_name":"Bob","drops":1},{"display_name":"Eve","drops":1}]'::jsonb,
  'the board skips a blank name and keeps the size limit'
);
select ok(
  position('blank_handle' in public.campaigns_for_map(9, 9, 11, 11)::text) = 0,
  'the skipped person is not identified'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.set_leaderboard_visibility(false), false, 'opting out from the board');
select is(
  (select (elem ->> 'my_drops')::integer
   from jsonb_array_elements(public.my_campaigns()) elem
   where elem ->> 'slug' = 'map-testbox'),
  1,
  'opting out keeps the person''s own count'
);
select is(
  (select (elem ->> 'show_on_leaderboard')::boolean
   from jsonb_array_elements(public.my_campaigns()) elem
   where elem ->> 'slug' = 'map-testbox'),
  false,
  'and the card says the name is hidden'
);
select is(
  (select elem -> 'leaderboard'
   from jsonb_array_elements(public.my_campaigns()) elem
   where elem ->> 'slug' = 'map-testbox'),
  '[{"display_name":"Eve","drops":1},{"display_name":"Frank","drops":1}]'::jsonb,
  'the hidden name leaves the board'
);
reset role;

set local role anon;
set local request.jwt.claims to '';
select is(
  (select (public.campaigns_for_map(9, 9, 11, 11) -> 0 ->> 'my_drops')::integer),
  0,
  'a signed-out visitor has no personal count'
);
select throws_ok(
  $$ select public.my_campaigns() $$,
  '42501', 'permission denied for function my_campaigns',
  'the profile list is not granted to a signed-out visitor'
);
select throws_ok(
  $$ select public.campaigns_for_map(11, 9, 9, 11) $$,
  '22023', 'invalid_location',
  'a backwards box is rejected'
);
reset role;

set local role authenticated;
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
do $$ begin
  perform set_config(
    't.named',
    public.create_drop('note', 10.2, 10.2, 'Named bench') ->> 'short_id',
    true
  );
  perform set_config(
    't.quiet',
    public.create_drop('note', 10.3, 10.3, 'Quiet bench', null, '{}'::jsonb, '{}'::text[], null, null, true) ->> 'short_id',
    true
  );
end $$;
select is(
  (public.drop_teaser(current_setting('t.quiet'))).author_badges,
  '[]'::jsonb,
  'an anonymous drop does not show the badge'
);
select ok(
  (public.drop_teaser(current_setting('t.named'))).author_badges::text like '%founding_local%',
  'a named drop shows Founding Local'
);
select ok(
  (select public.export_my_data() -> 'badges')::text like '%founding_local%',
  'the export includes the badge'
);
select is(
  public.export_my_data() -> 'profile' ->> 'show_on_leaderboard',
  'true',
  'the export includes the leaderboard switch'
);
reset role;

select throws_ok(
  $$ insert into public.user_badges (user_id, badge_id, region_id)
     select '44444444-4444-4444-8444-444444444444', b.id, null
     from public.badges b where b.slug = 'founding_local' $$,
  '22023', 'region_required',
  'a regional badge cannot be stored without a region, which is how a later global badge stays distinct'
);

select * from finish();
rollback;
