-- Pilot campaign and founding cap for Jamestown, New York.
--
-- XHere is global. This file is the pilot region's program, the same way
-- seed/walks/jamestown-ny.sql is the pilot walk. The numbers match
-- supabase/regions/us/jamestown-ny.yaml (programs:).
--
-- starts_at is null in that config, so the clock starts when this file is
-- applied. Re-running keeps those dates and refreshes the title, the goal,
-- and the cap. Apply it after the migrations. db push does not load it.

insert into public.regions (
  id, name, country, min_lng, min_lat, max_lng, max_lat, timezone
) values (
  'us-ny-jamestown',
  'Jamestown, New York, USA',
  'US',
  -79.32, 42.05, -79.18, 42.14,
  'America/New_York'
)
on conflict (id) do update
set name = excluded.name,
    country = excluded.country,
    min_lng = excluded.min_lng,
    min_lat = excluded.min_lat,
    max_lng = excluded.max_lng,
    max_lat = excluded.max_lat,
    timezone = excluded.timezone,
    active = true;

insert into public.region_founding (region_id, cap)
values ('us-ny-jamestown', 25)
on conflict (region_id) do update
set cap = excluded.cap;

insert into public.campaigns (
  region_id, slug, title, description, goal_drops, starts_at, ends_at, leaderboard_size
)
select
  'us-ny-jamestown',
  'map-jamestown',
  'Map Jamestown',
  'Add real drops in Jamestown over 14 days. Public records already on the map do not count.',
  200,
  clock.t,
  clock.t + interval '14 days',
  8
from (select now() as t) clock
on conflict (region_id, slug) do update
set title = excluded.title,
    description = excluded.description,
    goal_drops = excluded.goal_drops,
    leaderboard_size = excluded.leaderboard_size,
    active = true;
