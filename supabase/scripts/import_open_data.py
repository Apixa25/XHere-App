#!/usr/bin/env python3
"""Region-agnostic open-data importer for the XHere base layer.

Reads a region YAML (bounding box + sources), fetches free datasets, and
writes a JSON snapshot plus a SQL file that upserts it. Re-runs update the
same rows. No paid service, no API key.

Sources:
  wikipedia_geo   English Wikipedia geosearch (CC BY-SA 4.0). Titles and
                  coordinates only. Article text is not copied.
  wikidata_geo    Wikidata coordinate box (CC0).
  arcgis_feature  A public ArcGIS FeatureServer. The National Register of
                  Historic Places is the built-in field map (public domain).
  osm_overpass    OpenStreetMap historic / heritage / tourism points (ODbL).
  osm_historical_markers
                  OpenStreetMap plaques, historic markers, and historic
                  information boards (ODbL). Inscriptions come from OSM.
                  An HMdb website on the same object is stored as a link.
                  HMdb text is never copied.
  open311         Open311 GeoReport v2 requests.json. Only where a region
                  YAML lists an https endpoint. Request text is not copied.

HMdb and Atlas Obscura are refused. They are link-only.
A city 311 feed is imported only when that region's YAML lists one.

Examples:
  python3 supabase/scripts/import_open_data.py --self-test
  python3 supabase/scripts/import_open_data.py \\
    --region supabase/regions/us/jamestown-ny.yaml \\
    --out supabase/seed/open-data/jamestown-ny.json
  python3 supabase/scripts/import_open_data.py \\
    --check supabase/seed/open-data/jamestown-ny.json \\
    --region supabase/regions/us/jamestown-ny.yaml

Apply on a database (local or hosted). This does not change Supabase settings:

  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/open-data/jamestown-ny.sql
"""

from __future__ import annotations

import json
import math
import re
import sys
import time
import xml.etree.ElementTree as ET
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

USER_AGENT = (
    "XHereOpenDataImporter/1.0 "
    "(https://github.com/Apixa25/XHere-App; open-data seed)"
)
ROOT = Path(__file__).resolve().parents[2]
ALLOWED_CATEGORIES = {
    "historical_fact",
    "story",
    "memorial",
    "viewpoint",
    "trail_note",
    "wildlife",
    "water_source",
    "place_of_worship",
    "community_space",
    "public_art",
    "street_art",
    "infrastructure",
    "civic_issue",
    "historical_marker",
}
HISTORY_CATEGORIES = {"historical_fact", "story", "memorial", "historical_marker"}
FORBIDDEN_MARKERS = ("hmdb", "atlasobscura", "atlas-obscura")
# The public Overpass mirrors fail independently. The first one that answers is used.
# If every mirror fails, the importer falls back to the OSM map API in small tiles.
OVERPASS_ENDPOINTS = (
    "https://overpass.openstreetmap.fr/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass-api.de/api/interpreter",
)
OSM_MAP_API = "https://www.openstreetmap.org/api/0.6/map"
REQUIRED_ITEM_KEYS = (
    "source_slug",
    "source_name",
    "source_home",
    "external_id",
    "category_slug",
    "title",
    "summary",
    "lat",
    "lng",
    "source_url",
    "license",
    "attribution",
    "region_id",
    "attributes",
)


class ImportError(Exception):
    """A source, a license, or a snapshot failed a check."""


def main(argv: list[str]) -> int:
    args = argv[1:]
    if not args or "--help" in args or "-h" in args:
        print(__doc__.strip())
        return 0
    if "--self-test" in args:
        self_test()
        print("self-test ok")
        return 0
    region_path = _option(args, "--region")
    if "--check" in args:
        snapshot = Path(args[args.index("--check") + 1])
        region = load_region(Path(region_path) if region_path else _region_for(snapshot))
        items = json.loads(snapshot.read_text(encoding="utf-8"))
        counts = validate_snapshot(items, region)
        print(json.dumps({"ok": True, "counts": counts}, indent=2))
        return 0
    if not region_path:
        raise ImportError("pass --region path/to/region.yaml")
    region = load_region(Path(region_path))
    merge_id = _option(args, "--merge")
    if merge_id:
        out = Path(_option(args, "--out") or default_out(region))
        if not out.exists():
            raise ImportError(f"no snapshot to merge into: {out}")
        existing = json.loads(out.read_text(encoding="utf-8"))
        items = merge_into_snapshot(region, existing, merge_id)
        counts = validate_snapshot(items, region)
        write_snapshot(out, items)
        print(json.dumps({"wrote": str(out), "sql": str(sql_path_for(out)), "counts": counts}, indent=2))
        return 0
    write_live_seed(region)
    items = fetch_region(region)
    counts = validate_snapshot(items, region)
    out = Path(_option(args, "--out") or default_out(region))
    write_snapshot(out, items)
    print(json.dumps({"wrote": str(out), "sql": str(sql_path_for(out)), "counts": counts}, indent=2))
    return 0


def _option(args: list[str], flag: str) -> str | None:
    if flag not in args:
        return None
    index = args.index(flag)
    if index + 1 >= len(args):
        raise ImportError(f"{flag} needs a value")
    return args[index + 1]


def _region_for(snapshot: Path) -> Path:
    name = snapshot.stem
    matches = list((ROOT / "supabase" / "regions").rglob(f"{name}.yaml"))
    if len(matches) != 1:
        raise ImportError(f"pass --region for {snapshot.name}")
    return matches[0]


def default_out(region: dict) -> Path:
    return ROOT / "supabase" / "seed" / "open-data" / f"{region['region']['id']}.json"


def sql_path_for(json_path: Path) -> Path:
    return json_path.with_suffix(".sql")


# ---------------------------------------------------------------------------
# YAML (the subset the region files use). No third-party package.
# ---------------------------------------------------------------------------

def load_region(path: Path) -> dict:
    region = parse_yaml(path.read_text(encoding="utf-8"))
    if not isinstance(region, dict) or "region" not in region or "sources" not in region:
        raise ImportError(f"{path} needs region: and sources:")
    bbox = region["region"].get("bbox")
    if not (isinstance(bbox, list) and len(bbox) == 4):
        raise ImportError("region.bbox must be [west, south, east, north]")
    west, south, east, north = [float(n) for n in bbox]
    if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
        raise ImportError("region.bbox is not a west/south/east/north box")
    region["region"]["bbox"] = [west, south, east, north]
    if not region["region"].get("id"):
        raise ImportError("region.id is required")
    assert_sources_allowed(region)
    assert_live_block(region)
    return region


def assert_sources_allowed(region: dict) -> None:
    for source in region["sources"]:
        blob = " ".join(
            str(source.get(key, "")) for key in ("id", "adapter", "url", "publisher")
        ).lower()
        if any(marker in blob for marker in FORBIDDEN_MARKERS):
            raise ImportError(
                f"source {source.get('id')} is link-only (HMdb and Atlas Obscura are not imported)"
            )
        if not source.get("license") or not source.get("attribution") or not source.get("url"):
            raise ImportError(f"source {source.get('id')} needs license, attribution, and url")
        if not str(source["url"]).startswith("https://"):
            raise ImportError(f"source {source.get('id')} url must be https")
    for candidate in region.get("candidates") or []:
        decision = str(candidate.get("decision", "")).replace("_", "-").lower()
        if decision not in {"link-only", "reject", "reject-for-now"}:
            raise ImportError(
                f"candidate {candidate.get('id')} needs decision link-only or reject"
            )


