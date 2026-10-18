-- M7 / App Review 1.7: incident reports only where a police tip line is configured.
-- No test-area tip line here, so only the seeded Chautauqua area counts.
begin;
create extension if not exists pgtap with schema extensions;
select plan(7);

insert into auth.users (id, email) values
  ('c1c1c1c1-c1c1-4c1c-8c1c-c1c1c1c1c1c1', 'trusted@example.test');
update public.profiles set tier = 'trusted' where id = 'c1c1c1c1-c1c1-4c1c-8c1c-c1c1c1c1c1c1';

select is(
  (select name from private.tip_lines where region_slug = 'us-ny-chautauqua'),
  'Jamestown Police anonymous tip line',
  'the pilot region has the Jamestown Police tip line'
);
select ok(
  not has_table_privilege('authenticated', 'private.tip_lines', 'insert')
  and not has_table_privilege('anon', 'private.tip_lines', 'select'),
  'clients cannot read or add tip lines directly'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "c1c1c1c1-c1c1-4c1c-8c1c-c1c1c1c1c1c1", "role": "authenticated"}';

select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  '42501', 'incident_reports_unavailable',
  'an incident report outside every tip-line area is refused'
);
select lives_ok(
  $$ select public.create_drop('incident_report', 42.0970, -79.2353, 'Bike taken from the rack', 'Lock was cut.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  'an incident report in Jamestown goes through'
);
select is(
  (select count(*)::integer from public.drops d join public.categories c on c.id = d.category_id
   where c.slug = 'incident_report' and d.author_id = 'c1c1c1c1-c1c1-4c1c-8c1c-c1c1c1c1c1c1'),
  1,
  'only the Jamestown report was stored'
);
select lives_ok(
  $$ select public.create_drop('safety_alert', 40.0, -100.0, 'Downed wires', 'Across the road.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  'safety alerts do not need a tip line'
);
select lives_ok(
  $$ select public.create_drop('hazard', 40.0, -100.0, 'Pothole', 'Deep one.',
       jsonb_build_object('occurred_at', to_jsonb(now()))) $$,
  'hazards do not need a tip line'
);

reset role;
select * from finish();
rollback;
