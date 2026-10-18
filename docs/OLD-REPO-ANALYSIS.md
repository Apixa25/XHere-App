# XHere (old) — Repository Analysis

**Repo:** https://github.com/Apixa25/XHere-Web (public, owner `Apixa25` = Steven Sills II)
**Studied:** 2026-10-17 (America/New_York), read-only clone at `/workspace/xhere-rebuild/old-repo` (made read-only on disk; nothing pushed or changed upstream).
**Activity:** 363 commits, 2024-12-18 → 2025-07-21. Bursts: Dec 2024 (120), Jan 2025 (74), Jun 2025 (119), Jul 2025 (50). Untouched for ~15 months.

---

## 1. What the app is, in plain language

XHere ("X marks the spot, here") is a **location-data app**: people pin data (text, photos, video, keywords, a category) to a real-world point on a map so that other people can find it there. The original README vision, which is still the heart of it:

> "Users use their phone, click on the App, select what type of data they want to see, and the App shows them the data stored at that location… based on distance from the user. Different types of data will be shown at different distances."

Around that core, Steven layered a very large **gamified trust system** (reputation, trust levels, badges, leaderboards, weekly challenges), a **credit economy** (paid post categories, buying credits with Stripe, buying/trading location "ownership" at doubling prices, "official" locations with a 150-ft boundary, nominations), and a **quality-control/moderation stack** (duplicate detection, behavioral spam analysis, content-quality scoring, "smart filtering," moderator queue, reports & appeals, transparency dashboard). Docs also mention crypto wallets on signup and paid "view rewards" for visitors. The crypto part was never built.

**State:** It's a feature-rich prototype that worked on Steven's laptop and, by July 2025, on a Pixel 6 through a Capacitor wrapper. It isn't production-grade. The headline feature (proximity-gated discovery) was **never implemented**: the API just returns the latest 25 locations. Production deployment was attempted (Heroku → Railway → Render) and left half-configured. `xhere.world` currently answers HTTP 301, and per Steven its GoDaddy hosting is suspended.

## 2. Stack

| Layer | Old choice | Notes |
|---|---|---|
| Web frontend | React 18, Create React App (`react-scripts` 5, now deprecated), React Router 6 | `App.js` (2,210 lines) and `AppMobile.js` (1,954 lines) are monoliths; ~90 components, plain CSS |
| Maps | Google Maps JS API via `@react-google-maps/api`, AdvancedMarker, Map ID, marker clusterer | Paid API (free monthly credit, needs billing account). Key is **hard-coded** in source |
| Auth (client) | `@react-oauth/google`, `jwt-decode`; token stored client-side | |
| Payments | `@stripe/react-stripe-js` + backend `stripe` 14 | Webhook never verified end-to-end ("never got the Stripe CLI to ping my dev server") |
| Mobile | **Capacitor 7** wrapping the CRA build (`com.xhere.app`), Android only; plugins: geolocation (background on), camera, filesystem, network, device, splash, status bar | `XHereMobile/` holds only a vision doc. It planned React Native + TypeScript, which never started |
| Backend | Node/Express 4, Sequelize 6, `pg`, `multer` (local disk uploads), `bcryptjs`, `jsonwebtoken`, `google-auth-library`, `string-similarity` | `server.js` + 20 route files + 21 services (~15.7k lines) |
| DB | PostgreSQL + PostGIS (`GEOMETRY(POINT)`), Sequelize migrations (≈30, some named `XXXXXXXXXXXXXX-…` / `[timestamp]_…`) plus `sequelize.sync()` and dozens of one-off fix scripts | Schema drift handled by ad-hoc scripts |
| Hosting | `render.yaml` (Render static + Node + Postgres), `netlify.toml` (x2), Heroku `heroku-postbuild`, Railway checklist; GoDaddy DNS for `xhere.world`, `api.xhere.world` | Four hosting targets tried, none finished |
| Tests | Many `test-*.js` / `check-*.js` scripts that hit a live DB; no test runner (`"test": "echo Error"`) | No CI |

## 3. Architecture