def assert_live_block(region: dict) -> None:
    live = region.get("live")
    if not live:
        return
    alerts = live.get("weather_alerts") or {}
    if alerts.get("provider") not in {"nws", "none"}:
        raise ImportError("live.weather_alerts.provider must be nws or none")


def parse_yaml(text: str):
    lines = []
    for lineno, raw in enumerate(text.splitlines(), 1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        lines.append((indent, stripped, lineno))
    value, index = _parse_node(lines, 0)
    if index != len(lines):
        raise ImportError(f"yaml line {lines[index][2]}: could not parse")
    return value


def _parse_node(lines, index):
    if index >= len(lines):
        return None, index
    if lines[index][1].startswith("- "):
        return _parse_list(lines, index, lines[index][0])
    return _parse_map(lines, index, lines[index][0])


def _parse_map(lines, index, indent):
    result = {}
    while index < len(lines):
        ind, text, lineno = lines[index]
        if ind < indent or text.startswith("- "):
            break
        if ind != indent:
            raise ImportError(f"yaml line {lineno}: unexpected indent")
        key, _, rest = text.partition(":")
        key = key.strip()
        rest = rest.strip()
        if rest == "":
            if index + 1 < len(lines) and lines[index + 1][0] > indent:
                child, index = _parse_node(lines, index + 1)
            else:
                child = None
                index += 1
            result[key] = child
        else:
            result[key] = _parse_scalar(rest)
            index += 1
    return result, index


def _parse_list(lines, index, indent):
    items = []
    while index < len(lines):
        ind, text, lineno = lines[index]
        if ind < indent:
            break
        if ind != indent or not text.startswith("- "):
            break
        body = text[2:].strip()
        if ":" in body and not body.startswith(("'", '"', "[")):
            key, _, rest = body.partition(":")
            item = {}
            rest = rest.strip()
            if rest == "":
                if index + 1 < len(lines) and lines[index + 1][0] > indent:
                    child, index = _parse_node(lines, index + 1)
                else:
                    child = None
                    index += 1
                item[key.strip()] = child
            else:
                item[key.strip()] = _parse_scalar(rest)
                index += 1
            while index < len(lines) and lines[index][0] == indent + 2 and not lines[index][1].startswith("- "):
                stext = lines[index][1]
                skey, _, srest = stext.partition(":")
                srest = srest.strip()
                if srest == "":
                    if index + 1 < len(lines) and lines[index + 1][0] > indent + 2:
                        child, index = _parse_node(lines, index + 1)
                    else:
                        child = None
                        index += 1
                    item[skey.strip()] = child
                else:
                    item[skey.strip()] = _parse_scalar(srest)
                    index += 1
            items.append(item)
        else:
            items.append(_parse_scalar(body))
            index += 1
    return items, index


def _parse_scalar(text: str):
    if text in {"true", "True"}:
        return True
    if text in {"false", "False"}:
        return False
    if text in {"null", "~"}:
        return None
    if text.startswith("[") and text.endswith("]"):
        inner = text[1:-1].strip()
        if not inner:
            return []
        return [_parse_scalar(part.strip()) for part in _split_commas(inner)]
    if (text.startswith('"') and text.endswith('"')) or (text.startswith("'") and text.endswith("'")):
        return text[1:-1]
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    if re.fullmatch(r"-?\d+\.\d+", text):
        return float(text)
    return text


def _split_commas(text: str) -> list[str]:
    parts = []
    current = []
    quote = None
    for char in text:
        if quote:
            current.append(char)
            if char == quote:
                quote = None
            continue
        if char in {"'", '"'}:
            quote = char
            current.append(char)
        elif char == ",":
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if current:
        parts.append("".join(current))
    return parts


# ---------------------------------------------------------------------------
# Fetch
# ---------------------------------------------------------------------------

def fetch_region(region: dict) -> list[dict]:
    bbox = region["region"]["bbox"]
    region_id = region["region"]["id"]
    items: list[dict] = []
    seen_qids: set[str] = set()
    skip_markers = any(source["adapter"] == "osm_historical_markers" for source in region["sources"])
    for source in region["sources"]:
        batch = fetch_source(source, bbox, region_id, seen_qids, skip_markers)
        if source["adapter"] == "wikipedia_geo":
            seen_qids.update(item["qid"] for item in batch if item.get("qid"))
        print(f"{source['id']}: {len(batch)}", file=sys.stderr)
        items.extend(batch)
    deduped = dedupe(items)
    print(f"after dedupe: {len(deduped)}", file=sys.stderr)
    for item in deduped:
        item.pop("qid", None)
    return deduped


def fetch_wikipedia(source: dict, bbox: list[float], region_id: str) -> list[dict]:
    radius = min(int(source.get("radius_m") or 10000), 10000)
    language = source.get("language") or "en"
    pages: dict[int, dict] = {}
    for lat, lng in tile_centers(bbox, radius):
        continuation: dict[str, str] = {}
        while True:
            params = {
                "action": "query",
                "list": "geosearch",
                "gscoord": f"{lat}|{lng}",
                "gsradius": str(radius),
                "gslimit": "500",
                "format": "json",
            }
            params.update(continuation)
            payload = http_json(f"https://{language}.wikipedia.org/w/api.php?{urllib.parse.urlencode(params)}")
            for page in payload.get("query", {}).get("geosearch", []):
                if page.get("ns") not in (0, None):
                    continue
                title = page.get("title") or ""
                if title.lower().startswith("list of "):
                    continue
                if not inside_bbox(page["lat"], page["lon"], bbox):
                    continue
                pages[int(page["pageid"])] = page
            continuation = {k: v for k, v in (payload.get("continue") or {}).items() if isinstance(v, str)}
            if not continuation:
                break
            time.sleep(0.3)
        time.sleep(0.3)
    qids = wikipedia_qids(language, list(pages))
    items = []
    for page_id, page in pages.items():
        title = clip_title(page["title"])
        url = f"https://{language}.wikipedia.org/wiki/{urllib.parse.quote(page['title'].replace(' ', '_'))}"
        qid = qids.get(page_id)
        items.append(
            item(
                source,
                region_id,
                external_id=f"{language}wiki:{page_id}",
                title=title,
                summary="Wikipedia article about this place. Open the source for what is written there.",
                lat=page["lat"],
                lng=page["lon"],
                source_url=url,
                category=source.get("category") or "historical_fact",
                citation="Wikipedia",
                qid=qid,
            )
        )
    return items


def wikipedia_qids(language: str, page_ids: list[int]) -> dict[int, str]:
    found: dict[int, str] = {}
    for start in range(0, len(page_ids), 50):
        chunk = page_ids[start : start + 50]
        params = {
            "action": "query",
            "pageids": "|".join(str(page_id) for page_id in chunk),
            "prop": "pageprops",
            "ppprop": "wikibase_item",
            "format": "json",
        }
        payload = http_json(f"https://{language}.wikipedia.org/w/api.php?{urllib.parse.urlencode(params)}")
        for page_id, page in (payload.get("query", {}).get("pages") or {}).items():
            qid = (page.get("pageprops") or {}).get("wikibase_item")
            if qid:
                found[int(page_id)] = qid
        time.sleep(0.2)
    return found


def fetch_wikidata(source: dict, bbox: list[float], region_id: str, skip_qids: set[str]) -> list[dict]:
    west, south, east, north = bbox
    items = []
    offset = 0
    while True:
        sparql = f"""
        SELECT ?item ?itemLabel ?coord WHERE {{
          SERVICE wikibase:box {{
            ?item wdt:P625 ?coord .
            bd:serviceParam wikibase:cornerWest "Point({west} {south})"^^geo:wktLiteral .
            bd:serviceParam wikibase:cornerEast "Point({east} {north})"^^geo:wktLiteral .
          }}
          SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
        }}
        LIMIT 200 OFFSET {offset}
        """
        url = "https://query.wikidata.org/sparql?" + urllib.parse.urlencode({"query": sparql})
        payload = http_json(url, headers={"Accept": "application/sparql-results+json"})
        bindings = payload.get("results", {}).get("bindings", [])
        if not bindings:
            break
        for row in bindings:
            uri = row.get("item", {}).get("value", "")
            qid = uri.rstrip("/").split("/")[-1]
            label = (row.get("itemLabel") or {}).get("value") or ""
            if not qid.startswith("Q") or not label or label == qid or qid in skip_qids:
                continue
            point = (row.get("coord") or {}).get("value") or ""
            latlng = parse_wkt_point(point)
            if latlng is None or not inside_bbox(latlng[0], latlng[1], bbox):
                continue
            items.append(
                item(
                    source,
                    region_id,
                    external_id=f"wikidata:{qid}",
                    title=clip_title(label),
                    summary="Wikidata place record. Open the source for the structured data.",
                    lat=latlng[0],
                    lng=latlng[1],
                    source_url=f"https://www.wikidata.org/wiki/{qid}",
                    category=source.get("category") or "historical_fact",
                    citation="Wikidata",
                    qid=qid,
                )
            )
        if len(bindings) < 200:
            break
        offset += 200
        time.sleep(1)
    return items


def fetch_arcgis(source: dict, bbox: list[float], region_id: str) -> list[dict]:
    fields = source.get("fields") or {}
    for key in ("id", "title"):
        if key not in fields:
            raise ImportError(f"{source['id']} needs fields.{key}")
    layers = source.get("layers") or [0]
    west, south, east, north = bbox
    envelope = json.dumps(
        {"xmin": west, "ymin": south, "xmax": east, "ymax": north, "spatialReference": {"wkid": 4326}}
    )
    features = []
    for layer in layers:
        offset = 0
        while True:
            params = {
                "where": "1=1",
                "geometry": envelope,
                "geometryType": "esriGeometryEnvelope",
                "inSR": "4326",
                "spatialRel": "esriSpatialRelIntersects",
                "outFields": "*",
                "returnGeometry": "true",
                "outSR": "4326",
                "f": "geojson",
                "resultOffset": str(offset),
                "resultRecordCount": "200",
            }
            url = source["url"].rstrip("/") + f"/{layer}/query?{urllib.parse.urlencode(params)}"
            payload = http_json(url)
            batch = payload.get("features") or []
            features.extend(batch)
            if not payload.get("exceededTransferLimit") or not batch:
                break
            offset += len(batch)
    seen_ids: set[str] = set()
    items = []
    for feature in features:
        props = feature.get("properties") or {}
        external = str(props.get(fields["id"]) or "").strip()
        title = str(props.get(fields["title"]) or "").strip()
        if not external or not title or external in seen_ids:
            continue
        latlng = feature_point(feature.get("geometry"))
        if latlng is None or not inside_bbox(latlng[0], latlng[1], bbox):
            continue
        source_url = https_or_none(props.get(fields["url"]) if fields.get("url") else None)
        if source_url is None and external:
            source_url = f"https://npgallery.nps.gov/AssetDetail/NRIS/{urllib.parse.quote(external)}"
        if source_url is None:
            continue
        city = str(props.get(fields.get("city") or "") or "").strip()
        state = str(props.get(fields.get("state") or "") or "").strip()
        kind = str(props.get(fields.get("kind") or "") or "").strip() or "place"
        where = ", ".join(part for part in (city, state.title() if state.isupper() else state) if part)
        summary = f"{kind[:1].upper() + kind[1:]} on the National Register of Historic Places"
        if where:
            summary += f" ({where})"
        summary += ". Shown where the National Park Service published it."
        seen_ids.add(external)
        items.append(
            item(
                source,
                region_id,
                external_id=f"nrhp:{external}",
                title=clip_title(title),
                summary=summary,
                lat=latlng[0],
                lng=latlng[1],
                source_url=source_url,
                category=source.get("category") or "historical_fact",
                citation="National Register of Historic Places",
            )
        )
    return items


def fetch_source(
    source: dict,
    bbox: list[float],
    region_id: str,
    seen_qids: set[str],
    skip_markers: bool,
) -> list[dict]:
    adapter = source["adapter"]
    if adapter == "wikipedia_geo":
        return fetch_wikipedia(source, bbox, region_id)
    if adapter == "wikidata_geo":
        return fetch_wikidata(source, bbox, region_id, skip_qids=seen_qids)
    if adapter == "arcgis_feature":
        return fetch_arcgis(source, bbox, region_id)
    if adapter == "osm_overpass":
        return fetch_osm(source, bbox, region_id, skip_qids=seen_qids, skip_markers=skip_markers)
    if adapter == "osm_historical_markers":
        return fetch_historical_markers(source, bbox, region_id)
    if adapter == "open311":
        return fetch_open311(source, bbox, region_id)
    raise ImportError(f"unknown adapter {adapter}. Add it once, then reuse it from region YAML.")


def merge_into_snapshot(region: dict, existing: list, source_id: str) -> list[dict]:
    """Replace one source in an existing snapshot and leave the other rows.

    A full refetch would drift Wikipedia and the National Register. Historical
    markers are appended, and an older heritage row that is the same plaque
    (same name, memorial category) is dropped so the inscription is the one
    people see.
    """
    source = next((row for row in region["sources"] if row["id"] == source_id), None)
    if source is None:
        raise ImportError(f"unknown source {source_id}")
    bbox = region["region"]["bbox"]
    region_id = region["region"]["id"]
    skip_markers = any(row["adapter"] == "osm_historical_markers" for row in region["sources"])
    fresh = fetch_source(source, bbox, region_id, set(), skip_markers)
    for row in fresh:
        row.pop("qid", None)
    kept = [row for row in existing if row.get("source_slug") != source_id]
    if source["adapter"] == "osm_historical_markers":
        names = {normalize_name(row["title"]) for row in fresh}
        kept = [
            row for row in kept
            if not (
                row.get("source_slug") == "osm-heritage"
                and row.get("category_slug") == "memorial"
                and normalize_name(row.get("title") or "") in names
            )
        ]
    print(f"{source_id}: {len(fresh)}", file=sys.stderr)
    return kept + fresh


def fetch_osm(
    source: dict,
    bbox: list[float],
    region_id: str,
    skip_qids: set[str],
    skip_markers: bool = False,
) -> list[dict]:
    try:
        elements = overpass_elements(source, bbox)
    except ImportError as error:
        print(f"overpass unavailable ({error}); using the OSM map API", file=sys.stderr)
        elements = osm_map_elements(source, bbox)
    items = []
    seen: set[str] = set()
    for element in elements:
        tags = element.get("tags") or {}
        name = (tags.get("name") or "").strip()
        if not name:
            continue
        if skip_markers and is_historical_marker(tags):
            continue
        qid = tags.get("wikidata")
        if qid and qid in skip_qids:
            continue
        lat, lng = element_point(element)
        if lat is None or not inside_bbox(lat, lng, bbox):
            continue
        external_id = f"osm:{element['type']}/{element['id']}"
        if external_id in seen:
            continue
        seen.add(external_id)
        category = osm_category(tags)
        kind = tags.get("historic") or tags.get("tourism") or tags.get("heritage") or "place"
        items.append(
            item(
                source,
                region_id,
                external_id=external_id,
                title=clip_title(name),
                summary=f"OpenStreetMap records this as {kind.replace('_', ' ')}.",
                lat=lat,
                lng=lng,
                source_url=f"https://www.openstreetmap.org/{element['type']}/{element['id']}",
                category=category,
                citation="OpenStreetMap",
                qid=qid if isinstance(qid, str) and qid.startswith("Q") else None,
            )
        )
    return items


def fetch_historical_markers(source: dict, bbox: list[float], region_id: str) -> list[dict]:
    """Plaques, historic markers, and historic information boards from OSM.

    One Overpass query covers nodes and ways. If every mirror fails, the map
    API fallback keeps a marker that has an inscription and no name.
    """
    try:
        elements = overpass_marker_elements(bbox)
    except ImportError as error:
        print(f"overpass unavailable ({error}); using the OSM map API for markers", file=sys.stderr)
        elements = osm_map_markers(bbox)
    items = []
    seen: set[str] = set()
    for element in elements:
        external_id = f"osm:{element.get('type')}/{element.get('id')}"
        if external_id in seen:
            continue
        record = marker_record(source, region_id, element, bbox)
        if record is None:
            continue
        seen.add(external_id)
        items.append(record)
    return items


def overpass_marker_elements(bbox: list[float]) -> list[dict]:
    west, south, east, north = bbox
    box = f"({south},{west},{north},{east})"
    query = (
        "[out:json][timeout:60];("
        f'node["historic"="memorial"]["memorial"="plaque"]{box};'
        f'way["historic"="memorial"]["memorial"="plaque"]{box};'
        f'node["historic"="marker"]{box};'
        f'way["historic"="marker"]{box};'
        f'node["tourism"="information"]["information"="board"]["historic"]{box};'
        f'way["tourism"="information"]["information"="board"]["historic"]{box};'
        f'relation["historic"="memorial"]["memorial"="plaque"]{box};'
        f'relation["historic"="marker"]{box};'
        f'relation["tourism"="information"]["information"="board"]["historic"]{box};'
        ");out center tags;"
    )
    payload = overpass(query, timeout=70)
    return payload.get("elements") or []


def osm_map_markers(bbox: list[float]) -> list[dict]:
    return osm_map_elements(
        {"historic": True},
        bbox,
        require_name=False,
        accept=lambda tags: is_historical_marker(tags) and marker_title(tags, marker_inscription(tags)) is not None,
    )


def marker_record(source: dict, region_id: str, element: dict, bbox: list[float]) -> dict | None:
    tags = element.get("tags") or {}
    if not is_historical_marker(tags):
        return None
    inscription = marker_inscription(tags)
    title = marker_title(tags, inscription)
    if not title:
        return None
    lat, lng = element_point(element)
    if lat is None or lng is None or not inside_bbox(lat, lng, bbox):
        return None
    kind = marker_kind(tags)
    element_type = element.get("type")
    element_id = element.get("id")
    if not element_type or element_id is None:
        return None
    source_url = f"https://www.openstreetmap.org/{element_type}/{element_id}"
    link = hmdb_link(tags)
    attributes = attributes_for("historical_marker", source_url, "OpenStreetMap")
    attributes["marker_kind"] = kind
    if inscription:
        attributes["inscription"] = inscription
    if link:
        attributes["hmdb_url"] = link
    return item(
        source,
        region_id,
        external_id=f"osm:{element_type}/{element_id}",
        title=title,
        summary=f"OpenStreetMap records this historical marker ({kind}).",
        lat=lat,
        lng=lng,
        source_url=source_url,
        category="historical_marker",
        citation="OpenStreetMap",
        attributes=attributes,
    )


def is_historical_marker(tags: dict) -> bool:
    historic = (tags.get("historic") or "").strip()
    memorial = (tags.get("memorial") or "").strip()
    tourism = (tags.get("tourism") or "").strip()
    information = (tags.get("information") or "").strip()
    if historic == "memorial" and memorial == "plaque":
        return True
    if historic == "marker":
        return True
    return tourism == "information" and information == "board" and bool(historic)


def marker_kind(tags: dict) -> str:
    if (tags.get("historic") or "").strip() == "marker":
        return "marker"
    if (tags.get("tourism") or "").strip() == "information" and (tags.get("information") or "").strip() == "board":
        return "board"
    return "plaque"


def marker_inscription(tags: dict) -> str | None:
    raw = tags.get("inscription") or tags.get("inscription:en") or ""
    compact = " ".join(str(raw).split())
    if not compact:
        return None
    if len(compact) <= 1000:
        return compact
    return compact[:997].rstrip() + "..."


def marker_title(tags: dict, inscription: str | None) -> str | None:
    name = (tags.get("name") or tags.get("name:en") or "").strip()
    if name:
        return clip_title(name)
    if inscription:
        sentence = re.split(r"(?<=[.!?])\s", inscription, maxsplit=1)[0]
        titled = clip_title(sentence)
        if titled:
            return titled
    return {
        "marker": "Historic marker",
        "board": "Historic information board",
    }.get(marker_kind(tags), "Historical plaque")


def hmdb_link(tags: dict) -> str | None:
    for key in ("website", "url", "contact:website"):
        link = https_or_none(tags.get(key))
        if not link:
            continue
        host = urllib.parse.urlparse(link).netloc.lower()
        if host == "hmdb.org" or host.endswith(".hmdb.org"):
            return link
    return None


def overpass_elements(source: dict, bbox: list[float]) -> list[dict]:
    west, south, east, north = bbox
    box = f"({south},{west},{north},{east})"
    queries = []
    if source.get("historic", True):
        queries.append(f'node["name"]["historic"]{box};')
        queries.append(f'way["name"]["historic"]{box};')
    if source.get("heritage"):
        queries.append(f'node["name"]["heritage"]{box};')
        queries.append(f'way["name"]["heritage"]{box};')
    tourism = source.get("tourism") or []
    if tourism:
        pattern = "^(" + "|".join(re.escape(str(value)) for value in tourism) + ")$"
        queries.append(f'node["name"]["tourism"~"{pattern}"]{box};')
        queries.append(f'way["name"]["tourism"~"{pattern}"]{box};')
    elements: list[dict] = []
    for fragment in queries:
        query = f"[out:json][timeout:25];({fragment});out center tags;"
        payload = overpass(query)
        elements.extend(payload.get("elements") or [])
        time.sleep(0.4)
    return elements


def osm_map_elements(source: dict, bbox: list[float], *, require_name: bool = True, accept=None) -> list[dict]:
    """Named historic/heritage/tourism features from the OSM map API.

    One city bbox is more nodes than the API allows, so the box is walked
    in small tiles. This is the fallback when every Overpass mirror fails.
    """
    west, south, east, north = bbox
    step = 0.012
    tourism_ok = set(source.get("tourism") or [])
    want_historic = bool(source.get("historic", True))
    want_heritage = bool(source.get("heritage"))
    elements: list[dict] = []
    seen: set[str] = set()
    lat0 = south
    first = True
    while lat0 < north - 1e-9:
        lng0 = west
        lat1 = min(north, lat0 + step)
        while lng0 < east - 1e-9:
            lng1 = min(east, lng0 + step)
            if not first:
                time.sleep(1)
            first = False
            elements.extend(
                osm_map_tile(
                    lng0, lat0, lng1, lat1,
                    want_historic, want_heritage, tourism_ok, seen,
                    require_name=require_name, accept=accept,
                )
            )
            lng0 = lng1
        lat0 = lat1
    return elements


def osm_map_tile(
    west: float,
    south: float,
    east: float,
    north: float,
    want_historic: bool,
    want_heritage: bool,
    tourism_ok: set,
    seen: set[str],
    *,
    require_name: bool = True,
    accept=None,
) -> list[dict]:
    url = OSM_MAP_API + "?" + urllib.parse.urlencode({
        "bbox": f"{west},{south},{east},{north}",
    })
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/xml"})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        if error.code == 400 and b"too many nodes" in error.read().lower():
            # Split a dense tile once. The pieces stay inside the region bbox.
            mid_lng = (west + east) / 2
            mid_lat = (south + north) / 2
            pieces = []
            for piece in (
                (west, south, mid_lng, mid_lat),
                (mid_lng, south, east, mid_lat),
                (west, mid_lat, mid_lng, north),
                (mid_lng, mid_lat, east, north),
            ):
                time.sleep(1)
                pieces.extend(osm_map_tile(
                    *piece, want_historic, want_heritage, tourism_ok, seen,
                    require_name=require_name, accept=accept,
                ))
            return pieces
        raise
    root = ET.fromstring(payload)
    nodes: dict[str, tuple[float, float]] = {}
    for node in root.findall("node"):
        node_id = node.get("id")
        if node_id and node.get("lat") and node.get("lon"):
            nodes[node_id] = (float(node.get("lat")), float(node.get("lon")))
    found = []
    for element in list(root.findall("node")) + list(root.findall("way")):
        kind = element.tag
        element_id = element.get("id")
        if not element_id:
            continue
        key = f"{kind}/{element_id}"
        if key in seen:
            continue
        tags = {tag.get("k"): tag.get("v") for tag in element.findall("tag") if tag.get("k")}
        if require_name and not (tags.get("name") or "").strip():
            continue
        if accept is not None:
            if not accept(tags):
                continue
        elif not osm_tags_wanted(tags, want_historic, want_heritage, tourism_ok):
            continue
        if kind == "node":
            latlng = nodes.get(element_id)
        else:
            coords = [nodes[ref.get("ref")] for ref in element.findall("nd") if ref.get("ref") in nodes]
            latlng = (
                sum(pair[0] for pair in coords) / len(coords),
                sum(pair[1] for pair in coords) / len(coords),
            ) if coords else None
        if latlng is None:
            continue
        seen.add(key)
        found.append({
            "type": kind,
            "id": int(element_id),
            "lat": latlng[0],
            "lon": latlng[1],
            "tags": tags,
        })
    return found


def osm_tags_wanted(tags: dict, want_historic: bool, want_heritage: bool, tourism_ok: set) -> bool:
    if want_historic and tags.get("historic"):
        return True
    if want_heritage and tags.get("heritage"):
        return True
    tourism = tags.get("tourism")
    return bool(tourism_ok) and tourism in tourism_ok


def overpass(query: str, timeout: int = 35) -> dict:
    last_error: Exception | None = None
    data = urllib.parse.urlencode({"data": query}).encode()
    for endpoint in OVERPASS_ENDPOINTS:
        try:
            return http_json(endpoint, data=data, timeout=timeout, retries=1)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, urllib.error.HTTPError) as error:
            last_error = error
            time.sleep(1)
    raise ImportError(f"Overpass failed: {last_error}")


