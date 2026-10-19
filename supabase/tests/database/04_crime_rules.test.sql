-- Safety & Alerts, and incident_report (crime) in particular (PLAN §4.3):
-- Regular+ to post, pre-moderation below Trusted, ~100 m location blur,
-- 72 h lifetime, occurred_at required, no naming or describing people,
-- unconfirmed until 2 nearby confirmations.
begin;
create extension if not exists pgtap with schema extensions;
select plan(42);

-- Incident reports need a tip-line area (20261018001100_tip_lines.sql). These fixtures sit near 40, -100.
insert into private.tip_lines (region_slug, name, phone, area) values
  ('test-area', 'Test tip line', '555-0100',
   extensions.st_geogfromtext('SRID=4326;POLYGON((-101 39, -99 39, -99 41, -101 41, -101 39))'));

insert into auth.users (id, email) values
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'mo@example.test'),
  ('99999999-9999-4999-8999-999999999999', 'tina@example.test'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ed@example.test'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'fay@example.test');
update public.profiles set tier = 'regular' where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set tier = 'trusted', role = 'moderator' where id = '44444444-4444-4444-8444-444444444444';
update public.profiles set tier = 'trusted' where id = '99999999-9999-4999-8999-999999999999';

set local role authenticated;

-- Who may post
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  '42501', 'tier_too_low', 'New users cannot file incident reports'
);

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now()))) $$,
  '22023', 'guidelines_not_acknowledged', 'the guideline reminder must be acknowledged'
);
do $$
declare
  v_hint text;
begin
  perform public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.', jsonb_build_object('occurred_at', to_jsonb(now())));
exception when others then
  get stacked diagnostics v_hint = pg_exception_hint;
  perform set_config('t.hint', v_hint, true);
end;
$$;
select matches(current_setting('t.hint'), 'Describe what happened, not who.*911', 'the reminder and emergency disclaimer come back as the hint');

-- occurred_at: required, not in the future, not older than the 72 h window
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.', '{"severity": "med"}', p_acknowledge_guidelines => true) $$,
  '22023', 'invalid_attributes', 'occurred_at is required'
);
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now() + interval '1 hour')), p_acknowledge_guidelines => true) $$,
  '22023', 'invalid_attributes', 'occurred_at cannot be in the future'
);
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now() - interval '5 days')), p_acknowledge_guidelines => true) $$,
  '22023', 'invalid_attributes', 'stale incidents (older than 72 h) are rejected'
);
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now()), 'severity', 'critical'), p_acknowledge_guidelines => true) $$,
  '22023', 'invalid_attributes', 'severity is low, med, or high'
);

-- No naming or describing people
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'A tall man in a red hoodie broke the window.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  '22023', 'content_not_allowed', 'descriptions of people are blocked'
);
do $$
declare
  v_detail text;
begin
  perform public.create_drop('incident_report', 40.0, -100.0, 'Suspect ran north', 'Window broken.',
    jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true);
exception when others then
  get stacked diagnostics v_detail = pg_exception_detail;
  perform set_config('t.detail', v_detail, true);
end;
$$;
select is(current_setting('t.detail'), 'person_descriptors', 'the title is checked too, and the filter is named');
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Two teenagers, maybe 16 yo, took a bike.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  '22023', 'content_not_allowed', 'ages and age groups are blocked'
);
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Call me at 555-123-4567.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  '22023', 'content_not_allowed', 'phone numbers are blocked'
);
select throws_ok(
  $$ select public.create_drop('safety_alert', 40.0, -100.0, 'Fight', 'A group of women were shouting.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  '22023', 'content_not_allowed', 'safety alerts use the same people filter'
);

-- Lifetime and reveal radius
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_expires_at => now() + interval '5 days', p_acknowledge_guidelines => true) $$,
  '22023', 'expires_too_far', 'incident reports cannot outlive 72 h'
);
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_reveal_radius_m => 50, p_acknowledge_guidelines => true) $$,
  '22023', 'invalid_reveal_radius', 'the reveal radius cannot be finer than the blur'
);