```
React SPA (web)  ─┐                      ┌─ PostgreSQL + PostGIS
Capacitor Android ┼─ REST/JSON + JWT ──▶ Express monolith ──┤
(same React code) ┘                      └─ /uploads on local disk (served statically)
                                          setInterval(60s) cleanup job in-process
```
* Single Express process does everything: API, file serving, a cleanup "cron" every 60 s, and verbose debug logging (prints DB config at startup).
* Frontend environment detection is by hostname. API URLs contain placeholders (`your-backend-name.onrender.com` TODOs).

## 4. Features (as built vs. as documented)

| Area | Built? | Detail |
|---|---|---|
| Email/password + Google sign-in | ✅ | JWT; duplicate register/login routes in both `authRoutes` and `userRoutes` |
| Map, click-to-create location, edit/delete, media upload | ✅ | Media in `content.mediaUrls` JSONB, files on local disk |
| Location types | ✅ | `general` (free, auto-deletes after 7 days unless ≥2 points) + 9 paid types (yard_sale, crime, interesting, event, market, food_truck, church, historical, for_sale) at 100 credits |
| Keywords + keyword search, type filter | ✅ | |
| Up/down votes, statuses | ✅ | 5+ up = verified, 5+ down = flagged; voters stored as JSONB array on the row |
| Comments on locations (with media) | ✅ | Last commit: "comments now work on mobile" |
| Direct messages | ⚠️ partial | "inbox does not work yet" (Jun 2025) |
| Profiles, public profiles, profile pictures | ✅ | |
| Reputation score + trust levels (new/trusted/verified/moderator) | ✅ | 5-component formula; gates daily limits, approval, credit costs |
| Downvote penalties (warning → banned) | ✅ | Thresholds at 10/25/50/100 downvotes |
| Badges/achievements, weekly leaderboard | ✅ | |
| Community challenges (submit, vote, rewards) | ✅ "seems to be fully working" | Admin-created; criteria in JSONB |
| Credits + Stripe purchase | ⚠️ | Balance/transactions work; Stripe webhook unverified |
| Location ownership trading (price doubles each sale, 20% fee), "Make Official" (150-ft boundary), nominations | ✅ (per commits) | Speculative economy; docs disagree on cost (3 vs 300 credits) |
| Geofencing (paid protected areas) | ❌ | Only in the implementation guide |
| Duplicate detection, behavioral analysis, content quality, "smart filtering" | ✅ rule-based | Docs admit it's "AI-powered (but not really)" |
| Moderator queue, reports (with evidence, anonymous), appeals, transparency dashboard | ⚠️ "about half way debugged" (Jul 2025) | |
| Role-based nav (mod/admin), admin dashboard, admin panel | ✅ | |
| **Proximity-gated discovery** ("see data only within X m") | ❌ | Geolocation only centers the map |
| Proximity notifications, offline cache, AR view, photo-GPS verification | ❌ | Vision only |
| Crypto wallet, token view-rewards, business partnerships | ❌ | Vision only |

## 4b. Data classification in the old app (how "types" worked)

Steven treats classification as central, so this section covers the old scheme in detail.

**Single flat enum `Locations.locationType`.** It's a Postgres ENUM, so every new type needed an `ALTER TYPE` migration, and values can never be removed.

| Key | Label / icon (frontend `constants/locationTypes.js`) | Description | Rule differences |
|---|---|---|---|
| `general` | General 📍 | General location information | **Free**; `autoDelete` after **7 days** unless it reaches ≥2 points; **requires approval** for New users |
| `yard_sale` | Yard Sale 🏪 | Yard/garage sales, flea markets | 100 credits (less at higher trust); no auto-expiry unless the user sets one |
| `crime` | Crime 🚨 | Crime reports and safety alerts | same as other paid types. **No extra verification**, even though this is the most sensitive category |
| `interesting` | Interesting ⭐ | Interesting places and landmarks | paid (renamed from `point_of_interest`) |
| `event` | Event 🎪 | Festivals, pop-ups, parades, fairs | paid, no built-in start/end time (only manual "time to delete") |
| `market` | Market 🥕 | Produce, markets, seasonal stands | paid (renamed from `farmers_market`) |
| `food_truck` | Food Truck 🚚 | Mobile vendors | paid, no "moved/gone" handling |
| `church` | Church ⛪ | Places of worship | paid |
| `historical` | Historical 🏛️ | Landmarks, monuments, museums, battlefields | paid |
| `for_sale` | For Sale 🏷️ | Items/property for sale | paid, no price/contact fields |

