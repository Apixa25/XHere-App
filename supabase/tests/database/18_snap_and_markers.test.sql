-- Snap-to-drop alt text, and the historical marker category.
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

insert into auth.users (id, email) values
  ('11111111-1111-4111-8111-111111111111', 'alice@example.test');
update public.profiles set tier = 'trusted' where id = '11111111-1111-4111-8111-111111111111';

select is(
  (select icon from public.categories where slug = 'historical_marker'),
  '🪧',
  'historical markers have their own icon'
);

select ok(
  (select attributes_schema -> 'properties' ? 'inscription'
   from public.categories where slug = 'historical_marker'),
  'a historical marker can store an inscription'
);

set local role authenticated;
set local request.jwt.claims to '{"sub": "11111111-1111-4111-8111-111111111111", "role": "authenticated"}';

select public.create_drop(
  'note', 40.0, -100.0, 'Blue door',
  p_media => '[{
    "storage_path": "11111111-1111-4111-8111-111111111111/door.jpg",
    "media_kind": "image",
    "mime_type": "image/jpeg",
    "alt_text": "  A blue door on a brick building.  "
  }]'::jsonb
);

select is(
  (select alt_text from public.drop_media where storage_path like '%/door.jpg'),
  'A blue door on a brick building.',
  'alt text is stored trimmed on the photo'
);

select is(
  (select media -> 0 ->> 'alt_text'
   from public.drop_detail(
     (select id from public.drops where title = 'Blue door'),
     40.0, -100.0
   )),
  'A blue door on a brick building.',
  'the revealed card returns the alt text'
);

select public.create_drop(
  'note', 40.01, -100.0, 'Long alt',
  p_media => jsonb_build_array(jsonb_build_object(
    'storage_path', '11111111-1111-4111-8111-111111111111/long.jpg',
    'media_kind', 'image',
    'mime_type', 'image/jpeg',
    'alt_text', repeat('a', 400)
  ))
);

select is(
  (select char_length(alt_text) from public.drop_media where storage_path like '%/long.jpg'),
  300,
  'alt text is cut to 300 characters'
);

select public.create_drop(
  'note', 40.02, -100.0, 'No alt',
  p_media => '[{
    "storage_path": "11111111-1111-4111-8111-111111111111/plain.jpg",
    "media_kind": "image",
    "mime_type": "image/jpeg",
    "alt_text": "   "
  }]'::jsonb
);

select ok(
  (select alt_text is null from public.drop_media where storage_path like '%/plain.jpg'),
  'a blank alt text is stored as null'
);

set local role postgres;
select private.upsert_open_data_items($json$[
  {
    "source_slug": "osm-historical-markers",
    "source_name": "OpenStreetMap contributors",
    "source_home": "https://www.openstreetmap.org/copyright",
    "external_id": "osm:node/42",
    "category_slug": "historical_marker",
    "title": "Liberty plaque",
    "summary": "OpenStreetMap records this historical marker (plaque).",
    "lat": 42.095,
    "lng": -79.24,
    "source_url": "https://www.openstreetmap.org/node/42",
    "license": "ODbL 1.0",
    "attribution": "OpenStreetMap contributors, ODbL 1.0",
    "region_id": "us-ny-test",
    "attributes": {
      "citation": "OpenStreetMap",
      "source_url": "https://www.openstreetmap.org/node/42",
      "inscription": "Dedicated in 1892.",
      "marker_kind": "plaque",
      "hmdb_url": "https://www.hmdb.org/m.asp?m=1"
    }
  }
]$json$::jsonb);

select is(
  (select attributes ->> 'inscription'
   from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   where i.external_id = 'osm:node/42'),
  'Dedicated in 1892.',
  'a marker keeps the OpenStreetMap inscription and only links to HMdb'
);

select is(
  (select attributes ->> 'hmdb_url'
   from public.drops d
   join public.open_data_items i on i.drop_id = d.id
   where i.external_id = 'osm:node/42'),
  'https://www.hmdb.org/m.asp?m=1',
  'HMdb is stored only as a link'
);

select * from finish();
rollback;
