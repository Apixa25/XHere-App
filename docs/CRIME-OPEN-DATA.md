# Worked example: Safety / "problem property" open data for Jamestown NY & Chautauqua County (pilot)

*Researched 2026-10-18, ~12:50–1:30 AM ET. This is the pilot instance of `OPEN-DATA-PLAYBOOK.md`: same checklist, same contract, same display rules. Nothing here is Jamestown-specific code, only region config.*

**The agreed approach (Steven):**
- Official public records about places can be pinned at exact addresses, with source links.
- User reports only roll up into block-level **area activity**, with independent-report thresholds and moderator escalation.
- Every Safety screen links to the **police anonymous tip line**.

**Bottom line for this region:**
- No source publishes "trap houses" or address-level drug-activity data.
- The address-level official data that does exist covers condemnations, code enforcement, the DEA lab register, and city press statements. Most of it lacks an API or open licence.
- Usable now for free: state statistics (data.ny.gov) and the DEA register (manual CSV). Also the city's RSS feed (link cards), county parcels for geocoding, and the tip line.

---

## 1. Source inventory

Legend for **Precision**: `address` = exact street address; `block`; `area` = vague ("north side"); `agency` = totals only; `person` = person-level, no address.

| # | Source | URL | Data fields | Precision | Update | Format / API | License / terms (automated access?) | Cost | Decision |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **JPD anonymous tip line** | https://www.jamestownny.gov/police-department/anonymous-tip-line/ | Tip line **(716) 483-8477**, non-emergency (716) 483-7536, email info@jpdny.com, Facebook | n/a | static | HTML | Public info | Free | ✅ **Use**: `tip_lines` in the region config, shown on all Safety UI |
| 2 | JPD annual reports | https://www.jamestownny.gov/police-department/annual-reports-statistics/ | Part I offenses by type, 2020–2025 (2025: 1,059 Part I, 1,792 IBR arrests), density *maps* (images), accidents | agency (+ density images) | yearly (Feb) | PDF | City website; no open licence stated | Free | ✅ **Use as stat card** (manually transcribed numbers + link). Density maps: link only |
| 3 | City news / press releases (incl. JPD) | https://www.jamestownny.gov/category/newsmedia/ · **RSS: https://www.jamestownny.gov/category/newsmedia/feed/** | Title, date, body (some name specific problem properties, e.g. the 1061 Allen St condemnation, 2022) | mixed (sometimes address) | ad hoc | **RSS (WordPress)** | Public govt communications; no explicit licence. Using the feed is fine. Show headline + link, not full text | Free | ✅ **Use**: `rss_atom` link cards in the region "News" list. Address-specific official statements can be curated by a moderator into an official pin linking to the release |
| 4 | Post-Journal "Area Police Reports" | https://www.post-journal.com/news/police-reports/ | Daily arrests: names, ages, charges, vague location ("north side") | person / area | daily | HTML | Copyrighted news | Free to read | ❌ **No ingest** (person-level, copyright). Link-out at most |
| 5 | CrimeMapping.com / LexisNexis Community Crime Map / CityProtect | https://www.crimemapping.com · https://communitycrimemap.com · https://cityprotect.com | Incidents (where the agency opts in) | block | daily | Web map only | Vendor terms; no redistribution | Free to view | ❌ **No evidence JPD or the Sheriff participates** (searches found none; CityProtect showed no agencies). Re-check yearly |
| 6 | SpotCrime | https://spotcrime.com/NY/Jamestown · API https://spotcrime.io | Incident type, datetime, hundred-block/intersection (Jamestown entries exist, e.g. "200 block of Foote Ave," Jul 2026, plus test/junk rows) | block | varies | Paid REST API | Licence forbids sublicensing/redistribution and AI/LLM use without a separate deal; 90-day window | **$199/mo** (Developer), $599/mo (Pro) | ⛔ **Cost item, not recommended now.** Sparse local coverage, plus data quality issues |
| 7 | City Code Enforcement (GovPilot) | https://www.jamestownny.gov/department-of-development/code-enforcement/ · map https://map.govpilot.com/map/NY/jamestowncityny | Parcels, **reported non-emergency concerns and code-enforcement activity** (per the city), vacant/abandoned reports | address/parcel | live | Interactive web map (JS app); **no documented public API or open licence** | GovPilot/city terms unclear. **Don't scrape** | Free to view | 🟡 **Ask the source.** Request a periodic export (CSV) or permission to use the map's data feed. Code enforcement contact: dod@jamestownny.gov, (716) 483-7541. Highest-value official address-level source |
| 8 | Public Nuisance law (Ch. 214) | ecode360 (City of Jamestown Code) | Premises with ≥3 qualifying arrests in 24 months (DV assaults excluded) can be declared a public nuisance (abatement/closure/receiver) | address (if published) | ad hoc | No public list found | Public records via FOIL | Free | 🟡 **FOIL request** for designated Ch. 214 properties + condemned/vacant registries: City Clerk FOIL https://www.jamestownny.gov/city-clerk/foil/ (NextRequest portal cityofjamestownny.nextrequest.com). This is the official "problem property" source, so it's the one to pin |
| 9 | Ch. 215 Property Maintenance / Ch. 219 QOL tickets / Vacant Property Registration (2025) | ecode360 + city site | Violations, tickets, registered vacant properties | address | — | No public dataset | FOIL | Free | 🟡 Same FOIL/data request as #7–8 |
| 10 | **Chautauqua County GIS parcels** | https://maps.chautauquacounty.com/server/rest/services/ParcelViewer/ViewerOperational/MapServer/151 · NYS https://gis.ny.gov/parcels | Parcel ID, location address, municipality, property class, assessed values, **owner names** | parcel | periodic | **ArcGIS REST** (`query`, max 1,000/page) | Public GIS. No explicit open licence found; check the county GIS disclaimer. The county also offers a bulk download (see its "GIS Download Instructions" page) | Free | ✅ **Use for geocoding only** (address → parcel point). **Drop owner name fields** at ingest |
| 11 | Chautauqua County Sheriff: bookings / wanted | https://sheriff.chautauquacounty.com/ccj.aspx · /search.aspx · https://www.sheriff.us | Names, age, booking date, charge category, bail | person | live | HTML | Person-level | Free | ❌ **Excluded** (person-level, no addresses, accusation risk) |
| 12 | County news (Sheriff releases) | https://chqgov.com/news (Media Information Officer) | Press releases | mixed | ad hoc | HTML (check for RSS) | Govt comms | Free | ✅ Link cards (same as #3) if a feed exists |
| 13 | **NY Index Crimes by County and Agency** (DCJS) | https://data.ny.gov/d/ca8h-8gjq · API `https://data.ny.gov/resource/ca8h-8gjq.json` | county, agency, year, months_reported, total_index_crimes, violent, murder, forcible_rape, robbery, aggravated_assault, property, burglary, larceny, motor_vehicle_theft (e.g. **Jamestown City PD 2024: 840 index crimes, 192 violent**) | agency | yearly (prelim spring, final fall) | **Socrata SODA API**, CSV/JSON | data.ny.gov Terms of Use (open data; API use intended) | Free (app token optional) | ✅ **Use now**: stat cards for every NY agency in the region (Jamestown, Sheriff, Dunkirk, Fredonia, Lakewood-Busti, Ellicott…) |
| 14 | NY Adult Arrests by County (DCJS) | https://data.ny.gov/d/rikd-mt35 | county, year, total, felony/misdemeanor splits (drug, violent, DWI, property, other) | county | yearly (updated Sep 2026) | Socrata API | data.ny.gov ToU | Free | ✅ Use: county stat card (drug-arrest trend is relevant context) |
| 15 | FBI Crime Data Explorer | https://cde.ucr.cjis.gov (API via api.data.gov) | Agency-level NIBRS/UCR | agency | yearly | REST API (free key) | Public | Free | 🟡 Optional (duplicates #13; useful for the global/US template) |
| 16 | NY courts: WebCrims / eCourts / NYSCEF | https://iapps.courts.state.ny.us/webcrim_attorney/ | Case-level, person-level | person | live | Web | **Terms prohibit automated extraction** and mining/pay-for-use apps | Free | ❌ **Excluded** |
| 17 | **DEA National Clandestine Laboratory Register** | https://www.dea.gov/clan-lab | State, County, City, **Address**, Date of incident (labs/dumpsites reported by law enforcement) | **address** | irregular | Web table + **CSV download** (filter by state/year). **Automated requests are blocked** (Akamai "Access Denied" from the box) | US govt data. DEA conditions of use: unverified, may be remediated, DEA disclaims liability | Free | ✅ **Use via `manual_upload`**: an operator downloads the NY CSV quarterly through the normal site UI. Pin **only Chautauqua entries** with the DEA disclaimer. The default NY view returned no rows, so check each year. Few or no local entries is expected |
| 18 | NYS Sex Offender Registry | NY DCJS | Person-level | person | — | — | NY Correction Law §168 restrictions | — | ❌ **Excluded globally** (person-level) |

