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
