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
