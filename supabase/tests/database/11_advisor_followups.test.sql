-- Hosted-advisor follow-ups: lint 0003, 0006, 0001, and RLS on private.tip_lines.
-- Access checks prove the rewrites did not widen or shrink who can read or write.
begin;
create extension if not exists pgtap with schema extensions;
select plan(25);

-- Lint 0003. pg_policies.qual is the deparsed expression the advisor reads.
-- A wrapped call deparses as "( SELECT auth.uid() AS uid)", which still
-- contains "select auth.uid()". A bare call does not.
select is(
  (select count(*)::integer
   from pg_catalog.pg_policies
   where schemaname = 'public'
     and (
       (qual like '%auth.uid()%' and lower(qual) not like '%select auth.uid()%')
       or (with_check like '%auth.uid()%' and lower(with_check) not like '%select auth.uid()%')
     )),
  0,
  'public policies wrap auth.uid() in a scalar subquery'
);
select is(
  (select count(*)::integer
   from pg_catalog.pg_policies
   where schemaname = 'public'
     and (coalesce(qual, '') like '%auth.uid()%' or coalesce(with_check, '') like '%auth.uid()%')),
  23,
  'the auth.uid() policies are still there, including location flags'
);

-- Lint 0006. SELECT is only the public-read policy. Writes are split.
-- Compare names with IN (name vs unknown) rather than results_eq, which
-- errors on this database's collation.
select ok(
  (select count(*) = 4
      and count(*) filter (where pol.polname in (
        'tier_limits_admin_delete',
        'tier_limits_admin_insert',
        'tier_limits_admin_update',
        'tier_limits_select'
      )) = 4
   from pg_policy pol
   join pg_class c on c.oid = pol.polrelid
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relname = 'tier_limits'),
  'tier_limits policies are select plus insert, update, and delete'
);
select ok(
  (select count(*) = 4
      and count(*) filter (where pol.polname in (
        'content_filters_admin_delete',
        'content_filters_admin_insert',
        'content_filters_admin_update',
        'content_filters_select'
      )) = 4
   from pg_policy pol
   join pg_class c on c.oid = pol.polrelid
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relname = 'content_filters'),
  'content_filters policies are select plus insert, update, and delete'
);
select is(
  (select count(*)::integer from (
     select r.rolname, act.cmd
     from pg_policy p
     join pg_class c on p.polrelid = c.oid
     join pg_namespace n on c.relnamespace = n.oid
     join pg_roles r on p.polroles @> array[r.oid]
     , lateral (
       select x.cmd from unnest(
         case p.polcmd
           when 'r' then array['SELECT']
           when 'a' then array['INSERT']
           when 'w' then array['UPDATE']
           when 'd' then array['DELETE']
           when '*' then array['SELECT', 'INSERT', 'UPDATE', 'DELETE']
         end
       ) x(cmd)
     ) act(cmd)
     where p.polpermissive
       and n.nspname = 'public'
       and c.relname in ('tier_limits', 'content_filters')
       and r.rolname in ('anon', 'authenticated')
     group by c.relname, r.rolname, act.cmd
     having count(*) > 1
   ) dups),
  0,
  'tier_limits and content_filters have one permissive policy per role and action'
);

-- Lint 0001. The covering-index check matches the advisor: the index's
-- leading columns equal the foreign key's column list, in that order.
select ok(
  (select count(*) = 6 from pg_indexes
   where schemaname = 'public' and indexname in (
     'category_suggestion_log_chosen_category_idx',
     'drop_media_owner_idx',
     'drops_category_version_idx',
     'moderation_actions_actor_idx',
     'reports_resolved_by_idx',
     'user_moderation_updated_by_idx'
   )),
  'the six previously unindexed foreign keys have indexes'
);
select is(
  (with foreign_keys as (
     select ct.conrelid as table_oid, ct.conkey as col_attnums
     from pg_constraint ct
     join pg_class cl on cl.oid = ct.conrelid
     join pg_namespace ns on ns.oid = cl.relnamespace
     where ct.contype = 'f' and ns.nspname in ('public', 'private')
   ),
   index_ as (
     select pi.indrelid as table_oid,
            string_to_array(pi.indkey::text, ' ')::smallint[] as col_attnums
     from pg_index pi
     where pi.indisvalid
   )
   select count(*)::integer
   from foreign_keys fk
   left join index_ idx
     on fk.table_oid = idx.table_oid
    and fk.col_attnums = idx.col_attnums[1:array_length(fk.col_attnums, 1)]
   where idx.table_oid is null),
  0,
  'every public and private foreign key has a covering index'
);

