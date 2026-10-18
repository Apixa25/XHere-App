-- Local demo data (runs on `supabase db reset`, never in production).
-- Demo area: Lower Manhattan, New York. This is a placeholder until Steven picks
-- the demo city (PLAN §10, question 3); swap the coordinates below to move it.
--
-- Drops are created through public.create_drop, so the seed also proves the
-- category rules accept realistic posts. The demo accounts have no password
-- and cannot sign in.

insert into auth.users (id, email, aud, role, email_confirmed_at, raw_user_meta_data) values
  ('d0000000-0000-4000-8000-000000000001', 'guide.demo@example.com',   'authenticated', 'authenticated', now(), '{"demo": true}'),
  ('d0000000-0000-4000-8000-000000000002', 'trusted.demo@example.com', 'authenticated', 'authenticated', now(), '{"demo": true}'),
  ('d0000000-0000-4000-8000-000000000003', 'regular.demo@example.com', 'authenticated', 'authenticated', now(), '{"demo": true}'),
  ('d0000000-0000-4000-8000-000000000004', 'new.demo@example.com',     'authenticated', 'authenticated', now(), '{"demo": true}');

update public.profiles set handle = 'demo_guide', display_name = 'Demo Guide', tier = 'guide', home_area = 'Lower Manhattan'
  where id = 'd0000000-0000-4000-8000-000000000001';
update public.profiles set handle = 'demo_trusted', display_name = 'Demo Trusted', tier = 'trusted', role = 'moderator', home_area = 'Lower Manhattan'
  where id = 'd0000000-0000-4000-8000-000000000002';
update public.profiles set handle = 'demo_regular', display_name = 'Demo Regular', tier = 'regular'
  where id = 'd0000000-0000-4000-8000-000000000003';
update public.profiles set handle = 'demo_new', display_name = 'Demo New'
  where id = 'd0000000-0000-4000-8000-000000000004';

-- Guide: history, nature, art, food, infrastructure, secrets
select set_config('request.jwt.claims', '{"sub": "d0000000-0000-4000-8000-000000000001", "role": "authenticated"}', false);

select public.create_drop('historical_fact', 40.70338, -74.01133, 'Fraunces Tavern',
  'Washington said farewell to his officers in the Long Room here on December 4, 1783.',
  '{"era": "Revolutionary War", "year": 1783, "citation": "Fraunces Tavern Museum"}', '{history,revolution}');
select public.create_drop('historical_fact', 40.70731, -74.01026, 'Federal Hall',
  'The first Congress met here and Washington took the oath of office on the balcony in 1789.',
  '{"era": "Early Republic", "year": 1789}', '{history}');
select public.create_drop('viewpoint', 40.70329, -74.01700, 'Harbor view from the Battery',
  'Statue of Liberty, Ellis Island, and the ferries, all from one bench.',
  '{"season": "year_round", "difficulty": "easy", "accessibility": "Paved paths, step-free"}');
select public.create_drop('water_source', 40.70400, -74.01600, 'Refill fountain',
  'Bottle refill station by the park entrance. Works April to October.',
  '{"season": "summer"}');
select public.create_drop('public_art', 40.70556, -74.01344, 'Charging Bull',
  'Bronze, about 3,200 kg, installed without permission in 1989 and allowed to stay.',
  '{"artist": "Arturo Di Modica", "year": 1989}');
select public.create_drop('eatery_tip', 40.70420, -74.01060, 'Stone Street at lunch',
  'Cobblestones and outdoor tables. Go before noon or after 1:30 to get a seat.',
  '{"price_level": 2, "hours": "Weekdays 11-15"}');
select public.create_drop('underground', 40.71329, -74.00440, 'The old City Hall station',
  'A closed 1904 subway station with tiled vaults. Stay on the 6 train past the last stop to glimpse it.',
  '{"agency": "MTA"}');
select public.create_drop('hidden_gem', 40.70370, -74.00930, 'Elevated Acre',
  'A quiet lawn one escalator ride above Water Street. Most people walk right past it.',
  '{"best_time": "Weekday evenings"}');

-- Trusted (also a moderator): time-limited and safety posts
select set_config('request.jwt.claims', '{"sub": "d0000000-0000-4000-8000-000000000002", "role": "authenticated"}', false);

select public.create_drop('food_truck', 40.70810, -74.01280, 'Halal cart at Rector',
  'Chicken over rice, white sauce on the side.',
  '{"cuisine": "Halal", "price_level": 1}');
select public.create_drop('event', 40.70483, -74.01358, 'Bowling Green lunchtime concert',
  'Brass quintet on the green.',
  jsonb_build_object('starts_at', to_jsonb(now() + interval '2 days'), 'ends_at', to_jsonb(now() + interval '2 days 2 hours'), 'cost', 'Free'));
select public.create_drop('hazard', 40.70290, -74.01280, 'Sidewalk closed on Whitehall',
  'Scaffolding blocks the east sidewalk. Cross early.',
  jsonb_build_object('occurred_at', to_jsonb(now() - interval '1 hour'), 'severity', 'low', 'ongoing', true, 'hazard_type', 'sidewalk'));
select public.create_drop('incident_report', 40.70700, -74.01150, 'Bike stolen from rack',
  'A locked bike was taken from the rack this afternoon. Lock both wheels.',
  jsonb_build_object('occurred_at', to_jsonb(now() - interval '3 hours'), 'severity', 'low', 'ongoing', false),
  p_acknowledge_guidelines => true);

-- Regular: a stoop sale, a note, and an incident report that waits for review
select set_config('request.jwt.claims', '{"sub": "d0000000-0000-4000-8000-000000000003", "role": "authenticated"}', false);

select public.create_drop('yard_sale', 40.71000, -74.00800, 'Stoop sale: books and records',
  'Two tables of paperbacks and vinyl. Message me in the app.',
  jsonb_build_object('price', 5, 'currency', 'USD', 'contact_method', 'in_app', 'ends_at', to_jsonb(now() + interval '1 day')));
select public.create_drop('note', 40.70600, -74.01000, 'Benches in the shade',
  'The plaza benches on the north side get shade after 2 pm.');
select public.create_drop('incident_report', 40.70880, -74.01100, 'Car window smashed',
  'A parked car had its window broken overnight. Keep valuables out of sight.',
  jsonb_build_object('occurred_at', to_jsonb(now() - interval '8 hours'), 'severity', 'med', 'ongoing', false),
  p_acknowledge_guidelines => true);

select set_config('request.jwt.claims', '', false);