def item(
    source: dict,
    region_id: str,
    *,
    external_id: str,
    title: str,
    summary: str,
    lat: float,
    lng: float,
    source_url: str,
    category: str,
    citation: str,
    qid: str | None = None,
    attributes: dict | None = None,
    expires_at: str | None = None,
) -> dict:
    record = {
        "source_slug": source["id"],
        "source_name": source.get("publisher") or source["id"],
        "source_home": source["url"],
        "external_id": external_id,
        "category_slug": category,
        "title": title,
        "summary": summary,
        "lat": round(float(lat), 6),
        "lng": round(float(lng), 6),
        "source_url": source_url,
        "license": source["license"],
        "attribution": source["attribution"],
        "region_id": region_id,
        "attributes": attributes if attributes is not None else attributes_for(category, source_url, citation),
    }
    if expires_at:
        record["expires_at"] = expires_at
    if qid:
        record["qid"] = qid
    return record


def attributes_for(category: str, source_url: str, citation: str) -> dict:
    if category not in HISTORY_CATEGORIES:
        return {}
    attributes = {"citation": citation[:300]}
    if source_url and len(source_url) <= 500:
        attributes["source_url"] = source_url
    return attributes


def osm_category(tags: dict) -> str:
    tourism = tags.get("tourism")
    historic = tags.get("historic")
    if is_historical_marker(tags):
        return "historical_marker"
    if tourism == "artwork":
        return "public_art"
    if tourism == "viewpoint":
        return "viewpoint"
    if historic in {"memorial", "monument"}:
        return "memorial"
    return "historical_fact"


