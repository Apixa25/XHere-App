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

## 2026-10-18 (Sun), ~3:10 AM ET: M5 quick drop

Branch `m5/quick-drop` → [PR #10](https://github.com/Apixa25/XHere-App/pull/10). Composer for PLAN §4.2–§4.4 and the §7 M5 row: a photo or a line of text, three category chips, schema fields, then `create_drop`. A photo of something obvious posts on the shutter and the chip.

- The map has a drop button. The Drop tab is the same screen, and it still says a drop takes about three taps.
- CameraX takes the photo. The photo picker and a text field are the other ways in. ML Kit image labeling runs on the device. Tests bind a fake shutter and a fake labeler, so the suite does not need a camera or a model download. Robolectric strips the CameraX/ML Kit startup provider.
- Categories are ranked with a 384-dimension feature hash (`hash-v1`) plus nearby counts, recent picks, and a small time-of-day prior. The same 32 phrases are scored in Kotlin, in the Edge Function, and in pgTAP. All three put the expected category in the top 3 on **32/32 (100%)**, and in the top 1 on 32/32. The bar in PLAN is 70%. pgTAP prints `fixture top-3 32/32`.
- `suggest-category` calls `suggest_categories` (pgvector) for hash-v1. gte-small is used only when `XHERE_EMBEDDING_MODEL=gte-small` and the platform session exists, and only after `categories.embedding` is rebuilt in that same space. CI does not download weights. If the function cannot be reached, the app ranks on the phone.
- The form is the category `attributes_schema`. Sensitive categories (`incident_report`, `safety_alert`) always show the notice and a checkbox. `occurred_at` is filled with now so a hazard can post on the chip. An event still asks for start and end. Only `incident_report` blurs the pin (100 m), and the screen says so when the precision is above 0.
- Photos go to the private `drop-media` bucket through `strip-exif`, which removes JPEG Exif/XMP and PNG text chunks and stores the file under the user's id. The in-memory path strips the same bytes and never calls Storage. No service-role key is in the app or the function.
- `create_drop` errors are sentences: rate limit, incident reports needing a Regular account, and "describe what happened, not who" for the person-descriptor filter. A matching title within 50 m shows a duplicate hint. `category_suggestion_log` records what was offered and what was picked. Hints do not bump `categories.version`.
- New SQL is `20261018000700_suggestions.sql`, covered by `07_suggestions.test.sql` (19 assertions). Deno tests run in the supabase workflow (Deno 2.1.4, checksum-pinned) without a Supabase project.
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Branch protection:** keep **android / app**, **android / shared**, **ci / gitleaks**, and **supabase / pgtap** on `main`, and add **web / build** from the M8 site. The workflow also runs **supabase / functions** (Deno). Add that check to the ruleset if you want it required; the four above already cover the M5 app, the shared ranker, gitleaks, and pgTAP.
- **A free Supabase project** before a photo upload, a live suggestion, or a share page hits a server (PLAN §8). Until then the app ranks and stores photos in memory, and the site uses Jamestown fixtures. Put the project URL and the anon key in `local.properties` and on Pages. Do not put the service-role key in the app or the site. Apply the migrations (including `20261018000700_suggestions.sql`) and deploy `suggest-category` and `strip-exif`.
- **Leave embeddings on hash-v1** until you deliberately set `XHERE_EMBEDDING_MODEL=gte-small` and re-embed every category with that same model. Mixing the two spaces makes the nearest-category search noise. gte-small needs no paid key on Supabase's hosted runtime, and CI never downloads it.
- **Do not deploy the website** until you approve a free Cloudflare account and the DNS change (`web/CLOUDFLARE.md`). Replace the assetlinks placeholder with the Play App Signing SHA-256, and give `hello@xhere.world` a mailbox (or pick another address). Read the draft privacy, terms, and guidelines before treating them as in effect.
- Background location stays off until M13.
- Unchanged from M0–M4: license, secret scanning / push protection, a free Google Cloud OAuth web client id before Google sign-in works, first admin SQL once a project exists, crime-rule review, a Mac before iOS (M15), PLAN §8 and §9. The demo city is Jamestown (PR #4). The Jamestown FOIL request is sent; the code-enforcement email to dod@jamestownny.gov is not.

## 2026-10-18 (Sun), ~4:00 AM ET: M6 drop detail, proximity reveal, "Still true?"

Branch `m6/drop-detail` → [PR #12](https://github.com/Apixa25/XHere-App/pull/12). Detail screen for PLAN §4 (reveal radius and the confirmation bar) and the §7 M6 row.

- A map teaser (M4) and a Near me row open the drop. The screen stays locked until the phone is inside that category's reveal radius. The locked line is "Walk 120 m closer to reveal" when the gap is 120 m, using `Geo.revealGapMeters` and the category default (`eatery_tip` 500 m, `historical_fact` 150 m, `hidden_gem` / `tip` 50 m, and the rest of the §4.2 table).
- Distance updates only while the app is in the foreground. Leaving the foreground cancels the `LocationManager` updates. Nothing is written down. Background location is still not in the manifest.
- Inside `max(50 m, reveal_radius_m)`, Still true / Not anymore calls `confirm_drop`. The Confirmed badge appears when the count reaches that category's `confirmations_to_confirm`. Freshness is "Not confirmed yet" or how long ago the last confirmation was.
- Share sends `https://xhere.world/d/{short_id}`, the same link as the M8 page. `MainActivity` is `singleTask` and routes that App Link to the detail screen, including a cold start that finishes sign-in first.
- Two gaps in the M1 RPCs: `to_drop_card` and `drop_teaser` were still returning tags outside the reveal radius, and `confirm_drop` counted an update of your own verdict toward the hourly cap. `20261018000800_reveal_payload.sql` closes both. `08_reveal_payload.test.sql` (19 assertions) and `RevealPayloadTest` cover a hidden body (and tags, attributes, media) outside the radius, a server-side reject when the confirm is out of range, and the per-category confirm / deny / expiry transitions. The in-memory fake matches.
- Compose tests cover the locked teaser, the 120 m line, the revealed body, confirm and deny, the badge, foreground-only updates, Near me, and the https link.
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Apply `20261018000800_reveal_payload.sql`** on the Supabase project when one exists, with the earlier migrations. Until then Preview Jamestown still hides the body in the app.
- **Branch protection:** keep **android / app**, **android / shared**, **ci / gitleaks**, **supabase / pgtap**, and **supabase / functions** on `main`. Add **web / build** if it is not on the ruleset yet. This milestone does not change the site.
- Unchanged from M5: do not deploy the website until you approve Cloudflare and DNS; replace the assetlinks placeholder with the Play App Signing SHA-256 before App Links verify; a mailbox for `hello@xhere.world`; read the draft privacy, terms, and guidelines. A free Supabase project and a free Google OAuth web client id are still required before live sign-in. Background location stays off until M13. The Jamestown FOIL request is sent; the code-enforcement email to dod@jamestownny.gov is not.

## 2026-10-18 (Sun), ~5:40 AM ET: M7 safety & compliance

Branch `m7/safety` → [PR #13](https://github.com/Apixa25/XHere-App/pull/13). Report, block, and hide, the Safety posting flow, the moderator queue, the audit log, and the store checklists for PLAN §4.3 and the §7 M7 row. Each store rule is mapped to its code and tests in [COMPLIANCE.md](COMPLIANCE.md) (Google Play UGC, child safety, and account deletion; Apple 1.2, 1.7, and 5.1.1(v)).

- **Drop screen:** Report drop (reason, details, optional "also hide for me"), Hide for me with Undo, Report author, and Block author. Blocking hides drops both ways. Settings › Blocked and hidden undoes a block or a hide. The report form sits on the screen instead of in a dialog, because a text field in a dialog window never goes idle under Robolectric.
- **Safety posts** (`safety_alert`, `incident_report`): the composer shows a reminder, the disclaimer, "held for review" for New accounts, and the 100 m blur (incident reports only). The people, contact, and new street-address filters run as you type and turn Post off. Safety drops and the composer show the Jamestown Police anonymous tip line and 911.
- **Apple 1.7:** incident reports are offered only where a police tip line is configured. That was only a client check, so `20261018001100_tip_lines.sql` adds `private.tip_lines` (seeded for Chautauqua County) and a trigger that refuses an incident report anywhere else. The in-memory fake does the same.
- **Terms before UGC (Google Play):** nothing asked people to agree to the terms before posting. Sign-in now states that continuing means agreeing to the Terms and Community guidelines (zero tolerance for objectionable content or abusive users), with links to both, and the composer repeats it under Post.
- **Moderator queue** (Settings, `moderator`/`admin` only): approve, remove, restore, dismiss, action, and 7-day suspend or lift. `moderation_queue` returns held, reported, and disputed items, and profile reports. Every moderator action writes `moderation_actions`, which is now append-only (no update or delete, even for the owner). Tier and role changes are logged too.
- **Your data:** in-app delete was already there (verified again). Settings › Download my data calls the new `export_my_data` (`xhere-export-v1` JSON, only your own rows) and hands the file to the share sheet through a private `FileProvider`. The website's guidelines (new child safety section), delete-account, privacy, and terms pages match.
- New SQL: `20261018000900_audit_actions.sql`, `20261018001000_safety_compliance.sql`, `20261018001100_tip_lines.sql`. pgTAP is 368 assertions in 11 files (`09_safety_compliance` 68: Safety pre-moderation for New users, the auto-hide threshold, blocks both ways, moderator-only access, audit rows; `10_tip_lines` 7). Shared: 82 tests. App: 53 Robolectric tests, including the report, block, hide, export, guidelines, and moderator flows.
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

- **Apply the three M7 migrations** (`…0900`, `…1000`, `…1100`) on the Supabase project when one exists, after the earlier ones.
- **Check the Moderation queue about once a day.** It appears in Settings once your account has the admin or moderator role (the first-admin SQL already on your list, see `supabase/README.md`). The terms now say reports are reviewed, aiming for within 24 hours, and auto-hide only covers the wait.
- **Play Console forms:** use [COMPLIANCE.md](COMPLIANCE.md) for the UGC questions, the **Child safety standards** declaration (standards URL `https://xhere.world/guidelines#child-safety`, and you as the point of contact), and the Data safety form (deletion URL `https://xhere.world/delete-account`).
- **Read the new wording** before treating it as in effect: the sign-in agreement line, the child safety section, the 24-hour review target in the terms, and the export and audit-log text on the delete-account and privacy pages.
- **A new region gets incident reports only after you add its police tip line**, in `private.tip_lines` and in the shared `SafetyDirectory`, until M16b moves tip lines into the region config.

## 2026-10-18 (Sun), ~6:10 AM ET: M9 Play closed-test prep

Branch `m9/release-prep` → [PR #14](https://github.com/Apixa25/XHere-App/pull/14). Release prep for PLAN §7 M9 and the Play cost row in PLAN §8. The closed test is ready to upload once Steven does the steps in [PLAY-RELEASE.md](PLAY-RELEASE.md). No Play account was created and the $25 was not spent.

- **Signing.** Gradle reads `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Those names are the GitHub Actions secrets. The keystore is decoded into `android/keystore/release.jks` (gitignored) and is never committed. If any secret is missing, the release AAB is debug-signed so the job still passes. That artifact is not the one to upload.
- **Version.** `0.9.0` / versionCode `900` (`major * 10000 + minor * 100 + patch`). The next upload has to be a higher code.
- **R8.** Minify and resource shrinking are on for release. `proguard-rules.pro` keeps kotlinx.serialization, Ktor, supabase-kt, MapLibre, ML Kit, CameraX, Koin, and Sentry. `ReleaseStartupTest` opens the real application class. The **android / release** job installs the minified APK on an API 34 emulator and checks the process stays up.
- **CI.** `.github/workflows/android.yml` job `release` (check name **android / release**) builds the AAB and uploads `xhere-release-aab` plus the R8 mapping. It does not need the signing secrets.
- **Crashes.** Sentry is wired and does not start unless `SENTRY_DSN` is a real URL. Auto-init is off. No Sentry account was created. The free tier is the one PLAN §8 already lists.
- **Listing.** `android/play/` has the title, the short description, the full description, a 1024×500 feature graphic, four 1080×1920 phone screenshots, and a 512 icon. The pictures are rendered from Compose previews of the Jamestown drops.
- **Paperwork.** `docs/PLAY-RELEASE.md` has the Data safety answers (preview build vs a later Supabase rebuild), the foreground-location declaration, and the ordered checklist: Cloudflare so `https://xhere.world/privacy` resolves, the $25 account, the upload key, 12 testers for 14 days, and the Play App Signing SHA-256 into `web/src/lib/site.mjs` (that is what serves `/.well-known/assetlinks.json`).
- No money spent, no accounts created, no secrets committed. `Apixa25/XHere-Web` was not touched.

### Steven still needs to

The deduplicated list is the MVP status section below. The Play clicks, in order, are in [PLAY-RELEASE.md](PLAY-RELEASE.md).

## MVP status (M0–M9)

The closed-test app is in the repo. M0 through M9 are merged or in this branch: the repo and CI, the database and the Jamestown seed, the shared Kotlin core, the Android app (sign-in, map, quick drop, reveal, safety), the xhere.world site, and this Play release prep. What is left is yours. Each item is listed once.

1. **Google Play, $25 once.** Approve the developer account (PLAN §8). Create the app `world.xhere.app`, paste the listing from `android/play/`, paste the Data safety and location answers from `docs/PLAY-RELEASE.md`, upload the **signed** AAB from the **android / release** artifact, and run a closed test with **12 testers opted in for 14 days** before you apply for production. The closed test itself can start as soon as they opt in. Do not upload the debug-signed AAB that CI builds while the keystore secrets are empty.
2. **Upload key.** Create a keystore on your machine (command in `docs/PLAY-RELEASE.md`). Add GitHub Actions secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Re-run **android / release** and download `xhere-release-aab`.
3. **Play App Signing SHA-256.** After the first upload, copy the **App signing key** SHA-256 (not the upload key) into `fingerprintPlaceholder` in `web/src/lib/site.mjs`. That is the value `/.well-known/assetlinks.json` serves. The string in the repo is not a certificate. App Links stay unverified until you replace it and redeploy the site.
4. **The website.** Approve a free Cloudflare account and the DNS change (`web/CLOUDFLARE.md`) before anyone deploys. Play loads the privacy policy from **https://xhere.world/privacy**, which does not serve this site while `xhere.world` still points at the old Render services. Do not reactivate Render. GoDaddy registration runs through December 2026.
5. **A mailbox for hello@xhere.world**, or a different address, before the contact link can receive mail. The address on the site is a placeholder.
6. **Approve the draft privacy, terms, and guidelines**, including the sign-in agreement, the child-safety section, the 24-hour review line, and the export and audit-log text. They are not in effect until you say so.
7. **Hosted Supabase is up** (ref `jpeuldzegtbqfezijhuy`, us-east-1, PLAN §1.4 and §8, free tier). Put the project URL and the publishable key (it starts with `sb_publishable_`) in `local.properties` as `SUPABASE_URL` and `SUPABASE_ANON_KEY`, and on Pages as `PUBLIC_SUPABASE_URL` and `PUBLIC_SUPABASE_ANON_KEY`. Do not commit the key. Do not put the service-role key in the app or the site. The twelve migrations through `20261018001100_tip_lines.sql`, the Jamestown demo seed, and both Edge Functions are already applied. Apply the four migrations named in the hosted follow-up entry below (`…01200` through `…01500`). Before `supabase db push`, run the `migration repair` commands in `supabase/README.md`. Leave embeddings on hash-v1. Rebuild the AAB after the key is baked in and update Data safety to the "with Supabase" column in `docs/PLAY-RELEASE.md`.
8. **A free Google Cloud OAuth web client id** before Google sign-in works. The same id goes in `GOOGLE_WEB_CLIENT_ID` and in the Supabase Google provider.
9. **First admin.** The project exists. After you sign up, run the SQL in `supabase/README.md`. Then check Settings → Moderation queue about once a day. The terms say reports are reviewed, aiming for within 24 hours. Auto-hide only covers the wait.
10. **Incident reports in a new region** need that region's police tip line in `private.tip_lines` and in `SafetyDirectory`, until M16b moves tip lines into the region config. Jamestown is already seeded.
11. **Branch protection on `main`.** Require **ci / gitleaks**, **android / shared**, **android / app**, **android / release**, **supabase / pgtap**, **supabase / functions**, and **web / build**.
12. **Secret scanning and push protection** (free on this repo): Settings → Code security. Do not buy GitHub Advanced Security. Gitleaks in CI is the scanner if those toggles are missing.
13. **Pick a license.** `LICENSE` stays "All rights reserved" until you do.
14. **Sentry is optional.** Crash reporting is off until `SENTRY_DSN` is set. A free Sentry account needs your OK (PLAN §8). If you turn it on, say so on the Data safety form.
15. **A Mac and the Apple Developer Program ($99/year)** only when iOS starts (M15). Not needed for the Play test.
16. **Old repo (PLAN §9, `docs/SECURITY-FIXES.md`).** Rotate or delete the Render Postgres credentials that were committed, restrict the old Google Maps keys, and consider making `XHere-Web` private. Do not reactivate Render. This work does not touch that repo.
17. **Jamestown.** The demo city is Jamestown (PR #4). The FOIL request was sent. The code-enforcement email to dod@jamestownny.gov has not been sent.
18. **Still open, and not blocking the closed test:** whether a drop can hide its author from the public, and whether you want a business tier later (PLAN §10). You still need about 12 people for the tester count in item 1.
19. **Background location stays off** until M13. Do not add it to the manifest for this test.

## 2026-10-18 (Sun), ~7:45 AM ET: Hosted Supabase follow-ups

Branch `chore/hosted-supabase-followups` → [PR #17](https://github.com/Apixa25/XHere-App/pull/17). The free project **XHere** (`jpeuldzegtbqfezijhuy`, us-east-1) was created earlier this morning from `main` at `0d2796a` (PLAN §1.4, §8). This entry is the repo side of that. No key values are in git. `Apixa25/XHere-Web` was not touched. No paid service was used.

- **`suggest-category` 401.** A signed-out caller (no user JWT, or PostgREST 401/403) used to throw `rpc_suggest_categories_401` and the function returned 500. It now returns **401** `{"error":"not_authenticated"}`, the same JSON as `strip-exif`. Deno tests cover the hash path, the gte-small path, and a non-auth RPC failure that still throws.
- **Migrations to apply on the hosted project** (not applied from here; the dashboard/MCP apply is a separate step):
  - `20261018001200_rls_auth_uid_initplan.sql` — lint 0003, 18 policies, `(select auth.uid())`, same allow/deny.
  - `20261018001300_reference_admin_policies.sql` — lint 0006, `tier_limits` and `content_filters` admin policies are insert/update/delete only.
  - `20261018001400_foreign_key_indexes.sql` — lint 0001, six foreign-key indexes.
  - `20261018001500_tip_lines_rls.sql` — RLS on `private.tip_lines`, no new client policy. `create_drop` still reads the table.
- pgTAP `11_advisor_followups.test.sql` (25 assertions) checks those four and that a non-admin still cannot write the reference rows, an admin still can, a user can still edit only their own profile, a signed-out visitor can still read tier limits and content filters, and an incident report in Jamestown still goes through.
- **Docs.** `supabase/README.md` has the project ref, the `supabase migration repair` commands (hosted versions are apply timestamps, so `db push` would otherwise re-run the first twelve), and how to remove the demo seed that was applied to the pilot: `delete from auth.users where raw_user_meta_data->>'demo' = 'true';`. `.env.example`, the Android README, and the website Cloudflare note say the publishable `sb_publishable_…` key goes in `SUPABASE_ANON_KEY` / `PUBLIC_SUPABASE_ANON_KEY`. The placeholder strings stay in git so local builds keep using fixtures.

### Steven still needs to

- **Apply the four migrations above** on `jpeuldzegtbqfezijhuy`, in filename order, after the repair commands if you use the CLI.
- **Paste the publishable key** into gitignored `local.properties` and, when Pages exists, into `PUBLIC_SUPABASE_ANON_KEY`. Do not commit it.
- **Dashboard, not this PR:** Auth site URL `https://xhere.world` and the Android redirect; the sign-in email must include `{{ .Token }}`; Google provider still needs the OAuth web client (PLAN §8); first admin SQL after you sign up. The built-in SMTP is only for team addresses. Custom SMTP (Resend free tier) needs your OK. The project pauses after about 7 days without activity.
- **Demo data** is on the pilot on purpose. Delete it with the SQL above when you want a clean map. It ages itself down to the evergreen drops after about three days.
- Unchanged: do not deploy the website until you approve Cloudflare and DNS. Do not touch `Apixa25/XHere-Web`. Background location stays off until M13.

## 2026-10-18 (Sun), ~9:10 AM ET: Hide Google sign-in on tester builds

- The Google button is hidden until GOOGLE_WEB_CLIENT_ID is set, for tester builds.

## 2026-10-18 (Sun), ~4:12 PM ET: Open-data base layer (map is never empty)

Branch `cursor/open-data-base-layer-2211`. This is recommendation #1 from `docs/research/LOCATION-APPS-RESEARCH.md` and the first slice of M10 (PLAN §3, §4.1, §5; `docs/OPEN-DATA-PLAYBOOK.md` §8). XHere stays global. Jamestown is only the pilot region file.

- Imported places are `drops` with `source = open_data` and no author, so they do not count as a person's drops, points, tier, or export. Confirmations are rejected. The detail screen shows **From public records**, the attribution, and **View source**. Still-true is hidden on those pins.
- Free sources only. Jamestown snapshot after dedupe: Wikipedia 28 (CC BY-SA 4.0), Wikidata 64 (CC0), National Register 11 (public domain), OpenStreetMap historic/heritage/tourism 14 (ODbL). 117 places. HMdb and Atlas Obscura stay link-only.
- Migration `20261018001600_open_data.sql` is not applied on the hosted project from here. After impact feedback landed on main, `20261018210000_open_data_card.sql` puts provenance back on `to_drop_card` (public records stay revealed, and a null author is not "mine"). Apply both with `supabase db push` after the repair commands already in `supabase/README.md`, then `psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/open-data/jamestown-ny.sql`. `db push` does not load the seed. Re-running the SQL updates the same external ids.

## 2026-10-18 (Sun): Here Card

Branch `cursor/here-card-827d`. This is recommendation #3 from `docs/research/LOCATION-APPS-RESEARCH.md` §4.1 and §4.4 (template, no paid model) and PLAN §2 (free geocoding through a cache), §3 (reveal radius), and §4.1 (user drops and `open_data` on the same card). `docs/OPEN-DATA-PLAYBOOK.md` provenance rules stay: public records keep their attribution and a View source link, and a tap is not a new geocode of an offset pin.

- Tap or long-press any spot. One sheet lists nearby drops grouped by category, with distance, seen/confirmed counts, and — for a public record — attribution and View source. User-drop bodies stay hidden until the phone's own location is inside the reveal radius. The tap is not the reveal point, and opening the card does not count a view.
- The place name is OpenStreetMap via Nominatim: a 0.001° cache in `place_labels`, a global 1.1 second slot, and © OpenStreetMap contributors on the sheet. No paid geocoder. No user id on the cache. The same path works in any region.
- **Drop something here** opens the composer at that spot.
- Migration to apply on the hosted project (not applied from here): `supabase/migrations/20261019041827_here_card.sql`. Use `supabase db push` after the repair commands in `supabase/README.md`. Do not paste it into the dashboard. It does not change Auth.

## 2026-10-18 (Sun), ~7:20 PM ET: Walks and a starter quest

Branch `cursor/walks-starter-quest-57ee`. This is recommendation #4 from `docs/research/LOCATION-APPS-RESEARCH.md` (trails and quests, pulled forward from PLAN §3 quests / M14). XHere stays global. Jamestown is only the pilot walk.

- A walk is an ordered route of stops that name places already on the map (`source_slug` + `external_id`), with a title, description, straight-line distance, and time at 75 m/min. Region files hold the route. The map draws that line, shows the next stop and the distance, checks a stop off inside the same reveal radius as "there", and celebrates once when every stop is done.
- The starter quest is global (`region_id` null): visit 3 places, confirm 1 drop, make your first drop. Progress is derived. Dismiss hides the card and leaves the counts.
- Check-in does not store the point. Own drops and hidden drops are not visits. A stop does not check off until the walk is started.
- The pilot route is Downtown Jamestown history walk, eight public records already in the open-data snapshot, west to east then north: Erie Railroad Station, National Comedy Center, The Wellman Building, Lucille Ball Desi Arnaz Museum & Center for Comedy, Reg Lenna Center for the Arts, Jamestown Downtown Historic District, Robert H. Jackson Center, First Presbyterian Church. Copy on the stops is the snapshot sentence (a National Register building or district, or a Wikipedia article). Nothing was added.
- Not applied on hosted Supabase from here. After `supabase db push` (which includes `20261018235000_walks_and_quests.sql` and `20261019041827_here_card.sql`), load `supabase/seed/open-data/jamestown-ny.sql` if it is not there yet, then `supabase/seed/walks/jamestown-ny.sql`. `db push` does not load either seed.

## 2026-10-19 (Mon): Here Card RPC hardening

Branch `cursor/here-card-rpc-hardening-c969`. Advisor 0029 flags `claim_nominatim_slot` and `remember_place_label`: they are security-definer RPCs any signed-in user can call, so one account could hold the project Nominatim slot or write arbitrary rows into `place_labels`. PLAN §2 keeps geocoding free. The phone still calls Nominatim after a true claim. An Edge Function would move that traffic onto the project's shared egress and change the client, so the limits stay in the RPCs.

- `claim_nominatim_slot` is still one lookup every 1.1 seconds for the whole project. The same user cannot take another slot for 10 seconds. A busy project slot does not start that pause. The pause row is a user id and a timestamp, not a coordinate.
- `remember_place_label` stores only the 0.001° cell of the given coordinates. The name (1..160) and address (1..300) must contain a letter or number and only place punctuation. Markup, control characters, and `http` links are refused. The first write still wins for 90 days. Attribution is still stamped in the database. A mutating write is limited to one every 10 seconds and 30 an hour per user. Repeating a fresh cell does not count.
- **Signed-in only.** `XHereApp` shows the map only at `Gate.Ready`. `Gate.SignedOut` is the auth screen. The site (PLAN §1.5) is a landing page and share teasers, not a map. `here_card` is not granted to `anon`. A signed-out variant that returned only public records is not needed, because nothing in the app opens the card before sign-in.
- The client is unchanged: a false slot skips the lookup, and a refused cache write still shows the name that was just fetched. Migration to apply on the hosted project (not applied from here): `supabase/migrations/20261019180000_here_card_rpc_hardening.sql`, after `20261019041827_here_card.sql`. Use `supabase db push`. Do not paste it into the dashboard. It does not change Auth.
