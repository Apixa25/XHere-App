# web/

Astro site for [xhere.world](https://xhere.world): the landing page, share links, the legal drafts, and Android App Links. This is PLAN §1.5 and milestone M8. It is not the old web app.

The site is static. `npm run build` writes `dist/`. Fixture share pages are baked in when `PUBLIC_SUPABASE_URL` is unset or still the placeholder in `.env.example`. A Cloudflare Pages Function in `functions/` renders any other `/d/{short_id}` from the `drop_teaser` RPC once Steven approves a Supabase project and a Cloudflare account. **Do not deploy.** See [CLOUDFLARE.md](CLOUDFLARE.md).

## Pages

| Path | What it is |
|---|---|
| `/` | Landing page. Jamestown, New York is the pilot city. The app is global. |
| `/d/{short_id}` | Share page. Open Graph tags and a static map. Teaser only. The hidden body is never rendered. Sensitive categories (`safety_alert`, `incident_report`) also hide the author and show an approximate area. |
| `/privacy`, `/terms`, `/guidelines` | Plain-English drafts. Not in effect until Steven reviews them. |
| `/delete-account` | Web account-deletion steps Google Play requires, matching Profile → Settings → Delete account and the 30-day purge. |
| `/support` | How to get the app (the Play listing is not up) and how to write in. |
| `/.well-known/assetlinks.json` | App Links file. The SHA-256 value is the placeholder `PLACEHOLDER_NOT_A_REAL_CERT__REPLACE_WITH_PLAY_APP_SIGNING_SHA256`. |

Fixture ids, all in Jamestown: `JmtwnHist01a` (historical fact), `JmtwnNote02b` (note), `JmtwnInc03cd` (incident report).

## Maps

Share images are SVG files drawn from [OpenFreeMap](https://openfreemap.org/) planet vector tiles (`https://tiles.openfreemap.org/planet`), the same source the Android app loads with MapLibre. Colors follow the OpenFreeMap Positron style. The page does not ship MapLibre GL, so the preview stays a static image. Attribution is OpenStreetMap contributors, OpenMapTiles, and OpenFreeMap.

The build fetches those tiles. There is no API key. If the tile host is down, `npm run build` fails instead of publishing a blank map.

## Commands

```bash
cd web
npm ci
npm run build    # tiles + Astro, then dist/
npm run verify   # fixture share pages, hidden body absent, asset links
npm run lighthouse
```

Lighthouse runs against `dist/` in Chrome. The CI job is `web / build`.

## Android

The app intent filter is on `MainActivity` for `https://xhere.world/d/*`. Verification needs the real Play App Signing fingerprint in the asset-links file, which Steven adds after the signing key exists.