def dedupe(items: list[dict]) -> list[dict]:
    rank = {
        "nrhp": 0,
        "wikipedia-geo": 1,
        "wikidata-geo": 2,
        "osm-historical-markers": 3,
        "osm-heritage": 4,
    }
    ordered = sorted(items, key=lambda row: (rank.get(row["source_slug"], 9), row["title"].lower()))
    kept: list[dict] = []
    for row in ordered:
        qid = row.get("qid")
        if qid and any(other.get("qid") == qid for other in kept):
            continue
        if any(
            other["source_slug"] == row["source_slug"] and other["external_id"] == row["external_id"]
            for other in kept
        ):
            continue
        if any(
            haversine_m(row["lat"], row["lng"], other["lat"], other["lng"]) <= 60
            and similar_name(row["title"], other["title"])
            and not marker_kept_beside(row, other)
            for other in kept
        ):
            continue
        kept.append(row)
    return kept


def marker_kept_beside(row: dict, other: dict) -> bool:
    """A marker stays on the map even when a nearby place shares its name.

    Two plaques can also share a fallback title ("Historical plaque") without
    one of them being dropped.
    """
    return row.get("category_slug") == "historical_marker"


def similar_name(left: str, right: str) -> bool:
    a = normalize_name(left)
    b = normalize_name(right)
    if not a or not b:
        return False
    if a == b or a in b or b in a:
        return True
    tokens_a = set(a.split())
    tokens_b = set(b.split())
    if not tokens_a or not tokens_b:
        return False
    return len(tokens_a & tokens_b) / len(tokens_a | tokens_b) >= 0.6


