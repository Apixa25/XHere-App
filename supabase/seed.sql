-- Local demo data (runs on `supabase db reset`, never in production).
-- Demo area: Jamestown, New York (Chautauqua County), chosen by Steven
-- (PLAN §10, question 3). The 15 drops keep the same categories as the first
-- seed. They sit on public spots downtown, along the Chadakoin River, at the
-- National Comedy Center and Lucille Ball sites, and on the Chautauqua Lake shore.
--
-- Drops are created through public.create_drop, so the seed also proves the
-- category rules accept realistic posts. The demo accounts have no password
-- and cannot sign in.

insert into auth.users (id, email, aud, role, email_confirmed_at, raw_user_meta_data) values
  ('d0000000-0000-4000-8000-000000000001', 'guide.demo@example.com',   'authenticated', 'authenticated', now(), '{"demo": true}'),
  ('d0000000-0000-4000-8000-000000000002', 'trusted.demo@example.com', 'authenticated', 'authenticated', now(), '{"demo": true}'),
  ('d0000000-0000-4000-8000-000000000003', 'regular.demo@example.com', 'authenticated', 'authenticated', now(), '{"demo": true}'),
  ('d0000000-0000-4000-8000-000000000004', 'new.demo@example.com',     'authenticated', 'authenticated', now(), '{"demo": true}');

update public.profiles set handle = 'demo_guide', display_name = 'Demo Guide', tier = 'guide', home_area = 'Jamestown, NY'
  where id = 'd0000000-0000-4000-8000-000000000001';
update public.profiles set handle = 'demo_trusted', display_name = 'Demo Trusted', tier = 'trusted', role = 'moderator', home_area = 'Jamestown, NY'
  where id = 'd0000000-0000-4000-8000-000000000002';
update public.profiles set handle = 'demo_regular', display_name = 'Demo Regular', tier = 'regular'
  where id = 'd0000000-0000-4000-8000-000000000003';
update public.profiles set handle = 'demo_new', display_name = 'Demo New'
  where id = 'd0000000-0000-4000-8000-000000000004';

-- Guide: history, nature, art, food, infrastructure, secrets
select set_config('request.jwt.claims', '{"sub": "d0000000-0000-4000-8000-000000000001", "role": "authenticated"}', false);

-- Fenton History Center, 67 Washington Street
select public.create_drop('historical_fact', 42.09122, -79.23920, 'Fenton Mansion',
  'Reuben Fenton built this house in 1863, then served as governor of New York. It is a public museum now.',
  '{"era": "Civil War", "year": 1863, "citation": "Fenton History Center"}', '{history}');
-- Lucille Ball Desi Arnaz Museum, 2 West 3rd Street
select public.create_drop('historical_fact', 42.09643, -79.24158, 'Lucy-Desi Museum',
  'Lucille Ball was born in Jamestown on August 6, 1911. The museum telling that story opened on this corner in 1996.',
  '{"era": "20th century", "year": 1911, "citation": "Lucille Ball Desi Arnaz Museum"}', '{history,comedy}');
-- Chautauqua Lake shore, Lucille Ball Memorial Park, Celoron
select public.create_drop('viewpoint', 42.11115, -79.28155, 'Lake view from Celoron',
  'Chautauqua Lake, the park lighthouse, and the shore path, all from one bench.',
  '{"season": "year_round", "difficulty": "easy", "accessibility": "Paved boardwalk, step-free"}');
-- McCrea Point Park, Jones & Gifford Avenue
select public.create_drop('water_source', 42.10115, -79.25494, 'Refill fountain',
  'Bottle refill station by the McCrea Point shelter. Works April to October.',
  '{"season": "summer"}');
-- Carolyn Palmer statue, Lucille Ball Memorial Park
select public.create_drop('public_art', 42.11064, -79.28210, 'Lucy in the park',
  'Carolyn Palmer''s bronze of Lucille Ball, dedicated in Lucille Ball Memorial Park on August 6, 2016.',
  '{"artist": "Carolyn Palmer", "year": 2016}');
