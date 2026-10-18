-- M1: built-in taxonomy (PLAN §4.2), content filters, and tier limits.
-- This is reference data the app needs in every environment, so it is a
-- migration rather than seed.sql (which only runs locally). Admins can edit
-- these rows later without an app release; edits bump categories.version.

-- ---------------------------------------------------------------------------
-- Tier limits
-- ---------------------------------------------------------------------------
insert into public.tier_limits (tier, drops_per_hour, drops_per_day, confirmations_per_hour, reports_per_day, max_media_per_drop) values
  ('new',      3,  10,  20,  10,  4),
  ('regular', 10,  40,  60,  25,  6),
  ('trusted', 30, 120, 120,  50, 10),
  ('guide',   60, 240, 240, 100, 10);

-- ---------------------------------------------------------------------------
-- Content filters (case-insensitive POSIX regexes; \m and \M are word edges)
-- ---------------------------------------------------------------------------
insert into public.content_filters (slug, description, pattern, message) values
  ('contact_info',
   'Phone numbers (10+ digits) and email addresses. Contact stays in the app.',
   '(\+?[0-9][ .()-]*){10,}|[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}',
   'Keep contact in the app. Remove phone numbers and email addresses.'),
  ('person_descriptors',
   'Words that describe or identify people (gender, age, race, clothing, build). Safety posts describe what happened, not who.',
   '\m(suspects?|perps?|perpetrators?|m[ae]n|wom[ae]n|guys?|gals?|males?|females?|boys?|girls?|teens?|teenagers?|kids?|juveniles?|thugs?|wearing|hoodies?|skin|skinned|complexion|ethnicity|race|racial|black|white|brown|asian|hispanic|latin[oax]|african|arab|middle[- ]eastern|mexican|caucasian|tall|heavyset|skinny|bearded|beard|tattoos?|dreads|dreadlocks|years? old|[0-9]{1,2} ?(yo|y/o))\M',
   'Describe what happened, not who. Do not name or describe people.');

-- ---------------------------------------------------------------------------
-- Groups
-- ---------------------------------------------------------------------------
insert into public.category_groups (slug, label, description, icon, color, sort) values
  ('history_stories', 'History & Stories', 'What happened here, and the stories people tell about it.', '📜', '#8D6E63', 1),
  ('nature_outdoors', 'Nature & Outdoors', 'Views, trails, wildlife, and water.', '🌿', '#2E7D32', 2),
  ('food_drink', 'Food & Drink', 'Trucks, tips, and markets.', '🍔', '#EF6C00', 3),
  ('events', 'Events', 'Things happening at a set time.', '🎪', '#8E24AA', 4),
  ('sales_free', 'Sales & Free Stuff', 'Yard sales, things for sale, and free stuff.', '🏷️', '#00897B', 5),
  ('safety_alerts', 'Safety & Alerts', 'Hazards, alerts, and incident reports. Strict rules apply.', '🚨', '#C62828', 6),
  ('community_faith', 'Community & Faith', 'Places of worship, community spaces, and notices.', '🏛️', '#3949AB', 7),
  ('infrastructure', 'Infrastructure & Hidden City', 'How the city works, what is being built, and what is underneath.', '🔧', '#546E7A', 8),
  ('local_secrets', 'Local Secrets', 'Only revealed when you are standing there.', '💎', '#D81B60', 9),
  ('art_culture', 'Art & Culture', 'Street art, public art, and music.', '🎨', '#F9A825', 10),
  ('note', 'Note', 'Anything else worth knowing about this spot.', '📍', '#757575', 11);

