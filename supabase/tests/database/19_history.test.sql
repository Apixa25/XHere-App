-- Time Machine and "This day here". The phone reads a cache. It does not
-- call loc.gov. A sheet is an overlay only when it has three control points.
begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

select ok(
  (select bool_and(c.relrowsecurity)
   from pg_class c
   join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public'
     and c.relname in (
       'history_regions', 'history_sheets', 'history_photos',
       'history_georef', 'history_clippings'
     )),
  'RLS is enabled on the history cache'
);

select ok(
  not has_table_privilege('authenticated', 'public.history_sheets', 'select')
  and not has_table_privilege('anon', 'public.history_clippings', 'select')
  and not has_table_privilege('authenticated', 'public.history_regions', 'insert'),
  'clients cannot read or write the history tables'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.history_at(double precision, double precision)',
    'execute'
  )
  and not has_function_privilege(
    'anon',
    'public.clippings_at(double precision, double precision, text[])',
    'execute'
  )
  and not has_function_privilege('authenticated', 'public.history_upsert(jsonb)', 'execute')
  and not has_function_privilege('authenticated', 'public.history_claim_fetch()', 'execute')
  and not has_function_privilege('authenticated', 'public.history_bundle(text)', 'execute'),
  'anon cannot read history and a signed-in client cannot refresh it'
);

select ok(
  (select bool_and(p.prosecdef and exists (
      select 1 from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%'
    ))
   from pg_proc p
   join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public'
     and p.proname in ('history_at', 'clippings_at', 'history_upsert', 'history_claim_fetch', 'history_bundle')),
  'history functions are security definer with a locked search_path'
);

set local role service_role;

select ok(
  public.history_claim_fetch(),
  'the first refresh in the window is allowed'
);
select ok(
  not public.history_claim_fetch(),
  'a second refresh inside two seconds is refused'
);

select lives_ok(
  $$select public.history_upsert($history${
    "region": {
      "region_id": "us-ny-history-test",
      "name": "History Test",
      "country": "US",
      "town": "Jamestown",
      "state": "New York",
      "west": -80.0,
      "south": 41.0,
      "east": -79.5,
      "north": 42.0
    },
    "sheets": [{
      "id": "sheet-1886",
      "title": "Test sheet",
      "year": 1886,
      "date_label": "1886",
      "item_url": "https://www.loc.gov/item/example/",
      "iiif_service": "https://tile.loc.gov/image-services/iiif/service:example",
      "thumbnail_url": null,
      "sheet_index": 2,
      "digitized": true,
      "attribution": "Library of Congress, Geography and Map Division, Sanborn Maps Collection. Public domain.",
      "license": "Public domain"
    }],
    "photos": [
      {
        "id": "town-photo",
        "source": "loc",
        "title": "Third Street",
        "year": 1905,
        "date_label": "1905",
        "latitude": null,
        "longitude": null,
        "thumb_url": null,
        "image_url": null,
        "page_url": "https://www.loc.gov/item/town-photo/",
        "creator": null,
        "license": "No known restrictions",
        "attribution": "Library of Congress."
      },
      {
        "id": "far-photo",
        "source": "commons",
        "title": "Somewhere else",
        "year": 2020,
        "date_label": "2020",
        "latitude": 40.0,
        "longitude": -70.0,
        "thumb_url": null,
        "image_url": "https://commons.wikimedia.org/example.jpg",
        "page_url": "https://commons.wikimedia.org/wiki/File:Example",
        "creator": "Example",
        "license": "CC BY 4.0",
        "attribution": "Wikimedia Commons. Example. CC BY 4.0."
      }
    ],
    "georef": [
      {
        "sheet_id": "sheet-1886",
        "width": 100,
        "height": 100,
        "image_url": "https://tile.loc.gov/example/full/100,/0/default.jpg",
        "points": [{"pixel_x": 1, "pixel_y": 1, "longitude": -79.7, "latitude": 41.5}],
        "attribution": "too few points"
      }
    ],
    "clippings": [{
      "id": "paper-1",
      "terms": ["West 3rd Street"],
      "title": "West 3rd Street",
      "year": 1898,
      "month": 6,
      "day": 14,
      "snippet": "The pavement on West 3rd Street in Jamestown was torn up for the new main and the block was closed.",
      "page_url": "https://www.loc.gov/resource/example/1898/",
      "image_url": "https://tile.loc.gov/example/full/200,/0/default.jpg",
      "paper": "Example",
      "paper_city": "Jamestown",
      "paper_state": "New York",
      "attribution": "Chronicling America, Library of Congress. Public domain.",
      "license": "Public domain"
    }]
  }$history$::jsonb)$$,
  'service role can replace a region cache'
);

select lives_ok(
  $$select public.history_upsert($history${
    "region": {
      "region_id": "us-nd-history-test",
      "name": "Other Jamestown",
      "country": "US",
      "town": "Jamestown",
      "state": "North Dakota",
      "west": -99.0,
      "south": 46.0,
      "east": -98.0,
      "north": 47.0
    },
    "sheets": [],
    "photos": [],
    "georef": [],
    "clippings": [{
      "id": "paper-nd",
      "terms": ["West 3rd Street"],
      "title": "North Dakota",
      "year": 1901,
      "month": 1,
      "day": 2,
      "snippet": "West 3rd Street in Jamestown, North Dakota, held a meeting that ran until the lamps were lit.",
      "page_url": "https://www.loc.gov/resource/example/nd/",
      "image_url": null,
      "paper": "Jamestown Alert",
      "paper_city": "Jamestown",
      "paper_state": "North Dakota",
      "attribution": "Chronicling America, Library of Congress. Public domain.",
      "license": "Public domain"
    }]
  }$history$::jsonb)$$,
  'a second region can hold its own paper lines'
);

reset role;
set local role authenticated;

select is(
  public.history_at(41.5, -79.7) ->> 'region_id',
  'us-ny-history-test',
  'a point in the box reads that region'
);
select is(
  public.history_at(41.5, -79.7) -> 'georef',
  '[]'::jsonb,
  'two control points are not an overlay'
);
select ok(
  (select jsonb_array_length(public.history_at(41.5, -79.7) -> 'photos') = 1),
  'a town photo with no coordinates is included and a far photo is not'
);
select ok(
  public.history_at(10, 10) ->> 'region_id' is null,
  'a point outside every box is an empty place'
);
select is(
  jsonb_array_length(public.clippings_at(41.5, -79.7, array['WEST 3RD STREET'])),
  1,
  'terms match without caring about case'
);
select is(
  public.clippings_at(46.5, -98.5, array['west 3rd street']) -> 0 ->> 'id',
  'paper-nd',
  'the other Jamestown returns its own paper, not New York'
);
select is(
  jsonb_array_length(public.clippings_at(41.5, -79.7, array['west 3rd street'])),
  1,
  'New York does not receive the North Dakota line'
);

reset role;
set local role service_role;
select ok(
  public.history_bundle('us-ny-history-test') ->> 'town' = 'Jamestown',
  'the refresh door can read the cached bundle'
);

select * from finish();
rollback;
