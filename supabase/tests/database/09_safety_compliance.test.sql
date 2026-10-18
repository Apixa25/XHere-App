-- M7: safety and store compliance (PLAN §7 M7, docs/COMPLIANCE.md).
-- Pre-moderation of Safety posts by New users, the report auto-hide threshold,
-- blocks hiding content both ways, hide-for-me, moderator-only access, the
-- append-only audit log, data export, and the street-address filter.
begin;
create extension if not exists pgtap with schema extensions;
select plan(68);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test'),
  ('33333333-3333-4333-8333-333333333333', 'carol@example.test'),
  ('44444444-4444-4444-8444-444444444444', 'mo@example.test'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ed@example.test');
update public.profiles set tier = 'regular' where id = '33333333-3333-4333-8333-333333333333';
update public.profiles set tier = 'trusted', role = 'moderator' where id = '44444444-4444-4444-8444-444444444444';
update public.tier_limits set drops_per_hour = 50, drops_per_day = 50;
select set_config('t.alice_handle', (select handle from public.profiles where id = '11111111-1111-4111-8111-111111111111'), true);

select has_table('public', 'hidden_drops', 'hidden_drops exists');
select ok((select relrowsecurity from pg_class where oid = 'public.hidden_drops'::regclass), 'hidden_drops has RLS enabled');

-- Privilege changes are logged whoever makes them (here: SQL as postgres)
select ok(
  exists (select 1 from public.moderation_actions a
          where a.target_id = '44444444-4444-4444-8444-444444444444' and a.action = 'set_role'
            and a.metadata = '{"from": "user", "to": "moderator"}'::jsonb),
  'role changes write a set_role audit row'
);
select ok(
  exists (select 1 from public.moderation_actions a
          where a.target_id = '33333333-3333-4333-8333-333333333333' and a.action = 'set_tier'
            and a.metadata = '{"from": "new", "to": "regular"}'::jsonb),
  'tier changes write a set_tier audit row'
);

set local role authenticated;

-- ---------------------------------------------------------------------------
-- Safety pre-moderation for New users
-- ---------------------------------------------------------------------------
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window broken.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  '42501', 'tier_too_low', 'New users cannot file incident reports at all'
);
do $$
begin
  perform set_config('t.alert', public.create_drop('safety_alert', 40.0, -100.0, 'Power out', 'Traffic lights are dark on Main.',
    jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) ->> 'id', true);
end;
$$;
select is(
  (select status::text from public.drops where id = current_setting('t.alert')::uuid),
  'pending_review',
  'a New user''s safety alert is held for review'
);
select ok(
  exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.alert')::uuid),
  'the author still sees their own held alert'
);
select throws_ok($$ select * from public.moderation_queue() $$, '42501', 'not_moderator', 'non-moderators cannot read the queue');

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  not exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.alert')::uuid),
  'others do not see a held alert'
);
select throws_ok(
  format($$ select public.drop_detail(%L) $$, current_setting('t.alert')),
  'P0002', 'drop_not_found', 'a held alert cannot be opened by others'
);

set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select ok(
  (select q.kind = 'drop' and q.status = 'pending_review' and q.sensitive and q.category_slug = 'safety_alert'
          and q.subject_id = '11111111-1111-4111-8111-111111111111'
   from public.moderation_queue() q where q.target_id = current_setting('t.alert')::uuid),
  'the held alert is in the moderator queue, flagged sensitive'
);
select is(public.moderate_drop(current_setting('t.alert')::uuid, 'approve', 'Confirmed outage')::text, 'active', 'a moderator approves it');
select ok(
  not exists (select 1 from public.moderation_queue() q where q.target_id = current_setting('t.alert')::uuid),
  'approved drops leave the queue'
);
do $$
begin
  perform set_config('t.mo_drop', public.create_drop('story', 40.0, -100.0, 'Mo''s story') ->> 'id', true);
end;
$$;

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.alert')::uuid),
  'once approved, everyone sees it'
);