---

## 2. Legal considerations for this pilot (NY / US profile)

- **Defamation:** labelling a property a "trap house" is a factual accusation about its occupants and owner.
  - XHere **never generates that label**.
  - Official records are shown in neutral, templated language *as published* ("Condemned by City of Jamestown, {date}", "Listed in DEA register, {date}"), with a link. That keeps them within **NY Civil Rights Law §74** (fair and true report of official proceedings) and makes them accurate and attributable.
  - **§230** covers user reports XHere hosts, but **not** text XHere writes. Templates must stay neutral.
  - **NY anti-SLAPP (Civ. Rights Law §76-a, 2020)** gives added protection for public-interest speech, but don't design around litigation.
- **Privacy/records:**
  - No names or mugshots. NY Public Officers Law §89(2)(b)(viii) treats agency release of booking photos as an unwarranted privacy invasion.
  - Respect sealing. Under NY's **Clean Slate Act** (effective 2024-11-16), convictions are sealed automatically, so retention limits matter. Person-level data is excluded anyway.
- **Fair housing:** no neighborhood "danger scores," no descriptions of people, and moderator review of anything that reads as profiling.
- **Nuisance-law interplay:** NY/HUD guidance warns that nuisance ordinances can punish domestic-violence victims who call police. Jamestown Ch. 214 excludes DV assaults. XHere must **never** count 911 calls or police visits from user reports toward anything that looks like a designation.
- **App stores:**
  - Apple **1.7**: reporting alleged criminal activity requires local law-enforcement involvement. We satisfy this by sending crime info to the **JPD tip line**, and user reports are "area activity" awareness only.
  - Apple **1.1.1/1.2** and Google Play UGC: filtering, reporting, blocking, published contact info, moderation (M7).
