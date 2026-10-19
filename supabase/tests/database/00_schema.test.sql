-- Schema shape, privileges, taxonomy, and scheduled jobs.
begin;
create extension if not exists pgtap with schema extensions;
select plan(32);

-- Extensions
select has_extension('postgis', 'PostGIS is installed');
select has_extension('pg_trgm', 'pg_trgm is installed');
select has_extension('pg_jsonschema', 'pg_jsonschema is installed');
select has_extension('pg_cron', 'pg_cron is installed');
select has_extension('vector', 'pgvector is installed');

-- Tables
select is(
  (select array_agg(t order by t) from unnest(array[
    'profiles', 'user_moderation', 'user_blocks', 'tier_limits', 'content_filters',
    'category_groups', 'categories', 'category_versions', 'drops', 'drop_media',
    'confirmations', 'reports', 'moderation_actions'
  ]) as t where to_regclass('public.' || t) is null),
  null,
  'every core table exists'
);

select is(
  (select array_agg(c.relname::text order by c.relname)
   from pg_class c join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relkind in ('r', 'p') and not c.relrowsecurity),
  null,
  'RLS is enabled on every table in public'
);

-- Geography columns and indexes
select ok(
  (select a.atttypid = 'extensions.geography'::regtype and format_type(a.atttypid, a.atttypmod) like '%geography(Point,4326)'
   from pg_attribute a
   where a.attrelid = 'public.drops'::regclass and a.attname = 'geog'),
  'drops.geog is geography(Point,4326)'
);
select ok(
  exists (select 1 from pg_indexes where schemaname = 'public' and indexname = 'drops_display_geog_idx' and indexdef ilike '%using gist (display_geog)%'),
  'GiST index on drops.display_geog'
);
select ok(
  exists (select 1 from pg_indexes where schemaname = 'public' and indexname = 'drops_geog_idx' and indexdef ilike '%using gist (geog)%'),
  'GiST index on drops.geog'
);
select ok(
  exists (select 1 from pg_indexes where schemaname = 'public' and indexname = 'drops_text_trgm_idx' and indexdef ilike '%gin_trgm_ops%'),
  'trigram index on drop text'
);

-- Privileges
select ok(
  not has_table_privilege('authenticated', 'public.drops', 'insert'),
  'clients cannot insert drops directly'
);
select ok(
  not has_table_privilege('authenticated', 'public.confirmations', 'insert')
  and not has_table_privilege('authenticated', 'public.reports', 'insert')
  and not has_table_privilege('authenticated', 'public.moderation_actions', 'insert'),
  'clients cannot insert confirmations, reports, or moderation actions directly'
);
select ok(
  not has_column_privilege('authenticated', 'public.profiles', 'tier', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'role', 'update')
  and not has_column_privilege('authenticated', 'public.profiles', 'points', 'update'),
  'clients cannot change tier, role, or points'
);
select ok(
  not has_column_privilege('authenticated', 'public.drops', 'status', 'update')
  and not has_column_privilege('authenticated', 'public.drops', 'geog', 'update')
  and not has_column_privilege('authenticated', 'public.drops', 'confirm_count', 'update'),
  'clients cannot change drop status, location, or counters'
);
select ok(
  not has_table_privilege('anon', 'public.profiles', 'select')
  and not has_table_privilege('anon', 'public.drops', 'select'),
  'signed-out visitors cannot read profiles or raw drops'
);
select ok(
  not has_function_privilege('anon', 'public.create_drop(text,double precision,double precision,text,text,jsonb,text[],integer,timestamptz,boolean,jsonb,boolean,jsonb)', 'execute')
  and not has_function_privilege('anon', 'public.drops_nearby(double precision,double precision,integer,text[],integer)', 'execute'),
  'signed-out visitors cannot post or use proximity reveal'
);
select ok(
  has_function_privilege('anon', 'public.drops_in_view(double precision,double precision,double precision,double precision,text[],integer)', 'execute')
  and has_function_privilege('anon', 'public.drop_teaser(text)', 'execute'),
  'signed-out visitors can load the map viewport and share teasers'
);
select is(
  (select count(*)::integer from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname in ('public', 'private') and p.prosecdef
     and not exists (select 1 from unnest(coalesce(p.proconfig, '{}')) as c where c like 'search_path=%')),
  0,
  'every security definer function pins its search_path'
);
select is(
  (select count(*)::integer from information_schema.routine_privileges
   where routine_schema = 'private' and grantee = 'PUBLIC'),
  0,
  'nothing in private is executable by PUBLIC'
);

