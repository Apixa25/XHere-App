# XHere Open-Data Playbook (region-agnostic)

*v1, 2026-10-18 (ET). XHere is global. This playbook is the standard way to add **any** region and **any** open-data category (safety, history, permits, inspections, events, environment, transit, etc.). Jamestown NY / Chautauqua County is the pilot, and its worked example is `CRIME-OPEN-DATA.md`. This plan builds on PLAN.md §1.4 (Supabase), §3 "Open-data layers", §4 (classification axes: category / source / status), §5 (`external_items_cache`), and milestones **M10** (first open-data layer) and **M16** (more open data, city incident feeds).*

> Golden rules (apply everywhere):
> 1. **Never be more precise than the source.** Display at the precision the publisher released (address, hundred-block, intersection, snap point, area). Never "un-offset" or re-geocode an anonymized point.
> 2. **Official records about places can be pinned and sourced. Claims about people never are.** No names, mugshots, or person-level records in any layer, in any region.
> 3. **User reports are never pinned to a private address in sensitive categories.** They roll up to an area (H3 cell / block) with thresholds, then go to moderators.
> 4. **Every item carries provenance.** Source name, source URL (deep link), retrieved-at, license, and "as published by X on date Y."
> 5. **Config, not code.** A new region means a new YAML file plus, rarely, a new adapter *type*.
> 6. **Respect terms.** Use APIs and bulk downloads. No scraping where terms forbid it, and no workaround for bot blocks. Paid feeds need Steven's OK (cost item).

---

## 1. Source-discovery checklist (run for every new region)

Work top-down. Record every candidate in the region config's `candidates:` block, *including rejected ones and why*, so the next person doesn't redo it.

| Tier | What to look for | US examples | International equivalents |
|---|---|---|---|
| **Global / multi-country** | Open, global layers usable everywhere | Wikipedia/Wikidata geosearch, OSM (historic=*, memorials, amenities), OpenFreeMap tiles, GeoNames, GDELT (events, careful), Mapillary | Same. Plus Europeana (heritage), UNESCO WHC list |
| **National** | Statistical crime data, federal registers, heritage registers, recalls/hazards | FBI Crime Data Explorer API (agency-level), DEA Clandestine Lab Register, EPA Envirofacts/Superfund, NRHP (National Register of Historic Places), HMDB (historical markers; check terms), USGS | UK **data.police.uk** (street-level, OGL v3, monthly API); UK Historic England list; Canada StatCan (aggregate only); EU Eurostat (aggregate); Australia data.gov.au; NZ Police data (aggregate) |
| **State / province** | Open-data portal (often Socrata/CKAN/ArcGIS Hub), criminal-justice stats, court portals, licensing, environmental | data.ny.gov (Socrata/SODA), NYS GIS parcels, state DEC/environment, state liquor authority licenses | Ontario/BC open data, German Länder portals, French data.gouv.fr (DVF etc.) |
| **County / district** | GIS parcels (address → parcel → point), sheriff releases, property assessment, health inspections | County ArcGIS REST services, sheriff press releases | UK local authority open data, Canadian regional municipalities |
| **City / municipality** | Open-data portal, code enforcement / violations, condemned/vacant registry, building permits, restaurant inspections, 311, events calendar, historic districts | City Socrata/ArcGIS Hub, GovPilot/Accela/Tyler EnerGov/CityWorks public maps, FOIL/FOIA portals (NextRequest, GovQA) | Toronto Open Data (CSI, offset to intersections, OGL-Toronto); Vancouver GeoDASH (hundred-block/offset, weekly); Paris/Berlin open data |
| **Police** | Incident open data, crime map vendor, press releases, RSS, annual reports, **anonymous tip line** | Agency site; CrimeMapping.com, LexisNexis Community Crime Map, CityProtect (agency must opt in; vendor terms usually forbid redistribution) | data.police.uk (UK); Crime Stoppers (tip lines in many countries) |
| **Courts** | Case-lookup portals | State WebCrims/eCourts, PACER | Usually **excluded** (person-level, terms forbid automated access, sealing laws) |
| **Aggregators (commercial)** | Licensed feeds | SpotCrime API (paid), Crimeometer (paid), news APIs | Varies. All are **cost items** and often forbid redistribution/AI use |
| **Community / news** | Local news police blotters | Post-Journal, WNY News Now | **Link-out only** (copyright). Never ingest article text |

