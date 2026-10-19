# Town Hall

*Checked 2026-10-19. XHere is global. Jamestown, New York is the pilot. This follows PLAN.md §3 (open-data layers) and [OPEN-DATA-PLAYBOOK.md](OPEN-DATA-PLAYBOOK.md): a region names its sources, the importer does not special-case a city, and only free sources with allowed automated access are used. There is no `project-vision.md` in this repo.*

Town Hall answers "what is being built next door?" for a town that no longer has a local paper. Council agendas, hearings, permits, and notices are pinned to the address they are about. They are **not** drops. A person cannot post one.

## What the app shows

- A **Town Hall** chip and a map toggle. The layer starts off. Turning it on draws one icon per type: agenda 📋, hearing 📣, permit 🏗️, notice 📌.
- The Here Card section **Coming up near here** lists hearings, permits, notices, and meetings within about 300 m. Each row keeps the official link and the attribution.
- Explore lists upcoming meetings and notices for the region (about 25 km). A row with no pin is included when some other row in that region is nearby. Permits stay on the map and the Here Card, not in that meeting list.
- **Follow this address** writes a note in the same profile inbox when a later record matches that street in that region. The match is a whole phrase, so "West Third Street" matches "10 West Third Street" and does not match a different street.

Old rows expire. A meeting leaves the day after it happens (05:00 UTC). A permit or notice lasts 180 days unless the source sets a date. A nightly job archives them. A re-import archives rows that disappeared from that source.

Summaries are shortened public text. Tags are stripped and whitespace is collapsed. No language model is called.

## How a region turns it on

In `supabase/regions/<iso>/<region>.yaml`:

```yaml
civic:
  sources:
    - id: city-council
      adapter: manual          # or legistar, civicplus, socrata, arcgis_permits, rss_ical
      publisher: Example City
      url: https://example.gov/agendas
      license: Public record
      attribution: "Example City. Headline and link only."
      cost: 0
      file: supabase/seed/civic/example.manual.yaml
```

`cost` must be 0. `url` must be https. A manual source points at a YAML (`items:`) or CSV file. The importer writes JSON and SQL:

```bash
python3 supabase/scripts/import_civic.py \
  --region supabase/regions/us/jamestown-ny.yaml \
  --out supabase/seed/civic/jamestown-ny.json
python3 supabase/scripts/import_civic.py --self-test
```

`--geocode` is optional and is **not** used for the committed Jamestown file (the coordinates are already in the manual YAML). For a US address it calls the Census Bureau one-line geocoder (`Public_AR_Current`, no key). For any other country it calls the public Nominatim search endpoint, waits 1.1 seconds, and caches the answer in `supabase/seed/civic/geocode-cache.json`. It does not call the phone's reverse-geocode RPCs and it does not write to hosted Supabase. A point outside the region box is stored with no geometry.

Street addresses are pulled from the title or the location field with a numbered-address pattern first (`10 Oak Street`), then a bare street name (`Hall Avenue`).

## Jamestown, New York (checked 2026-10-19)

| Source | Result |
| --- | --- |
| City meeting schedule, https://www.jamestownny.gov/city-council/meeting-schedule/ | **Used.** HTTP 200. `robots.txt` does not disallow this page. Work sessions and voting sessions are at the Municipal Building, 200 East Third Street. The 2026 list is also a city PDF on the same site. |
| City public-hearing page, https://www.jamestownny.gov/city-clerk/public-hearing-notices/ | **Link only for one upcoming notice.** The page is allowed. Most listed hearings are already past. The Main Street Viaduct placemaking legal notice (5 October 2026) is a city-hosted PDF. Census and Nominatim did not match "Main Street Viaduct", so that row has no pin. |
| eCode360 agendas, https://ecode360.com/JA1371/documents/Agendas | **Link only.** A fetch of robots returned HTTP 403 (Cloudflare). Do not crawl. One already-public PDF is linked: the 21 September 2026 work session, which names Hall Avenue reconstruction from Front Street to Palmer Street. The resolution text is not copied. |
| AgendaCenter, https://www.jamestownny.gov/AgendaCenter | **Not used.** HTTP 404. Jamestown is not on CivicPlus AgendaCenter. |
| Legistar, `webapi.legistar.com/v1/jamestown` and `/chautauqua` | **Not used.** HTTP 500, "LegistarConnectionString setting is not set up". The adapter stays for cities that have a client. No API key is required when a client exists. |
| City calendar export | **Not used.** `robots.txt` disallows `/calendar/action~*` and the ai1ec exporter. |
| News RSS, https://www.jamestownny.gov/category/newsmedia/feed/ | Allowed, and already used as link cards elsewhere. It is not an agenda or permit feed. |
| Chautauqua County planning agendas, planningchautauqua.com | **Not used.** HTTP 403. Do not crawl. The 6 October 2026 board met at the Jamestown airport, which is north of the region box, and that date is past. No later meeting was invented. |
| Building permits | **Not used.** Applications go through GovPilot (`eforms-main.govpilot.com`), which is a form portal, not an open dataset. `data.ny.gov` catalog search (`domains=data.ny.gov`, `q=permit`) has no Jamestown or Chautauqua building-permit table. Other cities' permit tables showed up in a federated search and were not imported. `data.ny.gov` robots allow the API with a crawl delay. The Socrata and ArcGIS adapters stay for a region that publishes a dataset. |

The seed is the manual file `supabase/seed/civic/jamestown-ny.manual.yaml`. It has the remaining 2026 council dates (19 October through 28 December) at 200 East Third Street, the Hall Avenue notice, and the viaduct notice.

Coordinates, from the Census Bureau geocoder (`Public_AR_Current`) on 2026-10-19:

- 200 E 3rd St, Jamestown, NY 14701 → 42.096704, -79.238842
- Hall Ave & Front St → 42.092385, -79.255549
- Hall Ave & Palmer St → 42.090771, -79.255235
- Hall Avenue pin is the midpoint of those two intersections, 42.091578, -79.255392, inside the region box. It is not a parcel.
- The airport point for the past county meeting is north of the box and is not stored.

License on every row: public record. Attribution: City of Jamestown, headline and link only. Census coordinates are public domain.

## Apply on the hosted project

Do this from a machine linked to `jpeuldzegtbqfezijhuy`. Do not paste SQL into the dashboard. `db push` does not load seeds. This does not change Auth. There is no new Edge Function.

```bash
supabase link --project-ref jpeuldzegtbqfezijhuy
supabase db push
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/civic/jamestown-ny.sql
```

`db push` applies `supabase/migrations/20261021180000_civic_items.sql` and any earlier migration the host does not have yet. Then the seed calls `private.upsert_civic_items`. Running the seed again updates the same rows.

Local `supabase db reset` also loads `supabase/seed/civic/jamestown-ny.sql` because it is listed in `supabase/config.toml`.