-- ---------------------------------------------------------------------------
-- Categories. A temporary helper keeps each row readable with named rules.
-- ---------------------------------------------------------------------------
create function private.seed_category(
  p_group text,
  p_slug text,
  p_label text,
  p_description text,
  p_icon text,
  p_schema jsonb,
  p_reveal_m integer,
  p_ttl interval default null,
  p_max_ttl interval default null,
  p_max_ttl_new interval default null,
  p_expires_at_attribute text default null,
  p_confirm_extends_ttl interval default null,
  p_permanent_when_confirmed boolean default false,
  p_confirmations integer default 3,
  p_denials integer default 3,
  p_reports integer default 3,
  p_min_tier public.user_tier default 'new',
  p_moderation public.moderation_mode default 'post',
  p_premod_exempt_tier public.user_tier default null,
  p_sensitive boolean default false,
  p_precision_m integer default 0,
  p_filters text[] default '{}',
  p_allow_anonymous boolean default true,
  p_notice text default null,
  p_sort integer default 0
)
returns void
language sql
set search_path = ''
as $$
  insert into public.categories (
    group_id, slug, label, description, icon, color, attributes_schema,
    min_tier_to_post, moderation_mode, premod_exempt_tier, sensitive, posting_notice, content_filters,
    default_reveal_m, max_reveal_m, location_precision_m,
    default_ttl, max_ttl, max_ttl_new, expires_at_attribute, confirm_extends_ttl, permanent_when_confirmed,
    confirmations_to_confirm, denials_to_hide, reports_to_hide,
    allow_anonymous_display, is_builtin, sort
  )
  select g.id, p_slug, p_label, p_description, p_icon, g.color, p_schema,
         p_min_tier, p_moderation, p_premod_exempt_tier, p_sensitive, p_notice, p_filters,
         p_reveal_m, p_reveal_m, p_precision_m,
         p_ttl, p_max_ttl, p_max_ttl_new, p_expires_at_attribute, p_confirm_extends_ttl, p_permanent_when_confirmed,
         p_confirmations, p_denials, p_reports,
         p_allow_anonymous, true, p_sort
  from public.category_groups g
  where g.slug = p_group;
$$;

-- 📜 History & Stories: 150 m, permanent, confirm bar 3. Sources encouraged.
select private.seed_category('history_stories', 'historical_fact', 'Historical fact', 'Something that happened here, ideally with a source.', '🏛️',
  '{"type": "object", "additionalProperties": false, "properties": {
     "era": {"type": "string", "maxLength": 60},
     "year": {"type": "integer", "minimum": -5000, "maximum": 2100},
     "source_url": {"type": "string", "format": "uri", "maxLength": 500},
     "citation": {"type": "string", "maxLength": 300}}}',
  150, p_sort => 1);
select private.seed_category('history_stories', 'story', 'Story', 'A personal or local story tied to this spot.', '📖',
  '{"type": "object", "additionalProperties": false, "properties": {
     "era": {"type": "string", "maxLength": 60},
     "year": {"type": "integer", "minimum": -5000, "maximum": 2100},
     "source_url": {"type": "string", "format": "uri", "maxLength": 500},
     "citation": {"type": "string", "maxLength": 300}}}',
  150, p_sort => 2);
select private.seed_category('history_stories', 'memorial', 'Memorial', 'Remembering a person or event connected to this place.', '🕯️',
  '{"type": "object", "additionalProperties": false, "properties": {
     "era": {"type": "string", "maxLength": 60},
     "year": {"type": "integer", "minimum": -5000, "maximum": 2100},
     "source_url": {"type": "string", "format": "uri", "maxLength": 500},
     "citation": {"type": "string", "maxLength": 300}}}',
  150, p_sort => 3);

-- 🌿 Nature & Outdoors: 300 m, permanent; wildlife sightings expire after 7 days.
select private.seed_category('nature_outdoors', 'viewpoint', 'Viewpoint', 'A view worth stopping for.', '🏞️',
  '{"type": "object", "additionalProperties": false, "properties": {
     "season": {"enum": ["spring", "summer", "fall", "winter", "year_round"]},
     "difficulty": {"enum": ["easy", "moderate", "hard"]},
     "accessibility": {"type": "string", "maxLength": 200}}}',
  300, p_sort => 1);
select private.seed_category('nature_outdoors', 'trail_note', 'Trail note', 'Conditions, turns, and tips on a trail.', '🥾',
  '{"type": "object", "additionalProperties": false, "properties": {
     "season": {"enum": ["spring", "summer", "fall", "winter", "year_round"]},
     "difficulty": {"enum": ["easy", "moderate", "hard"]},
     "accessibility": {"type": "string", "maxLength": 200}}}',
  300, p_sort => 2);
select private.seed_category('nature_outdoors', 'wildlife', 'Wildlife sighting', 'An animal or plant seen here recently.', '🦉',
  '{"type": "object", "additionalProperties": false, "properties": {
     "species": {"type": "string", "maxLength": 100},
     "season": {"enum": ["spring", "summer", "fall", "winter", "year_round"]},
     "difficulty": {"enum": ["easy", "moderate", "hard"]},
     "accessibility": {"type": "string", "maxLength": 200}}}',
  300, p_ttl => '7 days', p_max_ttl => '7 days', p_sort => 3);
select private.seed_category('nature_outdoors', 'water_source', 'Water source', 'Drinking fountains, springs, and refill spots.', '💧',
  '{"type": "object", "additionalProperties": false, "properties": {
     "season": {"enum": ["spring", "summer", "fall", "winter", "year_round"]},
     "difficulty": {"enum": ["easy", "moderate", "hard"]},
     "accessibility": {"type": "string", "maxLength": 200}}}',
  300, p_sort => 4);

