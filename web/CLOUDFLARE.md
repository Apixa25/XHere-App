# Cloudflare Pages — do not deploy yet

XHere does not have a Cloudflare account. **Do not create one, do not run `wrangler login`, and do not run `wrangler pages deploy`.** Steven Sills II has to approve the account and the DNS change first (PLAN §1.5 and §8).

`wrangler.toml` in this directory only names the Pages project and the build output (`dist`). It has no `account_id` and no secrets.

## When Steven says yes

1. Create a free Cloudflare account. The free Pages tier allows commercial use. Do not pick a paid plan.
2. Create a Pages project from `Apixa25/XHere-App`.
   - Root directory: `web`
   - Build command: `npm ci && npm run build`
   - Output directory: `dist`
   - The `functions/` directory next to `dist` is picked up automatically. It renders `/d/{short_id}` and `/maps/{short_id}.svg` for ids that were not baked in at build time.
3. Environment variables, only after a free Supabase project also exists (PLAN §8):
   - `PUBLIC_SUPABASE_URL`
   - `PUBLIC_SUPABASE_ANON_KEY`
   - Do **not** add `SUPABASE_SERVICE_ROLE_KEY`. The website calls `drop_teaser` with the anon key, and that RPC does not reveal the hidden text.
4. Leave the variables empty until then. With the placeholders from `.env.example`, the site serves the Jamestown fixture drops and nothing else.
5. DNS stays where it is until Steven approves the cutover. Today `xhere.world` still points at the old suspended Render services (PLAN §1.5). GoDaddy is registration and DNS only, through December 2026. Do not renew GoDaddy hosting. Do not change nameservers in this task.
6. After DNS points at Pages, replace the placeholder in `/.well-known/assetlinks.json` with the **Play App Signing** SHA-256 certificate fingerprint (Play Console, not the upload key). Until that string is a real fingerprint, Android will not verify the App Link. The intent filter is already in the app.
7. `www` is not configured. If Steven wants it, redirect `www` to the apex. Do not add a second host until the asset-links file is served there too.

Build needs a network so it can read OpenFreeMap vector tiles for the fixture map images. There is no tile key.

## What this does not do

- No production hostname was attached.
- No Pages project was created.
- No secret is required to build or to run Lighthouse against `dist`.