Migration history shows the taxonomy churning: `general, yard_sale, crime, point_of_interest` → + `event, farmers_market, food_truck, church, historical` → `point_of_interest`→`interesting`, + `for_sale` → `farmers_market`→`market`.

**What "stored differently" actually meant.** Every type used the *same* columns. The only differences were economic and lifecycle rules (`general` free + 7-day expiry; everything else costs credits). There were **no per-category fields** (an event has no start/end, a sale has no price, a crime report has no incident time or severity). Separately:
* **`keywords`** (free-form JSONB tags) gave a second, user-driven classification axis that was used in search and challenge criteria.
* **Profile "types"** (`constants/profileTypes.js`: ANONYMOUS, HISTORY, ADVERTISING) were alternate avatars for *who* a post appears to come from: anonymous, a historical record, or an advertisement. That's a hint at a "source/voice" dimension separate from the content category.
* **Status** (`pending/verified/flagged/removed`) and **official** flag added trust classification on top.
* Map filtering by type and keyword existed ("persistent type filter", Jun 2025), with per-type marker styling.
* The README vision adds **themed layers** (Historical, Local Secrets, For Sale, Infrastructure, Ads, Reward Available) and **per-type reveal distance** ("different types of data shown at different distances"). Neither was built.

**Lessons for the rebuild:** use a data-driven taxonomy (a table, not an enum), give categories their own fields and rules (lifetime, reveal radius, verification bar, who can post), separate *category* (what it is) from *source* (user, official, open dataset, business) and from *status/trust*, and add extra safeguards for sensitive categories like crime.

## 5. Data model (old)

Core tables (Sequelize): **Users** (email, password hash, googleId, profile JSONB, points, reputation, reputationScore, trustLevel enum, credits default 100, isAdmin, isModerator, downvote-penalty fields, several JSONB history arrays) · **Locations** (UUID, `GEOMETRY(POINT)`, `content` JSONB {text, mediaUrls, mediaTypes, isAnonymous}, keywords JSONB, locationType enum, upvotes/downvotes, `voters` JSONB, totalPoints, pointsHistory JSONB, verificationStatus *and* locationStatus (two overlapping enums), autoDelete/deleteAt, credits, isOfficial, officialBoundary, officialOwnerId, requiresApproval) · LocationComment · Message · CreditTransaction · UserCreditStats · LocationOwnership + LocationOwnershipHistory · LocationNomination + NominationVote · Badge + UserBadge · Challenge(+ -simple) / ChallengeSubmission / ChallengeVote / ChallengeReward · LocationReport · LocationAppeal · behavioral_analysis_logs.

Observations: heavy use of denormalized JSONB arrays (voters, histories) instead of relational tables, so there's no uniqueness guarantee on votes and the rows keep growing. `geometry` without a geography cast, so distances are in degrees unless you're careful. Two status enums on one table. Counters are duplicated on Users.

## 6. Auth

* bcrypt password hashes + JWT signed with `JWT_SECRET` (no refresh tokens, no expiry strategy documented). Google sign-in verifies ID token with `google-auth-library`.
* `requireAdmin`/`requireModerator` call `authenticateToken` with a fake `next`, so errors can double-send responses.
* **`PUT /api/users/make-admin` has no auth**: anyone can call it and it promotes `stevensills2@gmail.com` to admin. It's harmless only because the email is hard-coded.
* Several admin-ish endpoints (`/smart-filtering/update-thresholds`, `/behavioral-analysis/update-thresholds`, `/admin/location-status/*`) only check `authenticateToken`, not admin.
* Media delete in `server.js` compares `location.creatorId !== req.user.userId`, but middleware sets `req.user.id`, so it always returns 403 (bug).

## 7. Maps & location handling