**For each candidate, capture:**
- `url`
- `publisher`
- `category_targets`
- `fields`
- `precision` (address / hundred_block / intersection / snap_point / area / agency_total)
- `person_data` (none / incidental / core)
- `update_frequency`
- `format_access` (API / bulk / feed / html / pdf / manual)
- `license` + `terms_url` + `automated_access` (allowed / unclear / forbidden)
- `cost`
- `decision` (use / link-only / candidate-ask-permission / reject) + `reason`

**Ask-the-source step:** when a valuable dataset has no API or unclear terms, the default action is a polite **data request or FOIA/FOIL**, or asking the agency to publish open data. It is never scraping. Track the request in the config.

---

## 2. Source-adapter interface and data contract

### 2.1 Adapter types (code, small fixed set)
| Adapter type | Covers |
|---|---|
| `socrata` | data.ny.gov, many US city/state portals (SODA v2/v3: `$where`, `$limit`, `$offset`, app token optional/free) |
| `arcgis_feature` | ArcGIS REST FeatureServer/MapServer query (`where`, `outFields`, `outSR=4326`, `resultOffset`, `f=geojson`) |
| `ckan` | CKAN portals (EU/Canada/UK gov) |
| `csv_bulk` | Periodic CSV/ZIP downloads (data.police.uk archive, DEA register export uploaded by an operator) |
| `rss_atom` | Press-release feeds (link-out cards only) |
| `police_uk` | data.police.uk JSON API (special because of snap points + LSOA) |
| `wikidata_geo` / `osm_overpass` | Global history/POI layers (M10/M16) |
| `manual_upload` | Operator uploads a file from a source whose site blocks bots (for example DEA) and which has no API. A human downloads it through the normal site UI |

All adapters run as **Supabase Edge Functions** (Deno) on **pg_cron** schedules and write to staging, then upsert to `external_items`. No adapter runs in the mobile app.

### 2.2 Adapter interface (TypeScript, in `supabase/functions/_shared/adapters/`)
```ts
interface SourceAdapter<Cfg> {
  type: string;                                   // 'socrata' | 'arcgis_feature' | ...
  fetch(cfg: Cfg, since?: Date): AsyncIterable<RawRecord>; // paginated, rate-limited, honors robots/terms
  map(raw: RawRecord, cfg: Cfg): NormalizedItem | null;    // field mapping from region config; null = drop
}
```

### 2.3 Normalized item (the data contract)
| Field | Notes |
|---|---|
| `source_id` | FK to `sources` (region config entry) |
| `source_record_id` | Publisher's ID, or a stable hash of the key fields. Used for **dedup/upsert** |
| `category_slug` | XHere taxonomy (PLAN §4.2), e.g. `safety.official_incident`, `safety.drug_lab_site`, `property.code_violation`, `property.condemned`, `history.marker`, `civic.permit`, `event.public` |
| `source_axis` | Always `open_data` (PLAN §4.1) |
| `title`, `summary` | Generated from **templates** in config (no free text that names people) |
| `occurred_at` / `published_at` / `valid_until` | `valid_until` drives expiry (see retention) |
| `geom` | PostGIS point or polygon in **as-published precision** |
| `precision` | `address` / `parcel` / `hundred_block` / `intersection` / `snap_point` / `area` / `agency` |
| `display_mode` | Derived from category rules + precision: `pin`, `block`, `area_heat`, `stat_card`, `link_card` |
| `attributes` | jsonb validated against the category's JSON Schema (pg_jsonschema) |
| `source_url` | Deep link to the record, or the dataset page when there is no deep link |
| `license`, `attribution_text` | Shown on every card |
| `retrieved_at`, `content_hash` | Change detection |
| `status` | `active` / `superseded` / `retracted_by_source` / `expired` / `takedown` |
| `region_id` | FK to the region |

### 2.4 Pipeline standards
- **Geocoding:**
  - Prefer the source's coordinates.
  - If the source only gives an address, geocode with **local authoritative parcels first** (county/city GIS), then a free geocoder (Nominatim within its usage policy, US Census Geocoder for US, at no cost), and cache the results.
  - Store `geocode_method` + `confidence`. If confidence is below the threshold, set `precision=area`.
  - **Never geocode an already-offset point back to an address.**
