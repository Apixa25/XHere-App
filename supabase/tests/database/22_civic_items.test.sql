-- Town Hall: address keys, snapshot upsert, nearby hearings, and no fake permits.
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

select is(
  private.civic_address_key('200 East Third Street, Jamestown, NY 14701'),
  '200 east 3rd st jamestown',
  'Third Street and a trailing ZIP fold into one key'
);
select is(
  private.civic_address_key('200 East 3rd Street, Jamestown, New York'),
  private.civic_address_key('200 E Third Street, Jamestown, NY 14701'),
  '3rd and Third share a key'
);
select ok(
  private.civic_address_match('West Third Street, Jamestown', '10 West Third Street, Jamestown, NY 14701'),
  'a followed street matches a longer official address'
);
select ok(
  not private.civic_address_match('st', '200 East Third Street, Jamestown, NY'),
  'one word is not enough to follow'
);

select is(
  private.upsert_civic_items($json$[
    {
      "region_id": "us-ny-test",
      "source_slug": "city-record",
      "external_id": "hearing-1",
      "item_type": "hearing",
      "title": "Zoning hearing",
      "summary": "Variance",
      "occurs_on": "2099-01-02",
      "body_name": "Planning Board",
      "source_url": "https://example.com/hearing",
      "address_text": "10 Oak Street, Testville, NY",
      "lat": 40.0001,
      "lng": -100.0001,
      "attribution": "Example City",
      "license": "Public record",
      "expires_at": "2099-01-03T05:00:00Z"
    },
    {
      "region_id": "us-ny-test",
      "source_slug": "city-record",
      "external_id": "permit-1",
      "item_type": "permit",
      "title": "Hall Avenue work",
      "summary": "A permit",
      "occurs_on": "2026-09-21",
      "body_name": "Building",
      "source_url": "https://example.com/permit",
      "address_text": "Hall Avenue, Testville, NY",
      "lat": 40.02,
      "lng": -100.02,
      "attribution": "Example City",
      "license": "Public record",
      "expires_at": "2099-06-01T05:00:00Z"
    },
    {
      "region_id": "us-ny-test",
      "source_slug": "city-record",
      "external_id": "notice-1",
      "item_type": "notice",
      "title": "Viaduct notice",
      "summary": "Unpinned",
      "occurs_on": "2026-10-05",
      "body_name": "Clerk",
      "source_url": "https://example.com/notice",
      "address_text": "Main Street Viaduct, Testville, NY",
      "attribution": "Example City",
      "license": "Public record",
      "expires_at": "2099-04-01T05:00:00Z"
    },
    {
      "region_id": "us-ny-test",
      "source_slug": "city-record",
      "external_id": "old-1",
      "item_type": "agenda_item",
      "title": "Old meeting",
      "summary": "Past",
      "occurs_on": "2020-01-01",
      "body_name": "Council",
      "source_url": "https://example.com/old",
      "address_text": "200 East Third Street",
      "lat": 40.0001,
      "lng": -100.0001,
      "attribution": "Example City",
      "license": "Public record",
      "expires_at": "2020-01-02T05:00:00Z"
    }
  ]$json$::jsonb),
  4,
  'four civic rows load'
);

select ok(
  (select public.civic_items_near(40.0, -100.0, 300) -> 0 ->> 'title') = 'Zoning hearing'
    and jsonb_array_length(public.civic_items_near(40.0, -100.0, 300)) = 1,
  '300 m keeps the hearing and drops the permit a kilometer away'
);
select ok(
  exists (
    select 1 from jsonb_array_elements(public.civic_upcoming(40.0, -100.0, 25000)) e
    where e ->> 'title' = 'Viaduct notice' and e ->> 'lat' is null
  ),
  'a citywide notice with no pin is listed for the region'
);
select ok(
  not exists (
    select 1 from jsonb_array_elements(public.civic_upcoming(40.0, -100.0, 25000)) e
    where e ->> 'title' in ('Hall Avenue work', 'Old meeting')
  ),
  'permits and expired meetings stay off the meeting list'
);

select ok(private.archive_civic_items() >= 1, 'the nightly job archives expired rows');
select ok(
  (select status = 'archived' from public.civic_items where external_id = 'old-1'),
  'the old meeting is archived'
);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice-civic@example.test'),
  ('22222222-2222-4222-8222-222222222222', 'bob-civic@example.test');
select set_config('t.key', private.civic_address_key('10 Oak Street, Testville'), true);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select is(
  public.follow_civic_address('us-ny-test', '10 Oak Street, Testville') ->> 'address_key',
  current_setting('t.key'),
  'following an address stores the comparable key'
);
select is(
  public.my_civic_notices() -> 0 ->> 'title',
  'Zoning hearing',
  'a follow notifies the hearing already on that street'
);

select public.mark_civic_notice_read((public.my_civic_notices() -> 0 ->> 'id')::uuid);
select ok(
  (public.my_civic_notices() -> 0 ->> 'read_at') is not null,
  'opening the notice marks it read'
);

set local request.jwt.claims to '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}';
select is(public.my_civic_notices(), '[]'::jsonb, 'another person does not see that notice');

reset role;
select private.upsert_civic_items($json$[
  {
    "region_id": "us-ny-test",
    "source_slug": "city-record",
    "external_id": "permit-1",
    "item_type": "permit",
    "title": "Hall Avenue work",
    "summary": "A permit",
    "occurs_on": "2026-09-21",
    "body_name": "Building",
    "source_url": "https://example.com/permit",
    "address_text": "Hall Avenue, Testville, NY",
    "lat": 40.02,
    "lng": -100.02,
    "attribution": "Example City",
    "license": "Public record",
    "expires_at": "2099-06-01T05:00:00Z"
  }
]$json$::jsonb);
select ok(
  (select status = 'archived' from public.civic_items where external_id = 'hearing-1'),
  'a row missing from the next snapshot is archived'
);

select ok(
  (select bool_and(c.relrowsecurity)
   from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public'
     and c.relname in ('civic_items', 'civic_follows', 'civic_notices')),
  'RLS is enabled on the Town Hall tables'
);
select ok(
  not has_function_privilege('anon', 'public.follow_civic_address(text, text)', 'execute'),
  'a signed-out visitor cannot follow an address'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';
select throws_ok(
  $$select public.create_drop('civic_permit', 40.0, -100.0, 'Fake permit', 'A person wrote this.')$$,
  '42501',
  'civic_not_a_user_drop',
  'a person cannot post a permit'
);

select * from finish();
rollback;