* Google Maps JS (web) and the same code inside the Android WebView. AdvancedMarkers need a Map ID.
* Points are stored as PostGIS `GEOMETRY(POINT)` SRID 4326. PostGIS functions (`ST_DWithin`, `ST_Distance`) are used **only** in duplicate detection, built with string-interpolated lat/lng (`${latitude}`), which is a SQL-injection risk. Elsewhere JS Haversine is used.
* No bounding-box or radius query for the map. `GET /api/locations` returns the latest N (default 25) globally, so the map doesn't scale and the distance-reveal vision is missing.
* Capacitor `backgroundLocation: true` is configured but unused (and it would trigger strict Play Store background-location review).

## 8. API surface (REST, ~200 endpoints)

`/api/auth`, `/api/users`, `/api/locations` (+ `/official/*`, `/status/*`), `/api/votes`, `/api/comments`, `/api/messages`, `/api/credits` (+ Stripe intent/webhook/key), `/api/ownership`, `/api/nominations`, `/api/reputation`, `/api/downvotes`, `/api/moderator`, `/api/leaderboard`, `/api/challenges`, `/api/duplicate-detection`, `/api/behavioral-analysis`, `/api/content-quality`, `/api/smart-filtering`, `/api/reports|appeals|transparency` (same router mounted 3×), `/api/admin`, `/api/health`. Many analysis endpoints are internal utilities exposed publicly.

## 9. Mobile

* Android via Capacitor (Java `MainActivity`, Gradle, splash/icons). Runs on emulator and Pixel 6 (Jul 2025). API base `10.0.2.2:3000` for the emulator.
* No iOS project. The "XHereMobile" React Native app never started.
* Steven's own takeaway (per Magnus): the web-wrapped approach "held him back from the app stores." Google Play and Apple do accept wrapped apps, but a WebView app gives a weaker native feel and weaker background location, camera, and notification integration, and Apple may reject a thin wrapper under guideline 4.2 (minimum functionality).

## 10. Deployment config & environment variables