- **Normalization:**
  - Map source codes to `category_slug` via the config's `category_map`.
  - Unmapped codes go to an `unmapped` queue (not displayed) for review.
- **Dedup:**
  - Within a source, dedup on `source_record_id`.
  - Across sources (e.g. city + state + vendor), dedup on category + distance ≤ precision radius + time window. Keep the most authoritative source (`authority_rank` in config) and link the others as "also reported by."
  - User drops near an official item show it as context, never merge with it.
- **Provenance:** every display card shows "Source: {publisher} · {dataset} · retrieved {date} · {license}" plus a "View original" link.
- **Refresh cadence (defaults):**
  - Match the publisher, with a minimum interval of 1 hour.
  - Daily/weekly feeds run nightly.
  - Monthly or annual stats run monthly.
  - Manual uploads are checked quarterly, with an "overdue" alert.
- **Retention/expiry (defaults; configurable per category and jurisdiction):**
  - Incidents: 12 months for display, then aggregate-only.
  - Code violations: until the source shows them closed, plus 90 days.
  - Condemnations/vacant: while active at the source.
  - Drug-lab sites: per the source, labeled with the incident date. Remove when the source removes or remediates them.
  - History: permanent.
  - Permits/events: until `valid_until` + 30 days.
  - If a record disappears from the source, mark it `retracted_by_source` and hide it within one refresh cycle.
- **Takedown/correction:**
  - Any user can tap "Report a problem with this record."
  - Property owners can request review.
  - The record is hidden pending review when the request shows the source changed or the record is wrong.
  - Everything is logged in the moderation tables (M7).

### 2.5 Tables (extends PLAN §5)
`regions`, `sources` (one row per config entry, with license/terms/cost/automated_access), `ingest_runs` (stats, errors), `external_items` (normalized), `external_items_staging`, `unmapped_codes`, `source_requests` (FOIL/FOIA/data-ask tracking), `area_activity` (user-report rollups per H3 cell).

---

## 3. Per-region config format (`supabase/regions/<iso>/<region>.yaml`)

```yaml
region:
  id: us-ny-chautauqua            # ISO-3166-2 based slug
  name: Chautauqua County, NY, USA
  country: US
  jurisdiction_profile: us-ny     # links to the legal checklist (§4)
  bbox: [-79.77, 41.99, -79.05, 42.58]
  timezone: America/New_York
  tip_lines:                      # shown on every Safety screen in this region
    - name: Jamestown Police anonymous tip line
      phone: "+1-716-483-8477"
      url: https://www.jamestownny.gov/police-department/anonymous-tip-line/
      area: city:jamestown
    - name: Emergency
      phone: "911"
geocoders:
  - type: arcgis_parcels
    url: https://maps.chautauquacounty.com/server/rest/services/ParcelViewer/ViewerOperational/MapServer/151
    address_fields: [Loc_St_Nbr, Loc_St_Name, Muni_Name]
  - type: census_us
sources:
  - id: dea-nclr-ny
    adapter: manual_upload        # DEA site blocks automated clients; operator downloads CSV via site UI
    publisher: U.S. DEA
    url: https://www.dea.gov/clan-lab
    license: US federal public data (no copyright); DEA conditions of use, display disclaimer
    automated_access: forbidden_by_bot_block
    cost: 0
    category_map: { "*": safety.drug_lab_site }
    precision: address
    filters: { State: New York, County: CHAUTAUQUA }
    title_template: "Reported drug lab or dumpsite (DEA register, {date})"
    refresh: quarterly
    authority_rank: 1
    disclaimer: dea_nclr
  - id: nys-index-crimes
    adapter: socrata
    url: https://data.ny.gov/resource/ca8h-8gjq.json
    where: "county='Chautauqua'"
    precision: agency
    display_mode: stat_card
    category_map: { "*": safety.crime_stats }
    refresh: monthly
    license: NY Open Data terms of use
candidates:                       # discovered but not ingested, with reason
  - id: spotcrime
    decision: reject_for_now
    reason: paid API ($199+/mo); license forbids redistribution and AI use
```

**Adding a region** means one YAML file, a CI validation run (JSON Schema for the config), a dry-run ingest that writes to staging only, a reviewer sign-off on the legal checklist (§4), and then publish. A new adapter *type* is code, and only happens when a portal technology isn't covered.

