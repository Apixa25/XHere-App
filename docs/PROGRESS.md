# XHere Rebuild — Progress Log

All times America/New_York (ET).

## 2026-10-17 (Sat), ~8:25–8:45 PM ET: Planning kickoff
- Cloned `Apixa25/XHere-Web` read-only to the box (`/workspace/xhere-rebuild/old-repo`, write permissions removed). Nothing was changed, pushed, or deleted upstream.
- Read every doc in the old repo (30 markdown files) and surveyed the code: stack, ~200 REST endpoints, models, auth, maps/PostGIS usage, Capacitor Android, deploy configs, env vars, and 363 commits (Dec 2024 → Jul 2025).
- Wrote `docs/OLD-REPO-ANALYSIS.md`, which includes the old data-classification scheme and the security findings (a committed production DB URL and Google Maps key in the public repo; committed `node_modules` and user uploads).
- Wrote `docs/PLAN.md`: fresh product framing (drops + open-data layers), platform decision (**native Android in Kotlin/Compose with a Kotlin Multiplatform shared core first, iOS in SwiftUI second**), Supabase/PostGIS backend, a small Astro site on Cloudflare for xhere.world, a first-class category taxonomy with per-category fields and rules, a data model, milestones M0–M17 sized for cloud agents, and a 💲 cost list.
- Tried to create the new GitHub repo (pre-authorized by Steven via Magnus) through the GitHub connector. It was **blocked: `403 Resource not accessible by integration`** (the connected GitHub app isn't allowed to create repos). Nothing was created.
- Found that `Apixa25/XHere` redirects to the old `XHere-Web`, so the proposed new name is **`XHere-App`** (available) to avoid hijacking that redirect.
- Prepared a ready-to-push local git repo on the box at `/workspace/xhere-rebuild/new-repo` (`main` with README, plus branch `docs/planning` with these three docs under `docs/`).
- No money spent, no accounts created, no messages sent.

### Next
- Steven to review PLAN.md §8 (costs), §9 (old-repo cleanup), §10 (open questions).
- Then start **M0 (repo bootstrap)** and **M1 (schema + taxonomy)**.

## 2026-10-17 (Sat), ~11:20 PM ET: Repo live + old-repo security pass
- Steven created the empty repo `Apixa25/XHere-App`. **Push blocked:** the GitHub connector can *read* the repo but every write (`create_or_update_file`) returns `403 Resource not accessible by integration`. This is the same error as the earlier repo-creation attempt, so the connector's GitHub App likely lacks access to this repo. The other GitHub connector has push rights but no file-write tool, and an empty repo needs a first commit before branches or PRs exist. Commits are staged locally (`main` 4aeb2d3, `docs/planning` bae6d8e) and bundled as `xhere-app.bundle`. Not yet on GitHub.
- Old-repo security (approved by Steven): traced every leaked secret in XHere-Web's current tree **and git history** without using any of them:
  - Render Postgres `xhere-database` (`dpg-d1c9eg8dl3ps73fe6adg-a`) URL+password in `backend/config/database.js`. Free Render DBs auto-delete after 30+14 days, so it's likely already gone; Steven to confirm.
  - Google Maps key in `App.js`/`AppMobile.js`/`test-google-maps.html` plus 2 older keys in history; Google Cloud project number likely `52660706525`.
  - Also in history: Stripe **test** keys, the old JWT secret, `.env` files (removed 2025-06-27 but still in history).
  - Found that the "suspended hosting" is Render (`xhere.world` DNS points at suspended Render services), not GoDaddy.
- No connector can change repo visibility/archive or touch Render/Google Cloud/Stripe, so I wrote click-by-click steps in `docs/SECURITY-FIXES.md`. A history purge isn't needed once the credentials are rotated or deleted.

## 2026-10-17 (Sat), ~11:40 PM ET: Push retry after Steven's GitHub access update (still blocked)
- `user-GitHub-xai` `create_or_update_file` (README.md → `main`): `403 Resource not accessible by integration` (PUT /repos/Apixa25/XHere-App/contents/README.md). Refetched the tool descriptor and retried once: same 403. The connector can still read the repo (it sees it's empty and has no branches).
- `cursor-github` (acts as Apixa25 with admin/push): it has no file-write or commit tool. `create_branch main` → `409 Git Repository is empty`, because a first commit has to exist before any branch, PR, or merge.
- Nothing was written to GitHub. Commits are staged locally (`main` 4aeb2d3, `docs/planning` with all docs) and in `xhere-app.bundle`.
- Likely cause: the GitHub App behind `user-GitHub-xai` still lacks **Contents: write** on XHere-App. Fixes: (a) on https://github.com/settings/installations → that app → Configure, set Repository access to include XHere-App and approve any pending permission request (Contents read & write, Pull requests read & write); or (b) Steven commits a README in the web UI ("creating a new file" link on the empty repo page), after which `cursor-github` can create the branch, but it still can't upload the docs; or (c) push the bundle from Stevens-WM with his own git.

## 2026-10-17 (Sat), ~11:45 PM ET: Docs hand-off to a cloud agent
- Steven added a README to `main` (first commit). Retried `user-GitHub-xai` `create_branch docs/planning`: still `403 Resource not accessible by integration` on POST /git/refs. The connector's writes remain blocked.
- Routed the docs through a Cursor cloud agent instead. `create_gist` (secret) also returned 403, so the full doc contents are embedded in the agent prompt. The agent commits them on `docs/planning`, opens a PR, and merges it. Merged README keeps Steven's title/description and adds the project summary + docs pointer.
- Secret check before sharing: scanned all docs for API-key, Stripe-key, Postgres-URL, long-hex, and password patterns. None found. Keys appear only as masked fingerprints.
- Cloud-agent prompts are prepared (`handoff/docs-agent-prompt.md` with the full docs embedded, `handoff/m0-agent-prompt.md`). The launch is done by the main Grok Bot session (grok-4.7, high effort).
- Next: M0 (repo bootstrap, CI, gitleaks) as a cloud agent after the docs merge.

## 2026-10-17 (Sat), ~11:50 PM ET: M0 repo bootstrap

Started from `main` after the planning docs merged ([PR #1](https://github.com/Apixa25/XHere-App/pull/1), merge `9cf2a6c`).

- Monorepo skeleton from PLAN §6: `android/`, `shared/`, `ios/`, `supabase/` (`migrations/`, `functions/`, `tests/`, each with `.gitkeep`), `web/`, `docs/adr/`, `.github/workflows/`. Each app directory is a short README naming the milestone that fills it. Gradle and the Astro app are not scaffolded (those are M2 and M8).
- Root files: README layout section (Steven's title and description kept), `LICENSE` ("All rights reserved", Copyright (c) 2026 Steven Sills II, until he picks a license), `.editorconfig`, `.gitattributes` (LF), `.gitignore`, `.env.example` (placeholders only), `CONTRIBUTING.md` with the PLAN §7 ground rules.
- CI: `.github/workflows/ci.yml` runs gitleaks **8.30.1** (free CLI binary, checksum-pinned) over every commit reachable from `HEAD`. `gitleaks-action` does not need a paid license on this personal account, but its push and pull_request modes scan only the new range, so the CLI is what actually covers full history. Path-filtered placeholders `android.yml`, `supabase.yml`, and `web.yml` run a trivial job. `.gitleaks.toml` extends the default rules. The masked fingerprints in the docs (`AIzaSyAX…iVQ0` and the other shortened keys) were not flagged, so nothing was allowlisted. `.github/dependabot.yml` watches `github-actions` only. Actions are pinned to a major version with `permissions: contents: read`.
- ADRs in short MADR: [ADR-001](adr/ADR-001-platform.md) (native Android, KMP shared core, SwiftUI later, Expo runner-up) and [ADR-002](adr/ADR-002-backend.md) (Supabase + PostGIS, MapLibre + OpenFreeMap, Astro on Cloudflare Pages).
- PR: https://github.com/Apixa25/XHere-App/pull/2
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Pick a license.** `LICENSE` stays "All rights reserved" until then.
- **Secret scanning + push protection** (free on this public personal repo): **Settings → Code security** → enable **Secret scanning** and **Push protection**. Do not buy GitHub Advanced Security; gitleaks in CI is the scanner if those toggles are missing.
- **Branch protection on `main`:** **Settings → Rules → Rulesets** (or Branches → classic protection) requiring the **ci / gitleaks** check before merge. Add the android, supabase, and web checks once they run real builds. Today only `ci` runs on every push and PR.
- Still open from the plan, unchanged by M0: [§8 costs](PLAN.md), [§9 old-repo cleanup](SECURITY-FIXES.md), [§10 questions](PLAN.md). Supabase and Cloudflare accounts were not created.

## 2026-10-18 (Sun), ~12:35 AM ET: M1 database schema + taxonomy

Branch `m1/database` → [PR #3](https://github.com/Apixa25/XHere-App/pull/3). Built and tested entirely on the free local Supabase stack (Supabase CLI 2.121.0 + Docker), with no Supabase account.

- `supabase/config.toml` from `supabase init` (`project_id = "xhere"`, no secrets).
- 7 migrations:
  - PostGIS, pg_jsonschema, pg_trgm, pgvector, pg_cron.
  - Core tables per PLAN §5: profiles, blocks, user moderation, categories/groups/versions, drops with `geography(Point)`, drop media refs, confirmations, reports, moderation audit log, tier limits, content filters.
  - GiST/trigram indexes; RLS on every table.
  - RPCs: `drops_in_view`, `drops_nearby`, `drop_detail`, `drop_teaser`, `create_drop`, `confirm_drop`, `report`, `block_user`/`unblock_user`, `delete_my_account`, plus moderator `moderate_drop`/`resolve_report`/`suspend_user`.
  - pg_cron jobs: expiry, 30-day account purge, nightly tier recompute.
- Categories are data: JSON-schema fields plus rules (reveal radius, lifetime caps, confirmation/denial/report thresholds, moderation mode, location blur, content filters, minimum tier) and are versioned. The full PLAN §4 taxonomy (11 groups, 30 categories) ships in a migration.
- Crime (`incident_report`): regular tier or above, pre-moderated (trusted exempt), ~100 m blur, hard 72 h expiry, no describing people or contact info, guidelines acknowledgement, auto-hidden at 2 reports.
- The true location is never returned to other users. Reveal and distance use the blurred point.
- `supabase/seed.sql`: local-only demo data for Lower Manhattan, a placeholder until §10 question 3 picks the demo city.
- 255 pgTAP assertions in `supabase/tests/database/`. `.github/workflows/supabase.yml` runs `supabase db reset`, `db lint`, and `test db` on the GitHub runner.
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Demo city:** Jamestown, New York, chosen 2026-10-18 (PLAN §10 question 3). The seed move is logged below.
- **Branch protection:** add the **supabase / pgtap** check to the `main` ruleset next to **ci / gitleaks**.
- **First admin/moderator**, once a hosted Supabase project exists: one SQL line in the dashboard, `update public.profiles set role = 'admin' where handle = '<your handle>';` (see `supabase/README.md`).
- Review the crime rules and the `person_descriptors` word list in `supabase/migrations/20261018000600_taxonomy.sql`. Tuning means a new migration that updates the row; no code change is needed.
- Still open from M0: license, secret scanning/push protection toggles, and PLAN §8–§10.

## 2026-10-18 (Sun), ~1:00 AM ET: Demo city is Jamestown

Steven picked **Jamestown, New York** as the demo/test city (PLAN §10 question 3). Branch `seed/jamestown` → [PR #4](https://github.com/Apixa25/XHere-App/pull/4).

- `supabase/seed.sql` still loads 4 demo accounts and 15 drops, in the same categories as the Lower Manhattan placeholder: `historical_fact` twice, and one each of `viewpoint`, `water_source`, `public_art`, `eatery_tip`, `underground`, `hidden_gem`, `food_truck`, `event`, `hazard`, `yard_sale`, and `note`, plus `incident_report` twice (one from the trusted moderator, one from the regular user waiting on review).
- Coordinates are real public spots: Fenton History Center (67 Washington Street), the Lucille Ball Desi Arnaz Museum (2 West 3rd), Lucille Ball Memorial Park on the Chautauqua Lake shore in Celoron (the lake view and Carolyn Palmer's 2016 statue), McCrea Point, West 3rd Street, the North Main Street tunnel under the old Erie grade, the I Love Lucy mural by the Chadakoin, Rosa Alley beside the National Comedy Center, Tracy Plaza, the Greater Jamestown Riverwalk at Brooklyn Square, the James Prendergast Library, Allen Park, and West 3rd Street Lot #1.
- `supabase/README.md` names Jamestown. PLAN §10 question 3 records the answer. The pgTAP files never hardcoded the old placeholder (they use their own fixtures around 40°N, 100°W), so they were left unchanged.
- `shared/` was not touched. M2 is being built there in parallel.
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- Unchanged from M0 and M1: pick a license, turn on secret scanning and push protection, and add **supabase / pgtap** beside **ci / gitleaks** on the `main` ruleset. PLAN §8 and §9 are still open.

## 2026-10-18 (Sun), ~1:30 AM ET: Open-data research (Safety pilot) + global playbook

Branch `docs/open-data-playbook` → [PR #5](https://github.com/Apixa25/XHere-App/pull/5).

- Wrote `OPEN-DATA-PLAYBOOK.md`, the region-agnostic standard:
  - source-discovery checklist (global → city, US + UK/Canada/EU equivalents);
  - `SourceAdapter` interface + normalized data contract (precision, provenance, dedup, refresh, retention);
  - per-region YAML config;
  - legal checklist per jurisdiction profile (US/UK/CA/EU incl. GDPR Art. 10, Apple 1.7) + standard disclaimers;
  - display rules (official = exact + sourced; user reports = area-only with thresholds);
  - non-crime categories;
  - M10/M16a–e fit.
- Wrote `CRIME-OPEN-DATA.md` (Jamestown/Chautauqua worked example): 18 sources assessed.
  - Usable free now: data.ny.gov Index Crimes + Adult Arrests (Socrata API), DEA clan-lab register (manual CSV; the site blocks bots), city news RSS, county parcels (geocoding), JPD tip line.
  - Address-level code enforcement / Ch. 214 nuisance / condemned lists aren't published, so the next step is a FOIL + data request. Drafted but not sent.
  - Excluded: courts (terms), Sheriff bookings / news blotters (person-level), SpotCrime (paid, $199+/mo, cost item).
- PLAN.md on `main` was not replaced. Merged in only the M16a–e split and the open-data / global-playbook references. M0 and M1 progress entries are unchanged.

## 2026-10-18 (Sun), ~1:10 AM ET: M2 shared KMP core

Branch `m2/shared-core` → [PR #7](https://github.com/Apixa25/XHere-App/pull/7). Root Gradle build (Kotlin 2.4.21, version catalog, wrapper 9.5.0) and the `shared/` module.

- Targets: Android library (`compileSdk` 36, `minSdk` 26, `com.android.kotlin.multiplatform.library`) and iOS (`iosArm64`, `iosSimulatorArm64`, static framework `Shared`). A JVM target exists only so `commonTest` can run on the Linux runner. iOS compile is not invoked in CI (no Xcode). `kotlin.native.ignoreDisabledTargets=true`.
- Models mirror the M1 tables: profiles, tiers, categories with field schemas and rules, drops, media, confirmations, reports, blocks.
- Repositories call the M1 RPCs through `supabase-kt` 3.8.0 (`drops_in_view`, `drops_nearby`, `drop_detail`, `drop_teaser`, `create_drop`, `confirm_drop`, `report`, `block_user` / `unblock_user`, `delete_my_account`, `moderate_drop`, `resolve_report`, `suspend_user`) plus auth (email OTP, Google / Apple id token). An in-memory fake implements the same interfaces. Taxonomy is cached in memory. No Room cache: PLAN puts offline maps at v1.2.
- Client checks mirror `create_drop` (tier, title, body, tags, reveal radius, lifetime, media, content filters, guidelines) and validate attributes against the category JSON schema. POSIX filter patterns (`[[:alnum:]._%+-]`, `\m` / `\M`) are translated before the Kotlin regex runs. Geo helpers match `private.fuzz_point`.
- `commonTest`: 45 tests. Kover on `rules` and `validation` is 91% line / 86% instruction, bound 80%. `.github/workflows/android.yml` (path-filtered) installs Java 21 and Android platform 36, then runs `:shared:jvmTest`, `:shared:koverVerify`, and `:shared:assembleAndroidMain`.
- Config is `SupabaseConfig.PLACEHOLDER`, the same strings as `.env.example`. No project, no account, no secrets. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Branch protection:** add **android / shared** next to **ci / gitleaks** and **supabase / pgtap**.
- **A free Supabase project** before the client can talk to a real API (PLAN §8). Until then the placeholder URL is intentional. Local Postgres from M1 does not serve the PostgREST the app calls.
- **A Mac** before the iOS framework is actually compiled (M15). The targets are configured now.
- Unchanged from M0/M1: license, secret scanning / push protection, first admin SQL, crime-rule review, PLAN §8–§10. The demo city is Jamestown (PR #4).

## 2026-10-18 (Sun), ~1:40 AM ET: M3 Android shell + auth

Branch `m3/android-shell` → [PR #8](https://github.com/Apixa25/XHere-App/pull/8). Compose app `world.xhere.app` on the M2 shared core (PLAN §2, §7).

- Material 3 theme with a fixed lagoon-and-amber palette (no dynamic color). Bottom navigation: Map, Drop, and Profile are placeholders for M4–M5. Settings sits on the profile tab.
- **Koin**, not Hilt. `XHereServices` is a plain Kotlin interface, so the app binds `SupabaseXHereClient` and Robolectric binds `InMemoryXHere` without kapt/ksp. AGP 9 already compiles Kotlin.
- Sign-in: Credential Manager Google ID token, and email OTP, both through `supabase-kt` (`signInWithIdToken`, `signInWithEmailOtp`, `verifyEmailOtp`). The Google nonce is SHA-256 to Google and raw to Supabase.
- Onboarding is the foreground-location rationale. Continue requests `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` only. "Not now" skips the request. `ACCESS_BACKGROUND_LOCATION` is not in the manifest (that is M13).
- Settings: sign out, and delete account. Delete calls `AccountRepository.deleteMyAccount()`, which the Supabase client implements as the `delete_my_account` RPC and then signs out.
- Config is `SupabaseConfig.PLACEHOLDER` unless `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `GOOGLE_WEB_CLIENT_ID` are set in gitignored `local.properties` or `.env`. The service-role key is not a BuildConfig field. The app builds and its tests run with no Supabase project.
- CI: `.github/workflows/android.yml` keeps the `shared` job and adds `app` (`assembleDebug` and `testDebugUnitTest`). `ci / gitleaks` and `supabase / pgtap` are unchanged.
- `docs/IDEAS.md` notes the Jamestown FOIL request was sent 2026-10-18 1:09 AM ET.
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Branch protection:** add **android / app** next to **android / shared**, **ci / gitleaks**, and **supabase / pgtap**.
- **A free Supabase project** before sign-in works against a server (PLAN §8). Put the project URL and the anon key in `local.properties`. Turn on Email (OTP) and Google. Do not put the service-role key in the app.
- **A free Google Cloud OAuth web client id** (same OK as any new account, PLAN §8) in `GOOGLE_WEB_CLIENT_ID`, and the same id as the Google provider client id in Supabase. The SHA-256 nonce above has to match that provider's check.
- Background location stays off until M13. Do not add it to the manifest for the closed test.
- Unchanged from M0–M2: license, secret scanning / push protection, first admin SQL once a project exists, crime-rule review, a Mac before iOS (M15), PLAN §8 and §9. The demo city is Jamestown (PR #4). The Jamestown FOIL request is sent; the code-enforcement email to dod@jamestownny.gov is not.

## 2026-10-18 (Sun), ~2:25 AM ET: M4 map, discovery, and category filters

Branch `m4/map-discovery` → [PR #9](https://github.com/Apixa25/XHere-App/pull/9). Map screen for PLAN §4.4 and §7 M4, on the M3 shell.

- MapLibre Native Android 13.7.1, OpenGL build, no API key. Style is OpenFreeMap Liberty, or Dark when the phone is in dark mode (`https://tiles.openfreemap.org/styles/liberty` and `.../dark`). The map shows "© OpenStreetMap contributors · © OpenMapTiles · OpenFreeMap", and MapLibre's own attribution control stays on.
- Default camera is downtown Jamestown, NY (West 3rd Street). The my-location control uses `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` only, through `LocationManager`. No background permission, no Play Services, no tile key.
- Camera idle waits 320 ms, then `DropRepository.dropsInView` for that box, zoom, and the current category list. Zoom 15 and above returns drops. Below that the repository returns grid clusters. A cluster tap zooms in.
- Category chips are the 11 taxonomy groups. Long-press opens a sub-category sheet. The built-in Weekend preset sends `events`, `food_drink`, and `sales_free`. Saved presets live in app-private preferences. Those slugs are the `p_categories` argument on `drops_in_view` and `drops_nearby`. The phone does not filter a downloaded city.
- Near me is `drops_nearby` from the fix, or from the Jamestown center when there is no fix, within 5 km, nearest first. A marker opens `drop_teaser`. The body stays hidden until reveal (M6). Marker icons come from the taxonomy.
- With no Supabase URL the app binds `InMemoryXHere` seeded with Jamestown demo drops (Fenton, West 3rd, a food cart, Tracy Plaza, the Riverwalk, Celoron, and one Buffalo drop outside the city so a pan cannot return the whole world). The sign-in screen offers Preview Jamestown. Tests still sign in with the email code against that fake. Tiles need a network; the demo drops do not.
- Compose UI tests cover the chip bar (including a captured image), the teaser card, viewport pan, group and preset filters, the sub-category sheet, near me, clustering, and a saved preset. `assembleDebug` and the unit tests pass locally. Supabase SQL was not changed, so `supabase / pgtap` is untouched.
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Branch protection:** keep **android / app** on the `main` ruleset next to **android / shared**, **ci / gitleaks**, and **supabase / pgtap**. M4 runs in the app job that M3 added.
- **A free Supabase project** before the map reads live drops (PLAN §8). Until then, Preview Jamestown is the local fake. Put the project URL and the anon key in `local.properties`. Do not put the service-role key in the app.
- **A network** when you want the OpenFreeMap tiles. There is no tile key to buy or to paste. The demo drops still show if the tiles do not load.
- Background location stays off until M13.
- Unchanged from M0–M3: license, secret scanning / push protection, a free Google Cloud OAuth web client id before Google sign-in works, first admin SQL once a project exists, crime-rule review, a Mac before iOS (M15), PLAN §8 and §9. The demo city is Jamestown (PR #4). The Jamestown FOIL request is sent; the code-enforcement email to dod@jamestownny.gov is not.

## 2026-10-18 (Sun), ~3:30 AM ET: M8 website for xhere.world

Branch `m8/website` → [PR #11](https://github.com/Apixa25/XHere-App/pull/11). Astro site in `web/` for PLAN §1.5 and §7 M8. Not deployed.

- Landing page: attach any data to any real-world place, and find what others left. Pilot city is Jamestown, NY. The app is global. Category groups match the M1 taxonomy.
- `/d/{short_id}` is a teaser share page. Open Graph and Twitter tags point at a static SVG map drawn from OpenFreeMap planet vector tiles (the same free tiles as the Android MapLibre styles). No MapLibre GL in the page, so the Lighthouse run stays a static document. Sensitive drops (`safety_alert`, `incident_report`) and any drop with a precision blur draw a dashed area, not a pin. The hidden body, attributes, media, and tags are not on the page type and are not rendered. The author handle is omitted on sensitive teasers.
- With no Supabase env, or with the `.env.example` placeholders, build and the share pages use three Jamestown fixtures (`JmtwnHist01a`, `JmtwnNote02b`, `JmtwnInc03cd`). `npm run verify` fails the build if a fixture's secret body or the sensitive author handle appears in `dist/`. Live reads, once `PUBLIC_SUPABASE_URL` and `PUBLIC_SUPABASE_ANON_KEY` are real, call `drop_teaser` only. Cloudflare Pages Functions under `web/functions/` serve unknown short ids the same way. The anon key is the only key those functions read. Static files win for the fixture ids.
- `/privacy`, `/terms`, and `/guidelines` are plain-English drafts. Each page says Steven Sills II has not approved the text. Guidelines include the incident-report rules: Regular tier and above, pre-moderation (trusted accounts exempt), about 100 m blur, 72-hour lifetime, unconfirmed until two nearby confirmations, auto-hide at two reports, no contact info and no person descriptors, and the notice to describe what happened rather than who, to call 911, and that XHere is not a channel to the police.
- `/delete-account` is the web deletion page Google Play asks for. In the app: Profile → Settings → Delete account → confirm. That is the `delete_my_account` soft-delete (handle replaced, display name, avatar, and home area cleared, blocks removed, signed out). `private.purge_deleted_accounts` hard-deletes the login after 30 days. Photo files in storage wait on the storage cleanup that is not running yet. Email from the account address is the fallback, because this site has no hosted login.
- `/support` is a mailto tester note. It stores nothing.
- `/.well-known/assetlinks.json` names package `world.xhere.app` and the fingerprint string `PLACEHOLDER_NOT_A_REAL_CERT__REPLACE_WITH_PLAY_APP_SIGNING_SHA256`. That string is not a certificate hash. `MainActivity` has an `autoVerify` VIEW filter for `https://xhere.world/d/*`. Verification cannot succeed until the placeholder is the Play App Signing SHA-256.
- Cloudflare: `web/wrangler.toml` (`pages_build_output_dir = dist`, no `account_id`) and `web/CLOUDFLARE.md`. Nothing was deployed and no Cloudflare account was created. DNS for `xhere.world` stays where it is.
- Owner on the pages is Steven Sills II. Contact is `hello@xhere.world`, labeled as a placeholder mailbox that may not exist. No company, address, phone, or court was invented.
- CI: `.github/workflows/web.yml` job `build` (check name **web / build**) runs `npm ci`, `npm run build`, `npm run verify`, and Lighthouse CI (`@lhci/cli`) against the static `dist`. The assert floor is 0.90 on performance, accessibility, best-practices, and SEO. Local run on 2026-10-18, one pass each, all four categories **100** on `/`, `/d/JmtwnHist01a/`, `/privacy/`, `/terms/`, `/guidelines/`, `/delete-account/`, and `/support/`.
- Android unit test `AppLinksManifestTest` checks that the two fixture https drop links resolve to `MainActivity` and that other hosts and paths do not. It runs in the existing **android / app** job.
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Do not deploy.** Approve a free Cloudflare account and the DNS change before anyone runs a Pages deploy. Steps are in `web/CLOUDFLARE.md`. `xhere.world` still points at the old Render services. GoDaddy registration runs through Dec 2026.
- **Replace the assetlinks placeholder** with the Play App Signing SHA-256 before App Links can verify. The string in the repo is not a fingerprint.
- **A mailbox for `hello@xhere.world`**, or a different address, before the contact link can receive mail. The address on the site is a placeholder.
- **Read the draft privacy, terms, and guidelines** and say what to change. They are not in effect.
- **A free Supabase project** before a share link can load a live drop (PLAN §8). Set `PUBLIC_SUPABASE_URL` and `PUBLIC_SUPABASE_ANON_KEY` on Pages. Do not set the service-role key.
- **Branch protection:** add **web / build** next to **android / app**, **android / shared**, **ci / gitleaks**, and **supabase / pgtap**.
- Unchanged from M0–M4: license, secret scanning / push protection, a free Google Cloud OAuth web client id before Google sign-in works, first admin SQL once a project exists, crime-rule review, a Mac before iOS (M15), PLAN §8 and §9. Background location stays off until M13. The demo city is Jamestown (PR #4). The Jamestown FOIL request is sent; the code-enforcement email to dod@jamestownny.gov is not.
