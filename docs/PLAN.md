# XHere — Rebuild Plan (v1)

*Prepared 2026-10-17 (America/New_York) for Steven Sills II. Companion to `OLD-REPO-ANALYSIS.md`.*
*Status: **proposal**. Nothing has been bought or signed up for. Every 💲 item needs Steven's OK.*
*Open data: the global standard is [`OPEN-DATA-PLAYBOOK.md`](OPEN-DATA-PLAYBOOK.md). The Jamestown / Chautauqua pilot is [`CRIME-OPEN-DATA.md`](CRIME-OPEN-DATA.md).*

---

## 0. The idea, restated fresh

**XHere is a lens on the world: stand somewhere and see what's known about *here*.**

There are two kinds of "here-data":
1. **Drops**: things people attach to a place (a story, a photo, a yard sale, a hazard, a hidden gem, a historical fact). Some reveal only when you're physically close.
2. **Layers of data that already exist but nobody can reach from the spot they're standing on**: Wikipedia/Wikidata articles about the building in front of you, OpenStreetMap features, historical markers, city open-data feeds (permits, incidents, inspections), and later business and official sources.

That second kind is Steven's "the future is reaching data that's already out there" theme. The old app had only the first. The rebuild treats both as citizens of the same map, with one **classification system** (§4), one trust model, and one discovery UX.

**Design principles**
* *Waze-fast contribution*: a drop takes about 3 taps. Classification is suggested for you.
* *Earned trust, not bought trust*: verification is "still true?" confirmations from people who are actually there, not up/down popularity.
* *Category-aware rules*: a crime report and a yard sale should not follow the same rules.
* *Fun without bloat*: a few delightful game loops, not 20 subsystems.
* *Free until it's worth paying for*: every component has a $0 path.
* *Safety and store compliance from day one* (UGC rules, location privacy, account deletion).

---

## 1. Platform decision: how to build the apps

Steven is leaning toward fully native apps (Kotlin/Jetpack Compose on Android, Swift/SwiftUI on iOS) because the web-wrapped app "held him back from the app stores." Here are the options weighed honestly.

### 1.1 Facts that shape the choice
* **Building for iOS requires macOS + Xcode** for Swift/SwiftUI, KMP iOS targets, and Flutter iOS alike. Steven's registered machine is "Stevens-WM" (likely Windows; the old repo has `.ps1` scripts). **The cloud agents that will do the build run Linux.** Only Expo/React Native can build iOS without a Mac (EAS cloud builds). Every other path needs a Mac, cloud Mac CI (GitHub Actions macOS minutes count 10× against a private repo's free quota), or a rented Mac 💲.
* Linux agents **can** build and unit-test Android fully (Gradle + Android SDK command-line tools, Robolectric/JVM tests, Compose screenshot tests). Emulator/instrumented tests are possible but slow.
* Stores: Google Play accepts any of these. Apple accepts all of them **except thin web wrappers** (guideline 4.2 "minimum functionality"). That WebView wrapper was the old app's real risk, not "web code" in general. React Native and Flutter apps render native or GPU UI and ship in the App Store routinely.
* Steven already has a Pixel 6, Android Studio, and Android experience. Google Play is $25 one-time; Apple is $99/yr.
* New personal Google Play accounts must run a **closed test with ≥12 testers for 14 continuous days** before production. Plan for this.

### 1.2 Comparison

| | **Native ×2** (Kotlin/Compose + Swift/SwiftUI) | **Kotlin Multiplatform** (shared core; Compose on Android; SwiftUI or Compose MP on iOS) | **React Native / Expo** | **Flutter** | **PWA** |
|---|---|---|---|---|---|
| Store acceptance | Best | Best | Very good | Very good | No App Store listing; Play only via TWA |
| Maps (MapLibre / Google / Apple) | First-class SDKs | First-class (native UI layer) | Good (`@maplibre/maplibre-react-native`, `react-native-maps`) | Good (`maplibre_gl`, `google_maps_flutter`) | MapLibre GL JS is good, but no background |
| Foreground location | Best | Best | Good (`expo-location`) | Good | OK |
| Background location / geofencing | Best (Play Services Geofencing, CoreLocation regions) | Best (native code) | Workable (`expo-location` + TaskManager), fiddlier | Workable (plugins), fiddlier | Not possible |
| Camera, EXIF, on-device ML (ML Kit, Vision) | Best (CameraX, ML Kit, Vision) | Best | Good (`expo-camera`, `vision-camera`) | Good | Limited |
| AR (ARCore Geospatial / ARKit) | Best | Best | Weak (bridges are thin) | Weak/medium | WebXR, poor on iOS |
| Dev speed (for 2 platforms) | Slowest (2 UIs, 2 codebases) | Medium (logic once, UI twice or Compose MP) | Fastest | Fast | Fastest |
| Solo-founder maintenance | Heaviest | Medium | Lightest (JS churn is real but manageable) | Light | Lightest |
| Builds on Linux cloud agents | Android yes / iOS **no** | Android yes / iOS **no** | **Both** (EAS) | Android yes / iOS no | Yes |
| Steven's stated preference | ✅ matches | ✅ matches (it *is* native Kotlin) | ❌ "web-ish" to him | Neutral | ❌ |

