-- Open-data base layer: provenance, upsert/dedupe, map payload, and
-- "this is not a user's contribution".
begin;
create extension if not exists pgtap with schema extensions;
select plan(24);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob@example.test');
update public.profiles set tier = 'trusted', handle = 'alice' where id = '11111111-1111-4111-8111-111111111111';

select has_table('public', 'open_data_sources', 'open_data_sources exists');
select has_table('public', 'open_data_items', 'open_data_items exists');
select ok(
  (select bool_and(c.relrowsecurity)
   from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relname in ('open_data_sources', 'open_data_items')),
  'RLS is enabled on the open-data tables'
);

select is(
  private.upsert_open_data_items($json$[
    {
      "source_slug": "wikipedia-geo",
      "source_name": "Wikipedia",
      "source_home": "https://en.wikipedia.org/",
      "external_id": "enwiki:1",
      "category_slug": "historical_fact",
      "title": "Test Library",
      "summary": "Wikipedia article. Open the source for what is written there.",
      "lat": 42.097,
      "lng": -79.24,
      "source_url": "https://en.wikipedia.org/wiki/Test_Library",
      "license": "CC BY-SA 4.0",
      "attribution": "Wikipedia contributors, CC BY-SA 4.0",
      "region_id": "us-ny-test",
      "attributes": {
        "source_url": "https://en.wikipedia.org/wiki/Test_Library",
        "citation": "Wikipedia"
      }
    }
  ]$json$::jsonb),
  1,
  'the first import inserts one place'
);

select ok(
  (select d.author_id is null and d.source = 'open_data' and c.slug = 'historical_fact'
   from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   join public.categories c on c.id = d.category_id
   where i.external_id = 'enwiki:1'),
  'the imported drop has no author, source open_data, and an existing category'
);

select is(
  private.upsert_open_data_items($json$[
    {
      "source_slug": "wikipedia-geo",
      "source_name": "Wikipedia",
      "source_home": "https://en.wikipedia.org/",
      "external_id": "enwiki:1",
      "category_slug": "historical_fact",
      "title": "Test Library revised",
      "summary": "Wikipedia article. Open the source for what is written there.",
      "lat": 42.098,
      "lng": -79.241,
      "source_url": "https://en.wikipedia.org/wiki/Test_Library",
      "license": "CC BY-SA 4.0",
      "attribution": "Wikipedia contributors, CC BY-SA 4.0",
      "region_id": "us-ny-test",
      "attributes": {
        "source_url": "https://en.wikipedia.org/wiki/Test_Library",
        "citation": "Wikipedia"
      }
    }
  ]$json$::jsonb),
  1,
  'a re-run reports the same row'
);

select is(
  (select count(*)::integer from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   where i.source_slug = 'wikipedia-geo' and i.external_id = 'enwiki:1'),
  1,
  're-run updates instead of inserting a duplicate'
);
select is(
  (select d.title from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   where i.external_id = 'enwiki:1'),
  'Test Library revised',
  're-run refreshes the title'
);

do $$
begin
  perform set_config(
    't.open',
    (select d.id::text from public.drops d
     join public.open_data_items i on i.drop_id = d.id
     where i.external_id = 'enwiki:1'),
    true
  );
end;
$$;

-- bob is nowhere near Jamestown. The public record is still readable.
set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';

select ok(
  (select revealed
      and body is not null
      and source = 'open_data'
      and is_mine = false
      and author_handle is null
      and source_name = 'Wikipedia'
      and source_url = 'https://en.wikipedia.org/wiki/Test_Library'
      and license = 'CC BY-SA 4.0'
      and attribution = 'Wikipedia contributors, CC BY-SA 4.0'
   from public.drop_detail(current_setting('t.open')::uuid, 40.0, -100.0)),
  'a public record stays revealed far away, with attribution and no author'
);

select is(
  (select source from public.drops_in_view(-79.25, 42.09, -79.23, 42.11, null, 16)
   where drop_id = current_setting('t.open')::uuid),
  'open_data',
  'the map viewport marks the pin as open data'
);

select throws_ok(
  format(
    'select public.confirm_drop(%L::uuid, %L::public.confirmation_verdict, 42.098, -79.241)',
    current_setting('t.open'),
    'still_true'
  ),
  '22023',
  'not_a_user_drop',
  'confirming a public record is rejected'
);