-- A clean report from a Regular user is held for review
do $$
begin
  perform set_config('t.regular_report', public.create_drop(
    'incident_report', 40.00037, -100.00041, 'Car break-in', 'A car window was smashed and a bag was taken.',
    jsonb_build_object('occurred_at', to_jsonb(now() - interval '2 hours'), 'severity', 'med', 'ongoing', false),
    p_acknowledge_guidelines => true)::text, true);
end;
$$;
select is(current_setting('t.regular_report')::jsonb ->> 'status', 'pending_review', 'Regular users'' incident reports are pre-moderated');
select is(
  (current_setting('t.regular_report')::jsonb ->> 'expires_at')::timestamptz,
  now() + interval '72 hours',
  'incident reports expire after 72 h'
);
select matches(current_setting('t.regular_report')::jsonb ->> 'posting_notice', '911', 'the response carries the disclaimer');

-- Location blur (~100 m grid)
reset role;
select ok(
  (select not extensions.st_equals(d.geog::extensions.geometry, d.display_geog::extensions.geometry)
   from public.drops d where d.id = (current_setting('t.regular_report')::jsonb ->> 'id')::uuid),
  'the display position differs from the true position'
);
select ok(
  (select extensions.st_distance(d.geog, d.display_geog) between 1 and 72
   from public.drops d where d.id = (current_setting('t.regular_report')::jsonb ->> 'id')::uuid),
  'the blur moves the point at most half a 100 m cell diagonal'
);
select ok(
  (select extensions.st_equals(private.fuzz_point(d.display_geog, 100)::extensions.geometry, d.display_geog::extensions.geometry)
   from public.drops d where d.id = (current_setting('t.regular_report')::jsonb ->> 'id')::uuid),
  'the display position is a grid-cell center (blurring is idempotent)'
);
select ok(
  (with c as (select private.fuzz_point(private.make_point(40.0123, -100.0456), 100) as center)
   select extensions.st_equals(
            private.fuzz_point(extensions.st_project(c.center, 20, radians(45)), 100)::extensions.geometry,
            private.fuzz_point(extensions.st_project(c.center, 20, radians(225)), 100)::extensions.geometry)
   from c),
  'points in the same cell share one display position, so repeat posts cannot be averaged'
);
select ok(
  (select abs((current_setting('t.regular_report')::jsonb ->> 'lat')::float8 - extensions.st_y(d.display_geog::extensions.geometry)) < 1e-9
      and abs((current_setting('t.regular_report')::jsonb ->> 'lat')::float8 - 40.00037) > 1e-6
   from public.drops d where d.id = (current_setting('t.regular_report')::jsonb ->> 'id')::uuid),
  'even the author''s create response returns the blurred position'
);
set local role authenticated;

-- Pending reports are invisible to others and visible to moderators
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  not exists (select 1 from public.drops_nearby(40.0, -100.0, 3000, '{safety_alerts}')),
  'pending incident reports are not shown to others'
);
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select is(
  (select d.status::text from public.drops d where d.id = (current_setting('t.regular_report')::jsonb ->> 'id')::uuid),
  'pending_review',
  'moderators see the review queue'
);

-- Trusted users go live, unconfirmed until 2 nearby confirmations
set local request.jwt.claims to '{"sub": "99999999-9999-4999-8999-999999999999", "role": "authenticated"}';
do $$
begin
  perform set_config('t.trusted_report', public.create_drop(
    'incident_report', 40.0101, -100.0101, 'Package theft', 'A package was taken from a porch.',
    jsonb_build_object('occurred_at', to_jsonb(now() - interval '30 minutes'), 'severity', 'low'),
    p_acknowledge_guidelines => true) ->> 'id', true);
  perform set_config('t.trusted_report2', public.create_drop(
    'incident_report', 40.0201, -100.0201, 'Bike theft', 'A bike was taken from the rack.',
    jsonb_build_object('occurred_at', to_jsonb(now() - interval '30 minutes'), 'severity', 'low'),
    p_acknowledge_guidelines => true) ->> 'id', true);