-- West 3rd Street, the downtown cafe block
select public.create_drop('eatery_tip', 42.09636, -79.24202, 'West 3rd at lunch',
  'Cafes and outdoor tables between Main and Washington. Go before noon or after 1:30 to get a seat.',
  '{"price_level": 2, "hours": "Weekdays 11-15"}');
-- North Main Street tunnel under the former Erie Railroad
select public.create_drop('underground', 42.09447, -79.24060, 'Under the Erie on North Main',
  'North Main Street passes under the old Erie Railroad in a short tunnel. The tracks still cross downtown on that grade.',
  '{"agency": "WNYP"}');
-- I Love Lucy mural, down by the Chadakoin
select public.create_drop('hidden_gem', 42.09265, -79.23996, 'I Love Lucy mural',
  'Painted on a wall down by the Chadakoin. Most people stay up on 3rd Street and walk right past it.',
  '{"best_time": "Weekday evenings"}');

-- Trusted (also a moderator): time-limited and safety posts
select set_config('request.jwt.claims', '{"sub": "d0000000-0000-4000-8000-000000000002", "role": "authenticated"}', false);

-- Rosa Alley, beside the National Comedy Center on West 2nd
select public.create_drop('food_truck', 42.09520, -79.24390, 'Texas hot cart on Rosa Alley',
  'A lunch cart by the Comedy Center. Texas hots, sauce on the side.',
  '{"cuisine": "Texas hot", "price_level": 1}');
-- Tracy Plaza / Jamestown City Hall
select public.create_drop('event', 42.09642, -79.23843, 'Tracy Plaza lunchtime concert',
  'Brass quintet on the plaza.',
  jsonb_build_object('starts_at', to_jsonb(now() + interval '2 days'), 'ends_at', to_jsonb(now() + interval '2 days 2 hours'), 'cost', 'Free'));
-- Greater Jamestown Riverwalk at Brooklyn Square
select public.create_drop('hazard', 42.09395, -79.24370, 'Sidewalk closed on the Riverwalk',
  'Scaffolding blocks the walk by Brooklyn Square. Cross early.',
  jsonb_build_object('occurred_at', to_jsonb(now() - interval '1 hour'), 'severity', 'low', 'ongoing', true, 'hazard_type', 'sidewalk'));
-- James Prendergast Library, 509 Cherry Street
select public.create_drop('incident_report', 42.09824, -79.24382, 'Bike stolen from rack',
  'A locked bike was taken from the rack this afternoon. Lock both wheels.',
  jsonb_build_object('occurred_at', to_jsonb(now() - interval '3 hours'), 'severity', 'low', 'ongoing', false),
  p_acknowledge_guidelines => true);

-- Regular: a park sale, a note, and an incident report that waits for review
select set_config('request.jwt.claims', '{"sub": "d0000000-0000-4000-8000-000000000003", "role": "authenticated"}', false);

-- Allen Park, south Jamestown
select public.create_drop('yard_sale', 42.08382, -79.22449, 'Pavilion sale: books and records',
  'Two tables of paperbacks and vinyl. Message me in the app.',
  jsonb_build_object('price', 5, 'currency', 'USD', 'contact_method', 'in_app', 'ends_at', to_jsonb(now() + interval '1 day')));
-- Greater Jamestown Riverwalk, north bank of the Chadakoin
select public.create_drop('note', 42.09368, -79.24289, 'Benches in the shade',
  'The Riverwalk benches on the north bank get shade after 2 pm.');
-- West 3rd Street Lot #1, a public downtown lot
select public.create_drop('incident_report', 42.09588, -79.24579, 'Car window smashed',
  'A parked car had its window broken overnight. Keep valuables out of sight.',
  jsonb_build_object('occurred_at', to_jsonb(now() - interval '8 hours'), 'severity', 'med', 'ongoing', false),
  p_acknowledge_guidelines => true);

select set_config('request.jwt.claims', '', false);
