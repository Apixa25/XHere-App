# XHere public read API

Other apps and assistants can ask XHere **what's here?** This is the first half of recommendation #8 in [`docs/research/LOCATION-APPS-RESEARCH.md`](../research/LOCATION-APPS-RESEARCH.md): a versioned, read-only HTTP API. It is global. Nothing in the routes is tied to one city.

The privacy rule is [PLAN.md](../PLAN.md) §5: the exact stored point is not what other people receive, and a position sent to a read is not kept. The provenance rule is [OPEN-DATA-PLAYBOOK.md](../OPEN-DATA-PLAYBOOK.md): every public record carries its source, license, and attribution. ODbL and CC BY-SA are share-alike. If you adapt those items, keep that license and the attribution.

The machine-readable contract is [`openapi.yaml`](openapi.yaml). A short note for agents is [`llms.txt`](llms.txt).

## What you can ask

The function name on Supabase is `public-api`. Paths are under `/functions/v1/public-api`.

| Method | Path | Question |
|---|---|---|
| `GET` | `/v1/here?lat&lon&radius` | What is public within `radius` meters of this point? |
| `GET` | `/v1/drops/{id}` | The share-page teaser for one drop. `id` is the 12-character short id or the uuid. |
| `GET` | `/v1/things/{id}` | The public story of one object. `id` is the 12-hex voice id or the uuid. No owner, and no list of who heard it. |
| `GET` | `/v1/categories` | Active groups and categories (labels, sensitivity, default reveal, blur). |
| `GET` | `/v1/walks?lat&lon` | Walks whose box contains this point, and the public-record stops on them. |
| `GET` | `/v1` | The list above. |

`lon` is the longitude. `lng` is accepted when `lon` is omitted. `radius` is whole meters. The default is 300. The maximum is 1000. A missing or unknown drop is `404` with `not_found`, including a row that is hidden, pending, expired, removed, demo, or reduced-trust. Those cases are not distinguished.

## What a response contains

Every JSON body includes `terms`. `terms.note` is the share-alike obligation. `terms.privacy` restates PLAN §5 for this API. `terms.share_alike` names ODbL 1.0 and CC BY-SA 4.0.

Every place, walk, stop, category, and live-region object has `attribution`, `license`, and `share_alike`.

- **Public records** (`kind: public_record`) are `source = open_data`. `summary` is the one-line text already stored for the map. `source_url` is the deep link. The license string is the one on the imported row (`CC BY-SA 4.0`, `ODbL 1.0`, `CC0 1.0`, public domain, and so on).
- **User drops** (`kind: user_teaser`) match the anonymous share page. `summary` is null. There is no body, attributes, media, or tags. `license` is `xhere-teaser`. `author_handle` is set only when that share page would print the handle: the category is not sensitive, and the drop is not anonymous.
- Coordinates are the display point. A category that blurs location (`location_precision_m` above 0), and a sensitive category, set `approximate` to true. The stored exact point is not selected.
- `live` on `/v1/here` is the configured region box from `region_live_at` (a weather-provider id such as `nws` or `none`). This API does not copy alert text.

Left out, even when the signed-in map still shows the pin:

- status other than `active` or `confirmed`, and anything already past `expires_at`
- `profiles.is_demo`, and an auth user flagged `demo: true`
- `drops.location_trust = reduced`

There is no reveal route. Sending a user JWT does not unlock a teaser. The SQL functions do not read `auth.uid()`.

## Limits

30 requests per 60 seconds per client address. The Edge Function hashes the address (SHA-256, a fixed prefix, lowercased) and calls `public_api_take`. The table `private.public_api_quota` stores that hash, a window start, and a count. It does not store the address. That table is ordinary Postgres, which the free plan already includes. There is no Redis and no paid rate-limit product.

The address is the last hop of `X-Forwarded-For` when that header is present, otherwise `X-Real-IP` when it is an IP, otherwise one shared `unknown` bucket. A client-supplied list cannot mint a new bucket by putting a fresh address first.

Responses send `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Cache-Control`. A `429` also sends `Retry-After`. Successful reads use `public, max-age=60` for `/v1/here` and `/v1/walks`, 120 seconds for a drop or a thing, and 3600 seconds for categories. Errors that are not a missing row use `no-store`. `Referrer-Policy: no-referrer` is set so a URL that contains coordinates is not forwarded.

`OPTIONS` is a CORS preflight (`Access-Control-Allow-Origin: *`) and does not spend a request. `POST` and the other methods return `405`.

## Deploy on the hosted project

CI does not deploy this function and does not run `supabase db push`. Nothing in `.github/workflows` talks to the hosted project. Apply it yourself, from a machine that is allowed to, after the history repair in [`supabase/README.md`](../../supabase/README.md#before-supabase-db-push).

```bash
supabase link --project-ref jpeuldzegtbqfezijhuy
supabase db push
supabase functions deploy public-api --no-verify-jwt
```

`supabase db push` applies `supabase/migrations/20261020050000_public_read_api.sql`. It also applies any other migration the host does not have yet, including `20261020052000_reputation_tiers.sql` when that file is still pending. It does not load `seed.sql`. Do not paste the file into the dashboard SQL editor. The migration does not change Auth.

`[functions.public-api] verify_jwt = false` in `supabase/config.toml` matches `--no-verify-jwt`. Hosted Edge Functions receive `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` from the platform. Do not copy the service-role key into the app, the website, or git.

Check:

```bash
curl -sS "https://jpeuldzegtbqfezijhuy.supabase.co/functions/v1/public-api/v1/here?lat=42.095&lon=-79.244&radius=300" \
  -H "apikey: $SUPABASE_ANON_KEY"
```

`SUPABASE_ANON_KEY` here is the publishable key (`sb_publishable_…`). The function does not use it to decide what to return. The gateway may require it in `apikey`.

Local checks, with no hosted project:

```bash
deno test --allow-net supabase/functions
deno check supabase/functions/public-api/index.ts
supabase test db
```
