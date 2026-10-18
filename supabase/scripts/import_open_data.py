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

HMdb and Atlas Obscura are refused. They are link-only.

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
}
HISTORY_CATEGORIES = {"historical_fact", "story", "memorial"}
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
    for source in region["sources"]:
        adapter = source["adapter"]
        if adapter == "wikipedia_geo":
            batch = fetch_wikipedia(source, bbox, region_id)
            seen_qids.update(item["qid"] for item in batch if item.get("qid"))
        elif adapter == "wikidata_geo":
            batch = fetch_wikidata(source, bbox, region_id, skip_qids=seen_qids)
        elif adapter == "arcgis_feature":
            batch = fetch_arcgis(source, bbox, region_id)
        elif adapter == "osm_overpass":
            batch = fetch_osm(source, bbox, region_id, skip_qids=seen_qids)
        else:
            raise ImportError(f"unknown adapter {adapter}. Add it once, then reuse it from region YAML.")
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


def fetch_osm(source: dict, bbox: list[float], region_id: str, skip_qids: set[str]) -> list[dict]:
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


def osm_map_elements(source: dict, bbox: list[float]) -> list[dict]:
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
                pieces.extend(osm_map_tile(*piece, want_historic, want_heritage, tourism_ok, seen))
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
        if not tags.get("name"):
            continue
        if not osm_tags_wanted(tags, want_historic, want_heritage, tourism_ok):
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


def overpass(query: str) -> dict:
    last_error: Exception | None = None
    data = urllib.parse.urlencode({"data": query}).encode()
    for endpoint in OVERPASS_ENDPOINTS:
        try:
            return http_json(endpoint, data=data, timeout=35, retries=1)
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
        "attributes": attributes_for(category, source_url, citation),
    }
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
    if tourism == "artwork":
        return "public_art"
    if tourism == "viewpoint":
        return "viewpoint"
    if historic in {"memorial", "monument"}:
        return "memorial"
    return "historical_fact"


def dedupe(items: list[dict]) -> list[dict]:
    rank = {"nrhp": 0, "wikipedia-geo": 1, "wikidata-geo": 2, "osm-heritage": 3}
    ordered = sorted(items, key=lambda row: (rank.get(row["source_slug"], 9), row["title"].lower()))
    kept: list[dict] = []
    for row in ordered:
        qid = row.get("qid")
        if qid and any(other.get("qid") == qid for other in kept):
            continue
        if any(
            haversine_m(row["lat"], row["lng"], other["lat"], other["lng"]) <= 60
            and similar_name(row["title"], other["title"])
            for other in kept
        ):
            continue
        kept.append(row)
    return kept


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
    ]
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
    assert osm_category({"historic": "building"}) == "historical_fact"
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

    counts = validate_snapshot([nrhp, wiki, other, _osm_sample(region)], region)
    assert counts == {
        "wikipedia-geo": 1,
        "wikidata-geo": 1,
        "nrhp": 1,
        "osm-heritage": 1,
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


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except ImportError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