-- ---------------------------------------------------------------------------
-- Street addresses are kept out of incident reports
-- ---------------------------------------------------------------------------
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
do $$
declare
  v_detail text;
begin
  perform public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Window smashed at 123 Main St last night.',
    jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true);
exception when others then
  get stacked diagnostics v_detail = pg_exception_detail;
  perform set_config('t.addr_detail', v_detail, true);
end;
$$;
select is(current_setting('t.addr_detail'), 'street_address', 'a house number and street are blocked in incident reports');
select throws_ok(
  $$ select public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Car window broken outside 45 W 3rd Street.',
       jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) $$,
  '22023', 'content_not_allowed', 'directional street addresses are blocked too'
);
select is(
  public.create_drop('incident_report', 40.0, -100.0, 'Break-in', 'Car window broken on Main Street around 2 am.',
    jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) ->> 'status',
  'pending_review',
  'a street name without a house number is fine (and held for a Regular user)'
);
select is(
  public.create_drop('story', 40.0, -100.0, 'Grew up at 12 Elm Street', 'Lovely porch.') ->> 'status',
  'active',
  'the address filter only applies to incident reports'
);

-- ---------------------------------------------------------------------------
-- Report auto-hide threshold (2 for Safety, 3 elsewhere)
-- ---------------------------------------------------------------------------
do $$
begin
  perform set_config('t.carol_alert', public.create_drop('safety_alert', 40.0, -100.0, 'Road closed', 'Bridge closed for repairs.',
    jsonb_build_object('occurred_at', to_jsonb(now())), p_acknowledge_guidelines => true) ->> 'id', true);
end;
$$;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(format($$ select public.report('drop', %L, 'misinformation', 'Bridge is open') $$, current_setting('t.carol_alert')), 'first report');
reset role;
select is((select status::text from public.drops where id = current_setting('t.carol_alert')::uuid), 'active', 'one report does not hide a safety alert');
set local role authenticated;
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select lives_ok(format($$ select public.report('drop', %L, 'spam') $$, current_setting('t.carol_alert')), 'second report');
reset role;
select is((select status::text from public.drops where id = current_setting('t.carol_alert')::uuid), 'pending_review', 'two reporters hide a safety alert');
select is(
  (select count(*)::integer from public.moderation_actions where target_id = current_setting('t.carol_alert')::uuid and action = 'auto_hide' and actor_id is null),
  1,
  'the auto-hide is in the audit log with no human actor'
);
set local role authenticated;
set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select ok(
  (select q.open_reports = 2 and q.reasons @> array['misinformation', 'spam'] and q.latest_details = 'Bridge is open'
          and cardinality(q.report_ids) = 2
   from public.moderation_queue() q where q.target_id = current_setting('t.carol_alert')::uuid),
  'the queue shows the report count, reasons, and latest details'
);
select ok(
  not exists (select 1 from public.moderation_queue() q where q.target_id = current_setting('t.mo_drop')::uuid),
  'moderators never see their own drops in the queue'
);

-- ---------------------------------------------------------------------------
-- Blocking hides content in both directions
-- ---------------------------------------------------------------------------
set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
do $$
begin
  perform set_config('t.carol_story', public.create_drop('story', 40.0, -100.0, 'Carol''s story', 'Hello.') ->> 'id', true);
  perform set_config('t.carol_short', (select d.short_id from public.drops d where d.id = current_setting('t.carol_story')::uuid), true);
end;
$$;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
do $$
begin
  perform set_config('t.bob_story', public.create_drop('story', 40.0, -100.0, 'Bob''s story', 'Hi.') ->> 'id', true);
