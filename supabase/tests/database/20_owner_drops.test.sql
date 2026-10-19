-- The author can edit and soft-delete their own drop. Nobody else can.
-- A deleted drop leaves confirmations in place and drops out of the map,
-- the Here Card, the public API, campaign totals, the leaderboard, and badges.
begin;
create extension if not exists pgtap with schema extensions;
select plan(41);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test');
update public.profiles
set tier = 'trusted', handle = 'alice', display_name = 'Alice'
where id = '11111111-1111-4111-8111-111111111111';
update public.profiles
set tier = 'trusted', handle = 'bob', display_name = 'Bob'
where id = '22222222-2222-4222-8222-222222222222';

insert into public.regions (id, name, country, min_lng, min_lat, max_lng, max_lat, timezone)
values ('test-owner', 'Owner test', 'US', -101, 39, -99, 41, 'UTC');
insert into public.region_founding (region_id, cap) values ('test-owner', 5);
insert into public.campaigns (region_id, slug, title, description, goal_drops, starts_at, ends_at)
values (
  'test-owner', 'owner-week', 'Owner week', 'Count live drops only.', 10,
  now() - interval '1 day', now() + interval '14 days'
);

select ok(
  exists (
    select 1 from pg_policies
    where schemaname = 'storage' and policyname = 'drop_media_select_granted'
  ),
  'a revealed photo can be read without making the bucket public'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select throws_ok(
  $$ select public.delete_my_drop('11111111-1111-4111-8111-111111111111') $$,
  '42501',
  'not_owner',
  'another person cannot delete a drop that is not theirs'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  $$ select public.set_my_public_name('Ada Lovelace') $$,
  'the author can pick a display name and keep the handle'
);

select is(
  (select display_name from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  'Ada Lovelace',
  'the chosen display name is saved'
);

do $$
begin
  perform set_config('t.drop', public.create_drop(
    'historical_fact', 40.0, -100.0, 'Old mill', 'Burned in 1890.',
    p_attributes => '{"era":"1800s"}'::jsonb,
    p_media => '[{
      "storage_path": "11111111-1111-4111-8111-111111111111/mill.jpg",
      "media_kind": "image",
      "mime_type": "image/jpeg",
      "alt_text": "The mill"
    }]'::jsonb
  ) ->> 'id', true);
end;
$$;