- **Terms:** WebCrims, vendor crime maps, and news sites can't be ingested. GovPilot data can't be used without permission. The DEA site blocks bots, so it gets a manual download only.

---

## 3. Recommended design: "Official records" Safety layer (pilot config `us-ny-chautauqua`)

**Ingestion** (Supabase Edge Functions + pg_cron, per the playbook §2):

| Source | Adapter | Refresh | Category slug | Display |
|---|---|---|---|---|
| data.ny.gov Index Crimes (#13) | `socrata` | monthly check (data changes spring/fall) | `safety.crime_stats` | **Stat card** per agency area (city polygon for Jamestown PD; county for Sheriff) with trend + link |
| data.ny.gov Adult Arrests (#14) | `socrata` | monthly check | `safety.crime_stats` | County stat card |
| JPD annual report (#2) | `manual_upload` (numbers JSON) | yearly | `safety.crime_stats` | Stat card + PDF link |
| DEA register (#17) | `manual_upload` (CSV) → parcel geocoder | quarterly + overdue alert | `safety.drug_lab_site` | **Exact pin**, "Official · DEA" badge, incident date, DEA disclaimer, link |
| City/County news RSS (#3, #12) | `rss_atom` | hourly | `news.official` | Link cards in the region News list. Moderators may promote an address-specific official statement (condemnation etc.) to an `property.condemned` pin with the release link |
| Code enforcement / Ch. 214 / condemned / vacant (#7–9) | `csv_bulk` or `arcgis_feature` **once the city provides an export or permission** | per agreement (weekly) | `property.code_violation`, `property.condemned`, `property.public_nuisance` | **Exact pin**, neutral template ("Public nuisance designation under City Code Ch. 214, {date}"), status, link. No owner names |
| County parcels (#10) | geocoder | monthly cache refresh | — | Not displayed (geocoding only) |

**Display** (playbook §5):
- **Official pins:** only for address-published official records (DEA, city designations/condemnations once obtained).
- **Stat cards:** for agency/county totals.
- **No official incident points** exist for this region today.
- **User reports** (Safety → "Suspicious/problem activity in this area"):
  - Picked in the quick-drop flow (the AI suggests the category).
  - The app immediately shows the **JPD tip line** (call/email) as the primary action.
  - The report is stored privately, then rolled up to an **H3 res-9 cell / street block**.
  - Public "area activity" appears only when **≥3 independent reporters within 30 days**.
  - **≥5, or any mention of weapons or children, escalates to the moderator queue.** Moderators may also forward the info to JPD via the tip line. No automatic forwarding, since that sends data to a third party.
  - Area activity decays after 30 days. Free text is never public.
  - An address typed in a report is stripped by the PII filter.
- **Map filter:** a "Safety (official)" layer toggle separate from "Area activity (community)," per PLAN §4.4.

**Disclaimers:** use the playbook's §4.1 standard strings, plus the DEA-specific one. Every Safety screen footer shows "Have information about a crime? Jamestown Police anonymous tip line (716) 483-8477 · Emergency 911."

**Fit with PLAN.md:** this is the **M16b** pilot (Safety official layer) and **M16c** (area activity), built on the M10 framework and the M7 moderation work. See the playbook §7. Before M16b starts, Steven sends two free asks:
1. A FOIL/data request to the City Clerk for Ch. 214 designations and condemned/vacant registries.
2. A request to Code Enforcement for a GovPilot export or permission.

Both need Steven's OK because they're outbound messages (drafts below).

### Draft asks (for Steven's approval; nothing sent)
- **To:** City of Jamestown Records Access Officer (clerk@jamestownny.gov or the NextRequest portal). **Subject:** FOIL request: public nuisance designations and condemned/vacant property lists
  > Under NY FOIL, I request electronic copies (CSV or spreadsheet preferred) of: (1) properties declared public nuisances under City Code Chapter 214 since January 1, 2023, with address, date, and current status; (2) the current list of condemned properties (address, date condemned, status); (3) the Vacant Property Registration list (address, registration date). No personal information beyond property addresses is requested. Thank you. Steven Sills II
- **To:** Department of Development, Code Enforcement (dod@jamestownny.gov). **Subject:** Open-data request: code enforcement activity
  > I'm building XHere, a free community map app piloting in Jamestown. Would the City be willing to provide a periodic export (or permission to use the GovPilot public map feed) of code-enforcement cases (address, violation type, status, dates)? We'd display them with attribution and a link to the City, and without owner names. Steven Sills II