-- alice posts her own drop. It must not pick up the imported place.
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
do $$
begin
  perform set_config(
    't.mine',
    public.create_drop('note', 42.096, -79.242, 'My bench', 'Shade after two.') ->> 'id',
    true
  );
end;
$$;

select is(
  (select count(*)::integer from public.drops where author_id = '11111111-1111-4111-8111-111111111111'),
  1,
  'the imported place is not one of alice''s drops'
);
select is(
  (select points from public.profiles where id = '11111111-1111-4111-8111-111111111111'),
  0,
  'importing a place does not give anyone points'
);
select ok(
  (select public.export_my_data()::text not like '%Test Library%'
      and public.export_my_data()::text not like '%enwiki:1%'),
  'account export does not include public records'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select ok(
  (select not revealed and body is null and source = 'user' and attribution is null
   from public.drop_detail(current_setting('t.mine')::uuid, 40.0, -100.0)),
  'a user drop far away is still only a teaser'
);

reset role;

select throws_ok(
  $$ select private.upsert_open_data_items('[{
    "source_slug": "hmdb",
    "source_name": "HMdb",
    "external_id": "hmdb:1",
    "category_slug": "historical_fact",
    "title": "A marker",
    "summary": "no",
    "lat": 42.1,
    "lng": -79.2,
    "source_url": "https://www.hmdb.org/m.asp?m=1",
    "license": "none",
    "attribution": "no",
    "region_id": "us-ny-test",
    "attributes": {}
  }]'::jsonb) $$,
  '22023',
  'license_not_allowed',
  'HMdb content is refused'
);

select is(
  private.upsert_open_data_items($json$[
    {
      "source_slug": "wikipedia-geo",
      "source_name": "Wikipedia",
      "source_home": "https://en.wikipedia.org/",
      "external_id": "enwiki:2",
      "category_slug": "memorial",
      "title": "Test Memorial",
      "summary": "Wikipedia article. Open the source for what is written there.",
      "lat": 42.099,
      "lng": -79.242,
      "source_url": "https://en.wikipedia.org/wiki/Test_Memorial",
      "license": "CC BY-SA 4.0",
      "attribution": "Wikipedia contributors, CC BY-SA 4.0",
      "region_id": "us-ny-test",
      "attributes": {"citation": "Wikipedia"}
    }
  ]$json$::jsonb),
  1,
  'a later snapshot can add a different record'
);
select is(
  (select d.status::text from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   where i.external_id = 'enwiki:1'),
  'removed',
  'a record missing from the new snapshot is hidden'
);
select is(
  (select d.status::text from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   where i.external_id = 'enwiki:2'),
  'active',
  'the record still in the snapshot stays on the map'
);

select is(
  private.upsert_open_data_items($json$[
    {
      "source_slug": "wikipedia-geo",
      "source_name": "Wikipedia",
      "source_home": "https://en.wikipedia.org/",
      "external_id": "enwiki:1",
      "category_slug": "historical_fact",
      "title": "Test Library revised",
      "summary": "Wikipedia article. Open the source for what is written there.",
      "lat": 42.098,
      "lng": -79.241,
      "source_url": "https://en.wikipedia.org/wiki/Test_Library",
      "license": "CC BY-SA 4.0",
      "attribution": "Wikipedia contributors, CC BY-SA 4.0",
      "region_id": "us-ny-test",
      "attributes": {
        "source_url": "https://en.wikipedia.org/wiki/Test_Library",
        "citation": "Wikipedia"
      }
    }
  ]$json$::jsonb),
  1,
  'the original external id can be imported again'
);
select is(
  (select d.status::text from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   where i.external_id = 'enwiki:1'),
  'active',
  're-importing a hidden external id makes it active again'
);

set local role anon;
select is(
  (select attribution from public.open_data_items where external_id = 'enwiki:1'),
  'Wikipedia contributors, CC BY-SA 4.0',
  'signed-out visitors can read attribution'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select throws_ok(
  $$ insert into public.open_data_sources (slug, name, license, attribution, url)
     values ('nope-source', 'Nope', 'none', 'none', 'https://example.com/license') $$,
  '42501',
  'permission denied for table open_data_sources',
  'clients cannot insert sources'
);
select throws_ok(
  $$ select private.upsert_open_data_items('[]'::jsonb) $$,
  '42501',
  'permission denied for function upsert_open_data_items',
  'clients cannot run the importer'
);

select * from finish();
rollback;