end;
$$;

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select status = 'active' and not is_confirmed
   from public.drops_nearby(40.0101, -100.0101, 3000) where id = current_setting('t.trusted_report')::uuid),
  'Trusted users'' incident reports are live but unconfirmed'
);
select ok(
  (select d.lat <> 40.0101 and d.lng <> -100.0101
   from public.drops_nearby(40.0101, -100.0101, 3000) as d where d.id = current_setting('t.trusted_report')::uuid),
  'drops_nearby returns the blurred position, not the true one'
);
select ok(
  (select v.lat <> 40.0101 and v.lng <> -100.0101
   from public.drops_in_view(-100.02, 40.0, -100.0, 40.02, null, 16) as v where v.drop_id = current_setting('t.trusted_report')::uuid),
  'drops_in_view returns the blurred position, not the true one'
);
select ok(
  (select location_precision_m = 100 from public.drop_detail(current_setting('t.trusted_report')::uuid, 40.0101, -100.0101)),
  'clients are told the position is approximate'
);
select is(
  public.confirm_drop(current_setting('t.trusted_report')::uuid, 'still_true', 40.0101, -100.0101) ->> 'is_confirmed',
  'false',
  'one confirmation is not enough'
);
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select is(
  public.confirm_drop(current_setting('t.trusted_report')::uuid, 'still_true', 40.0102, -100.0100) ->> 'status',
  'confirmed',
  'two nearby confirmations confirm it'
);

-- Trusted authors need two extra reports. An incident report hides at 4, not 2.
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok($$ select public.report('drop', current_setting('t.trusted_report2')::uuid, 'misinformation') $$, 'first report');
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select lives_ok($$ select public.report('drop', current_setting('t.trusted_report2')::uuid, 'personal_info') $$, 'second report');
reset role;
select is(
  (select status::text from public.drops where id = current_setting('t.trusted_report2')::uuid),
  'active',
  'two reports do not hide a Trusted author''s incident report'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select lives_ok($$ select public.report('drop', current_setting('t.trusted_report2')::uuid, 'spam') $$, 'third report');
reset role;
select is(
  (select status::text from public.drops where id = current_setting('t.trusted_report2')::uuid),
  'active',
  'three reports still leave a Trusted incident report up'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "role": "authenticated"}';
select lives_ok($$ select public.report('drop', current_setting('t.trusted_report2')::uuid, 'other') $$, 'fourth report');
reset role;
select is(
  (select status::text from public.drops where id = current_setting('t.trusted_report2')::uuid),
  'pending_review',
  'a Trusted incident report is hidden after 4 reports (2 plus 2)'
);
select ok(
  exists (select 1 from public.moderation_actions a
          where a.target_id = current_setting('t.trusted_report2')::uuid and a.action = 'auto_hide' and a.actor_id is null),
  'the automatic hide is in the audit log'
);
set local role authenticated;

-- Other Safety categories have their own rules
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(
  public.create_drop('safety_alert', 40.03, -100.03, 'Power out', 'Traffic lights are dark on Main.',
    jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) ->> 'status',
  'pending_review',
  'safety alerts from New users are pre-moderated'
);
select is(
  public.create_drop('hazard', 40.03, -100.03, 'Black ice on the bridge', 'Very slick this morning.',
    jsonb_build_object('occurred_at', to_jsonb(now()), 'severity', 'high', 'hazard_type', 'road')) ->> 'status',
  'active',
  'hazards post immediately and are not subject to the people filter'
);
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select is(
  public.create_drop('safety_alert', 40.03, -100.03, 'Power out', 'Traffic lights are dark on Main.',
    jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) ->> 'status',
  'active',
  'safety alerts from Regular users go live'
);
select is(
  (public.create_drop('hazard', 40.03, -100.03, 'Flooded underpass', null,
    jsonb_build_object('occurred_at', to_jsonb(now()), 'hazard_type', 'flood')) ->> 'expires_at')::timestamptz,
  now() + interval '24 hours',
  'hazards expire after 24 h unless confirmed'
);

select * from finish();
rollback;