end;
$$;
select ok(
  exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.carol_story')::uuid),
  'before blocking, bob sees carol''s drop'
);
select lives_ok($$ select public.block_user('33333333-3333-4333-8333-333333333333') $$, 'bob blocks carol');
select ok(
  not exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.carol_story')::uuid),
  'drops_nearby hides the blocked person''s drops'
);
select ok(
  not exists (select 1 from public.drops_in_view(-100.01, 39.99, -99.99, 40.01, null, 16) where drop_id = current_setting('t.carol_story')::uuid),
  'drops_in_view hides them'
);
select throws_ok(format($$ select public.drop_detail(%L) $$, current_setting('t.carol_story')), 'P0002', 'drop_not_found', 'drop_detail hides them');
select throws_ok(format($$ select public.drop_teaser(%L) $$, current_setting('t.carol_short')), 'P0002', 'drop_not_found', 'drop_teaser hides them');
select is(
  (select array_agg(b.handle) from public.my_blocks() b),
  array[(select handle from public.profiles where id = '33333333-3333-4333-8333-333333333333')],
  'my_blocks lists the blocked person'
);

set local request.jwt.claims to '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}';
select ok(
  not exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.bob_story')::uuid),
  'the blocked person no longer sees the blocker''s drops either'
);
select throws_ok(format($$ select public.drop_detail(%L) $$, current_setting('t.bob_story')), 'P0002', 'drop_not_found', 'not even by id');
select is_empty($$ select 1 from public.my_blocks() $$, 'blocks are private to the blocker');

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok($$ select public.unblock_user('33333333-3333-4333-8333-333333333333') $$, 'bob unblocks carol');
select ok(
  exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.carol_story')::uuid),
  'unblocking brings the drops back'
);

-- ---------------------------------------------------------------------------
-- Hide a drop for myself
-- ---------------------------------------------------------------------------
select lives_ok(format($$ select public.hide_drop(%L) $$, current_setting('t.alert')), 'bob hides alice''s alert');
select lives_ok(format($$ select public.hide_drop(%L) $$, current_setting('t.alert')), 'hiding twice is harmless');
select ok(
  not exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.alert')::uuid),
  'a hidden drop leaves bob''s map'
);
select throws_ok(format($$ select public.drop_detail(%L) $$, current_setting('t.alert')), 'P0002', 'drop_not_found', 'and cannot be opened');
select is((select array_agg(h.drop_id::text) from public.my_hidden_drops() h), array[current_setting('t.alert')], 'my_hidden_drops lists it');
select throws_ok(format($$ select public.hide_drop(%L) $$, current_setting('t.bob_story')), '22023', 'cannot_hide_own_drop', 'you cannot hide your own drop');
select throws_ok($$ select public.hide_drop(gen_random_uuid()) $$, 'P0002', 'drop_not_found', 'unknown drops cannot be hidden');
select throws_ok(
  format($$ insert into public.hidden_drops (user_id, drop_id) values (auth.uid(), %L) $$, current_setting('t.carol_story')),
  '42501', null, 'clients cannot write hidden_drops directly'
);

set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select ok(
  exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.alert')::uuid),
  'hiding only affects the person who hid it'
);
select is_empty($$ select 1 from public.hidden_drops $$, 'other people''s hidden lists are private (RLS)');

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(format($$ select public.unhide_drop(%L) $$, current_setting('t.alert')), 'bob unhides it');
select ok(
  exists (select 1 from public.drops_nearby(40.0, -100.0, 500) where id = current_setting('t.alert')::uuid),
  'unhiding brings it back'
);

-- ---------------------------------------------------------------------------
-- Profile lookups and profile reports
-- ---------------------------------------------------------------------------
select is(
  (select p.id::text from public.public_profile(upper(current_setting('t.alice_handle'))) p),
  '11111111-1111-4111-8111-111111111111',
  'public_profile resolves a handle, case-insensitively'
);
do $$
begin
  perform set_config('t.profile_report', public.report('profile', '11111111-1111-4111-8111-111111111111', 'harassment', 'Rude replies')::text, true);
end;
$$;