---

## 4. Legal/compliance checklist (one `jurisdiction_profile` per country/state)

Fill this out once per jurisdiction profile (e.g. `us-ny`, `uk`, `ca-on`, `eu-de`), reviewed by a human, and re-check it yearly. *This is a design checklist, not legal advice. Before launch in any new country, have counsel review it (cost item).*

| # | Check | US (baseline) | UK | Canada | EU (GDPR) |
|---|---|---|---|---|---|
| 1 | **Platform liability for user posts** | 47 U.S.C. §230 protects hosting user content. It does **not** protect content XHere itself writes (titles/summaries we generate) | No §230. Defamation Act 2013 s.5 operator defence requires a notice-and-takedown process | No §230 equivalent. Notice-and-takedown, with case-by-case risk | E-Commerce/DSA hosting safe harbour **only** if we act expeditiously on notices. DSA Art. 16 notice-and-action |
| 2 | **Defamation via official data** | Accurately reporting public records with attribution has a strong fair-report privilege (NY Civ. Rights Law §74). Accuracy and attribution are essential | Qualified privilege for fair and accurate reports of public records (Defamation Act 1996 Sch. 1) | Similar qualified privilege | Varies by country |
| 3 | **Anti-SLAPP** | NY Civ. Rights Law §76-a (2020) covers public-interest communications | Limited | Ontario/BC anti-SLAPP | Patchy (EU anti-SLAPP directive covers cross-border cases) |
| 4 | **Criminal-offence data** | Generally lawful if public, but: no names, no mugshots (NY POL §89(2)(b)(viii) limits agency release), respect sealing (NY Clean Slate Act, eff. 2024-11-16) | UK GDPR Art. 10 + DPA 2018 Sch. 1: criminal-offence data needs a specific condition. **Use only already-anonymized official data (data.police.uk)** | PIPEDA/provincial privacy. Use only police-anonymized open data | **GDPR Art. 10**: processing criminal-offence data needs official authority or EU/member-state law. **Aggregate/anonymized official data only, no address-level crime claims** |
| 5 | **Address = personal data?** | Usually not regulated, but treat it as sensitive | Yes, if it relates to an identifiable resident/owner | Often yes | **Yes.** A crime label on an address can be personal data about the occupant/owner. DPIA required |
| 6 | **Fair housing / discrimination** | Fair Housing Act: avoid "bad neighborhood" scoring, steering, and racialized descriptions (Nextdoor lessons). Block descriptions of people | Equality Act 2010 | Human rights codes | Anti-discrimination law |
| 7 | **Source terms of use** | Record `automated_access`. Court portals (e.g. NY WebCrims) forbid automated extraction: **excluded** | OGL v3 needs attribution | OGL-Toronto/Ontario need attribution | Licences vary (CC BY 4.0 common) |
| 8 | **App stores** | Apple 1.1.1 (no defamatory or mean-spirited content), 1.2 (UGC must have filtering, reporting, blocking, and published contact info), **1.7 (apps for reporting alleged criminal activity must involve local law enforcement and be offered only where that involvement is active)**. Google Play UGC policy (terms, moderation, in-app reporting/blocking) | Same | Same | Same + DSA transparency |
| 9 | **Data subject rights / takedown** | Correction/takedown flow (§2.4) | UK GDPR rights + ICO | PIPEDA access/correction | GDPR Arts. 15–21, DPIA, EU representative if no EU establishment |
| 10 | **Minors** | No juvenile records, ever | Same | YCJA forbids identifying youth | Same |

**Apple 1.7 consequence (global):** XHere must not be positioned as a "report crimes" app. Safety user reports are framed as *area activity / quality-of-life awareness*. The **official tip line** (law enforcement) is the action button for crime, and it's region-configured (`tip_lines`). Regions without a configured tip line get **no Safety-report UI**. Official-data layers still show.

### 4.1 Standard disclaimers (localizable strings)
- **Official record card:**
  - "Official public record published by {publisher} on {date}. XHere shows it as published and has not verified it."
  - "It is not an accusation against any current owner, resident, or person."
  - "Records may be outdated or corrected. See the original."
  - Plus a {View source} link.
