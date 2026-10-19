-- Demo accounts stay on the map and never take a founding slot or a board place.
begin;
create extension if not exists pgtap with schema extensions;
select plan(26);

select col_not_null('public', 'profiles', 'is_demo', 'is_demo is required');
select col_type_is('public', 'profiles', 'is_demo', 'boolean', 'is_demo is a boolean');
select col_default_is('public', 'profiles', 'is_demo', 'false', 'a new account is not a demo');
select ok(
  not has_column_privilege('public', 'public.profiles', 'is_demo', 'update')
  and not has_column_privilege('anon', 'public.profiles', 'is_demo', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'is_demo', 'update')
  and has_column_privilege('service_role', 'public.profiles', 'is_demo', 'update'),
  'is_demo is writable by service_role and not by clients'
);
select ok(
  (select p.prosecdef
     and exists (
       select 1 from unnest(coalesce(p.proconfig, '{}')) c
       where c in ('search_path=', 'search_path=""')
     )
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'set_account_demo')
  and has_function_privilege('authenticated', 'public.set_account_demo(uuid, boolean)', 'execute')
  and not has_function_privilege('anon', 'public.set_account_demo(uuid, boolean)', 'execute')
  and not has_function_privilege('authenticated', 'private.sync_founding_when_demo_changes()', 'execute'),
  'set_account_demo is a locked-down admin RPC'
);

select ok(
  (select count(*) = 4 and bool_and(is_demo)
   from public.profiles
   where id in (
     'd0000000-0000-4000-8000-000000000001',
     'd0000000-0000-4000-8000-000000000002',
     'd0000000-0000-4000-8000-000000000003',
     'd0000000-0000-4000-8000-000000000004'
   )),
  'the four seeded demo accounts are marked'
);
select is(
  (select count(*)::integer
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local'
     and ub.user_id in (
       'd0000000-0000-4000-8000-000000000001',
       'd0000000-0000-4000-8000-000000000002',
       'd0000000-0000-4000-8000-000000000003',
       'd0000000-0000-4000-8000-000000000004'
     )),
  0,
  'seeded demo accounts hold no Founding Local rank'
);
select is(
  (select (public.campaigns_for_map(-79.32, 42.05, -79.18, 42.14) -> 0 ->> 'drop_count')::integer),
  0,
  'seeded demo drops do not add to the Jamestown campaign total'
);
select is(
  public.campaigns_for_map(-79.32, 42.05, -79.18, 42.14) -> 0 -> 'leaderboard',
  '[]'::jsonb,
  'seeded demo names are not on the Jamestown board'
);

insert into auth.users (id, email) values
  ('d0d00000-0000-4000-8000-0000000000d1', 'demo.slot@example.test'),
  ('d0d00000-0000-4000-8000-0000000000a1', 'real.a@example.test'),
  ('d0d00000-0000-4000-8000-0000000000b1', 'real.b@example.test'),
  ('d0d00000-0000-4000-8000-0000000000ad', 'admin.slot@example.test'),
  ('d0d00000-0000-4000-8000-0000000000c1', 'plain.slot@example.test');

update public.profiles
set tier = 'trusted', handle = 'demo_slot', display_name = 'Demo Slot', is_demo = true
where id = 'd0d00000-0000-4000-8000-0000000000d1';
update public.profiles
set tier = 'trusted', handle = 'real_a', display_name = 'Real A'
where id = 'd0d00000-0000-4000-8000-0000000000a1';
update public.profiles
set tier = 'trusted', handle = 'real_b', display_name = 'Real B'
where id = 'd0d00000-0000-4000-8000-0000000000b1';
update public.profiles
set tier = 'trusted', handle = 'admin_slot', display_name = 'Admin Slot', role = 'admin'
where id = 'd0d00000-0000-4000-8000-0000000000ad';
update public.profiles
set tier = 'trusted', handle = 'plain_slot', display_name = 'Plain Slot'
where id = 'd0d00000-0000-4000-8000-0000000000c1';

select is(
  (select is_demo from public.profiles where id = 'd0d00000-0000-4000-8000-0000000000c1'),
  false,
  'an ordinary new account is not a demo'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "d0d00000-0000-4000-8000-0000000000c1", "role": "authenticated"}';
select throws_ok(
  $$ update public.profiles set is_demo = true where id = auth.uid() $$,
  '42501', null,
  'a signed-in user cannot write is_demo'
);
select throws_ok(
  $$ select public.set_account_demo('d0d00000-0000-4000-8000-0000000000c1', true) $$,
  '42501', 'not_authorized',
  'a regular user cannot mark an account as demo'
);

set local request.jwt.claims to '{"sub": "d0d00000-0000-4000-8000-0000000000ad", "role": "authenticated"}';
select throws_ok(
  $$ update public.profiles set is_demo = true where id = auth.uid() $$,
  '42501', null,
  'an admin cannot write is_demo through a table update'
);
select is(
  public.set_account_demo('d0d00000-0000-4000-8000-0000000000c1', true),
  true,
  'an admin marks a demo account through the RPC'
);
reset role;

select is(
  (select is_demo from public.profiles where id = 'd0d00000-0000-4000-8000-0000000000c1'),
  true,
  'the admin RPC persisted the flag'
);

update public.profiles set is_demo = false where id = 'd0d00000-0000-4000-8000-0000000000c1';
set local role service_role;
update public.profiles set is_demo = true where id = 'd0d00000-0000-4000-8000-0000000000c1';
reset role;
select is(
  (select is_demo from public.profiles where id = 'd0d00000-0000-4000-8000-0000000000c1'),
  true,
  'service_role can mark a demo account directly'
);