def normalize_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", value.lower()).strip()


def clip_title(title: str) -> str:
    compact = " ".join(title.split())
    if len(compact) <= 120:
        return compact
    return compact[:117].rstrip() + "..."


def inside_bbox(lat: float, lng: float, bbox: list[float]) -> bool:
    west, south, east, north = bbox
    return west <= lng <= east and south <= lat <= north


def tile_centers(bbox: list[float], radius_m: int) -> list[tuple[float, float]]:
    west, south, east, north = bbox
    step = max((radius_m * 0.75) / 111_320, 0.02)
    centers = []
    lat = south
    while lat <= north + 1e-9:
        lng_step = step / max(math.cos(math.radians(lat)), 0.2)
        lng = west
        while lng <= east + 1e-9:
            centers.append((round(lat, 5), round(lng, 5)))
            lng += lng_step
        lat += step
    if not centers:
        centers.append((round((south + north) / 2, 5), round((west + east) / 2, 5)))
    return centers


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    radius = 6_371_000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlng / 2) ** 2
    return 2 * radius * math.asin(min(1, math.sqrt(a)))


def parse_wkt_point(value: str) -> tuple[float, float] | None:
    match = re.search(r"Point\(([-\d.]+)\s+([-\d.]+)\)", value)
    if not match:
        return None
    lng, lat = float(match.group(1)), float(match.group(2))
    return lat, lng