- **Offset data:** "Location approximate. {publisher} offsets locations to protect privacy. Do not associate it with a specific property or person."
- **DEA register (specific):** "Listed in the DEA National Clandestine Laboratory Register (a reported lab or dumpsite, possibly remediated). DEA does not verify entries. Contact local health authorities for clean-up status."
- **Area activity (user reports):** "Several independent XHere users reported activity in this general area. These are unverified community reports, not official records, and they don't refer to any specific address or person. If you have information about a crime, contact {tip_line}. In an emergency call {emergency}."
- **Stat card:** "Aggregate statistics from {publisher}. Counts reflect reported crimes only."

---

## 5. Display rules (standard, all categories)

| Data type | Map display | Detail | Rules |
|---|---|---|---|
| **Official place record published at address precision** (code violation, condemnation, permit, inspection, drug-lab register, historic marker) | **Exact pin**, category icon + "Official" badge | Templated title, date, status, source link, license, disclaimer | Pin only if `precision ∈ {address, parcel}` **and** the category allows `pin`. Crime-adjacent property categories never show owner/occupant names, even when the source includes them |
| **Official incident published offset** (data.police.uk, Toronto, Vancouver) | Point at the *published* location, rendered as a soft block-level marker or heat cell, never a building outline | Category, month/date, approximate-location disclaimer | Never re-geocode, never snap to a building |
| **Official aggregate** (agency totals, NY Index Crimes) | No point. **Stat card** for the city/agency area | Trend vs. prior years, source | |
| **User reports in sensitive categories** (Safety: suspected drug activity, "problem property") | **Area activity only.** H3 res-9 cell (~0.1 km²) or street-block polygon, shown as a shaded area with a count band ("several reports"), never a pin, never an address | Generic description from category templates, recency band, tip-line button, disclaimer | **Threshold:** shown publicly only when ≥3 independent reporters (distinct accounts, account age ≥7 days, not blocked, device/IP diversity) within 30 days. ≥5 in 30 days, or any report mentioning weapons/children, escalates to the moderator queue. Free text is moderator-only (PII filter, M7) and never shown publicly. Auto-decay after 30 days without new reports. Per-user rate limits (PLAN tiers) |
| **User reports, non-sensitive categories** (history, events, food, nature) | Normal drop pins per PLAN §4 | Normal | Standard confirmations |
| **News/press releases** | No pins. Region "News" list, link-out cards (headline + source + date) | Link to the original | Copyright: no article text ingestion |

**Category rules** live in the `categories.rules` jsonb (PLAN §4.3): `allowed_display_modes`, `min_precision_for_pin`, `user_reports: area_only|pin`, `threshold`, `escalation`, `retention_days`, `requires_tip_line`, `disclaimer_key`, `exclude_jurisdictions`. The rules engine applies these in the RPC layer (server-side), so clients can't bypass them.

---

## 6. Beyond crime: the same playbook for other categories
| Category | Typical sources | Precision / display | Notes |
|---|---|---|---|
| History & heritage | Wikipedia/Wikidata, OSM historic=*, NRHP/Historic England, HMDB | Pin | Permanent. CC BY-SA/ODbL attribution (M10) |
| Property & civic | Code violations, condemned/vacant registries, permits, assessments | Pin (official only, no owner names in sensitive categories) | Expire when closed at the source |
| Inspections | Restaurant/health inspections | Pin | Show the latest grade + date + link |
| Events | City calendars, ICS feeds, Eventbrite (terms) | Pin, with `valid_until` | Expire after the event |
| Environment & hazards | EPA/DEC, flood zones, air quality, recalls | Pin/polygon | Health-authority disclaimers |
| Transit & mobility | GTFS/GTFS-RT | Pin/line | Open licences |
| Safety | See §5 | Rules above | Strictest category |

---

## 7. Fit with PLAN.md milestones
- **M10 (Wikipedia/Wikidata layer):** builds the generic pipeline: the `sources`/`regions`/`external_items` tables, the `SourceAdapter` interface, the region YAML schema + CI validator, the provenance card, and attribution. Wikidata is adapter #1, so the framework is proven on a low-risk category.
- **M16 is split into self-contained chunks:**
  - **M16a:** `socrata` + `arcgis_feature` + `csv_bulk`/`manual_upload` adapters, plus the geocoder chain and dedup.
  - **M16b:** Safety official layer (pilot region config `us-ny-chautauqua`), stat cards, display rules, disclaimers, tip lines.
  - **M16c:** user-report **area activity** (H3 rollup, thresholds, escalation queue, decay), building on M7 moderation.
  - **M16d:** `police_uk` adapter + a UK region config as the first non-US proof (validates the global design and the UK GDPR Art. 10 profile).
  - **M16e:** legal profiles + localized disclaimers + the takedown flow.