-- 🍔 Food & Drink: 500 m. Food trucks last 6 h and each "still here" renews them.
select private.seed_category('food_drink', 'food_truck', 'Food truck', 'A truck or cart serving here right now.', '🚚',
  '{"type": "object", "additionalProperties": false, "properties": {
     "cuisine": {"type": "string", "maxLength": 60},
     "price_level": {"type": "integer", "minimum": 1, "maximum": 4},
     "hours": {"type": "string", "maxLength": 120}}}',
  500, p_ttl => '6 hours', p_max_ttl => '24 hours', p_confirm_extends_ttl => '6 hours',
  p_confirmations => 2, p_denials => 2, p_sort => 1);
select private.seed_category('food_drink', 'eatery_tip', 'Eatery tip', 'What to order, when to go, what to skip.', '🍽️',
  '{"type": "object", "additionalProperties": false, "properties": {
     "cuisine": {"type": "string", "maxLength": 60},
     "price_level": {"type": "integer", "minimum": 1, "maximum": 4},
     "hours": {"type": "string", "maxLength": 120}}}',
  500, p_sort => 2);
select private.seed_category('food_drink', 'market', 'Market', 'Farmers markets, night markets, and stalls.', '🧺',
  '{"type": "object", "additionalProperties": false, "properties": {
     "cuisine": {"type": "string", "maxLength": 60},
     "price_level": {"type": "integer", "minimum": 1, "maximum": 4},
     "hours": {"type": "string", "maxLength": 120}}}',
  500, p_sort => 3);

-- 🎪 Events: 1 km, start and end required, expire at ends_at. New users may post up to 30 days out.
select private.seed_category('events', 'event', 'Event', 'Something happening here at a set time.', '🎟️',
  '{"type": "object", "additionalProperties": false, "required": ["starts_at", "ends_at"], "properties": {
     "starts_at": {"type": "string", "format": "date-time"},
     "ends_at": {"type": "string", "format": "date-time"},
     "cost": {"type": "string", "maxLength": 60},
     "link": {"type": "string", "format": "uri", "maxLength": 500}}}',
  1000, p_max_ttl => '365 days', p_max_ttl_new => '30 days', p_expires_at_attribute => 'ends_at',
  p_confirmations => 2, p_sort => 1);
select private.seed_category('events', 'popup', 'Pop-up', 'A short-lived pop-up shop, show, or stand.', '⛺',
  '{"type": "object", "additionalProperties": false, "required": ["starts_at", "ends_at"], "properties": {
     "starts_at": {"type": "string", "format": "date-time"},
     "ends_at": {"type": "string", "format": "date-time"},
     "cost": {"type": "string", "maxLength": 60},
     "link": {"type": "string", "format": "uri", "maxLength": 500}}}',
  1000, p_max_ttl => '365 days', p_max_ttl_new => '30 days', p_expires_at_attribute => 'ends_at',
  p_confirmations => 2, p_sort => 2);

-- 🏷️ Sales & Free Stuff: 1 km, contact stays in the app, author always shown.
select private.seed_category('sales_free', 'yard_sale', 'Yard sale', 'A yard, garage, or stoop sale.', '🏠',
  '{"type": "object", "additionalProperties": false, "properties": {
     "price": {"type": "number", "minimum": 0, "maximum": 1000000000},
     "currency": {"type": "string", "pattern": "^[A-Z]{3}$"},
     "contact_method": {"enum": ["in_app"]},
     "ends_at": {"type": "string", "format": "date-time"}}}',
  1000, p_ttl => '3 days', p_max_ttl => '7 days', p_expires_at_attribute => 'ends_at',
  p_confirmations => 2, p_filters => '{contact_info}', p_allow_anonymous => false,
  p_notice => 'Keep contact in the app. No weapons, drugs, or regulated items.', p_sort => 1);
select private.seed_category('sales_free', 'for_sale', 'For sale', 'One item for sale, picked up here.', '💵',
  '{"type": "object", "additionalProperties": false, "properties": {
     "price": {"type": "number", "minimum": 0, "maximum": 1000000000},
     "currency": {"type": "string", "pattern": "^[A-Z]{3}$"},
     "contact_method": {"enum": ["in_app"]},
     "ends_at": {"type": "string", "format": "date-time"}}}',
  1000, p_ttl => '14 days', p_max_ttl => '30 days', p_expires_at_attribute => 'ends_at',
  p_confirmations => 2, p_filters => '{contact_info}', p_allow_anonymous => false,
  p_notice => 'Keep contact in the app. No weapons, drugs, or regulated items.', p_sort => 2);
