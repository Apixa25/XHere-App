#!/usr/bin/env python3
"""Fill the Time Machine cache from free Library of Congress and Wikimedia APIs.

The phone never calls those APIs. This script writes a JSON snapshot and a SQL
seed that calls private.upsert_history. Another town is another region file
with a history: block. Nothing here is Jamestown-only.

    python3 supabase/scripts/import_history.py --self-test
    python3 supabase/scripts/import_history.py \\
        --region supabase/regions/us/jamestown-ny.yaml \\
        --out supabase/seed/history/jamestown-ny.json \\
        --sql supabase/seed/history/jamestown-ny.sql
    python3 supabase/scripts/import_history.py \\
        --check supabase/seed/history/jamestown-ny.json \\
        --region supabase/regions/us/jamestown-ny.yaml

Live fetches sleep between calls. --self-test and --check do not use the network.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from import_open_data import load_region  # noqa: E402

USER_AGENT = "XHereHistoryImporter/1.0 (https://xhere.world; history research)"
PAUSE_S = 1.1
SANBORN_ATTRIBUTION = (
    "Library of Congress, Geography and Map Division, Sanborn Maps Collection. Public domain."
)
PAPER_ATTRIBUTION = "Chronicling America, Library of Congress. Public domain."
LOC_PHOTO_ATTRIBUTION = (
    "Library of Congress. Public domain or no known restrictions. Open the item page for the rights statement."
)
GENERIC = {
    "street", "st", "road", "rd", "avenue", "ave", "boulevard", "blvd",
    "drive", "dr", "lane", "ln", "place", "court", "way", "park",
    "the", "and", "of", "new", "york", "ny", "usa", "united", "states",
}
ORDINALS = {
    "1st": "first", "2nd": "second", "3rd": "third", "4th": "fourth",
    "5th": "fifth", "6th": "sixth", "7th": "seventh", "8th": "eighth", "9th": "ninth",
    "first": "1st", "second": "2nd", "third": "3rd", "fourth": "4th",
    "fifth": "5th", "sixth": "6th", "seventh": "7th", "eighth": "8th", "ninth": "9th",
}


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        self_test()
        print("history self-test ok")
        return 0
    region_path = _arg(argv, "--region")
    if "--check" in argv:
        check(Path(_arg(argv, "--check")), Path(region_path))
        print("history snapshot matches the region file")
        return 0
    out = Path(_arg(argv, "--out"))
    sql = Path(_arg(argv, "--sql"))
    doc = build_live(load_region(Path(region_path)), Path(region_path))
    out.parent.mkdir(parents=True, exist_ok=True)
    sql.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    sql.write_text(to_sql(doc), encoding="utf-8")
    print(
        f"wrote {out} sheets={len(doc['sheets'])} photos={len(doc['photos'])} "
        f"clippings={len(doc['clippings'])} georef={len(doc['georef'])}"
    )
    return 0


def build_live(region: dict, region_path: Path) -> dict:
    history = region.get("history") or {}
    town = str(history.get("town") or "").strip()
    state = str(history.get("state") or "").strip()
    if not town or not state:
        raise SystemExit("history.town and history.state are required")
    doc = empty_doc(region, town, state)
    sanborn = history.get("sanborn") or {}
    locations = [str(part) for part in (sanborn.get("location") or [town, state])]
    volumes = fetch_json(sanborn_url(locations, 40)).get("results") or []
    doc["sheets"].extend(volumes_to_sheets(volumes, town, state))
    for volume in volumes:
        if volume.get("digitized") is not True:
            continue
        search = segment_search(volume)
        if not search:
            continue
        next_url = search
        pages = 0
        while next_url and pages < 3:
            time.sleep(PAUSE_S)
            page = fetch_json(next_url)
            doc["sheets"].extend(sheets_from_page(page, volume))
            pages += 1
            nxt = (page.get("pagination") or {}).get("next")
            next_url = nxt if isinstance(nxt, str) and nxt.startswith("https://") else None
    photos = history.get("photos") or {}
    loc = photos.get("loc") or {}
    if loc:
        time.sleep(PAUSE_S)
        limit = int(loc.get("limit") or 24)
        found = fetch_json(photo_url(town, state, limit)).get("results") or []
        doc["photos"].extend(photos_from_loc(found, town, state, limit))
    commons = photos.get("commons") or {}
    if commons.get("enabled") is True:
        time.sleep(PAUSE_S)
        west, south, east, north = region["region"]["bbox"]
        doc["photos"].extend(
            photos_from_commons(
                (south + north) / 2,
                (west + east) / 2,
                int(commons.get("radius_m") or 600),
                int(commons.get("limit") or 8),
            )
        )
    papers = history.get("newspapers") or {}
    for query in papers.get("seed_queries") or []:
        time.sleep(PAUSE_S)
        try:
            results = fetch_json(paper_search(str(query), town, state)).get("results") or []
        except Exception as error:
            print(f"newspaper search skipped for {query}: {error}", file=sys.stderr)
            continue
        added = 0
        for result in results:
            if added >= 2 or len(doc["clippings"]) >= 12:
                break
            time.sleep(PAUSE_S)
            text = ocr_text(result)
            clipping = clipping_from(result, str(query), text, town, state)
            if clipping is None:
                continue
            doc["clippings"].append(clipping)
            added += 1
    doc["georef"] = load_georef(region_path.with_suffix(".georef.json"), doc["sheets"])
    return doc


def empty_doc(region: dict, town: str, state: str) -> dict:
    west, south, east, north = region["region"]["bbox"]
    return {
        "region": {
            "region_id": region["region"]["id"],
            "name": region["region"]["name"],
            "country": region["region"]["country"],
            "town": town,
            "state": state,
            "west": west,
            "south": south,
            "east": east,
            "north": north,
        },
        "sheets": [],
        "photos": [],
        "georef": [],
        "clippings": [],
    }


def sanborn_url(locations: list[str], count: int) -> str:
    fa = "|".join("location:" + loc_token(part) for part in locations)
    return (
        "https://www.loc.gov/collections/sanborn-maps/?fa="
        + urllib_quote(fa, safe=":|+")
        + f"&fo=json&c={count}"
    )


def photo_url(town: str, state: str, limit: int) -> str:
    return (
        "https://www.loc.gov/photos/?q="
        + urllib_quote(town)
        + "&fa=location:"
        + urllib_quote(loc_token(state), safe="+")
        + f"&fo=json&c={limit}"
    )


def loc_token(value: str) -> str:
    return re.sub(r"\s+", "+", value.strip().lower())


def urllib_quote(value: str, safe: str = "") -> str:
    from urllib.parse import quote

    return quote(value, safe=safe)


def fetch_json(url: str) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict):
        raise RuntimeError("loc.gov did not return an object")
    return payload


def volumes_to_sheets(results: list, town: str, state: str) -> list[dict]:
    sheets = []
    for item in results:
        if not covers(item, town, state):
            continue
        if item.get("digitized") is True:
            continue
        external = external_id(item.get("id") or item.get("url") or "")
        url = https(item.get("url"))
        if not external or not url:
            continue
        date = str(item.get("date") or "")
        sheets.append(
            sheet_row(
                external,
                str(item.get("title") or "Sanborn map").strip(),
                year_of(date),
                date,
                url,
                None,
                None,
                None,
                False,
            )
        )
    return sheets


def segment_search(volume: dict) -> str | None:
    from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

    resources = volume.get("resources") or []
    if not resources:
        return None
    search = resources[0].get("search")
    if not isinstance(search, str) or "segmentof:" not in search:
        return None
    parts = urlsplit(search)
    query = [(key, value) for key, value in parse_qsl(parts.query) if key not in {"c", "sp"}]
    query.extend([("fo", "json"), ("c", "100")])
    return urlunsplit(parts._replace(query=urlencode(query, safe=":+")))


def sheets_from_page(page: dict, volume: dict) -> list[dict]:
    parent = external_id(volume.get("id") or "")
    rows = []
    for item in page.get("results") or []:
        url = https(item.get("url"))
        image = first_iiif(item.get("image_url"))
        if not url or not image:
            continue
        service = iiif_service(image)
        index = sheet_index(url)
        date = str(item.get("date") or volume.get("date") or "")
        title = str(item.get("title") or "Sanborn sheet").strip()
        rows.append(
            sheet_row(
                f"{parent}-{index or len(rows) + 1}",
                title,
                year_of(date),
                date,
                url,
                service,
                sized(service, 200) if service else None,
                index,
                True,
            )
        )
    return rows


def sheet_row(external, title, year, date, url, service, thumb, index, digitized) -> dict:
    return {
        "id": external,
        "title": title[:180],
        "year": year,
        "date_label": date,
        "item_url": url,
        "iiif_service": service,
        "thumbnail_url": thumb,
        "sheet_index": index,
        "digitized": digitized,
        "attribution": SANBORN_ATTRIBUTION,
        "license": "Public domain",
    }


def photos_from_loc(results: list, town: str, state: str, limit: int) -> list[dict]:
    rows = []
    for item in results:
        if len(rows) >= limit:
            break
        if not covers(item, town, state):
            continue
        rights = rights_text(item)
        if not license_allowed(rights):
            continue
        url = https(item.get("url"))
        if not url:
            continue
        image = first_https(item.get("image_url"))
        date = str(item.get("date") or "")
        lat, lng = pair(item.get("latitude"), item.get("longitude"))
        rows.append(
            {
                "id": "loc-" + external_id(item.get("id") or url),
                "source": "loc",
                "title": str(item.get("title") or "Photograph").strip()[:180],
                "year": year_of(date),
                "date_label": date,
                "latitude": lat,
                "longitude": lng,
                "thumb_url": image,
                "image_url": image,
                "page_url": url,
                "creator": None,
                "license": "No known restrictions" if "no known" in rights.lower() else "Public domain",
                "attribution": LOC_PHOTO_ATTRIBUTION,
            }
        )
    return rows


def photos_from_commons(lat: float, lng: float, radius_m: int, limit: int) -> list[dict]:
    radius = max(10, min(radius_m, 10000))
    url = (
        "https://commons.wikimedia.org/w/api.php?action=query&list=geosearch"
        f"&gscoord={lat}|{lng}&gsradius={radius}&gslimit={limit}&gsnamespace=6&format=json"
    )
    try:
        found = fetch_json(url).get("query", {}).get("geosearch") or []
    except Exception as error:
        print(f"commons geosearch skipped: {error}", file=sys.stderr)
        return []
    if not found:
        return []
    titles = "|".join(item["title"] for item in found if item.get("title"))
    time.sleep(PAUSE_S)
    info_url = (
        "https://commons.wikimedia.org/w/api.php?action=query&prop=imageinfo"
        "&iiprop=url|extmetadata&format=json&titles=" + urllib_quote(titles, safe="|")
    )
    try:
        pages = fetch_json(info_url).get("query", {}).get("pages") or {}
    except Exception as error:
        print(f"commons imageinfo skipped: {error}", file=sys.stderr)
        return []
    by_title = {}
    for page in pages.values():
        by_title[page.get("title")] = page
    rows = []
    for item in found:
        page = by_title.get(item.get("title")) or {}
        info = (page.get("imageinfo") or [{}])[0]
        meta = info.get("extmetadata") or {}
        license_name = strip_html(meta_value(meta, "LicenseShortName"))
        if not license_allowed(license_name):
            continue
        image = https(info.get("url"))
        thumb = https(info.get("thumburl")) or image
        page_url = https(info.get("descriptionurl"))
        if not image or not page_url:
            continue
        date = strip_html(meta_value(meta, "DateTimeOriginal")) or ""
        rows.append(
            {
                "id": "commons-" + hashlib.sha1(item["title"].encode()).hexdigest()[:16],
                "source": "commons",
                "title": item["title"].removeprefix("File:")[:180],
                "year": year_of(date),
                "date_label": date[:40],
                "latitude": float(item["lat"]),
                "longitude": float(item["lon"]),
                "thumb_url": thumb,
                "image_url": image,
                "page_url": page_url,
                "creator": strip_html(meta_value(meta, "Artist"))[:120] or None,
                "license": license_name[:80],
                "attribution": commons_attribution(meta_value(meta, "Artist"), license_name),
            }
        )
    return rows


def commons_attribution(artist: str, license_name: str) -> str:
    who = strip_html(artist) or "the contributor"
    return f"Wikimedia Commons. {who}. {license_name}. Open the file page for the terms."


def paper_search(query: str, town: str, state: str) -> str:
    return (
        "https://www.loc.gov/collections/chronicling-america/?dl=page"
        "&searchType=advanced&ops=AND|PHRASE&qs="
        + urllib_quote(query)
        + "|"
        + urllib_quote(town)
        + "&fa=location_state:"
        + urllib_quote(loc_token(state), safe="+")
        + "&fo=json&c=5"
    )


def ocr_text(result: dict) -> str:
    description = " ".join(result.get("description") or []) if isinstance(result.get("description"), list) else str(result.get("description") or "")
    service = None
    for image in result.get("image_url") or []:
        if isinstance(image, str) and "/iiif/" in image and "/full/" in image:
            service = image.split("/full/")[0]
            break
    blobs = [description]
    if service and "service:" in service:
        path = service.split("service:", 1)[1].replace(":", "/")
        alto_url = "https://tile.loc.gov/storage-services/service/" + path + ".xml"
        try:
            request = urllib.request.Request(alto_url, headers={"User-Agent": USER_AGENT, "Accept": "text/xml"})
            with urllib.request.urlopen(request, timeout=60) as response:
                xml = response.read().decode("utf-8", "replace")
            words = re.findall(r'CONTENT="([^"]*)"', xml)
            blobs.append(" ".join(words))
        except Exception:
            pass
    return "\n".join(blobs)


def paper_name(result: dict) -> str:
    title = str(result.get("title") or "")
    match = re.search(r"\bof (.+?),", title)
    if match:
        return match.group(1).strip()[:120]
    for part in result.get("partof") or []:
        text = str(part).strip()
        key = text.lower()
        if not text or "chronicling america" in key or "serial and government" in key:
            continue
        return text[:120]
    return "Newspaper"


def clipping_from(result: dict, query: str, text: str, town: str, state: str) -> dict | None:
    cities = result.get("location_city") or []
    states = result.get("location_state") or []
    paper_city = str(cities[0] if cities else "")
    paper_state = str(states[0] if states else "")
    if not same_place(paper_state, state):
        return None
    paper = paper_name(result)
    snippet = window(text, query, town, paper_city, paper_state, state)
    if not snippet:
        return None
    url = https(result.get("url"))
    if not url:
        return None
    date = str(result.get("date") or "")
    match = re.search(r"(\d{4})-(\d{2})-(\d{2})", date)
    if not match:
        return None
    image = first_iiif(result.get("image_url"))
    return {
        "id": "paper-" + hashlib.sha1(url.encode()).hexdigest()[:16],
        "terms": [query.strip()],
        "title": str(result.get("title") or paper).strip()[:180],
        "year": int(match.group(1)),
        "month": int(match.group(2)),
        "day": int(match.group(3)),
        "snippet": snippet[:600],
        "page_url": url.split("&")[0],
        "image_url": image,
        "paper": paper[:120],
        "paper_city": paper_city.title() if paper_city.islower() else paper_city,
        "paper_state": state,
        "attribution": PAPER_ATTRIBUTION,
        "license": "Public domain",
    }


def load_georef(path: Path, sheets: list[dict]) -> list[dict]:
    if not path.exists():
        return []
    raw = json.loads(path.read_text(encoding="utf-8"))
    known = {sheet["id"] for sheet in sheets}
    rows = []
    for sheet in raw.get("sheets") or []:
        points = sheet.get("control_points") or []
        if len(points) < 3:
            continue
        if sheet.get("sheet_id") not in known:
            continue
        rows.append(
            {
                "sheet_id": sheet["sheet_id"],
                "width": int(sheet["width"]),
                "height": int(sheet["height"]),
                "image_url": sheet["image_url"],
                "points": points,
                "attribution": sheet.get("attribution") or SANBORN_ATTRIBUTION,
            }
        )
    return rows


def covers(item: dict, town: str, state: str) -> bool:
    places = [normalize(part) for part in (item.get("location") or [])]
    blob = " ".join(places)
    state_names = [normalize(part) for part in (item.get("location_state") or [])]
    if state_names and not any(same_place(part, state) for part in state_names):
        return False
    return normalize(town) in blob and (normalize(state) in blob or any(same_place(part, state) for part in state_names))


def rights_text(item: dict) -> str:
    item_body = item.get("item") or {}
    return str(item_body.get("rights_advisory") or item_body.get("rights_information") or item.get("rights") or "")


def license_allowed(license_name: str) -> bool:
    key = license_name.lower()
    if not key.strip() or "all rights reserved" in key:
        return False
    return any(token in key for token in ("public domain", "no known", "cc0", "pdm", "cc by", "cc-by"))


def iiif_service(image: str | None) -> str | None:
    if not image or "/full/" not in image:
        return None
    base = image.split("/full/")[0].split("#")[0].rstrip("/")
    if not base.startswith("https://"):
        return None
    return base


def first_iiif(images) -> str | None:
    if not isinstance(images, list):
        return None
    for image in images:
        if isinstance(image, str) and "/iiif/" in image and image.startswith("https://"):
            return image.split("#")[0]
    return None


def first_https(images) -> str | None:
    if not isinstance(images, list):
        return None
    for image in images:
        if isinstance(image, str) and image.startswith("https://"):
            return image.split("#")[0]
    return None


def sized(service: str, width: int) -> str:
    return f"{service}/full/{width},/0/default.jpg"


def https(value) -> str | None:
    if isinstance(value, str) and value.startswith("https://"):
        return value
    return None


def external_id(value: str) -> str:
    match = re.search(r"(sanborn\d+_\d+)", value)
    if match:
        return match.group(1)
    tail = value.rstrip("/").split("/")[-1]
    tail = re.sub(r"[^a-zA-Z0-9_-]", "", tail)
    return tail[:80]


def year_of(value: str | None) -> int | None:
    if not value:
        return None
    match = re.search(r"(1[7-9]\d{2}|20\d{2})", value)
    return int(match.group(1)) if match else None


def sheet_index(url: str) -> int | None:
    match = re.search(r"[?&]sp=(\d+)", url)
    return int(match.group(1)) if match else None


def pair(lat, lng):
    try:
        if lat is None or lng is None:
            return None, None
        lat_f, lng_f = float(lat), float(lng)
    except (TypeError, ValueError):
        return None, None
    if not (-90 <= lat_f <= 90 and -180 <= lng_f <= 180):
        return None, None
    return lat_f, lng_f


def meta_value(meta: dict, key: str) -> str:
    slot = meta.get(key) or {}
    if isinstance(slot, dict):
        return str(slot.get("value") or "")
    return str(slot or "")


def strip_html(value: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", value or "")).strip()


def flatten_text(payload) -> str:
    chunks = []

    def walk(node) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "full_text" and isinstance(value, str):
                    chunks.append(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(payload)
    return "\n".join(chunks)


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", value.lower())).strip()


def same_place(left: str, right: str) -> bool:
    a, b = normalize(left), normalize(right)
    if not a or not b:
        return False
    if a == b:
        return True
    return a == abbrev(b) or b == abbrev(a) or a.startswith(b) or b.startswith(a)


def abbrev(name: str) -> str:
    return {"new york": "ny", "north dakota": "nd", "pennsylvania": "pa"}.get(name, name)


def window(text: str, phrase: str, town: str, paper_city: str, paper_state: str, region_state: str) -> str | None:
    if not same_place(paper_state, region_state):
        return None
    flat = re.sub(r"\s+", " ", text).strip()
    letters = sum(1 for char in flat if char.isalpha())
    if letters < 40 or letters / max(len(flat), 1) < 0.55:
        return None
    phrase_at = find_span(flat, phrase)
    if phrase_at is None:
        return None
    if not same_place(paper_city, town):
        town_at = find_span(flat, town)
        if town_at is None or abs(town_at - phrase_at) > 400:
            return None
        start = max(0, min(town_at, phrase_at) - 80)
        end = min(len(flat), max(town_at, phrase_at) + len(phrase) + 80)
        around = flat[start:end]
        if normalize(region_state) == "new york":
            key = f" {normalize(around)} "
            if "north dakota" in key or " n d " in key or " nd " in key:
                return None
    start = max(0, phrase_at - 90)
    end = min(len(flat), phrase_at + len(phrase) + 160)
    snippet = flat[start:end].strip()
    if start > 0:
        snippet = "…" + snippet
    if end < len(flat):
        snippet = snippet + "…"
    if len(snippet) < 40:
        return None
    return snippet


def find_span(text: str, phrase: str):
    needle = normalize(phrase)
    if not needle:
        return None
    forms = [needle]
    swapped = " ".join(ORDINALS.get(token, token) for token in needle.split())
    if swapped not in forms:
        forms.append(swapped)
    for form in forms:
        pattern = re.compile(r"\s+".join(re.escape(part) for part in form.split()), re.I)
        found = pattern.search(text)
        if found:
            return found.start()
    return None


def to_sql(doc: dict) -> str:
    payload = json.dumps(doc, ensure_ascii=False)
    if "$history$" in payload:
        raise SystemExit("snapshot contains the SQL dollar quote")
    return (
        "-- Time Machine cache. Generated by supabase/scripts/import_history.py.\n"
        "-- supabase db push does not load this file. Apply it with psql after the history migration.\n"
        f"select private.upsert_history($history${payload}$history$::jsonb);\n"
    )


def check(snapshot: Path, region_path: Path) -> None:
    region = load_region(region_path)
    doc = json.loads(snapshot.read_text(encoding="utf-8"))
    history = region["history"]
    box = region["region"]["bbox"]
    got = doc["region"]
    if got["region_id"] != region["region"]["id"]:
        raise SystemExit("region id does not match")
    if got["town"] != history["town"] or got["state"] != history["state"]:
        raise SystemExit("town or state does not match the region file")
    if [got["west"], got["south"], got["east"], got["north"]] != box:
        raise SystemExit("history box does not match region.bbox")
    if not doc["sheets"]:
        raise SystemExit("snapshot has no Sanborn rows")
    for sheet in doc["sheets"]:
        if not str(sheet["item_url"]).startswith("https://"):
            raise SystemExit(f"sheet {sheet['id']} needs an https item url")
        if "Library of Congress" not in sheet["attribution"]:
            raise SystemExit(f"sheet {sheet['id']} is missing Library of Congress attribution")
        if sheet["license"] != "Public domain":
            raise SystemExit(f"sheet {sheet['id']} is not public domain")
        if sheet.get("iiif_service") and not str(sheet["iiif_service"]).startswith("https://"):
            raise SystemExit(f"sheet {sheet['id']} has a bad image service")
    for photo in doc["photos"]:
        if photo["source"] not in {"loc", "commons"}:
            raise SystemExit(f"photo {photo['id']} has an unknown source")
        if not license_allowed(photo["license"]):
            raise SystemExit(f"photo {photo['id']} license is not one we show")
        if not str(photo["page_url"]).startswith("https://"):
            raise SystemExit(f"photo {photo['id']} needs an https page")
    for clipping in doc["clippings"]:
        if not same_place(clipping["paper_state"], history["state"]):
            raise SystemExit(f"clipping {clipping['id']} is from another state")
        if not (40 <= len(clipping["snippet"]) <= 600):
            raise SystemExit(f"clipping {clipping['id']} snippet length")
        if not clipping["terms"]:
            raise SystemExit(f"clipping {clipping['id']} has no terms")
    georef_path = region_path.with_suffix(".georef.json")
    if georef_path.exists():
        raw = json.loads(georef_path.read_text(encoding="utf-8"))
        ready = [row for row in raw.get("sheets") or [] if len(row.get("control_points") or []) >= 3]
        if not ready and doc["georef"]:
            raise SystemExit("seed has an overlay but the georef file has no verified control points")
    for row in doc["georef"]:
        if len(row.get("points") or []) < 3:
            raise SystemExit("georef row has fewer than 3 control points")


def self_test() -> None:
    text = (
        "The pavement on West 3rd Street in Jamestown was torn up for the new main, "
        "and wagons were turned onto Cherry until the block was open again yesterday morning."
    )
    hit = window(text, "West 3rd Street", "Jamestown", "New York", "New York", "New York")
    assert hit and "West 3rd Street" in hit and "Jamestown" in hit
    north_dakota = (
        "West 3rd Street in Jamestown, N.D. was the scene of a long meeting of the county board, "
        "which sat until well after dark and then adjourned without a vote."
    )
    assert window(north_dakota, "West 3rd Street", "Jamestown", "New York", "New York", "New York") is None
    assert window(text, "West 3rd Street", "Jamestown", "Jamestown", "North Dakota", "New York") is None
    third = (
        "Work on West Third Street in Jamestown continued through the week, "
        "with the curb reset and the walk opened to foot traffic again."
    )
    assert window(third, "West 3rd Street", "Jamestown", "New York", "NY", "New York")
    assert license_allowed("CC BY-SA 4.0")
    assert license_allowed("No known restrictions on publication.")
    assert not license_allowed("All rights reserved")
    assert not license_allowed("")
    sample = {
        "results": [
            {
                "id": "http://www.loc.gov/item/sanborn06011_001/",
                "title": "Sanborn Fire Insurance Map from Jamestown, Chautauqua County, New York.",
                "date": "1886-03",
                "url": "https://www.loc.gov/item/sanborn06011_001/",
                "digitized": False,
                "location": ["new york", "jamestown"],
                "location_state": ["new york"],
            },
            {
                "id": "http://www.loc.gov/item/sanborn00000_001/",
                "title": "Sanborn Fire Insurance Map from Jamestown, North Dakota.",
                "date": "1898",
                "url": "https://www.loc.gov/item/sanborn00000_001/",
                "digitized": False,
                "location": ["north dakota", "jamestown"],
                "location_state": ["north dakota"],
            },
        ]
    }
    sheets = volumes_to_sheets(sample["results"], "Jamestown", "New York")
    assert [row["id"] for row in sheets] == ["sanborn06011_001"]
    image = (
        "https://tile.loc.gov/image-services/iiif/service:gmd:example:06011_1886-0002"
        "/full/pct:12.5/0/default.jpg#h=10"
    )
    page = {
        "results": [
            {
                "title": "Image 2 of Sanborn Fire Insurance Map from Jamestown",
                "date": "1886-03",
                "url": "https://www.loc.gov/resource/g3804jm.g3804jm_g060111886/?sp=2",
                "image_url": [image],
            }
        ]
    }
    parsed = sheets_from_page(page, {"id": "http://www.loc.gov/item/sanborn06011_001/", "date": "1886-03"})
    assert parsed[0]["id"] == "sanborn06011_001-2"
    assert parsed[0]["iiif_service"].endswith("06011_1886-0002")
    assert year_of("1886-03") == 1886
    region = load_region(Path("supabase/regions/us/jamestown-ny.yaml"))
    assert region["history"]["town"] == "Jamestown"
    assert region["history"]["state"] == "New York"


def _arg(argv: list[str], flag: str) -> str:
    if flag not in argv:
        raise SystemExit(f"missing {flag}")
    return argv[argv.index(flag) + 1]


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except Exception as error:
        print(f"import_history: {error}", file=sys.stderr)
        raise SystemExit(1) from error