-- Taxonomy (PLAN §4.2)
select is((select count(*)::integer from public.category_groups), 11, '11 category groups');
select is(
  (select array_agg(s order by s) from unnest(array[
    'historical_fact', 'story', 'memorial',
    'viewpoint', 'trail_note', 'wildlife', 'water_source',
    'food_truck', 'eatery_tip', 'market',
    'event', 'popup',
    'yard_sale', 'for_sale', 'free_stuff',
    'hazard', 'safety_alert', 'incident_report',
    'place_of_worship', 'community_space', 'notice_board',
    'infrastructure', 'construction', 'underground',
    'hidden_gem', 'tip',
    'street_art', 'public_art', 'music_spot',
    'note'
  ]) as s where not exists (select 1 from public.categories c where c.slug = s and c.is_builtin and c.active)),
  null,
  'every PLAN §4.2 category is seeded and active'
);
select is((select count(*)::integer from public.categories), 32, 'exactly 32 built-in categories');
select is(
  (select row(c.min_tier_to_post, c.moderation_mode, c.premod_exempt_tier, c.location_precision_m,
              c.default_ttl, c.max_ttl, c.sensitive, c.confirmations_to_confirm, c.content_filters)::text
   from public.categories c where c.slug = 'incident_report'),
  row('regular'::public.user_tier, 'pre'::public.moderation_mode, 'trusted'::public.user_tier, 100,
      interval '72 hours', interval '72 hours', true, 2, '{contact_info,person_descriptors,street_address}'::text[])::text,
  'incident_report carries the strict crime rules'
);
select ok(
  (select c.attributes_schema -> 'required' ? 'occurred_at' and c.posting_notice like '%911%'
   from public.categories c where c.slug = 'incident_report'),
  'incident_report requires occurred_at and shows the emergency disclaimer'
);
select is(
  (select array_agg(c.slug order by c.slug) from public.categories c where c.location_precision_m > 0),
  array['incident_report'],
  'only incident reports are location-blurred'
);
select is((select count(*)::integer from public.category_versions where version = 1), 32, 'version 1 of every category is recorded');
select is((select count(*)::integer from public.tier_limits), 4, 'limits exist for all four tiers');

-- Editing a rule bumps the version and snapshots it; cosmetic sort changes do not
update public.categories set default_ttl = interval '48 hours' where slug = 'safety_alert';
update public.categories set sort = 99 where slug = 'safety_alert';
select is(
  (select c.version from public.categories c where c.slug = 'safety_alert'),
  2,
  'changing a rule bumps the category version once'
);
select ok(
  exists (select 1 from public.category_versions v join public.categories c on c.id = v.category_id
          where c.slug = 'safety_alert' and v.version = 2 and v.snapshot ->> 'default_ttl' = '48:00:00'),
  'the new version is snapshotted'
);

-- Jobs and the new-user trigger
select is(
  (select array_agg(j.jobname::text order by j.jobname) from cron.job j where j.jobname like 'xhere-%'),
  array['xhere-expire-drops', 'xhere-purge-deleted-accounts', 'xhere-purge-location-integrity', 'xhere-recompute-tiers'],
  'pg_cron jobs are scheduled'
);

insert into auth.users (id, email) values ('00000000-0000-4000-8000-0000000000aa', 'schema@example.test');
select is(
  (select p.tier::text || '/' || p.role::text from public.profiles p where p.id = '00000000-0000-4000-8000-0000000000aa'),
  'new/user',
  'a new auth user gets a New-tier profile'
);

select * from finish();
rollback;