| Var | Used by | Service | Costs money? |
|---|---|---|---|
| `DATABASE_URL`, `DB_USER/PASSWORD/HOST/NAME` | backend | Postgres (Render/Railway/Heroku) | Render/Railway/Heroku Postgres: paid after free trials |
| `JWT_SECRET` | backend | — | — |
| `GOOGLE_CLIENT_ID/SECRET`, `REACT_APP_GOOGLE_CLIENT_ID` | both | Google OAuth | Free |
| `GOOGLE_MAPS_API_KEY`, `REACT_APP_GOOGLE_MAPS_API_KEY`, `REACT_APP_GOOGLE_MAPS_MAP_ID`, `REACT_APP_USE_ADVANCED_MARKER` | both | Google Maps Platform | **Yes**: pay-as-you-go after monthly free usage; billing account required |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET` | backend | Stripe | **Yes**: per-transaction fees |
| `REACT_APP_API_URL`, `REACT_APP_ENVIRONMENT`, `FRONTEND_URL`, `PORT`, `NODE_ENV`, `ADMIN_TOKEN` | misc | — | — |
| (domain) `xhere.world` | DNS | GoDaddy | **Yes**: renewal; hosting plan suspended |

## 11. 🚨 Security & hygiene findings (action items for Steven, not done by us)

The repo is **public**, so these are exposed right now:
1. **A production database connection string with password is hard-coded** in `backend/config/database.js` (Render Postgres, comment: "TEMPORARY, INSECURE WORKAROUND"). → Rotate the password or delete that Render database. The secret stays in git history even if the file is edited.
2. **A Google Maps API key is hard-coded** in `frontend/src/App.js`, `frontend/src/AppMobile.js`, `test-google-maps.html`. → Delete or restrict that key in Google Cloud Console (and check the billing account for unexpected usage).
3. Local dev DB passwords sit in `config/config.json` / `backend/config/config.json` / `config.js` (low risk if only used locally).
4. **`node_modules` is committed** (38,423 of 39,151 tracked files; `.git` = 296 MB).
5. **~270 user-uploaded files are committed**, including ~18 profile pictures (`backend/uploads/profile-pictures/…`). They may contain real people's photos or EXIF GPS data.
6. Unauthenticated `make-admin` endpoint; missing admin checks on threshold endpoints; SQL string interpolation in PostGIS queries.

Consider making the old repo private or archiving it. That changes the old repo, so it needs Steven's explicit OK and we have not done it.

## 12. Known bugs / TODOs / unfinished (from code + commit messages)

* Proximity reveal not implemented. Map loads only the newest 25 items.
* Messaging inbox broken. Report & Appeal "half way debugged."
* Stripe webhook unverified. Credit-placement UI abandoned ("New Location Modal needs to be completely refactored").
* Frontend prod config TODO placeholders. Four half-done hosting configs.
* `sequelize.sync` + manual scripts → schema drift. Duplicate route files (`authRoutes` vs `userRoutes` both do register/login/google).
* 60-second in-process cleanup interval (should be a scheduled job).
* Monolithic `App.js`/`AppMobile.js`. Two parallel UIs for web and mobile.
* No automated tests or CI.

## 13. Commit-history highlights

* 2024-12-18 first commit; 12-22 "Completely stable version"; 12-24 Heroku deploy attempt; 12-31 admin full authority.
* 2025-01-01→09 voting/verification, credits ("XHere tokens"), admin dashboard, Google AdvancedMarkers ("Woo Hoo!"), profile pictures; 01-11 XHereMobile folder.
* (5-month gap)
* 2025-06-21→30 info window, Render + PostGIS, messaging, user-location centering, type/keyword filters, Stripe webhook struggle, trading UI, Official Location system, map-centering fix.
* 2025-07-02→08 trust dashboard, community challenges, behavioral analysis, report/appeal, role-based nav.
* 2025-07-21 Android Studio emulator, then running on a physical Pixel 6, then comments on mobile (last commit).

## 14. Steven's vision, distilled from ALL the docs

Docs read in full: README, project-vision.md, docs/ai-context-guide.md, XHereMobile/project-vision-mobile.md, XHere-Master-Guide.md, XHere-Reputation-System-Guide.md, LOCATION_TRADING_IMPLEMENTATION_GUIDE.md, DATA_QUALITY_IMPLEMENTATION_GUIDE.md, XHere-AI-Powered-Spam-Detection.md, XHere-Community-Challenges-Guide.md, XHere-Report-and-Appeal.md, README-withcryptointegration.md, README-Orderofoperations.md, DEPLOYMENT_GUIDE.md, DEPLOYMENT_CHECKLIST.md, ANDROID_SETUP.md, ai-setup.md, frontend/{README, DEVELOPMENT_WORKFLOW, MOBILE_DEVELOPMENT}.md, frontend/docs/location-trading-frontend.md, backend/docs/{ai-vs-traditional-detection, behavioral-analysis, cleanup, content-quality-analysis, duplicate-detection, enhanced-rating, location-trading-api, smart-filtering}.md.

The recurring ideas, ranked by how central they are:
1. **Data lives at places; your phone's position unlocks it.** Different kinds of data reveal at different distances.
2. **Truth through community.** Verify/refute, "Verified" status, reputation that unlocks privileges, transparent moderation and appeals. Steven: "Rewards should feel earned, not given."
3. **Fun (Enneagram-7 energy).** Points, badges, "Top Explorer in [City]," weekly missions ("Hidden Gems," "Historic Challenge"), storytelling prompts, layered map themes (Historical, Local Secrets, For Sale, Infrastructure).
4. **Proof.** Photo/video evidence, GPS/EXIF/timestamp checks, "must be physically present."
5. **Economy.** Credits, paid categories as spam deterrent, ownership and trading, "official" places, business partnerships, ads, token rewards for viewers.
6. **Mobile-first, native feel.** GPS, camera, push, offline, AR "future."

Steven's broader theme for the rebuild is that *the future is reaching data that's already out there but not yet accessible*. That fits idea #1 directly. XHere can become the **place-anchored lens** that combines what people drop with open geodata that already exists (OpenStreetMap, Wikipedia/Wikidata, public datasets) and makes it findable by standing there.