- Each chunk has acceptance tests:
  - pgTAP: precision rule enforced, threshold enforced, expired items hidden.
  - Config validator rejects missing license/terms fields.
- **Prereq from M7:** moderation queue + PII filter.
- **Cost items:** counsel review per new country, any paid aggregator, a geocoder beyond free tiers.

---

## 8. Shipped base layer (so the map is never empty)

This is the first slice of **M10** (PLAN §3 "Open-data layers", §4.1 source axis `open_data`, §5). It is the layer that keeps a new city from opening onto a blank map. Jamestown, New York is the **pilot region only**. The importer does not mention Jamestown except by reading `supabase/regions/us/jamestown-ny.yaml`.

### 8.1 What is stored

Imported places are ordinary `drops` with `source = open_data` and `author_id` null. They use the existing category rows (`historical_fact`, `memorial`, `viewpoint`, `public_art`). They show up through `drops_in_view`, `drops_nearby`, `drop_detail`, and `drop_teaser`. They do not count as anyone's drops, points, or tier, and `export_my_data` does not include them. `confirm_drop` rejects them (`not_a_user_drop`): a public record is not a contribution you can vote on.

Provenance is not stuffed into the drop body. It lives on:

| Table | What |
|---|---|
| `public.open_data_sources` | Publisher slug, display name, license, attribution, home URL |
| `public.open_data_items` | One row per place: `drop_id`, `source_slug`, stable `external_id`, https `source_url`, license, attribution, `region_id`, `retrieved_at` |

Unique key is `(source_slug, external_id)`. A re-run updates that drop. A later snapshot for the same source and region marks missing rows `removed` (it does not delete them, so the external id can come back). Clients may `select` both tables. They cannot insert. The write path is `private.upsert_open_data_items(jsonb)`, granted to `service_role` only.

The card the app already reads (`drop_card`) gained `source`, `source_name`, `source_url`, `license`, and `attribution`. A public record stays revealed from any distance, including the share teaser. A user drop is unchanged: the body stays hidden until you are close enough.

The map draws these pins apart from user drops (a small mark on the pin, and "public record" on the plain-text map). The teaser and the detail screen show a **From public records** badge, the attribution line, and a **View source** button. Still-true / not-true is hidden, because confirming one would look like a user contribution.

### 8.2 Sources in this slice (free, licensed)

| Config id | Adapter | License | What is stored |
|---|---|---|---|
| `wikipedia-geo` | `wikipedia_geo` | CC BY-SA 4.0 | English Wikipedia geotagged page **title**, page id, coordinates, and a one-line summary we wrote. Article text is not copied. External id `enwiki:{pageid}` |
| `wikidata-geo` | `wikidata_geo` | CC0 1.0 | Wikidata item with coordinates and an English label that is not just the Q-id. External id `wikidata:{Qid}` |
| `nrhp` | `arcgis_feature` | Public domain | US National Register points (layer 0) and polygon centroids (layer 1) inside the bbox. External id `nrhp:{NRIS_Refnum}`. Link is the NARA URL when it is https, otherwise `https://npgallery.nps.gov/AssetDetail/NRIS/{ref}` |
| `osm-heritage` | `osm_overpass` | ODbL 1.0 | Named `historic=*`, `heritage=*`, and tourism in `artwork`, `attraction`, `museum`, `viewpoint`, `gallery`. Hotels and other tourism values are not imported. External id `osm:{node\|way}/{id}` |

HMdb and Atlas Obscura stay **link-only**. Both the importer and `upsert_open_data_items` refuse a slug or URL that looks like either one (`license_not_allowed`). Their reasons are in the region file's `candidates:` block so the next region does not re-decide it.

Within one run, the same place is kept once. Order is NRHP, then Wikipedia, then Wikidata, then OSM. A shared Wikidata Q-id, or a name within 60 m that is the same or nearly the same, drops the later copy.