select ok(
  (select c.relrowsecurity
   from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'private' and c.relname = 'tip_lines'),
  'private.tip_lines has row level security enabled'
);
select is(
  (select count(*)::integer
   from pg_policy pol
   join pg_class c on c.oid = pol.polrelid
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'private' and c.relname = 'tip_lines'),
  0,
  'tip lines have no client policies'
);
select ok(
  not has_table_privilege('anon', 'private.tip_lines', 'select')
  and not has_table_privilege('authenticated', 'private.tip_lines', 'select')
  and not has_table_privilege('authenticated', 'private.tip_lines', 'insert'),
  'clients still have no privileges on tip lines'
);

-- Same effective access, under the rewritten policies.
insert into auth.users (id, email) values
  ('aaaa1111-1111-4111-8111-111111111111', 'ada.followup@example.test'),
  ('bbbb2222-2222-4222-8222-222222222222', 'adam.followup@example.test');
update public.profiles set tier = 'regular' where id = 'aaaa1111-1111-4111-8111-111111111111';
update public.profiles set tier = 'trusted', role = 'admin' where id = 'bbbb2222-2222-4222-8222-222222222222';

set local role authenticated;
set local request.jwt.claims to '{"sub": "aaaa1111-1111-4111-8111-111111111111", "role": "authenticated"}';

select lives_ok(
  $$ update public.profiles set display_name = 'Ada' where id = 'aaaa1111-1111-4111-8111-111111111111' $$,
  'wrapped auth.uid() still lets a user edit their own profile'
);
select is(
  (select display_name from public.profiles where id = 'aaaa1111-1111-4111-8111-111111111111'),
  'Ada',
  'the own-profile update landed'
);
do $$
declare
  n integer;
begin
  update public.profiles set display_name = 'hacked' where id = 'bbbb2222-2222-4222-8222-222222222222';
  get diagnostics n = row_count;
  perform set_config('t.other_profile', n::text, true);
  update public.tier_limits set drops_per_hour = 99 where tier = 'new';
  get diagnostics n = row_count;
  perform set_config('t.tier_upd', n::text, true);
  delete from public.content_filters where slug = 'contact_info';
  get diagnostics n = row_count;
  perform set_config('t.filter_del', n::text, true);
end;
$$;
select is(current_setting('t.other_profile'), '0', 'wrapped auth.uid() still blocks edits to someone else');
select is(current_setting('t.tier_upd'), '0', 'non-admins cannot update tier limits');
select is(current_setting('t.filter_del'), '0', 'non-admins cannot delete content filters');
select throws_ok(
  $$ insert into public.content_filters (slug, description, pattern, message)
     values ('sneaky', 'x', 'z', 'x') $$,
  '42501', null,
  'non-admins cannot insert content filters'
);
select is(
  (select drops_per_hour from public.tier_limits where tier = 'new'),
  3,
  'signed-in users can still read tier limits'
);

set local request.jwt.claims to '{"sub": "bbbb2222-2222-4222-8222-222222222222", "role": "authenticated"}';
select lives_ok(
  $$ update public.tier_limits set drops_per_hour = 4 where tier = 'new' $$,
  'admins can update tier limits'
);
select is(
  (select drops_per_hour from public.tier_limits where tier = 'new'),
  4,
  'the admin tier-limit update landed'
);
select lives_ok(
  $$ insert into public.content_filters (slug, description, pattern, message)
     values ('advisor_probe', 'probe', 'zz', 'probe') $$,
  'admins can insert content filters'
);
select lives_ok(
  $$ update public.content_filters set description = 'probe 2' where slug = 'advisor_probe' $$,
  'admins can update content filters'
);
select lives_ok(
  $$ delete from public.content_filters where slug = 'advisor_probe' $$,
  'admins can delete content filters'
);
select lives_ok(
  $$ select public.create_drop(
       'incident_report', 42.0970, -79.2353, 'Tip line still works', 'Lock was cut.',
       jsonb_build_object('occurred_at', to_jsonb(now())),
       p_acknowledge_guidelines => true) $$,
  'create_drop still reads private.tip_lines after RLS is enabled'
);

reset role;
set local role anon;
select is(
  (select count(*)::integer from public.tier_limits),
  4,
  'signed-out visitors can still read tier limits'
);
select cmp_ok(
  (select count(*)::integer from public.content_filters),
  '>=',
  3,
  'signed-out visitors can still read content filters'
);

select * from finish();
rollback;