select lives_ok(
  format('select public.drop_detail(%L::uuid, 40, -100)', current_setting('t.drop')),
  'the author can open their own drop'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select throws_ok(
  format('select public.delete_my_drop(%L::uuid)', current_setting('t.drop')),
  '42501',
  'not_owner',
  'another person cannot delete this drop'
);
select throws_ok(
  format(
    $$ select public.update_my_drop(%L::uuid, 'Hacked', 'nope', '{}'::jsonb, 'nope') $$,
    current_setting('t.drop')
  ),
  '42501',
  'not_owner',
  'another person cannot edit this drop'
);

select lives_ok(
  format($$ select public.confirm_drop(%L::uuid, 'still_true', 40.0, -100.0) $$, current_setting('t.drop')),
  'a neighbor can still confirm before the author deletes'
);

set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select is(
  (select (public.update_my_drop(
      current_setting('t.drop')::uuid,
      'Mill pond',
      'The wheel is still there.',
      '{"era":"1890s"}'::jsonb,
      'A stone mill wheel'
    )).title),
  'Mill pond',
  'the author can rename their drop'
);

select is(
  (select body from public.drops where id = current_setting('t.drop')::uuid),
  'The wheel is still there.',
  'the description is updated'
);
select is(
  (select attributes ->> 'era' from public.drops where id = current_setting('t.drop')::uuid),
  '1890s',
  'category fields are updated'
);
select is(
  (select alt_text from public.drop_media where drop_id = current_setting('t.drop')::uuid),
  'A stone mill wheel',
  'photo alt text is updated'
);
select is(
  (select (public.drop_detail(current_setting('t.drop')::uuid)).author_display_name),
  'Ada Lovelace',
  'the card shows the display name, not only the handle'
);

select is(
  (select count(*)::integer from public.my_drops() where id = current_setting('t.drop')::uuid),
  1,
  'my drops lists the live drop'
);

reset role;

select ok(
  exists (
    select 1 from public.user_badges ub
    join public.badges b on b.id = ub.badge_id
    where ub.user_id = '11111111-1111-4111-8111-111111111111' and b.slug = 'first_drop'
  ),
  'the first drop badge is awarded'
);
select ok(
  exists (
    select 1 from public.user_badges ub
    join public.badges b on b.id = ub.badge_id
    where ub.user_id = '11111111-1111-4111-8111-111111111111'
      and b.slug = 'founding_local'
      and ub.region_id = 'test-owner'
  ),
  'founding local counts this drop'
);
select is(
  (
    select (public.campaigns_for_map(-101, 39, -99, 41) -> 0 ->> 'drop_count')::integer
  ),
  1,
  'the campaign total includes the live drop'
);
select is(
  (
    select public.campaigns_for_map(-101, 39, -99, 41) -> 0 -> 'leaderboard' -> 0 ->> 'display_name'
  ),
  'Ada Lovelace',
  'the leaderboard uses the display name'
);
select ok(
  (select private.public_api_visible(d) from public.drops d where d.id = current_setting('t.drop')::uuid),
  'the public API can see the live drop'
);
select ok(
  exists (
    select 1 from public.media_grants g
    where g.user_id = '11111111-1111-4111-8111-111111111111'
      and g.storage_path like '%/mill.jpg'
  ),
  'opening the drop grants a short-lived photo read'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  format('select public.delete_my_drop(%L::uuid)', current_setting('t.drop')),
  'the author can soft-delete'
);

do $$
declare
  n integer;
begin
  delete from public.drops where id = current_setting('t.drop')::uuid;
  get diagnostics n = row_count;
  perform set_config('t.hard', n::text, true);
end;
$$;
select is(current_setting('t.hard'), '0', 'the author cannot hard-delete the row');

select is(
  (select count(*)::integer from public.my_drops() where id = current_setting('t.drop')::uuid),
  0,
  'my drops hides the deleted drop'
);
select is_empty(
  format(
    $$ select 1 from public.drops_in_view(-101, 39, -99, 41, null, 16) v
       where v.drop_id = %L::uuid $$,
    current_setting('t.drop')
  ),
  'the pin leaves the map'
);
select is(
  (
    select count(*)::integer from public.here_card(40, -100, 40, -100, 500, 20) h
    where h.id = current_setting('t.drop')::uuid
  ),
  0,
  'the Here Card omits the deleted drop'
);

reset role;

select is(
  (select status::text from public.drops where id = current_setting('t.drop')::uuid),
  'removed',
  'delete sets status to removed'
);
select ok(
  (select deleted_at is not null and removed_reason = 'author' and status_before_delete = 'active'
   from public.drops where id = current_setting('t.drop')::uuid),
  'delete records when the author removed it and what to restore'
);
select is(
  (select count(*)::integer from public.confirmations where drop_id = current_setting('t.drop')::uuid),
  1,
  'the confirmation row stays'
);
select ok(
  not private.can_view_drop(
    (select d from public.drops d where d.id = current_setting('t.drop')::uuid),
    '11111111-1111-4111-8111-111111111111'
  ),
  'the author does not keep seeing a deleted drop on the map'
);
select ok(
  not (select private.public_api_visible(d) from public.drops d where d.id = current_setting('t.drop')::uuid),
  'the public API hides the deleted drop'
);
select is(
  (select (public.campaigns_for_map(-101, 39, -99, 41) -> 0 ->> 'drop_count')::integer),
  0,
  'the campaign total drops the deleted drop'
);
select is(
  (select jsonb_array_length(public.campaigns_for_map(-101, 39, -99, 41) -> 0 -> 'leaderboard')),
  0,
  'the leaderboard drops the author'
);
select ok(
  not exists (
    select 1 from public.user_badges ub
    join public.badges b on b.id = ub.badge_id
    where ub.user_id = '11111111-1111-4111-8111-111111111111' and b.slug = 'first_drop'
  ),
  'deleting the only drop takes First drop back'
);
select ok(
  not exists (
    select 1 from public.user_badges ub
    join public.badges b on b.id = ub.badge_id
    where ub.user_id = '11111111-1111-4111-8111-111111111111' and b.slug = 'founding_local'
  ),
  'founding local is recomputed without the deleted drop'
);
select is_empty(
  format(
    $$ select 1 from public.reputation_ledger
       where drop_id = %L::uuid and kind = 'moderation' $$,
    current_setting('t.drop')
  ),
  'an author delete is not a moderation strike'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  format('select public.restore_my_drop(%L::uuid)', current_setting('t.drop')),
  'the author can undo'
);

reset role;

select ok(
  (select status = 'active' and deleted_at is null and removed_reason is null
   from public.drops where id = current_setting('t.drop')::uuid),
  'undo puts the drop back'
);
select is(
  (select (public.campaigns_for_map(-101, 39, -99, 41) -> 0 ->> 'drop_count')::integer),
  1,
  'the campaign total counts the restored drop'
);
select ok(
  exists (
    select 1 from public.user_badges ub
    join public.badges b on b.id = ub.badge_id
    where ub.user_id = '11111111-1111-4111-8111-111111111111' and b.slug = 'first_drop'
  ),
  'undo gives First drop back'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select public.delete_my_drop(current_setting('t.drop')::uuid);
reset role;
update public.drops
set deleted_at = now() - interval '11 minutes'
where id = current_setting('t.drop')::uuid;

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select throws_ok(
  format('select public.restore_my_drop(%L::uuid)', current_setting('t.drop')),
  '22023',
  'undo_expired',
  'undo does not last forever'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  $$ select public.set_my_public_name('Bobby', 'alice') $$,
  '23505',
  'handle_taken',
  'a handle that is already taken is rejected'
);

select * from finish();
rollback;