### 8.3 Files

| Path | Role |
|---|---|
| `supabase/scripts/import_open_data.py` | Stdlib Python. No pip, no paid API |
| `supabase/regions/<country>/<region>.yaml` | The only per-region config. Bbox is west, south, east, north |
| `supabase/seed/open-data/<file>.json` | The snapshot the script writes |
| `supabase/seed/open-data/<file>.sql` | `select private.upsert_open_data_items(...)`. Safe to re-run |
| `supabase/migrations/20261018001600_open_data.sql` | Tables, card fields, map RPC, upsert, confirmation guard |
| `supabase/tests/database/12_open_data.test.sql` | pgTAP |

`supabase/config.toml` loads `seed.sql` and then `seed/open-data/jamestown-ny.sql` on `supabase db reset`. `supabase db push` does **not** load either file.

Overpass mirrors fail on their own. The script tries `overpass.openstreetmap.fr`, then `overpass.kumi.systems`, then `overpass-api.de`. If all three fail, it reads the same tags from the OSM map API in small tiles (the map call rejects a whole city as too many nodes). Same ODbL data, same external ids.

### 8.4 Add another region

1. Copy `supabase/regions/us/jamestown-ny.yaml` to `supabase/regions/<country>/<name>.yaml`.
2. Set `region.id` (lowercase, hyphenated, for example `fr-paris`), `name`, `country`, `bbox`, and `timezone`.
3. Keep, drop, or add `sources`. A national source that does not cover the country (NRHP is US-only) should be omitted there. Record rejected sources under `candidates` with `decision: link-only` or `reject` and a reason.
4. Fetch and check, from the repo root:

```bash
python3 supabase/scripts/import_open_data.py --self-test
python3 supabase/scripts/import_open_data.py \
  --region supabase/regions/us/jamestown-ny.yaml \
  --out supabase/seed/open-data/jamestown-ny.json
python3 supabase/scripts/import_open_data.py \
  --check supabase/seed/open-data/jamestown-ny.json \
  --region supabase/regions/us/jamestown-ny.yaml
```

`--check` is offline. It requires every configured source to have at least one item, https links, the license and attribution strings from the YAML, a known category, a unique external id, and no HMdb or Atlas Obscura row.

5. Apply on a database that already has migration `20261018001600` (local: `supabase db reset`; hosted: the steps in `supabase/README.md`):

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/open-data/jamestown-ny.sql
```

`DATABASE_URL` is the Postgres connection string. The SQL calls a function granted to `service_role` and the database owner. The anon key cannot run it. Re-running the same file updates rows; it does not insert a second pin for the same external id.

### 8.5 Jamestown pilot counts

The committed snapshot is `supabase/seed/open-data/jamestown-ny.json` for region `us-ny-jamestown`, bbox `[-79.32, 42.05, -79.18, 42.14]`. After dedupe (2026-10-18):

| Source | Places |
|---|---|
| `wikipedia-geo` | 28 |
| `wikidata-geo` | 64 |
| `nrhp` | 11 |
| `osm-heritage` | 14 |
| **Total** | **117** |

Before dedupe the fetch was 31 Wikipedia, 65 Wikidata, 11 NRHP, and 18 OSM. Refresh the file with the command in §8.4, then apply the `.sql` again. Do not hand-edit the JSON or the SQL.

---

## 9. Live conditions (weather alerts, optional 311, right now)

This is recommendation #5 from `docs/research/LOCATION-APPS-RESEARCH.md` (PLAN §3 open-data layers, §4.1 source axis, §5 expiry). It is global. A region opts in by config. Jamestown is only the pilot file.

### 9.1 Official weather alerts

Active alerts are not copied into `drops`. The phone asks a `WeatherAlertProvider` for the center of the map the person is looking at. The US provider is the National Weather Service, `https://api.weather.gov/alerts/active?point={lat},{lng}`. No API key. The client sends `User-Agent: XHere/0.1 (https://xhere.world; weather-alerts)` and `Accept: application/geo+json`, and it caches using `Cache-Control` (at least 30 seconds, at most an hour; an empty answer or a 404 is kept for at least an hour).

Which provider to call:

1. `public.region_live_at(lat, lng)` — the smallest configured box, from `supabase/seed/live/<region-id>.sql`.
2. If that call fails or returns no row, the bundled catalog in `LiveRegionCatalog.kt` (the importer self-test checks it against every region YAML).
3. If there is still no row, the first provider whose coverage box contains the point. NWS coverage is the US (lower 48, Alaska, Hawaii, Puerto Rico, the US Virgin Islands, Guam, American Samoa). Paris and London are outside it. `weather_provider: none` returns no alerts even inside a coverage box.

Another country adds a class that implements `WeatherAlertProvider` and a provider id in that region's `live.weather_alerts.provider`. The banner shows severity, the event, the expiry in UTC, and the publisher. Dismiss hides that alert until the next one in the list. The Here Card repeats the same visible alerts. Opening the card does not count a view.

### 9.2 Optional Open311 / civic feeds

`open311` is an adapter on the same importer. It runs only when a source block sets `adapter: open311` and an `https` `endpoint`. It calls `{endpoint}/requests.json?status=open`, keeps rows inside the bbox, and writes `civic_issue` drops with `expires_at` (`ttl_hours`, default 7 days, max 30). The title is the service name. The summary is the fixed line `Open civic request. Status: open.` The request description is not copied, because it can name a person.

**Jamestown finding (checked 2026-10-18).** There is no free 311 feed to configure.

- Jamestown is not on the Open311 GeoReport v2 server list (`https://wiki.open311.org/GeoReport_v2/Servers/`). No New York city is listed.
- `jamestownny.gov` does not publish an open 311, SeeClickFix, or Socrata service-request API.
- Police annual reports are PDFs of calls for service. Those are about people. They are not imported.
- data.ny.gov "311 Service Requests" is New York City (`erm2-nwe9`), not Jamestown.
- The SeeClickFix issues API returned 403. It is not a free open feed.

The candidate is `jamestown-311` with `decision: reject` in `supabase/regions/us/jamestown-ny.yaml`. No source block was added. A later city that does publish GeoReport v2 adds a source block; this adapter is already there.

### 9.3 Right now

`right_now` is a user category in the safety group (no new group). The person picks 1 hour, 4 hours, or 24 hours. The default is 4 hours. `private.enforce_right_now_window` rejects anything else on insert. A confirmation may still extend the row inside the 24 hour cap. Any other category can also be shortened to those three lifetimes; "Usual" keeps that category's own default.

`drops_in_view`, `drops_nearby`, `drop_detail`, and `here_card` already hide a row once `expires_at` has passed (`private.can_view_drop`). `private.expire_drops` is already scheduled on free pg_cron (`xhere-expire-drops`, every 5 minutes) and sets `status` to `expired`. There is no second job and no paid queue.

### 9.4 What to apply on a database

Migration `20261019233041_live_conditions.sql`, then the live seed. `supabase db push` does not load the seed.

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/live/us-ny-jamestown.sql
```

That calls `private.upsert_region_live` as the database owner. It does not insert a 311 snapshot, because Jamestown has no feed.

### 9.5 Accessibility tags and Project Sidewalk

`osm_accessibility` reads free OpenStreetMap tags `wheelchair=*`, `toilets:wheelchair=*`, and `ramp=*` (ODbL). Rows are `access_place` drops. A source may set `snapshot:` to a second seed file so those pins are not deduped against the building they describe. Jamestown uses `supabase/seed/open-data/jamestown-ny-access.sql`.

Project Sidewalk is optional and off unless the city has a published dump. The region block is:

```yaml
accessibility:
  project_sidewalk:
    enabled: false
    # url, license, attribution, and a source with adapter: project_sidewalk
    # only when this city is in the Project Sidewalk gallery.
```

Jamestown's candidate `project-sidewalk` is `reject`. The gallery does not include it. Do not invent labels.

## 10. Town Hall (agendas, hearings, permits, notices)

Meetings and permits are not drops. A region lists them under `civic:` and the importer in `supabase/scripts/import_civic.py` writes `civic_items`. The adapters are Legistar, CivicPlus AgendaCenter, Socrata, ArcGIS permits, a generic RSS/iCal feed, and a manual YAML or CSV file. Jamestown uses the manual file because the other adapters have nothing free and allowed to read there. The worked example, the robots and terms checks, and the hosted apply steps are in [`TOWN-HALL.md`](TOWN-HALL.md). Summaries are shortened public text. No language model is called.
