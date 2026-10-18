# Old repo (Apixa25/XHere-Web): security fixes

*Investigated 2026-10-17, ~11:20 PM ET. No leaked credential was used to log in to anything. XHere-Web was not modified, deleted, or rewritten.*

> Secret values are **not** reproduced here. Keys are identified only by their first 8 and last 4 characters so you can match them in the consoles.

## What leaked, and where

| # | Secret | Where (public repo) | Belongs to | Status / risk |
|---|---|---|---|---|
| 1 | **Production Postgres URL with user + password** (`xhere_database_user` @ host `dpg-d1c9eg8dl3ps73fe6adg-a`, db `xhere_database`) | `backend/config/database.js` (current `main`), added in commit `7b168dcf` on 2025-06-22 ("temp: Hardcode database URL as final workaround") | **Render** Postgres (`dpg-…` is a Render Postgres ID; the URL uses the *internal* hostname, so it was only reachable from inside Render, but an external hostname exists for the same DB). Created by the `render.yaml` Blueprint as `xhere-database` (Postgres 15) on 2025-06-22 | Render **Free** Postgres expires 30 days after creation and is deleted 14 days later, so if it stayed free it's **almost certainly gone already**. Only a paid upgrade would have kept it. Can't verify from outside (Render DNS is wildcard). **Check the Render dashboard.** |
| 2 | **Google Maps API key** `AIzaSyAX…iVQ0` | `frontend/src/App.js:156`, `frontend/src/AppMobile.js:66`, `test-google-maps.html:29` (current `main`) | Google Cloud. The same repo's OAuth client ID has prefix `52660706525-`, so the Google Cloud **project number is very likely `52660706525`** | Live until deleted or restricted. Anyone can bill Maps usage to the account |
| 3 | Older Google Maps keys `AIzaSyB4…0o_g`, `AIzaSyBS…Z6CY` | git history only (`frontend/.env`, `backend/.env`, prebuilt `frontend/build` files, Dec 2024) | Same Google Cloud project (likely) | Delete them too if they still exist |
| 4 | **Stripe test-mode** secret + publishable keys (`sk_test_…`, `pk_test_…`) | git history: `backend/.env` (committed 2024-12-18 → removed 2025-06-27) | Steven's Stripe account (test mode) | Low risk (no real money in test mode), but roll the test secret key |
| 5 | JWT signing secret (128 chars) | git history: `backend/.env`, `frontend/.env` | The old XHere backend | Only matters if the old backend runs again. Never reuse it |
| 6 | Local dev DB passwords | `config/config.json`, `backend/config/config.json`, `backend/config/config.js` | Local Postgres on Steven's PC | Low. Change the local `postgres` password if it's reused anywhere else |
| — | Google OAuth client ID `52660706525-…` | `.env` history | Google Cloud project above | Public by design, not a secret. The client *secret* in history was only a placeholder |
| — | ~270 user uploads incl. ~18 profile photos; `node_modules` | `backend/uploads/` | — | Privacy issue, fixed by making the repo private |

**Also found:** the "suspended hosting" is **Render**, not GoDaddy. `xhere.world` uses GoDaddy DNS (`ns45/ns46.domaincontrol.com`): the apex `A 216.24.57.1` is Render's IP, and `www` and `api` CNAME to `xhere-web-front-end.onrender.com`. Both `xhere-web-front-end.onrender.com` and `xhere-web.onrender.com` show **"Service Suspended."**

## What I could and couldn't do with tools
* ✅ Located every leaked secret (current tree + full git history) and identified its owner (above).
* ❌ **Make XHere-Web private or archived**: no connected tool can change repo visibility or archive (the GitHub connector has no repo-settings tool, and its secret-scanning API returns 403). Steven needs to do this (step C).
* ❌ Render / Google Cloud / Stripe: no connectors. Steven or a signed-in browser session needs to do these (steps A, B, D).

## Steps for Steven (≈10 minutes total, do A and B first)