select private.seed_category('sales_free', 'free_stuff', 'Free stuff', 'Curb alerts and giveaways.', '🎁',
  '{"type": "object", "additionalProperties": false, "properties": {
     "price": {"type": "number", "minimum": 0, "maximum": 0},
     "currency": {"type": "string", "pattern": "^[A-Z]{3}$"},
     "contact_method": {"enum": ["in_app"]},
     "ends_at": {"type": "string", "format": "date-time"}}}',
  1000, p_ttl => '14 days', p_max_ttl => '30 days', p_expires_at_attribute => 'ends_at',
  p_confirmations => 2, p_filters => '{contact_info}', p_allow_anonymous => false,
  p_notice => 'Keep contact in the app. No weapons, drugs, or regulated items.', p_sort => 3);

-- 🚨 Safety & Alerts (PLAN §4.3). occurred_at is required everywhere.
-- Hazards: 2 km, post immediately, 24 h unless confirmed (each confirmation adds 24 h, up to 72 h ahead).
select private.seed_category('safety_alerts', 'hazard', 'Hazard', 'Road, sidewalk, or flood hazards.', '⚠️',
  '{"type": "object", "additionalProperties": false, "required": ["occurred_at"], "properties": {
     "occurred_at": {"type": "string", "format": "date-time"},
     "severity": {"enum": ["low", "med", "high"]},
     "ongoing": {"type": "boolean"},
     "hazard_type": {"enum": ["road", "sidewalk", "flood", "other"]}}}',
  2000, p_ttl => '24 hours', p_max_ttl => '72 hours', p_confirm_extends_ttl => '24 hours',
  p_confirmations => 2, p_denials => 2, p_reports => 2, p_filters => '{contact_info}', p_sort => 1);
-- Safety alerts: 72 h, New users are reviewed first, no describing people.
select private.seed_category('safety_alerts', 'safety_alert', 'Safety alert', 'Something people nearby should know about now.', '📢',
  '{"type": "object", "additionalProperties": false, "required": ["occurred_at"], "properties": {
     "occurred_at": {"type": "string", "format": "date-time"},
     "severity": {"enum": ["low", "med", "high"]},
     "ongoing": {"type": "boolean"}}}',
  2000, p_ttl => '72 hours', p_max_ttl => '72 hours',
  p_confirmations => 2, p_denials => 2, p_reports => 2,
  p_moderation => 'pre_for_new', p_sensitive => true, p_filters => '{contact_info,person_descriptors}',
  p_notice => 'Describe what happened, not who. In an emergency call 911. XHere is not a reporting channel to police.',
  p_sort => 2);
-- Incident reports (crime): Regular+ to post, reviewed below Trusted, location blurred to ~100 m,
-- 72 h lifetime, unconfirmed until 2 nearby confirmations, no naming or describing people.
select private.seed_category('safety_alerts', 'incident_report', 'Incident report', 'A crime or incident that happened nearby. Approximate location only.', '🚓',
  '{"type": "object", "additionalProperties": false, "required": ["occurred_at"], "properties": {
     "occurred_at": {"type": "string", "format": "date-time"},
     "severity": {"enum": ["low", "med", "high"]},
     "ongoing": {"type": "boolean"}}}',
  2000, p_ttl => '72 hours', p_max_ttl => '72 hours',
  p_confirmations => 2, p_denials => 2, p_reports => 2,
  p_min_tier => 'regular', p_moderation => 'pre', p_premod_exempt_tier => 'trusted',
  p_sensitive => true, p_precision_m => 100, p_filters => '{contact_info,person_descriptors}',
  p_notice => 'Describe what happened, not who. Do not name or describe people. In an emergency call 911. XHere is not a reporting channel to police.',
  p_sort => 3);

-- 🏛️ Community & Faith: 300 m, permanent; notices last 30 days and need Regular+.
select private.seed_category('community_faith', 'place_of_worship', 'Place of worship', 'Churches, mosques, temples, synagogues, and more.', '🛐',
  '{"type": "object", "additionalProperties": false, "properties": {
     "denomination": {"type": "string", "maxLength": 80},
     "schedule": {"type": "string", "maxLength": 200}}}',
  300, p_sort => 1);
select private.seed_category('community_faith', 'community_space', 'Community space', 'Libraries, centers, gardens, and halls open to the public.', '🏘️',
  '{"type": "object", "additionalProperties": false, "properties": {
     "type": {"type": "string", "maxLength": 80},
     "schedule": {"type": "string", "maxLength": 200}}}',
  300, p_sort => 2);