def feature_point(geometry: dict | None) -> tuple[float, float] | None:
    if not geometry:
        return None
    kind = geometry.get("type")
    coords = geometry.get("coordinates")
    if kind == "Point" and isinstance(coords, list) and len(coords) >= 2:
        return float(coords[1]), float(coords[0])
    ring = None
    if kind == "Polygon" and coords:
        ring = coords[0]
    elif kind == "MultiPolygon" and coords and coords[0]:
        ring = coords[0][0]
    if not ring:
        return None
    lngs = [float(pair[0]) for pair in ring]
    lats = [float(pair[1]) for pair in ring]
    return sum(lats) / len(lats), sum(lngs) / len(lngs)


def element_point(element: dict) -> tuple[float | None, float | None]:
    if "lat" in element and "lon" in element:
        return float(element["lat"]), float(element["lon"])
    center = element.get("center") or {}
    if "lat" in center and "lon" in center:
        return float(center["lat"]), float(center["lon"])
    return None, None


def https_or_none(value) -> str | None:
    if isinstance(value, str) and value.startswith("https://") and len(value) <= 500:
        return value
    return None


def http_json(url: str, data: bytes | None = None, headers: dict | None = None, timeout: int = 60, retries: int = 4) -> dict:
    request_headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(url, data=data, headers=request_headers)
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code not in {429, 502, 503, 504}:
                raise
            time.sleep(2 * (attempt + 1))
        except (urllib.error.URLError, TimeoutError) as error:
            last_error = error
            time.sleep(2 * (attempt + 1))
    raise last_error if last_error else ImportError(url)


# ---------------------------------------------------------------------------
# Open311 GeoReport v2. Optional. A region with no endpoint imports nothing.
# Request descriptions are not copied: they can name people.
# ---------------------------------------------------------------------------

def fetch_open311(source: dict, bbox: list[float], region_id: str) -> list[dict]:
    endpoint = str(source.get("endpoint") or "").rstrip("/")
    if not endpoint.startswith("https://"):
        raise ImportError(f"source {source.get('id')} open311 endpoint must be https")
    ttl = _ttl_hours(source)
    now = datetime.now(timezone.utc)
    start = (now - timedelta(hours=ttl)).strftime("%Y-%m-%dT%H:%M:%SZ")
    query = urllib.parse.urlencode({"status": "open", "start_date": start})
    payload = http_json_value(f"{endpoint}/requests.json?{query}")
    return map_open311(payload, source, bbox, region_id, now)


def map_open311(payload, source: dict, bbox: list[float], region_id: str, now: datetime) -> list[dict]:
    if isinstance(payload, dict):
        rows = payload.get("service_requests") or payload.get("requests") or []
    elif isinstance(payload, list):
        rows = payload
    else:
        raise ImportError("open311 response is not a list")
    if not isinstance(rows, list):
        raise ImportError("open311 response is not a list")
    ttl = _ttl_hours(source)
    endpoint = str(source.get("endpoint") or "").rstrip("/")
    category = source.get("category") or "civic_issue"
    found = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        if str(row.get("status") or "").lower() != "open":
            continue
        lat = _float_or_none(row.get("lat"))
        lng = _float_or_none(row.get("long") if row.get("long") is not None else row.get("lng"))
        if lat is None or lng is None or not inside_bbox(lat, lng, bbox):
            continue
        request_id = str(row.get("service_request_id") or "").strip()
        if not request_id or len(request_id) > 160:
            continue
        requested = _parse_open311_time(row.get("requested_datetime"))
        expires = (requested or now) + timedelta(hours=ttl)
        if expires <= now:
            continue
        service = str(row.get("service_name") or "Civic request").strip() or "Civic request"
        title = clip_title(service)
        quoted = urllib.parse.quote(request_id, safe="")
        source_url = f"{endpoint}/requests/{quoted}.json"
        if len(source_url) > 500 or not source_url.startswith("https://"):
            continue
        found.append(item(
            source,
            region_id,
            external_id=f"open311:{request_id}",
            title=title,
            summary="Open civic request. Status: open.",
            lat=lat,
            lng=lng,
            source_url=source_url,
            category=category,
            citation=source.get("publisher") or "Open311",
            attributes={"service": service[:80], "status": "open"},
            expires_at=expires.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        ))
    return found


def _ttl_hours(source: dict) -> int:
    raw = source.get("ttl_hours")
    try:
        hours = int(raw) if raw is not None else 168
    except (TypeError, ValueError):
        hours = 168
    return max(1, min(hours, 720))


