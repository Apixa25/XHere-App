-- Downtown Jamestown history walk.
--
-- XHere is global. This file is the pilot region's walk, the same way
-- seed/open-data/jamestown-ny.sql is the pilot region's places.
-- Apply that open-data file first. This one only names those places.
--
-- Every stop is a public-record drop already in that snapshot. Titles below
-- are the snapshot titles. Nothing here adds a historical claim.
-- Distance is the straight line between the published coordinates (PostGIS
-- geography), and the time is that length at 75 meters per minute.
-- Re-running updates the same region slug and the same stop positions.

insert into public.walks (
  region_id, slug, title, description,
  distance_m, duration_min,
  min_lng, min_lat, max_lng, max_lat
) values (
  'us-ny-jamestown',
  'downtown-history',
  'Downtown Jamestown history walk',
  'A walk past public records in downtown Jamestown, New York. Each stop is a place already on the map. National Register stops are a building or a district in Jamestown, New York. Wikipedia stops are articles about the named place. The distance is the straight line between those published points.',
  1,
  1,
  -79.32, 42.05, -79.18, 42.14
)
on conflict (region_id, slug) do update
set title = excluded.title,
    description = excluded.description,
    min_lng = excluded.min_lng,
    min_lat = excluded.min_lat,
    max_lng = excluded.max_lng,
    max_lat = excluded.max_lat,
    active = true;

insert into public.walk_stops (walk_id, position, source_slug, external_id)
select w.id, v.position, v.source_slug, v.external_id
from public.walks w
join (values
  (1, 'nrhp', 'nrhp:03000045'),
  (2, 'wikipedia-geo', 'enwiki:58462917'),
  (3, 'wikipedia-geo', 'enwiki:27747830'),
  (4, 'wikipedia-geo', 'enwiki:10959769'),
  (5, 'wikipedia-geo', 'enwiki:64045862'),
  (6, 'nrhp', 'nrhp:14000935'),
  (7, 'wikipedia-geo', 'enwiki:47375466'),
  (8, 'nrhp', 'nrhp:100011762')
) as v(position, source_slug, external_id) on true
where w.region_id = 'us-ny-jamestown'
  and w.slug = 'downtown-history'
on conflict (walk_id, position) do update
set source_slug = excluded.source_slug,
    external_id = excluded.external_id;

-- Published coordinates from supabase/seed/open-data/jamestown-ny.json.
-- Kept here so the length does not depend on a drop existing yet.
with pts(position, lat, lng) as (
  values
    (1, 42.094509::float8, -79.244603::float8),
    (2, 42.094722::float8, -79.243889::float8),
    (3, 42.095922::float8, -79.242619::float8),
    (4, 42.09651::float8, -79.24161::float8),
    (5, 42.096689::float8, -79.239267::float8),
    (6, 42.096357::float8, -79.237379::float8),
    (7, 42.0983::float8, -79.23764::float8),
    (8, 42.098818::float8, -79.238704::float8)
),
geogs as (
  select position,
         extensions.st_setsrid(extensions.st_makepoint(lng, lat), 4326)::extensions.geography as geog
  from pts
),
total as (
  select round(sum(extensions.st_distance(a.geog, b.geog)))::integer as meters
  from geogs a
  join geogs b on b.position = a.position + 1
)
update public.walks w
set distance_m = total.meters,
    duration_min = greatest(1, ceil(total.meters / 75.0)::integer)
from total
where w.region_id = 'us-ny-jamestown'
  and w.slug = 'downtown-history';
