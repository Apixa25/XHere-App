# Time Machine and This day here

XHere is global. Jamestown, New York is the pilot. A new town is a `history:` block in its region file (`supabase/regions/`), not a change to the app. This follows PLAN.md (the open-data layers and the rule that the phone does not keep a trail of where someone stood) and `docs/research/LOCATION-APPS-RESEARCH.md` §4.2 (seed a region from free sources, including Wikimedia Commons photos when the license allows it). `docs/OPEN-DATA-PLAYBOOK.md` is the provenance rule: config, a free API, a license line, no paid feed, no scraping.

The phone reads a cache. It does not call loc.gov or Wikimedia.

## What you see

**Time Machine** is a button on the Here Card and a button on the map. The map button uses the center of the map. The sheet has the years that the cache holds for that town. Sanborn fire-insurance maps from the Library of Congress open in a deep-zoom viewer (IIIF tiles from `tile.loc.gov`). A sheet is drawn on the map, with an opacity slider, only when that sheet has at least three surveyed control points and the corners land in a sensible area.

**This day here** is on the Here Card. It shows up to three cached newspaper lines whose terms overlap the street or the place name. A line from this calendar month and day is listed first. Each line links to the full page at the Library of Congress. The paper has to be from the region's state. If the paper was printed in another city, the town name has to sit near the street. Jamestown, North Dakota does not appear on a New York card.

## What is cached for Jamestown

`supabase/seed/history/jamestown-ny.json` was built from the free APIs on 2026-10-19.

- Sanborn volumes for Jamestown, New York: digitized sheets for 1886 (15), 1891 (21), 1896 (36), and 1902 (67), plus the undigitized volumes for 1918, 1921, 1923, 1930, 1949, and 1973. Undigitized volumes open on loc.gov. They have no viewer image.
- Library of Congress photos whose location text is Jamestown, New York, including "Third Street, Jamestown, N.Y." (1905). Most of these have no coordinates, so they show for any tap inside the town box.
- Up to eight Wikimedia Commons photos inside 600 m of the town center, and only when the license is public domain, CC0, or CC BY (including BY-SA and the non-commercial variants the filter allows). Commons is not a curated historic set. A recent photo can appear, with its year and license.
- Two Chronicling America lines for "Main Street" from the New-York tribune, both of which say Jamestown, N. Y. next to that street (1905-07-16 and 1918-05-14). There is no Jamestown, New York paper in Chronicling America. Pages that mention the street in one story and the town in another are left out.

## What is not on the map yet

The 1886 sheet `sanborn06011_001-2` (IIIF `…/06011_1886-0002`, native 6450×7650, item `https://www.loc.gov/resource/g3804jm.g3804jm_g060111886/?sp=2`) is the sheet to line up first. It has a small inset at the top left. Control points belong on the main map only.

OpenStreetMap intersections measured for that sheet, for whoever records the pixels:

| Corner | Latitude | Longitude |
|---|---|---|
| North Main and East 7th | 42.099927 | -79.241835 |
| North Main and East 6th | 42.098959 | -79.242051 |
| North Main and East 8th | 42.100892 | -79.241513 |
| Cherry and West 6th | 42.098756 | -79.243297 |
| Pine and East 6th | 42.099168 | -79.240839 |
| East 6th and Spring | 42.099351 | -79.239696 |

Those are guides, not pixels. A guessed pixel would put the sheet on the wrong streets, so the seed has no overlay. The in-app demo alignment used by UI tests is labeled "Demo alignment" and is not in the SQL seed.

Record a point, then rebuild the seed:

```bash
python3 supabase/scripts/georef_control_points.py \
  --file supabase/regions/us/jamestown-ny.georef.json \
  --sheet sanborn06011_001-2 \
  --pixel-x 1200 --pixel-y 3400 \
  --longitude -79.241835 --latitude 42.099927 \
  --label "North Main and East 7th"
```

Three points turn the overlay on the next import. The app still checks the fit (mean residual at most 80 m, corner-to-corner span 30 m to 8 km) and hides a bad one.

## Refresh

```bash
python3 supabase/scripts/import_history.py \
  --region supabase/regions/us/jamestown-ny.yaml \
  --out supabase/seed/history/jamestown-ny.json \
  --sql supabase/seed/history/jamestown-ny.sql
```

The script sleeps between loc.gov calls. `--self-test` and `--check` do not use the network.

The Edge Function `history` is a later refresh door. It is not deployed from here. A cache younger than a day returns without a fetch. Otherwise it takes one slot (two seconds between fetches) and reads the Sanborn collection once. Sheet rows already in the cache stay. See `supabase/README.md`.