def _float_or_none(value) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _parse_open311_time(value) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def http_json_value(url: str):
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def render_live_sql(region: dict) -> str:
    meta = region["region"]
    alerts = (region.get("live") or {}).get("weather_alerts") or {}
    provider = alerts.get("provider")
    if provider not in {"nws", "none"}:
        raise ImportError("live seed needs weather_alerts.provider nws or none")
    west, south, east, north = meta["bbox"]
    payload = {
        "region_id": meta["id"],
        "name": meta["name"],
        "country": meta["country"],
        "west": west,
        "south": south,
        "east": east,
        "north": north,
        "weather_provider": provider,
    }
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return (
        "-- Generated by supabase/scripts/import_open_data.py. Do not edit by hand.\n"
        "-- Per-region live config. Safe to re-run.\n"
        "select private.upsert_region_live(\n$xhere_live$\n"
        + body
        + "\n$xhere_live$::jsonb);\n"
    )


def write_live_seed(region: dict) -> Path | None:
    if not (region.get("live") or {}).get("weather_alerts"):
        return None
    path = ROOT / "supabase" / "seed" / "live" / f"{region['region']['id']}.sql"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_live_sql(region), encoding="utf-8")
    return path


def expected_live_catalog() -> list:
    rows = []
    for path in sorted((ROOT / "supabase" / "regions").rglob("*.yaml")):
        region = load_region(path)
        alerts = (region.get("live") or {}).get("weather_alerts")
        if not alerts:
            continue
        meta = region["region"]
        rows.append({
            "id": meta["id"],
            "country": meta["country"],
            "bbox": meta["bbox"],
            "weather_provider": alerts["provider"],
        })
    return rows


def kotlin_live_catalog() -> list:
    path = ROOT / "shared" / "src" / "commonMain" / "kotlin" / "world" / "xhere" / "shared" / "live" / "LiveRegionCatalog.kt"
    text = path.read_text(encoding="utf-8")
    start = text.index("LIVE_REGIONS_JSON_START")
    end = text.index("LIVE_REGIONS_JSON_END")
    match = re.search(r"\[.*\]", text[start:end], re.S)
    if not match:
        raise ImportError("LIVE_REGIONS_JSON markers have no JSON array")
    return json.loads(match.group(0))


# ---------------------------------------------------------------------------
# Snapshot
# ---------------------------------------------------------------------------

def validate_snapshot(items: list, region: dict) -> dict[str, int]:
    if not isinstance(items, list) or not items:
        raise ImportError("snapshot must be a non-empty list")
    bbox = region["region"]["bbox"]
    region_id = region["region"]["id"]
    configured = {source["id"]: source for source in region["sources"]}
    seen: set[tuple[str, str]] = set()
    counts: dict[str, int] = {source_id: 0 for source_id in configured}
    for index, row in enumerate(items):
        if not isinstance(row, dict):
            raise ImportError(f"item {index} is not an object")
        missing = [key for key in REQUIRED_ITEM_KEYS if key not in row or row[key] in ("", None)]
        if missing:
            raise ImportError(f"item {index} missing {', '.join(missing)}")
        if row["source_slug"] not in configured:
            raise ImportError(f"item {index} source {row['source_slug']} is not in the region config")
        if row["region_id"] != region_id:
            raise ImportError(f"item {index} region_id {row['region_id']} != {region_id}")
        if row["category_slug"] not in ALLOWED_CATEGORIES:
            raise ImportError(f"item {index} category {row['category_slug']} is not in the taxonomy")
        if not inside_bbox(float(row["lat"]), float(row["lng"]), bbox):
            raise ImportError(f"item {index} is outside the region bbox")
        if not str(row["source_url"]).startswith("https://") or not str(row["source_home"]).startswith("https://"):
            raise ImportError(f"item {index} source URL must be https")
        blob = f"{row['source_slug']} {row['source_url']} {row['external_id']}".lower()
        if any(marker in blob for marker in FORBIDDEN_MARKERS):
            raise ImportError(f"item {index} looks like HMdb or Atlas Obscura")
        source = configured[row["source_slug"]]
        if row["license"] != source["license"] or row["attribution"] != source["attribution"]:
            raise ImportError(f"item {index} license/attribution does not match the region config")
        if not (1 <= len(row["title"]) <= 120):
            raise ImportError(f"item {index} title length")
        key = (row["source_slug"], row["external_id"])
        if key in seen:
            raise ImportError(f"duplicate external id {key}")
        seen.add(key)
        counts[row["source_slug"]] += 1
    empty = [source_id for source_id, count in counts.items() if count == 0]
    if empty:
        raise ImportError("snapshot has no items from: " + ", ".join(empty))
    return counts