### 1.3 ✅ Recommendation: **Native Android first, in Kotlin + Jetpack Compose, with a Kotlin Multiplatform shared core. iOS follows in SwiftUI using that same core.**

Why this one:
1. **It's what Steven wants, and it holds up on the merits.** XHere's core value is location, camera, background proximity, and AR later. Those are the areas where native has the clearest advantage.
2. **It fits the build reality.** Linux cloud agents can build, test, and ship the Android app end to end. iOS can't be built without a Mac on *any* native path anyway, so doing Android first means no Mac is needed until the product is proven.
3. **KMP from day one is cheap insurance.** Domain models, the classification rules engine, validation, and the Supabase data layer (`supabase-kt` is KMP-native) live in `shared/`, so the iOS app later reuses that code instead of rewriting it. The UI stays fully native (Compose / SwiftUI).
4. **The Android store path is cheap**: $25 once, then the 12-tester closed test.

When to reconsider: if Steven decides iOS must ship **at the same time** as Android and he won't get a Mac, switch to **Expo (React Native, TypeScript)**. It's the only path that builds iOS from Linux, and modern RN apps are real native apps. We're *not* recommending that by default, because it gives up the native-first goal and the strongest background-location and AR story.

### 1.4 Backend choice: **Supabase** (managed Postgres + PostGIS + Auth + Storage + Edge Functions + Realtime + pg_cron)
* **PostGIS** for geography: `geography(Point,4326)`, GiST indexes, `ST_DWithin` radius queries, `ST_ClusterDBSCAN` and server-side clustering. Free tier: 500 MB DB, 1 GB storage, 50k MAU, 5 GB egress. Pauses after 7 days of inactivity (resume anytime).
* **Row-Level Security** puts authorization in the database, replacing the old app's hand-rolled (and leaky) admin checks.
* **Auth**: Google, Apple (required on iOS if Google is offered), and email OTP/magic link. Passkeys later.
* **Edge Functions** (Deno/TypeScript) for anything that needs secrets or orchestration (open-data proxies, moderation, category suggestion with Supabase's built-in `gte-small` embedding model at no extra cost).
* **pg_cron** for expiries and cleanups (replaces the old 60-second `setInterval`).
* Official SDKs: `supabase-kt` (Kotlin Multiplatform), `supabase-swift`, `supabase-js` (web).
* Alternatives considered: **Firebase** (great mobile SDKs, but Firestore geo-queries are weak and it has no PostGIS); **Neon + custom API on Cloudflare Workers** (more code to own); **self-hosted Postgres on a VPS** (💲 and ops burden). Supabase wins on "PostGIS + auth + storage, free, minimal code."
* Portability: it's plain Postgres. If Supabase ever becomes a problem, `pg_dump` and migrate.

### 1.5 Is a web presence still needed? **Yes, but small, and it doesn't use the old web app.**
`xhere.world` should host:
* A **landing page** (what XHere is, store badges, waitlist/tester signup via a simple form).
* **Shareable place links** `xhere.world/d/{shortId}` with an OpenGraph preview (title, category, static map image), "Open in XHere," and a fallback to the store listing. Sensitive or proximity-locked content shows only a teaser on the web.
* **Android App Links** (`/.well-known/assetlinks.json`) and later **iOS Universal Links** (`apple-app-site-association`).
* **Privacy policy, Terms, Community Guidelines, Support, and a web account-deletion page.** Google Play and Apple require these.
* Later: a read-only web map viewer and an admin/moderation console.

Tech: **Astro** (static-first, tiny) deployed to **Cloudflare Pages/Workers free tier** (commercial use allowed). One Worker route renders the share page from Supabase. We're not picking Vercel Hobby because it's non-commercial only.

**Domain:** Steven owns `xhere.world` on GoDaddy through **Dec 2026**. *Update 2026-10-17:* the "suspended hosting" is actually the old **Render** services (`xhere.world` A → Render IP; `www`/`api` CNAME → `xhere-web-front-end.onrender.com`, which shows "Service Suspended"). GoDaddy provides only registration + DNS. We don't need Render or GoDaddy hosting; see `SECURITY-FIXES.md`.
* Free: keep the registration at GoDaddy and point DNS records (or switch nameservers to Cloudflare's free DNS) at Cloudflare Pages.
* 💲 **Domain renewal** before Dec 2026 is a cost item (check GoDaddy's renewal price; Cloudflare Registrar renews at cost, and a transfer includes a 1-year renewal).
* 💲 Don't renew or reactivate the GoDaddy hosting plan. It isn't needed.
* Free fallback if the domain lapses: `xhere.pages.dev`.

---

## 2. Recommended stack (summary)

| Concern | Choice | Cost |
|---|---|---|
| Android app | Kotlin 2.x, Jetpack Compose + Material 3, Navigation Compose, Hilt or Koin, Coroutines/Flow, Room (offline cache), WorkManager, CameraX / Photo Picker, Credential Manager (Google sign-in), Play Services Location + Geofencing, ML Kit (image labeling, on-device), Play Integrity (anti-spoof, later) | Free |
| Shared core | Kotlin Multiplatform `shared/` module: models, category rules engine, validation, `supabase-kt` repositories, Ktor | Free |
| iOS app (phase 2) | SwiftUI, consuming `shared` via an XCFramework (SKIE for nicer Swift APIs), MapLibre Native iOS, CoreLocation, Sign in with Apple | Needs a Mac 💲 + Apple program 💲 |
| Maps | **MapLibre Native** + **OpenFreeMap** vector tiles (free, no key). Fallback/scale: Protomaps PMTiles on Cloudflare R2 (free tier). Avoid Google Maps Platform, which needs a billing account | Free |
| Geocoding / search | Photon or Nominatim (respect their usage policies) via an Edge Function cache; later self-hosted or paid if volume grows | Free (low volume) |
| Backend | Supabase (Postgres 15+/PostGIS, `pg_trgm`, `pg_jsonschema`, `pgvector`, pg_cron, RLS, Storage, Edge Functions, Realtime) | Free tier |
| Web | Astro + Cloudflare Pages/Workers; Supabase JS for share pages | Free |
| Email (auth OTP) | Supabase's built-in SMTP is fine for dev only (heavily rate-limited). For launch: Resend or similar free tier via custom SMTP | Free tier (sign-up = Steven's OK) |
| Crash / analytics | Firebase Crashlytics (free) or Sentry free tier; privacy-friendly analytics (PostHog free tier) later | Free |
| CI | GitHub Actions: Android build + unit tests, SQL tests, web build. Monorepo path filters | Free quota |
| AI helpers | On-device ML Kit (free); Supabase `gte-small` embeddings in Edge Functions (free); optional LLM moderation later 💲 | Free first |

---

## 3. Feature plan: fresh, best-practice per feature

For each old feature: keep, rethink, defer, or drop. The best current practice is listed alongside.

| Feature | Old approach | Rebuild approach (best practice) | When |
|---|---|---|---|
| **Drops** (data at a place) | Generic post with JSONB content | Typed **drop**: category + per-category attributes + text + up to N photos; optional expiry; **reveal radius** | **MVP** |
| **Proximity reveal** (the core vision) | Not built | Server-enforced: `drops_nearby()` returns a *teaser* (category, title, approximate position) for everything in view, and the full body only if the caller's reported position is within `reveal_radius_m` (category default, author can tighten it). Anti-spoofing later with Play Integrity + mock-location detection + velocity sanity checks | **MVP** (spoof hardening v1.1) |
| **Map & discovery** | Latest 25, global | Viewport/bbox query + server clustering, category filter chips, "Near me" list sorted by distance, freshness | **MVP** |
| **Classification** | Flat enum | Data-driven taxonomy with per-category fields and rules (§4) | **MVP** |
| **Quick drop flow** | Large modal form | Waze-style FAB, then photo/text, then suggested category chips, then post (radius/expiry pre-filled) | **MVP** |
| **Verification** | Up/down votes, 5 = verified | **"Still true?"** confirm/deny from people *within X m* of the drop (Waze/Google Maps pattern). Confirmations refresh freshness; denials hide or expire it. A per-category confirmation bar sets the Confirmed badge | **MVP** |
| **Open-data layers** ("data already out there") | — | Toggleable layers fetched live through an Edge Function cache: **Wikipedia/Wikidata geosearch** first; then OSM points of interest and historical markers, and city open-data portals (e.g. official incident data for Safety). Stored as references with attribution, not copied wholesale (respects CC BY-SA / ODbL). Global rules: [`OPEN-DATA-PLAYBOOK.md`](OPEN-DATA-PLAYBOOK.md). Pilot: [`CRIME-OPEN-DATA.md`](CRIME-OPEN-DATA.md) | v1.1 (1 layer), later more |
| Comments | Comments with media | Short text replies on a drop, rate-limited | v1.1 |
| Reputation / trust levels | 5-part formula, 4 tiers, penalties | **Simple and visible**: points come from confirmed-accurate drops and from accurate confirmations (agreeing with the eventual consensus). Tiers (New / Regular / Trusted / Guide) gate rate limits and sensitive categories. Abuse is handled by moderation actions, not a downvote formula | basic tiers **MVP**, points v1.1 |
| Badges / achievements | Many, formula-based | ~10 delightful badges (First Drop, Explorer of 5 neighborhoods, Truth Keeper, Historian…), awarded by DB triggers/cron | v1.1 |
| Leaderboards | Weekly global | Local (city/area) weekly, opt-out available | v1.2 |
| Challenges / quests | Admin-created submit+vote | **Quests**: themed checklists ("Visit 3 historical drops downtown", "Document a hidden gem"), seasonal, auto-validated by geo plus category | v1.2 |
| Moderation | Mod queue, trust gating, smart filtering | **Store-mandatory UGC set**: report, block user, hide content, mod queue, published guidelines, contact. Pre-moderation only for sensitive categories or New users. Actions are audited | **MVP** |
| Appeals / transparency | Full system, half-debugged | Simple appeal on a removed drop and a monthly transparency summary | v1.2 |
| Spam / duplicates | Rule-based "AI" services | Rate limits per tier; duplicate-nearby warning (`ST_DWithin` + `pg_trgm` similarity) at post time; optional LLM/vision moderation later | MVP (rate limits + dup hint) |
| Photo proof | Planned | Capture-time photos get server-side EXIF GPS stripped (privacy) after the coordinates are checked for consistency, which earns a "taken here" mark | v1.1 |
| Messaging (DMs) | Partial | **Drop it** for now. It brings a heavy moderation and safety burden. Revisit later | later |
| Credits, paid categories, Stripe | Credits gate posting | **Drop it from MVP.** Posting is free; spam is handled by trust and rate limits. If monetized later: Apple/Google require **in-app purchase** for digital goods (15% small-business rate) 💲 | later |
| Location ownership / trading / "official" places | Doubling price, 20% fee | **Drop the speculative trading.** Replace with **claimed places**: a verified business or organization can claim a place and post official info (verification by email domain or phone). Possibly a paid tier later 💲 | later |
| Crypto wallet / token rewards | Planned | **Drop.** Adds legal, regulatory, and store-policy risk with no user value at this stage | no |
| Proximity notifications | Planned | Geofences for the nearest ~50–90 relevant drops (Android limit 100), refreshed on significant location change; user picks categories; quiet hours. Requires the background-location permission flow and Play declaration | v1.2 |
| Offline | Planned | Cache last viewed area (Room) and MapLibre offline region for a saved area | v1.2 |
| AR view | Future | ARCore Geospatial / ARKit "look through the camera, see labels" | v2 |
| iOS app | None | SwiftUI on the shared core | v2 (after Android closed test) |
| Web admin console | Admin panel in app | Small Astro/React admin area behind Supabase auth (moderators) | v1.1 |

### MVP definition (Android, closed test)
Sign in → see map of drops and teasers near you, filtered by category → walk close and the drop reveals → create a drop in about 3 taps with a suggested category → confirm or deny drops nearby → report or block → profile with my drops → share link opens a web preview → delete account. Plus the web landing, privacy, and terms pages.

---

## 4. Classification: first-class taxonomy

Classification is what turns a pile of pins into a usable lens. It drives icons, map filters, per-category fields, reveal distance, lifetime, verification bar, who can post, and moderation.

### 4.1 Three independent axes (don't mix them)
1. **Category**: *what it is* (hierarchical: group → category). Example: `safety › hazard`.
2. **Source**: *who is speaking*: `user`, `official` (claimed org), `open_data` (Wikipedia, OSM, city feed), `business` (later). Shown as a badge; affects trust defaults.
3. **Status/trust**: `active / pending_review / confirmed / disputed / expired / removed`.

Plus **free-form tags** (lowercase, limited) for search and quests.

### 4.2 Built-in taxonomy (v1, editable data, not an enum)

| Group | Categories (slug) | Per-category fields (attributes) | Default reveal | Default lifetime | Category rules |
|---|---|---|---|---|---|
| 📜 **History & Stories** | `historical_fact`, `story`, `memorial` | `era`, `year`, `source_url` (optional), `citation` | 150 m | Permanent | Confirm bar 3. "Source" field encouraged; open-data Wikipedia layer lives here |
| 🌿 **Nature & Outdoors** | `viewpoint`, `trail_note`, `wildlife`, `water_source` | `season`, `difficulty`, `accessibility` | 300 m | Permanent (seasonal flag) | Wildlife sightings auto-expire 7 d |
| 🍔 **Food & Drink** | `food_truck`, `eatery_tip`, `market` | `cuisine`, `price_level`, `hours` | 500 m | Food truck **6 h** (renewable); others permanent | "Still here?" prompts for trucks |
| 🎪 **Events** | `event`, `popup` | **`starts_at`, `ends_at` (required)**, `cost`, `link` | 1 km | Expires at `ends_at` | Must end ≤ 30 d out for New users |
| 🏷️ **Sales & Free Stuff** | `yard_sale`, `for_sale`, `free_stuff` | `price`, `currency`, `contact_method` (in-app only, no phone numbers in MVP), `ends_at` | 1 km | 3 d (yard sale) / 14 d | Personal-info filter; no weapons, drugs, or regulated items (guidelines) |
| 🚨 **Safety & Alerts** | `hazard` (road, sidewalk, flood), `safety_alert`, `incident_report` (crime) | `occurred_at` (required), `severity` (low/med/high), `ongoing` (bool) | 2 km for hazards; incident reports show **approximate location** (snapped to ~100 m grid) | **72 h** (hazard 24 h unless confirmed) | **Strict rules (§4.3)** |
| 🏛️ **Community & Faith** | `place_of_worship`, `community_space`, `notice_board` | `denomination`/`type`, `schedule` | 300 m | Permanent / 30 d for notices | Notices: Regular tier+ |
| 🔧 **Infrastructure & Hidden City** | `infrastructure`, `construction`, `underground` | `agency`, `project_url`, `est_completion` | 200 m | Construction expires at completion | — |
| 💎 **Local Secrets** | `hidden_gem`, `tip` | `best_time` | **50 m (must be there)** | Permanent | Unlock ideas for gamification |
| 🎨 **Art & Culture** | `street_art`, `public_art`, `music_spot` | `artist`, `year` | 150 m | Permanent | — |
| 📍 **Note** | `note` (catch-all) | — | 100 m | **7 d unless confirmed ×2** (carries the old "general" rule forward) | Default when AI can't decide |

Room to extend: categories are rows. Admins add or rename categories, attach a JSON Schema for the attributes, and set rules, all without an app release (the app caches the taxonomy and refreshes it). Each category has a `version`, and drops keep the category version they were created with.

### 4.3 Category-specific rules (rules engine)
Rules live as columns on `categories` and are enforced **in the database** (RLS + check triggers) and mirrored in `shared/` for instant UI feedback:
* `min_tier_to_post` (e.g. `incident_report` requires Regular+).
* `moderation_mode`: `post` (visible immediately, reportable), `pre` (held for review), or `pre_for_new` (pre-moderation for New tier only).
* `confirmations_to_confirm` (badge threshold) and `denials_to_hide`.
* `default_reveal_m`, `max_reveal_m`, `location_precision_m` (fuzzing for sensitive categories).
* `default_ttl`, `max_ttl`, `requires_fields`.
* `requires_photo` (optional per category), `allow_anonymous_display` (e.g. never for Sales).
* `sensitive` (bool): adds an extra confirm step and a guideline reminder.

**Safety & Alerts (crime) gets the hardest treatment, because this is where location apps cause real harm** (e.g. Citizen/Nextdoor racial-profiling problems):
* Pre-moderation for New/Regular tiers; Trusted+ goes live but is marked "unconfirmed" until 2 nearby confirmations.
* No naming or describing individuals. Text gets a PII/descriptor filter, and posts show a reminder ("Describe what happened, not who").
* Location fuzzed to about 100 m; short TTL (72 h); `occurred_at` required.
* Disclaimer: "In an emergency call 911. XHere is not a reporting channel to police."
* Where available, **official open-data incident feeds** (city portals) power the Safety layer as `source=open_data`, so user crime reports are not the main signal. The region-agnostic standard is [`OPEN-DATA-PLAYBOOK.md`](OPEN-DATA-PLAYBOOK.md); the Jamestown / Chautauqua worked example is [`CRIME-OPEN-DATA.md`](CRIME-OPEN-DATA.md).

### 4.4 Classification in the UX
* **Map filter**: a horizontal row of group chips (with an "All" chip), long-press to open sub-categories, saved filter presets ("Weekend": Events + Food + Sales). Server-side filtering by category slugs; per-category marker icon and color; clusters show a mini category breakdown.
* **Layers sheet** (Google-Maps style): toggle open-data layers (Wikipedia, OSM history, City incidents) separately from drop categories.
* **Quick drop flow (Waze-like)**:
  1. Tap the big ⊕. Camera opens, or tap "Text only."
  2. Snap a photo or type a line.
  3. **Suggested category chips** appear (top 3 plus "More"). One tap picks one. Required per-category fields appear inline as compact chips (e.g. Event asks "Ends: Today 6 pm ▾").
  4. Post. Reveal radius and lifetime are pre-filled from the category; "Adjust" is optional.
* **AI-suggested category** (all free):
  * On-device **ML Kit Image Labeling** turns the photo into labels (e.g. "food truck," "monument," "flood").
  * Text: **Supabase Edge Function** embeds the text with `gte-small` and compares it with category description embeddings (pgvector).
  * Context priors: popular categories near this spot, time of day, the user's recent picks.
  * A blended score gives the top 3. The user always confirms (no silent auto-classification). Corrections are logged, so mappings can be tuned later.
  * Future: on-device Gemini Nano (ML Kit GenAI) on supported phones; optional server LLM 💲.
* **Category pages**: a short explainer of each category's rules (shown the first time someone posts in it).

---

## 5. Data model (Postgres/PostGIS on Supabase)

```sql
-- taxonomy
category_groups(id, slug unique, label, icon, color, sort)
categories(
  id, group_id → category_groups, slug unique, label, description, icon, color,
  attributes_schema jsonb,          -- JSON Schema validated with pg_jsonschema
  min_tier_to_post tier, moderation_mode text, sensitive bool,
  default_reveal_m int, max_reveal_m int, location_precision_m int,
  default_ttl interval, max_ttl interval,
  confirmations_to_confirm int, denials_to_hide int,
  requires_photo bool, allow_anonymous_display bool,
  embedding vector(384),            -- for AI suggestion
  is_builtin bool, active bool, version int, created_at, updated_at)

-- people
profiles(id = auth.users.id, handle unique, display_name, avatar_path,
  tier tier default 'new', points int, home_area text, created_at, deleted_at)
user_blocks(blocker_id, blocked_id, created_at, pk(blocker_id, blocked_id))

-- drops
drops(
  id uuid, short_id text unique,     -- for xhere.world/d/{short_id}
  author_id → profiles, category_id → categories, category_version int,
  source text check in ('user','official','open_data','business'),
  title text, body text, attributes jsonb, tags text[],
  geog geography(Point,4326) not null,   -- true position (private)
  display_geog geography(Point,4326),    -- fuzzed if category requires
  reveal_radius_m int, status drop_status, expires_at timestamptz,
  confirm_count int, deny_count int, last_confirmed_at timestamptz,
  created_at, updated_at, removed_reason text)
  -- indexes: GIST(display_geog), GIST(geog), (category_id, status), (expires_at), trigram(title||body)
drop_media(id, drop_id, storage_path, width, height, taken_here bool, created_at)
confirmations(drop_id, user_id, verdict ('still_true','not_true'), note,
  distance_m int, created_at, pk(drop_id,user_id))      -- one per user (fixes old JSONB voters)
comments(id, drop_id, author_id, body, created_at, removed bool)   -- v1.1

-- safety
reports(id, target_type ('drop','comment','profile'), target_id, reporter_id,
  reason, details, status ('open','actioned','dismissed'), created_at, resolved_by, resolved_at)
moderation_actions(id, actor_id, target_type, target_id, action, reason, created_at) -- audit log
appeals(id, drop_id, author_id, message, status, decided_by, decided_at)            -- v1.2

-- open data (references, not copies)
external_sources(id, slug, name, license, attribution, url)
external_items_cache(source_id, external_id, category_id, title, geog, url, fetched_at, ttl)

-- gamification (v1.1+)
badges(id, slug, name, description, icon, rule jsonb)
user_badges(user_id, badge_id, awarded_at)
quests / quest_steps / quest_progress                      -- v1.2
category_suggestion_log(id, user_id, inputs jsonb, suggested text[], chosen_category_id, created_at)
```

**Key RPCs (Postgres functions, `security definer` where needed):**
* `drops_in_view(bbox, categories[], zoom)` → clusters or teasers (never the body).
* `drops_nearby(lat, lng, radius_m, categories[])` → teasers, plus the full body where `ST_DWithin(geog, me, reveal_radius_m)`.
* `drop_detail(id, lat, lng)` → enforces reveal; logs a coarse view count.
* `create_drop(...)` → validates category rules, rate limit by tier, JSON Schema attributes, duplicate hint, fuzzing, TTL.
* `confirm_drop(id, verdict, lat, lng)` → must be within `max(50 m, reveal_radius_m)`; updates counters and status.
* `report(...)`, `block_user(...)`, `delete_my_account()` (soft-delete now, hard-delete via cron within 30 d).
* pg_cron: expire drops, purge deleted accounts, recompute tiers nightly, refresh open-data cache.

**Privacy:** exact `geog` is never sent to other users for fuzzed categories. Strip EXIF before public storage. Don't store user location history (positions in reveal/confirm calls are used transiently and only `distance_m` is kept).

---

## 6. Repo layout (new repo, proposed name `Apixa25/XHere-App`)

> Naming note: `github.com/Apixa25/XHere` currently **redirects to the old `XHere-Web`** (the old repo was renamed). Creating a new repo literally named `XHere` would hijack that redirect, so old links and clones would point at the new repo. `XHere-App` is free and avoids that. If Steven prefers `XHere`, that also works; just know old links will then resolve to the new repo.

```
XHere-App/
├─ android/            # Android app (Compose) — Gradle project
├─ shared/             # Kotlin Multiplatform core (models, rules, data, supabase-kt)
├─ ios/                # SwiftUI app (phase 2, placeholder README for now)
├─ supabase/           # migrations/, seed.sql, functions/ (Edge), tests/ (pgTAP)
├─ web/                # Astro site for xhere.world (landing, share pages, legal, .well-known)
├─ docs/               # PLAN.md, OLD-REPO-ANALYSIS.md, PROGRESS.md, OPEN-DATA-PLAYBOOK.md, CRIME-OPEN-DATA.md, ADRs, guidelines
└─ .github/workflows/  # android.yml, supabase.yml, web.yml (path-filtered)
```
One monorepo keeps the shared contract (DB schema ↔ Kotlin models ↔ web) in one place for agents. KMP and Gradle sit at the root (`settings.gradle.kts` includes `:shared` and `:android:app`).

---

## 7. Milestones: self-contained chunks for cloud agents

**Ground rules for every chunk** (copy into each agent brief):
* Work on a branch, open a PR to `main`, keep CI green, update `docs/PROGRESS.md` with a dated (America/New_York) entry, and link the PR.
* Never commit secrets. Use `.env.example` and GitHub Actions secrets. Add a secret scanner (gitleaks) in CI from M0.
* No paid services and no new accounts without Steven's OK. If blocked on a credential, stub it, document it in the PR, and stop.
* Respect licenses (OSM ODbL, Wikipedia CC BY-SA attribution, OpenFreeMap attribution).
* Each chunk lists **Inputs**, **Deliverables**, and **Acceptance criteria**. An agent should finish one chunk per PR.

| # | Chunk | Inputs / prerequisites | Deliverables | Acceptance criteria |
|---|---|---|---|---|
| **M0** | Repo bootstrap | This plan | Monorepo skeleton (§6), root README, LICENSE choice (ask Steven; default "All rights reserved" until he decides), `.editorconfig`, `.gitignore` (no `node_modules`, no uploads, no `.env`), CI with path filters + gitleaks, ADR-001 (platform), ADR-002 (backend), CONTRIBUTING with the ground rules | CI runs green on an empty build; gitleaks passes |
| **M1** | Database schema + taxonomy seed | M0 | `supabase/migrations` for all MVP tables (§5), enums, GiST/trigram indexes, `pg_jsonschema` attribute schemas, category groups and categories with rules (§4.2), RLS policies, RPCs (`drops_in_view`, `drops_nearby`, `drop_detail`, `create_drop`, `confirm_drop`, `report`, `block_user`, `delete_my_account`), pg_cron jobs, seed data for one demo city | pgTAP tests (run in CI on `postgis/postgis` Docker image) prove: body hidden outside radius, fuzzing for Safety, rate limits per tier, attribute schema validation, one confirmation per user, blocked users' drops hidden, RLS denies cross-user writes |
| **M2** | Shared KMP core | M1 schema | `shared/`: data models matching the DB, category rules engine (mirrors DB rules for UI), validation, `supabase-kt` repositories (auth, drops, confirmations, reports, taxonomy cache), fake repos for tests | JVM unit tests ≥80% on rules engine/validation; builds for `androidTarget` (iOS targets declared but may be CI-skipped on Linux) |
| **M3** | Android app shell + auth | M2 | Compose app (`world.xhere.app`), Material 3 theme, navigation (Map / Drop / Profile), Hilt/Koin DI, Credential Manager Google sign-in + email OTP via Supabase, onboarding with location-permission rationale (foreground only), settings, sign-out | Builds `assembleDebug` in CI; Robolectric/Compose tests for auth screens; works against a Supabase project (or local) when env provided; no background permission requested |
| **M4** | Map, discovery, category filters | M3 | MapLibre Native + OpenFreeMap style with attribution, my-location puck, viewport fetch via `drops_in_view`, clustering, category chip filter bar + sub-category sheet + saved presets, "Near me" list (distance-sorted), teaser cards, marker icon per category | Panning loads only viewport data; filters change results server-side; screenshot tests for chip bar and teaser card |
| **M5** | Quick drop flow + AI category suggestion | M4 | FAB → CameraX/Photo Picker or text → ML Kit image labels + Edge Function `suggest-category` (gte-small + pgvector + nearby priors) → top-3 chips → per-category inline fields from `attributes_schema` → post via `create_drop`; image upload to Storage with EXIF stripped (Edge Function), duplicate-nearby hint, rate-limit errors handled; suggestion log | A drop can be created in ≤3 taps with a photo; unit tests for schema-driven field rendering; Edge Function tests; suggestion picks a sensible category on a fixture set (≥70% top-3) |
| **M6** | Drop detail, proximity reveal, "Still true?" | M5 | Detail screen with lock/unlock state ("Walk 120 m closer to reveal"), distance updates while foregrounded, confirm/deny within range, freshness indicator, confirmed badge per category bar, share button (`xhere.world/d/{short_id}`) | Integration tests: outside radius body never in payload; confirm outside range rejected; counters/status transitions per category rules |
| **M7** | Safety & compliance | M6 | Report (drop/profile), block user, hide, community guidelines screen, Safety-category special flow (reminder, PII filter, disclaimer, fuzzing), moderator queue (simple in-app screen gated by role), audit log, in-app + web account deletion, data export (basic JSON) | Meets Google Play UGC policy checklist and Apple 1.2 checklist (documented in PR); tests for pre-moderation of Safety posts by New users |
| **M8** | Web: xhere.world | M1 (read RPC) | Astro site: landing, `/d/{short_id}` share page (OG tags + static map image, teaser only for locked/sensitive), `/privacy`, `/terms`, `/guidelines`, `/delete-account`, `/support`, `/.well-known/assetlinks.json`; Android App Links in the app; Cloudflare Pages config (deploy only once Steven approves account/DNS) | `npm run build` green in CI; Lighthouse ≥90; share page renders from fixture data |
| **M9** | Release prep: Play closed test | M3–M8 | Release signing setup (keys stored as CI secrets, never in repo), Play listing assets, data-safety form answers, privacy policy URL, location-permission declarations, crash reporting, closed-testing track build (AAB) via GitHub Actions | Signed AAB builds in CI; checklist ready for Steven to upload (needs 💲 Play account + 12 testers × 14 days) |
| — | **MVP done** | | | |
| M10 | Open-data layer #1: Wikipedia/Wikidata | M6, [`OPEN-DATA-PLAYBOOK.md`](OPEN-DATA-PLAYBOOK.md) | Edge Function proxy + cache to `external_items_cache`, "History" layer toggle, attribution, dedupe vs user drops. This chunk also stands up the generic pipeline the playbook describes (sources, regions, `SourceAdapter`, provenance) | Layer shows within 1 s for cached areas; attribution visible |
| M11 | Reputation points + badges + comments | M6 | Points rules, nightly tier recompute, ~10 badges, profile showcase, comments (rate-limited, reportable) | Tests for point awarding and tier transitions |
| M12 | Anti-spoofing & photo proof | M6 | Play Integrity check on reveal/confirm, mock-location detection, velocity sanity, "taken here" EXIF consistency mark | Spoofed-location test fixtures rejected |
| M13 | Proximity notifications | M6 | Geofencing for nearest relevant drops, category opt-in, quiet hours, background-location permission flow + Play declaration video script | Battery-safe (WorkManager), opt-in only |
| M14 | Quests + local leaderboards | M11 | Seasonal quests, auto-validation, area leaderboards (opt-out) | — |
| M15 | iOS app (SwiftUI) | M2–M8, **Mac + Apple program 💲** | SwiftUI app on `shared` XCFramework, MapLibre iOS, Sign in with Apple, Universal Links | TestFlight build |
| M16a | `socrata` + `arcgis_feature` + `csv_bulk` / `manual_upload` adapters, plus the geocoder chain and dedup | M10, [`OPEN-DATA-PLAYBOOK.md`](OPEN-DATA-PLAYBOOK.md) §7 | Those adapters, the geocoder chain, and dedup | pgTAP: precision rule enforced; expired items hidden; config validator rejects missing license/terms |
| M16b | Safety official layer (pilot region config `us-ny-chautauqua`): stat cards, display rules, disclaimers, tip lines | M16a, [`CRIME-OPEN-DATA.md`](CRIME-OPEN-DATA.md) | Pilot region config and the Safety official layer | Official records display at the precision the publisher released, with source and disclaimer |
| M16c | User-report area activity (H3 rollup, thresholds, escalation queue, decay), building on M7 moderation | M7, M16a | Area-only Safety reports | pgTAP: public threshold enforced; sensitive user reports are never address pins |
| M16d | `police_uk` adapter + a UK region config, the first non-US proof (global design and the UK GDPR Art. 10 profile) | M16a | Adapter and UK region config | Published snap points stay offset; the UK legal profile is applied |
| M16e | Legal profiles, localized disclaimers, and the takedown flow | M7, [`OPEN-DATA-PLAYBOOK.md`](OPEN-DATA-PLAYBOOK.md) §4 | US/UK/CA/EU profiles, localized disclaimers, correction/takedown | Takedown path documented; disclaimers are localizable strings |
| M16 | Web map viewer, admin console, appeals/transparency, offline regions | — | — | — |
| M17 | Claimed places / business tier, AR view | — | — | 💲 decisions |

M16a–e are the open-data split of the original M16 row ([`OPEN-DATA-PLAYBOOK.md`](OPEN-DATA-PLAYBOOK.md) §7). OSM history/markers and city incident feeds live in that split. The row still labeled M16 keeps the rest of the original scope: web map viewer, admin console, appeals/transparency, and offline regions.

Rough pace with agents: M0–M2 in ~1 week, M3–M7 in ~2–3 weeks, M8–M9 in ~1 week. Then 14+ days of Play closed testing.

---

## 8. 💲 Cost items: each needs Steven's explicit OK

| Item | When needed | Cost | Free alternative / how to avoid |
|---|---|---|---|
| Google Play developer account | M9 (closed test) | **$25 one-time** | None for Play. Meanwhile, sideload APKs / Firebase App Distribution (free) |
| Apple Developer Program | M15 (iOS) | **$99 / year** | Defer iOS until Android is validated |
| Mac for iOS development | M15 | Hardware, or cloud Mac rental | GitHub Actions macOS runners (free quota, 10× minute multiplier on private repos); borrow a Mac |
| `xhere.world` renewal | **Before Dec 2026** | GoDaddy renewal fee (check current price) | Transfer to Cloudflare Registrar (at-cost; transfer includes 1-yr renewal) or let it lapse and use `xhere.pages.dev` |
| Old Render services (suspended) / any GoDaddy hosting | Never | Recurring if reactivated | **Don't reactivate.** Delete the Render services; point DNS at Cloudflare Pages (free) |
| Supabase Pro | Only if we hit 500 MB DB / need no-pause + daily backups | $25 / month | Free tier; keep-alive via real usage; `pg_dump` backups to GitHub Actions artifacts |
| Map tiles beyond free | At scale | Varies | OpenFreeMap (free) → self-host PMTiles on R2 free tier |
| Google Maps Platform | Not planned | Pay-as-you-go + billing account | MapLibre + OpenFreeMap |
| Transactional email at volume | Launch | Free tier usually enough | Resend/Brevo free tiers (account sign-up still needs OK) |
| LLM / vision moderation or categorization | v1.2+ optional | Per-call | On-device ML Kit + Supabase gte-small (free) |
| Geocoding at volume | If search usage grows | Varies | Photon/Nominatim within usage policy; self-host later |
| In-app purchases / business tier | Later | Store takes 15–30% | Keep free |
| Legal (LLC, lawyer review of Terms/Safety policy) | Before public launch (recommended) | Varies | Template policies to start |

**Free accounts that still need Steven's OK to create** (sign-ups, no money): Supabase, Cloudflare, Firebase/Crashlytics or Sentry, Resend. Until then, agents use local Postgres/PostGIS in Docker and stubs.

---

## 9. Old-repo cleanup recommendations (need Steven's OK; we won't touch XHere-Web)
1. Rotate or delete the Render Postgres database whose URL+password is committed in `backend/config/database.js`.
2. Delete or restrict the Google Maps API key hard-coded in `App.js`, `AppMobile.js`, `test-google-maps.html`; check Google Cloud billing.
3. Consider making `XHere-Web` private or archived (it contains committed user uploads, including profile photos, and `node_modules`).

## 10. Open questions for Steven
1. Does he have (or want) a Mac, or is "Android first, iOS later" acceptable?
2. Is the new repo private (default) or public? License?
3. Which city or area should be the demo/seed area (for testers and open data)?
4. Does he have about 12 testers for the 14-day Play closed test (friends, family, a local group)?
5. Should drops allow anonymous *display* (author hidden publicly but known to moderators)?
6. Monetization appetite later: claimed places/business tier vs. none.