insert into public.regions (id, name, country, min_lng, min_lat, max_lng, max_lat, timezone)
values ('zz-demo-box', 'Demo box', 'ZZ', 12, 12, 14, 14, 'UTC');
insert into public.region_founding (region_id, cap) values ('zz-demo-box', 2);
insert into public.campaigns (region_id, slug, title, description, goal_drops, starts_at, ends_at, leaderboard_size)
values (
  'zz-demo-box', 'map-demo-box', 'Map the demo box', 'Real drops only.', 5,
  now() - interval '1 hour', now() + interval '14 days', 2
);

create function public._demo_plant(
  p_author uuid,
  p_title text,
  p_at timestamptz
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
    p_author, c.id, c.version, p_title,
    'POINT(12.2 12.2)'::extensions.geography,
    'POINT(12.2 12.2)'::extensions.geography,
    100, 'active', 'user', p_at
  from public.categories c
  where c.slug = 'note'
  returning id into v_id;
  return v_id;
end;
$$;

do $$ begin
  perform public._demo_plant('d0d00000-0000-4000-8000-0000000000d1', 'Demo one', now() - interval '50 minutes');
  perform public._demo_plant('d0d00000-0000-4000-8000-0000000000d1', 'Demo two', now() - interval '49 minutes');
  perform public._demo_plant('d0d00000-0000-4000-8000-0000000000d1', 'Demo three', now() - interval '48 minutes');
  perform public._demo_plant('d0d00000-0000-4000-8000-0000000000d1', 'Demo four', now() - interval '47 minutes');
  perform public._demo_plant('d0d00000-0000-4000-8000-0000000000d1', 'Demo five', now() - interval '46 minutes');
  perform public._demo_plant('d0d00000-0000-4000-8000-0000000000a1', 'Real A one', now() - interval '30 minutes');
  perform public._demo_plant('d0d00000-0000-4000-8000-0000000000a1', 'Real A two', now() - interval '29 minutes');
  perform public._demo_plant('d0d00000-0000-4000-8000-0000000000b1', 'Real B one', now() - interval '10 minutes');
end $$;

select set_config(
  't.demo_drop',
  (select id::text from public.drops
   where author_id = 'd0d00000-0000-4000-8000-0000000000d1'
   order by created_at limit 1),
  true
);

select is(
  (select jsonb_agg(
      jsonb_build_object('id', ub.user_id, 'rank', (ub.meta ->> 'rank')::integer)
      order by (ub.meta ->> 'rank')::integer
    )
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-demo-box'),
  '[
    {"id":"d0d00000-0000-4000-8000-0000000000a1","rank":1},
    {"id":"d0d00000-0000-4000-8000-0000000000b1","rank":2}
  ]'::jsonb,
  'the two founding slots go to the real people, earliest first'
);
select is(
  (select count(*)::integer from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-demo-box'
     and ub.user_id = 'd0d00000-0000-4000-8000-0000000000d1'),
  0,
  'a demo account does not take a founding slot'
);
select is(
  (select (public.campaigns_for_map(12, 12, 14, 14) -> 0 ->> 'drop_count')::integer),
  3,
  'campaign progress counts the real drops only'
);
select is(
  public.campaigns_for_map(12, 12, 14, 14) -> 0 -> 'leaderboard',
  '[{"display_name":"Real A","drops":2},{"display_name":"Real B","drops":1}]'::jsonb,
  'a demo account does not take a leaderboard place'
);
select is(
  (select count(*)::integer from public.drops where author_id = 'd0d00000-0000-4000-8000-0000000000d1'),
  5,
  'the demo drops are still stored'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "d0d00000-0000-4000-8000-0000000000b1", "role": "authenticated"}';
select ok(
  exists (
    select 1 from public.drops_nearby(12.2, 12.2, 5000, null, 50) c
    where c.id = current_setting('t.demo_drop')::uuid
  ),
  'a demo drop still shows up near its point'
);
reset role;

set local role authenticated;
set local request.jwt.claims to '{"sub": "d0d00000-0000-4000-8000-0000000000ad", "role": "authenticated"}';
select is(
  public.set_account_demo('d0d00000-0000-4000-8000-0000000000a1', true),
  true,
  'marking the current rank 1 as demo'
);
reset role;

select is(
  (select jsonb_agg(
      jsonb_build_object('id', ub.user_id, 'rank', (ub.meta ->> 'rank')::integer)
      order by (ub.meta ->> 'rank')::integer
    )
   from public.user_badges ub
   join public.badges b on b.id = ub.badge_id
   where b.slug = 'founding_local' and ub.region_id = 'zz-demo-box'),
  '[{"id":"d0d00000-0000-4000-8000-0000000000b1","rank":1}]'::jsonb,
  'the founding slot moves to the next real person at rank 1'
);
select is(
  public.campaigns_for_map(12, 12, 14, 14) -> 0 -> 'leaderboard',
  '[{"display_name":"Real B","drops":1}]'::jsonb,
  'the newly demo account leaves the board and the earlier demo stays off it'
);
select is(
  (select count(*)::integer from public.drops
   where author_id in (
     'd0d00000-0000-4000-8000-0000000000d1',
     'd0d00000-0000-4000-8000-0000000000a1'
   )),
  7,
  'marking an account demo does not remove its drops'
);

select * from finish();
rollback;