select private.seed_category('community_faith', 'notice_board', 'Notice', 'A community notice for people nearby.', '📌',
  '{"type": "object", "additionalProperties": false, "properties": {
     "type": {"type": "string", "maxLength": 80},
     "schedule": {"type": "string", "maxLength": 200}}}',
  300, p_ttl => '30 days', p_max_ttl => '30 days', p_min_tier => 'regular', p_sort => 3);

-- 🔧 Infrastructure & Hidden City: 200 m; construction expires at est_completion.
select private.seed_category('infrastructure', 'infrastructure', 'Infrastructure', 'Bridges, utilities, and how the city works.', '🌉',
  '{"type": "object", "additionalProperties": false, "properties": {
     "agency": {"type": "string", "maxLength": 120},
     "project_url": {"type": "string", "format": "uri", "maxLength": 500},
     "est_completion": {"type": "string", "format": "date"}}}',
  200, p_sort => 1);
select private.seed_category('infrastructure', 'construction', 'Construction', 'What is being built here, and until when.', '🚧',
  '{"type": "object", "additionalProperties": false, "properties": {
     "agency": {"type": "string", "maxLength": 120},
     "project_url": {"type": "string", "format": "uri", "maxLength": 500},
     "est_completion": {"type": "string", "format": "date"}}}',
  200, p_max_ttl => '3 years', p_expires_at_attribute => 'est_completion', p_sort => 2);
select private.seed_category('infrastructure', 'underground', 'Underground', 'Tunnels, buried rivers, and what lies beneath.', '🕳️',
  '{"type": "object", "additionalProperties": false, "properties": {
     "agency": {"type": "string", "maxLength": 120},
     "project_url": {"type": "string", "format": "uri", "maxLength": 500},
     "est_completion": {"type": "string", "format": "date"}}}',
  200, p_sort => 3);

-- 💎 Local Secrets: 50 m, you have to be there.
select private.seed_category('local_secrets', 'hidden_gem', 'Hidden gem', 'Easy to walk past. Worth stopping for.', '💎',
  '{"type": "object", "additionalProperties": false, "properties": {
     "best_time": {"type": "string", "maxLength": 60}}}',
  50, p_confirmations => 2, p_sort => 1);
select private.seed_category('local_secrets', 'tip', 'Local tip', 'Something only locals know about this spot.', '🤫',
  '{"type": "object", "additionalProperties": false, "properties": {
     "best_time": {"type": "string", "maxLength": 60}}}',
  50, p_confirmations => 2, p_sort => 2);

-- 🎨 Art & Culture: 150 m, permanent.
select private.seed_category('art_culture', 'street_art', 'Street art', 'Murals, graffiti, and paste-ups.', '🖌️',
  '{"type": "object", "additionalProperties": false, "properties": {
     "artist": {"type": "string", "maxLength": 100},
     "year": {"type": "integer", "minimum": -5000, "maximum": 2100}}}',
  150, p_sort => 1);
select private.seed_category('art_culture', 'public_art', 'Public art', 'Sculptures, installations, and monuments.', '🗿',
  '{"type": "object", "additionalProperties": false, "properties": {
     "artist": {"type": "string", "maxLength": 100},
     "year": {"type": "integer", "minimum": -5000, "maximum": 2100}}}',
  150, p_sort => 2);
select private.seed_category('art_culture', 'music_spot', 'Music spot', 'Buskers, open mics, and great acoustics.', '🎶',
  '{"type": "object", "additionalProperties": false, "properties": {
     "artist": {"type": "string", "maxLength": 100},
     "year": {"type": "integer", "minimum": -5000, "maximum": 2100}}}',
  150, p_sort => 3);

-- 📍 Note: the catch-all. 100 m, 7 days unless confirmed twice (then permanent),
-- reviewed first for New users (the old "general" rule).
select private.seed_category('note', 'note', 'Note', 'Anything else worth knowing about this spot.', '📍',
  '{"type": "object", "additionalProperties": false, "properties": {}}',
  100, p_ttl => '7 days', p_permanent_when_confirmed => true,
  p_confirmations => 2, p_moderation => 'pre_for_new', p_sort => 1);

drop function private.seed_category(
  text, text, text, text, text, jsonb, integer, interval, interval, interval, text, interval, boolean,
  integer, integer, integer, public.user_tier, public.moderation_mode, public.user_tier, boolean,
  integer, text[], boolean, text, integer
);