-- ---------------------------------------------------------------------------
-- Moderator-only actions
-- ---------------------------------------------------------------------------
select throws_ok(format($$ select public.moderate_drop(%L, 'remove') $$, current_setting('t.carol_story')), '42501', 'not_moderator', 'non-moderators cannot remove drops');
select throws_ok(format($$ select public.resolve_report(%L, 'actioned') $$, current_setting('t.profile_report')), '42501', 'not_moderator', 'non-moderators cannot resolve reports');
select throws_ok(
  $$ select public.suspend_user('11111111-1111-4111-8111-111111111111', now() + interval '1 day') $$,
  '42501', 'not_moderator', 'non-moderators cannot suspend users'
);
select is_empty($$ select 1 from public.moderation_actions $$, 'non-moderators cannot read the audit log');
select throws_ok(
  $$ update public.profiles set role = 'admin' where id = auth.uid() $$,
  '42501', null, 'users cannot promote themselves'
);

set local request.jwt.claims to '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}';
select ok(
  (select q.kind = 'profile' and q.subject_handle = current_setting('t.alice_handle') and q.reasons = array['harassment']
   from public.moderation_queue() q where q.target_id = '11111111-1111-4111-8111-111111111111'),
  'profile reports show up in the queue'
);
select lives_ok(format($$ select public.resolve_report(%L, 'actioned', 'Warned') $$, current_setting('t.profile_report')), 'moderator actions the report');
select lives_ok(
  $$ select public.suspend_user('11111111-1111-4111-8111-111111111111', now() + interval '1 day', 'Harassment') $$,
  'moderator suspends alice'
);
select lives_ok(format($$ select public.moderate_drop(%L, 'remove', 'Bridge is open') $$, current_setting('t.carol_alert')), 'moderator removes the reported alert');
select is(
  (select array_agg(a.action::text order by a.id) from public.moderation_actions a
   where a.actor_id = '44444444-4444-4444-8444-444444444444'),
  array['approve', 'action_report', 'suspend_user', 'reject'],
  'every moderator action has an audit row with the moderator as actor'
);

-- The audit log is append-only, even for the table owner
reset role;
select throws_ok(
  $$ update public.moderation_actions set reason = 'edited' where action = 'approve' $$,
  '42501', 'audit_log_is_append_only', 'audit rows cannot be edited'
);
select throws_ok(
  $$ delete from public.moderation_actions where action = 'approve' $$,
  '42501', 'audit_log_is_append_only', 'audit rows cannot be deleted'
);
set local role authenticated;

-- ---------------------------------------------------------------------------
-- Data export
-- ---------------------------------------------------------------------------
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(format($$ select public.hide_drop(%L) $$, current_setting('t.carol_story')), 'bob hides a drop before exporting');
do $$
begin
  perform set_config('t.export', public.export_my_data()::text, true);
end;
$$;
select ok(
  (select e ->> 'format' = 'xhere-export-v1' and e ->> 'email' = 'bob@example.test'
          and e ->> 'user_id' = '22222222-2222-4222-8222-222222222222'
   from (select current_setting('t.export')::jsonb as e) x),
  'the export is labelled and belongs to the caller'
);
select is(
  (select array_agg(d ->> 'title') from jsonb_array_elements(current_setting('t.export')::jsonb -> 'drops') d),
  array['Bob''s story'],
  'the export has the caller''s drops and nobody else''s'
);
select ok(
  (select jsonb_array_length(e -> 'reports_filed') = 2 and jsonb_array_length(e -> 'hidden_drops') = 1
          and not (e -> 'reports_filed' -> 0 ? 'resolution_note')
   from (select current_setting('t.export')::jsonb as e) x),
  'reports filed and hidden drops are included, without moderator notes'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select ok(
  (select e -> 'suspension' ->> 'reason' = 'Harassment'
          and not exists (select 1 from jsonb_array_elements(e -> 'moderation_of_my_content') m where m ? 'actor_id')
          and exists (select 1 from jsonb_array_elements(e -> 'moderation_of_my_content') m where m ->> 'action' = 'approve')
   from (select public.export_my_data() as e) x),
  'the export shows moderation of my content without naming moderators'
);

set local request.jwt.claims to '{"role": "authenticated"}';
select throws_ok($$ select public.export_my_data() $$, '42501', 'not_authenticated', 'export needs a signed-in user');

select * from finish();
rollback;
