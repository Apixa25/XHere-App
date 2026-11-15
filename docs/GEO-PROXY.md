# Geo proxy

Nominatim allows at most one request per second for the whole application. Overpass is meant for well under about 100 queries a day from an app. The phones used to call both hosts themselves, so every install counted separately and the host could not be changed without an app update.

PLAN §2 keeps geocoding on the free public service. The phone now calls the `geo-proxy` Edge Function. That function is the only place that talks to Nominatim or Overpass.

## What the phone sends

`POST /functions/v1/geo-proxy` with the signed-in JWT (`verify_jwt = true`). The body is `{"op":"reverse","lat":…,"lng":…}` or `{"op":"overpass","south":…,"west":…,"north":…,"east":…}`. There is no upstream URL in the app. The service-role key is not in the app. The phone still waits 1.1 seconds between its own reverse lookups.

A limited or empty answer is `{"ok":true,"body":null}` with HTTP 200. The map keeps working and shows no place name, or falls through to Wikidata for historic places.

## What the function does

- Reads the upstream from `GEO_NOMINATIM_URL` or `GEO_OVERPASS_URL` when those are set, otherwise from `private.geo_upstream`. Either one can be changed without shipping an app update. Seeded hosts are the public Nominatim reverse endpoint and `overpass-api.de`.
- Sends `User-Agent: XHere/0.1 (https://xhere.world; public-places)`.
- Rounds a reverse lookup to 0.001° (the same cell as `Here.cell`) and a viewport to a 0.02° box, so neighbors share one cache row. A viewport larger than about 0.5° is shrunk to the center before Overpass sees it.
- Stores the JSON in `private.geo_cache` (30 days for a Nominatim hit, 7 days for Overpass, shorter when the answer is empty). No user id.
- Nominatim upstream calls take the existing `private.nominatim_gate`: one request every 1.1 seconds for the whole project, shared with older builds that still call Nominatim themselves. The new app does not call `claim_nominatim_slot` before the proxy, or that claim would burn the slot and the proxy could not fetch.
- Overpass upstream calls stop at 90 per UTC day and wait 2 seconds between calls.
- When the slot is busy, the function returns a cached body even if the TTL has passed, or an empty body. It does not return an error that would break the sheet.

## Apply on the hosted project

Not applied from this repo. Not applied by CI. After the history repair in `supabase/README.md` if that repair has not been done:

```bash
supabase link --project-ref jpeuldzegtbqfezijhuy
supabase db push
supabase functions deploy geo-proxy --project-ref jpeuldzegtbqfezijhuy
```

`db push` applies `supabase/migrations/20261022150000_geo_proxy.sql`. Push the database first so the cache and the rate-limit functions exist, then deploy the function. `[functions.geo-proxy] verify_jwt = true` matches a deploy without `--no-verify-jwt`.

Optional mirrors, set as function secrets (they override the table):

```bash
supabase secrets set GEO_NOMINATIM_URL=https://nominatim.openstreetmap.org/reverse --project-ref jpeuldzegtbqfezijhuy
supabase secrets set GEO_OVERPASS_URL=https://overpass-api.de/api/interpreter --project-ref jpeuldzegtbqfezijhuy
```

Leave them unset to use `private.geo_upstream`. Update that table to switch hosts later. Do not put `SUPABASE_SERVICE_ROLE_KEY` in the app. The hosted function already receives it.