def write_snapshot(path: Path, items: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(items, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    payload = json.dumps(items, ensure_ascii=False, separators=(",", ":"))
    if "$xhere_open_data$" in payload:
        raise ImportError("snapshot collided with the SQL delimiter")
    sql = (
        "-- Generated by supabase/scripts/import_open_data.py. Do not edit by hand.\n"
        "-- Safe to re-run: rows upsert on (source_slug, external_id).\n"
        "-- Records missing from this snapshot for the same source and region are marked removed.\n"
        "select private.upsert_open_data_items(\n$xhere_open_data$\n"
        + payload
        + "\n$xhere_open_data$::jsonb);\n"
    )
    sql_path_for(path).write_text(sql, encoding="utf-8")


# ---------------------------------------------------------------------------
# Self-test (no network)
# ---------------------------------------------------------------------------

def self_test() -> None:
    region = load_region(ROOT / "supabase" / "regions" / "us" / "jamestown-ny.yaml")
    assert region["region"]["id"] == "us-ny-jamestown"
    assert region["region"]["bbox"] == [-79.32, 42.05, -79.18, 42.14]
    assert [source["adapter"] for source in region["sources"]] == [
        "wikipedia_geo",
        "wikidata_geo",
        "arcgis_feature",
        "osm_overpass",
        "osm_historical_markers",
    ]
    assert region["sources"][3]["id"] == "osm-heritage"
    assert region["sources"][4]["id"] == "osm-historical-markers"
    decisions = {candidate["id"]: candidate["decision"] for candidate in region["candidates"]}
    assert decisions["hmdb"] == "link-only"
    assert decisions["atlas-obscura"] == "link-only"
    try:
        assert_sources_allowed(
            {
                "sources": [
                    {
                        "id": "hmdb",
                        "adapter": "manual_upload",
                        "url": "https://www.hmdb.org/",
                        "license": "no",
                        "attribution": "no",
                    }
                ]
            }
        )
    except ImportError:
        pass
    else:
        raise AssertionError("HMdb source was accepted")

    assert osm_category({"tourism": "artwork"}) == "public_art"
    assert osm_category({"tourism": "viewpoint"}) == "viewpoint"
    assert osm_category({"historic": "memorial"}) == "memorial"
    assert osm_category({"historic": "monument"}) == "memorial"
    assert osm_category({"historic": "memorial", "memorial": "plaque"}) == "historical_marker"
    assert osm_category({"historic": "building"}) == "historical_fact"
    assert is_historical_marker({"historic": "memorial", "memorial": "plaque"})
    assert is_historical_marker({"historic": "marker"})
    assert not is_historical_marker({"historic": "memorial", "memorial": "statue"})
    assert not is_historical_marker({"tourism": "information", "information": "board"})
    assert is_historical_marker({"tourism": "information", "information": "board", "historic": "memorial"})
    assert marker_title({"historic": "memorial", "memorial": "plaque"}, None) == "Historical plaque"
    assert marker_title({"historic": "marker", "inscription": "Mill site."}, "Mill site.") == "Mill site."
    assert clip_title("x" * 200).endswith("...")
    assert len(clip_title("x" * 200)) == 120
    assert parse_wkt_point("Point(-79.24 42.10)") == (42.10, -79.24)

    nrhp = item(
        region["sources"][2],
        "us-ny-jamestown",
        external_id="nrhp:1",
        title="Fenton History Center",
        summary="Building on the National Register of Historic Places.",
        lat=42.091,
        lng=-79.239,
        source_url="https://npgallery.nps.gov/AssetDetail/NRIS/1",
        category="historical_fact",
        citation="National Register of Historic Places",
    )
    wiki = item(
        region["sources"][0],
        "us-ny-jamestown",
        external_id="enwiki:9",
        title="Fenton History Center",
        summary="Wikipedia article about this place. Open the source for what is written there.",
        lat=42.0911,
        lng=-79.2391,
        source_url="https://en.wikipedia.org/wiki/Fenton_History_Center",
        category="historical_fact",
        citation="Wikipedia",
        qid="Q1",
    )
    other = item(
        region["sources"][1],
        "us-ny-jamestown",
        external_id="wikidata:Q2",
        title="Chadakoin River",
        summary="Wikidata place record. Open the source for the structured data.",
        lat=42.094,
        lng=-79.240,
        source_url="https://www.wikidata.org/wiki/Q2",
        category="historical_fact",
        citation="Wikidata",
        qid="Q2",
    )
    kept = dedupe([wiki, nrhp, other])
    assert [row["external_id"] for row in kept] == ["nrhp:1", "wikidata:Q2"]

    plaque = _marker_sample(region)
    assert plaque["summary"] == "OpenStreetMap records this historical marker (plaque)."
    assert "hmdb" not in plaque["summary"].lower()
    assert "Copied HMDB" not in json.dumps(plaque)
    assert plaque["attributes"]["inscription"] == "Dedicated in 1892 to Reuben Fenton."
    assert plaque["attributes"]["hmdb_url"] == "https://www.hmdb.org/m.asp?m=1"
    assert "description" not in plaque["attributes"]
    beside = dedupe([nrhp, plaque])
    assert {row["external_id"] for row in beside} == {"nrhp:1", "osm:node/99"}

    counts = validate_snapshot([nrhp, wiki, other, _osm_sample(region), plaque], region)
    assert counts == {
        "wikipedia-geo": 1,
        "wikidata-geo": 1,
        "nrhp": 1,
        "osm-heritage": 1,
        "osm-historical-markers": 1,
    }
    try:
        validate_snapshot([nrhp, wiki, other], region)
    except ImportError as error:
        assert "osm-heritage" in str(error)
    else:
        raise AssertionError("incomplete snapshot was accepted")
    outside = dict(nrhp)
    outside["lat"] = 0
    outside["lng"] = 0
    try:
        validate_snapshot([outside, other, _osm_sample(region), wiki], region)
    except ImportError as error:
        assert "outside" in str(error)
    else:
        raise AssertionError("out-of-bbox item was accepted")

    decisions = {candidate["id"]: candidate["decision"] for candidate in region["candidates"]}
    assert decisions["jamestown-311"] == "reject"
    reason = next(candidate["reason"] for candidate in region["candidates"] if candidate["id"] == "jamestown-311")
    assert "Open311" in reason
    assert "open311" not in [source["adapter"] for source in region["sources"]]
    assert region["live"]["weather_alerts"]["provider"] == "nws"
    seed = ROOT / "supabase" / "seed" / "live" / "us-ny-jamestown.sql"
    assert seed.read_text(encoding="utf-8") == render_live_sql(region)
    assert kotlin_live_catalog() == expected_live_catalog()

    now = datetime(2026, 10, 18, 16, 0, tzinfo=timezone.utc)
    feed = {
        "id": "open311-example",
        "adapter": "open311",
        "publisher": "Example city",
        "url": "https://example.com/open311",
        "endpoint": "https://example.com/open311",
        "license": "CC0 1.0",
        "attribution": "Example city open data",
        "category": "civic_issue",
        "ttl_hours": 168,
    }
    mapped = map_open311(
        [
            {
                "service_request_id": "9",
                "status": "open",
                "service_name": "Sidewalk",
                "description": "Alice lives at 1 Main",
                "lat": 42.09,
                "long": -79.24,
                "requested_datetime": "2026-10-18T12:00:00Z",
            },
            {
                "service_request_id": "closed",
                "status": "closed",
                "service_name": "Closed",
                "lat": 42.09,
                "long": -79.24,
                "requested_datetime": "2026-10-18T12:00:00Z",
            },
            {
                "service_request_id": "out",
                "status": "open",
                "service_name": "Far away",
                "lat": 0,
                "long": 0,
                "requested_datetime": "2026-10-18T12:00:00Z",
            },
            {
                "service_request_id": "old",
                "status": "open",
                "service_name": "Old",
                "lat": 42.09,
                "long": -79.24,
                "requested_datetime": "2020-01-01T00:00:00Z",
            },
            {
                "service_request_id": "nocoord",
                "status": "open",
                "service_name": "Missing",
                "requested_datetime": "2026-10-18T12:00:00Z",
            },
        ],
        feed,
        region["region"]["bbox"],
        "us-ny-jamestown",
        now,
    )
    assert [row["external_id"] for row in mapped] == ["open311:9"]
    blob = json.dumps(mapped)
    assert "Alice" not in blob
    assert mapped[0]["summary"] == "Open civic request. Status: open."
    assert mapped[0]["attributes"] == {"service": "Sidewalk", "status": "open"}
    assert mapped[0]["expires_at"] == "2026-10-25T12:00:00Z"
    assert mapped[0]["category_slug"] == "civic_issue"


def _osm_sample(region: dict) -> dict:
    return item(
        region["sources"][3],
        "us-ny-jamestown",
        external_id="osm:node/1",
        title="A viewpoint",
        summary="OpenStreetMap records this as viewpoint.",
        lat=42.10,
        lng=-79.25,
        source_url="https://www.openstreetmap.org/node/1",
        category="viewpoint",
        citation="OpenStreetMap",
    )


def _marker_sample(region: dict) -> dict:
    record = marker_record(
        region["sources"][4],
        "us-ny-jamestown",
        {
            "type": "node",
            "id": 99,
            "lat": 42.09105,
            "lon": -79.23905,
            "tags": {
                "historic": "memorial",
                "memorial": "plaque",
                "name": "Fenton History Center",
                "inscription": "Dedicated in 1892 to Reuben Fenton.",
                "description": "Copied HMDB text must not land in the summary.",
                "website": "https://www.hmdb.org/m.asp?m=1",
            },
        },
        region["region"]["bbox"],
    )
    if record is None:
        raise AssertionError("marker sample was dropped")
    return record


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except ImportError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