### A. Render: kill the leaked database (and the suspended services)
1. Go to **https://dashboard.render.com** and sign in (probably "GitHub" as Apixa25).
2. On the dashboard's service list, look for a **PostgreSQL** item named **`xhere-database`** (ID `dpg-d1c9eg8dl3ps73fe6adg-a`).
   * **Not listed?** It's already deleted, and the leaked password is dead. Skip to step 4.
   * **Listed and you don't need the data?** Open it, go to **Settings**, scroll to the bottom, click **Delete Database**, and type the name to confirm.
   * **Listed and you want the data?** On its **Info** page, copy the External Database URL and run `pg_dump` from your PC first, then delete it as above. (Or, to keep the DB, use the credentials section on the Info page to create a new credential and remove the old `xhere_database_user`.)
3. Make sure nothing else is listed as using it.
4. Also delete the suspended services you no longer need: **`xhere-web`** (web service) and **`xhere-web-front-end`** (static site). Open each one, go to **Settings**, and click **Delete Web Service / Delete Static Site** at the bottom.
5. Open **Billing** (workspace settings) and check for unpaid invoices or a card on file. Suspension often means a failed payment. Settle or remove it as you prefer.

### B. Google Cloud: delete (or lock down) the Maps keys
1. Go to **https://console.cloud.google.com/cloud-resource-manager** and find the project whose **Project number is `52660706525`** (likely named something like "XHere" or "location-app"). Select it.
2. Open **https://console.cloud.google.com/apis/credentials** (APIs & Services → Credentials) with that project selected.
3. Under **API Keys**, click **Show key** on each key and match `AIzaSyAX…iVQ0`, `AIzaSyB4…0o_g`, `AIzaSyBS…Z6CY`.
4. The new XHere uses free MapLibre maps, not Google Maps, so **delete them**: click the 🗑 trash icon on each key, then **Delete**.
   *If you want to keep one:* click the key name. Under **Application restrictions** choose *Websites* and add only your real domains. Under **API restrictions** choose *Restrict key* → *Maps JavaScript API*. Click **Save**. Since the key is public in git history, restricting it is weaker than deleting it.
5. Go to **Billing → Reports** (https://console.cloud.google.com/billing) and look for unexpected Maps charges since Dec 2024. Optionally set **Budgets & alerts** at $1.
6. Leave the **OAuth 2.0 Client ID** (`52660706525-…`) alone. It isn't secret, and it can be reused or replaced later for "Sign in with Google."

### C. GitHub: make XHere-Web private (and optionally archive it)
1. Go to **https://github.com/Apixa25/XHere-Web/settings**.
2. Scroll to **Danger Zone** → **Change repository visibility** → **Change visibility** → **Make private** → confirm. (0 forks exist, so no public copies on GitHub.)
3. Optional, same Danger Zone: **Archive this repository** (read-only; it can be unarchived later).
4. Note: `github.com/Apixa25/XHere` redirects to this repo. That's harmless.

### D. Stripe: roll the test secret key (2 minutes, low priority)
1. Go to **https://dashboard.stripe.com/test/apikeys** (make sure **Test mode** is on).
2. On **Secret key**, click **⋯ → Roll key**, set expiry "now", and confirm. The publishable key doesn't need rolling.

### E. Local
* If your local Postgres `postgres` user's password is the same one in `backend/config/*.json` and you use it anywhere else, change it.

## Do we need to purge git history?
**No, not once the steps above are done.** The repo has been public since Dec 2024, so the secrets must be assumed copied already, and rewriting history can't un-leak them. **Rotating or deleting** the credentials (A, B, D) is what fixes the risk. Making the repo private (C) handles the user photos and keeps it from being scraped further. A history rewrite (`git filter-repo`/BFG + force-push) would only be worth it if the repo goes **public again** later. Even then, delete or rotate first and ask GitHub Support to purge cached views.

## Checklist
- [ ] A. Render DB `xhere-database` gone (or credential rotated); suspended services deleted; billing checked
- [ ] B. Maps keys `AIzaSyAX…iVQ0`, `AIzaSyB4…0o_g`, `AIzaSyBS…Z6CY` deleted; billing checked
- [ ] C. XHere-Web private (optionally archived)
- [ ] D. Stripe test secret key rolled
