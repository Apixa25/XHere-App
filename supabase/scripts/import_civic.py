#!/usr/bin/env python3
"""Town Hall importer. Region-agnostic. No paid API and no language model.

A region YAML `civic:` block names the sources. Each source picks one adapter:

  legistar     Legistar Web API (many US cities, no key).
  civicplus    CivicPlus AgendaCenter RSS or an HTML list of agenda links.
  socrata      A Socrata open-data permit table (SODA JSON).
  arcgis_permits
               An ArcGIS FeatureServer or GeoJSON permit layer.
  rss_ical     A generic RSS/Atom feed or an iCal calendar.
  manual       A curated YAML or CSV file. The fallback when a town has
               no machine-readable feed.

The script writes JSON plus SQL that calls private.upsert_civic_items.
It does not talk to hosted Supabase. Geocoding is optional (`--geocode`):
US addresses use the Census Bureau geocoder; other countries use the public
Nominatim search endpoint with a 1.1 second pause and a local cache.
The phone's Nominatim RPC stays reverse-only.

Examples:
  python3 supabase/scripts/import_civic.py --self-test
  python3 supabase/scripts/import_civic.py \\
    --region supabase/regions/us/jamestown-ny.yaml \\
    --out supabase/seed/civic/jamestown-ny.json
  python3 supabase/scripts/import_civic.py \\
    --check supabase/seed/civic/jamestown-ny.json \\
    --region supabase/regions/us/jamestown-ny.yaml
"""

from __future__ import annotations

import csv
import html
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

import import_open_data as open_data  # noqa: E402

USER_AGENT = "XHereTownHall/1.0 (civic import; +https://xhere.world)"
ADAPTERS = ("legistar", "civicplus", "socrata", "arcgis_permits", "rss_ical", "manual")
TYPES = ("agenda_item", "hearing", "permit", "notice")
STREET = (
    r"(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|"
    r"Court|Ct|Place|Pl|Way|Parkway|Pkwy|Highway|Hwy|Terrace|Ter|Circle|Cir|Trail|Trl)"
)
NUMBERED_ADDRESS = re.compile(
    rf"\b(\d{{1,6}}[A-Za-z]?)\s+"
    rf"((?:(?:N|S|E|W|North|South|East|West)\.?\s+)?"
    rf"(?:[A-Za-z0-9.'-]+\s+){{0,5}}{STREET}\.?)\b",
    re.IGNORECASE,
)
BARE_STREET = re.compile(
    rf"\b((?:[A-Z][A-Za-z0-9.'-]*\s+){{1,4}}(?:Street|Avenue|Road|Boulevard|Drive|Lane|Court|Place|Parkway|Highway|Terrace|Circle|Trail))\b"
)


class CivicError(Exception):
    """A civic source, a fixture, or a snapshot failed a check."""


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
        region = _load(Path(region_path) if region_path else _region_for(snapshot))
        existing = json.loads(snapshot.read_text(encoding="utf-8"))
        fresh = build(region, geocode=False)
        if _canon(existing) != _canon(fresh):
            raise CivicError(f"{snapshot} does not match the region file. Re-run the importer.")
        print(json.dumps({"ok": True, "count": len(fresh)}, indent=2))
        return 0
    if not region_path:
        raise CivicError("pass --region path/to/region.yaml")
    region = _load(Path(region_path))
    items = build(region, geocode="--geocode" in args)
    out = Path(_option(args, "--out") or default_out(region))
    write_snapshot(out, items)
    print(json.dumps({"wrote": str(out), "sql": str(out.with_suffix(".sql")), "count": len(items)}, indent=2))
    return 0


def _option(args: list[str], flag: str) -> str | None:
    if flag not in args:
        return None
    index = args.index(flag)
    if index + 1 >= len(args):
        raise CivicError(f"{flag} needs a value")
    return args[index + 1]


def _region_for(snapshot: Path) -> Path:
    matches = list((ROOT / "supabase" / "regions").rglob(f"{snapshot.stem}.yaml"))
    if len(matches) != 1:
        raise CivicError(f"pass --region for {snapshot.name}")
    return matches[0]


def default_out(region: dict) -> Path:
    return ROOT / "supabase" / "seed" / "civic" / f"{region['region']['id']}.json"


def _load(path: Path) -> dict:
    region = open_data.load_region(path)
    assert_civic_block(region, path.parent)
    return region


def assert_civic_block(region: dict, base: Path | None = None) -> None:
    block = region.get("civic")
    if block is None:
        return
    if not isinstance(block, dict):
        raise CivicError("civic: must be a map")
    sources = block.get("sources") or []
    if not isinstance(sources, list):
        raise CivicError("civic.sources must be a list")
    for source in sources:
        adapter = source.get("adapter")
        if adapter not in ADAPTERS:
            raise CivicError(f"source {source.get('id')} adapter must be one of {', '.join(ADAPTERS)}")
        if not source.get("license") or not source.get("attribution") or not source.get("url"):
            raise CivicError(f"source {source.get('id')} needs license, attribution, and url")
        if not str(source["url"]).startswith("https://"):
            raise CivicError(f"source {source.get('id')} url must be https")
        if source.get("cost") not in (None, 0, "0"):
            raise CivicError(f"source {source.get('id')} must be free (cost: 0)")
        if adapter == "manual":
            file_name = source.get("file")
            if not file_name:
                raise CivicError(f"source {source.get('id')} manual adapter needs file:")
            path = Path(file_name)
            if not path.is_absolute():
                path = ROOT / path
            if not path.exists():
                raise CivicError(f"manual file not found: {path}")


def build(region: dict, geocode: bool) -> list[dict]:
    block = region.get("civic") or {}
    coder = Geocoder() if geocode else None
    items: list[dict] = []
    for source in block.get("sources") or []:
        items.extend(fetch_source(source, region, coder))
    return sorted(items, key=lambda row: (row["source_slug"], row["external_id"]))


def fetch_source(source: dict, region: dict, coder) -> list[dict]:
    adapter = source["adapter"]
    if adapter == "manual":
        return parse_manual(source, region, coder)
    if source.get("fixture"):
        path = ROOT / source["fixture"]
        payload = path.read_text(encoding="utf-8")
        return parse_adapter(adapter, payload, source, region, coder)
    if adapter == "legistar":
        payload = fetch_legistar(source)
    elif adapter == "civicplus":
        payload = http_text(source.get("feed") or source["url"])
    elif adapter == "socrata":
        payload = http_text(source["url"])
    elif adapter == "arcgis_permits":
        payload = http_text(source["url"])
    elif adapter == "rss_ical":
        payload = http_text(source.get("feed") or source["url"])
    else:
        raise CivicError(f"unknown adapter {adapter}")
    return parse_adapter(adapter, payload, source, region, coder)


def parse_adapter(adapter: str, payload: str, source: dict, region: dict, coder) -> list[dict]:
    if adapter == "legistar":
        return parse_legistar(payload, source, region, coder)
    if adapter == "civicplus":
        return parse_civicplus(payload, source, region, coder)
    if adapter == "socrata":
        return parse_socrata(payload, source, region, coder)
    if adapter == "arcgis_permits":
        return parse_arcgis(payload, source, region, coder)
    if adapter == "rss_ical":
        return parse_rss_ical(payload, source, region, coder)
    raise CivicError(f"unknown adapter {adapter}")


# ---------------------------------------------------------------------------
# Adapters
# ---------------------------------------------------------------------------

def parse_legistar(payload: str, source: dict, region: dict, coder) -> list[dict]:
    data = json.loads(payload)
    events = data.get("events") or data.get("Events") or data
    if isinstance(events, dict):
        events = [events]
    rows = []
    for event in events:
        body = event.get("EventBodyName") or source.get("body")
        when = _date_only(event.get("EventDate"))
        link = https_or_none(event.get("EventAgendaFile") or event.get("EventInSiteURL") or source["url"])
        items = event.get("EventItems") or []
        if not items:
            title = clean(event.get("EventBodyName") or "Meeting", 160) or "Meeting"
            rows.append(make_item(
                source, region, coder,
                external_id=f"event-{event.get('EventId') or title}",
                item_type="agenda_item",
                title=f"{title} {when or ''}".strip(),
                summary=clean(event.get("EventTime"), 400),
                occurs_on=when,
                body_name=body,
                source_url=link,
                address=event.get("EventLocation"),
                text=title,
            ))
            continue
        for item in items:
            title = clean(item.get("EventItemTitle") or item.get("EventItemMatterName"), 160)
            if not title:
                continue
            kind = item.get("EventItemMatterType") or title
            rows.append(make_item(
                source, region, coder,
                external_id=f"item-{item.get('EventItemId') or slug_id(title)}",
                item_type=guess_type(str(kind), default="agenda_item"),
                title=title,
                summary=clean(item.get("EventItemAgendaNote") or title, 400),
                occurs_on=when,
                body_name=body,
                source_url=link,
                address=item.get("EventItemAddress"),
                text=title,
            ))
    return [row for row in rows if row]


def parse_civicplus(payload: str, source: dict, region: dict, coder) -> list[dict]:
    if "<rss" in payload.lower() or "<item" in payload.lower() or "<entry" in payload.lower():
        return parse_feed(payload, source, region, coder, default_type="agenda_item")
    rows = []
    for href, label in re.findall(r'href="(https://[^"]+)"[^>]*>([^<]{3,180})', payload, flags=re.I):
        title = clean(html.unescape(label), 160)
        if not title:
            continue
        if "agenda" not in href.lower() and "agenda" not in title.lower() and "hearing" not in title.lower():
            continue
        rows.append(make_item(
            source, region, coder,
            external_id=slug_id(href),
            item_type=guess_type(title, default="agenda_item"),
            title=title,
            summary=title,
            occurs_on=date_in_text(title),
            body_name=source.get("body"),
            source_url=href,
            address=None,
            text=title,
        ))
    return [row for row in rows if row]


def parse_socrata(payload: str, source: dict, region: dict, coder) -> list[dict]:
    data = json.loads(payload)
    if isinstance(data, dict):
        data = data.get("data") or data.get("results") or []
    fields = source.get("fields") or {}
    rows = []
    for index, raw in enumerate(data):
        if not isinstance(raw, dict):
            continue
        title = clean(pick(raw, fields, "title", "work", "description", "permit_type"), 160)
        address = pick(raw, fields, "address", "street_address", "location")
        if not title or not address:
            continue
        link = https_or_none(pick(raw, fields, "url", "link", "permit_url")) or source["url"]
        external = str(pick(raw, fields, "id", "permit_number", "permit_id") or f"row-{index}")
        rows.append(make_item(
            source, region, coder,
            external_id=external[:180],
            item_type="permit",
            title=title,
            summary=clean(pick(raw, fields, "summary", "status") or title, 400),
            occurs_on=_date_only(pick(raw, fields, "date", "issue_date", "issued")),
            body_name=source.get("body") or source.get("publisher"),
            source_url=link,
            address=str(address),
            text=f"{title} {address}",
            lat=float_or_none(pick(raw, fields, "lat", "latitude")),
            lng=float_or_none(pick(raw, fields, "lng", "longitude")),
        ))
    return [row for row in rows if row]


def parse_arcgis(payload: str, source: dict, region: dict, coder) -> list[dict]:
    data = json.loads(payload)
    features = data.get("features") or []
    fields = source.get("fields") or {}
    rows = []
    for index, feature in enumerate(features):
        attrs = feature.get("attributes") or feature.get("properties") or {}
        title = clean(pick(attrs, fields, "title", "WORK", "WORKDESC", "DESCRIPTION"), 160)
        address = pick(attrs, fields, "address", "ADDRESS", "SITE_ADDR")
        if not title:
            continue
        point = feature.get("geometry") or {}
        lat = point.get("y") if "y" in point else None
        lng = point.get("x") if "x" in point else None
        if lat is None and isinstance(point.get("coordinates"), list):
            lng, lat = point["coordinates"][0], point["coordinates"][1]
        link = https_or_none(pick(attrs, fields, "url", "URL")) or source["url"]
        external = str(pick(attrs, fields, "id", "PERMITID", "PERMIT_NO") or f"feature-{index}")
        rows.append(make_item(
            source, region, coder,
            external_id=external[:180],
            item_type="permit",
            title=title,
            summary=clean(str(address or title), 400),
            occurs_on=_date_only(pick(attrs, fields, "date", "ISSUED", "ISSUEDATE")),
            body_name=source.get("body") or source.get("publisher"),
            source_url=link,
            address=str(address) if address else None,
            text=f"{title} {address or ''}",
            lat=float_or_none(lat),
            lng=float_or_none(lng),
        ))
    return [row for row in rows if row]


def parse_rss_ical(payload: str, source: dict, region: dict, coder) -> list[dict]:
    if payload.lstrip().startswith("BEGIN:VCALENDAR") or "BEGIN:VEVENT" in payload:
        return parse_ical(payload, source, region, coder)
    return parse_feed(payload, source, region, coder, default_type=source.get("item_type") or "notice")


def parse_feed(payload: str, source: dict, region: dict, coder, default_type: str) -> list[dict]:
    import xml.etree.ElementTree as ET

    root = ET.fromstring(payload)
    rows = []
    for node in root.iter():
        if local_name(node.tag) not in {"item", "entry"}:
            continue
        title = clean(text_child(node, "title"), 160)
        link = https_or_none(text_child(node, "link") or attr_child(node, "link", "href"))
        summary = clean(text_child(node, "description") or text_child(node, "summary"), 400)
        if not title or not link:
            continue
        when = date_in_text(text_child(node, "pubDate") or "") or date_in_text(title or "")
        rows.append(make_item(
            source, region, coder,
            external_id=slug_id(link),
            item_type=guess_type(f"{title} {summary or ''}", default=default_type),
            title=title,
            summary=summary or title,
            occurs_on=when,
            body_name=source.get("body"),
            source_url=link,
            address=None,
            text=f"{title} {summary or ''}",
        ))
    return [row for row in rows if row]


def parse_ical(payload: str, source: dict, region: dict, coder) -> list[dict]:
    rows = []
    for block in re.findall(r"BEGIN:VEVENT(.*?)END:VEVENT", unfold_ical(payload), flags=re.S):
        fields = {}
        for line in block.splitlines():
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            fields[key.split(";")[0].upper()] = value.strip()
        title = clean(fields.get("SUMMARY"), 160)
        link = https_or_none(fields.get("URL")) or source["url"]
        if not title:
            continue
        rows.append(make_item(
            source, region, coder,
            external_id=slug_id(fields.get("UID") or title),
            item_type=guess_type(title, default="agenda_item"),
            title=title,
            summary=clean(fields.get("DESCRIPTION"), 400) or title,
            occurs_on=_date_only(fields.get("DTSTART")),
            body_name=source.get("body"),
            source_url=link,
            address=fields.get("LOCATION"),
            text=f"{title} {fields.get('LOCATION') or ''}",
        ))
    return [row for row in rows if row]


def parse_manual(source: dict, region: dict, coder) -> list[dict]:
    path = ROOT / source["file"]
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".csv":
        raw_items = list(csv.DictReader(io.StringIO(text)))
    else:
        parsed = open_data.parse_yaml(text)
        raw_items = parsed.get("items") if isinstance(parsed, dict) else None
        if not isinstance(raw_items, list):
            raise CivicError(f"{path} needs items:")
    rows = []
    for raw in raw_items:
        title = clean(raw.get("title"), 160)
        link = https_or_none(raw.get("source_url"))
        kind = str(raw.get("type") or raw.get("item_type") or "")
        if kind not in TYPES or not title or not link:
            raise CivicError(f"{path} has an item missing type, title, or https source_url")
        external = str(raw.get("external_id") or slug_id(title))
        rows.append(make_item(
            source, region, coder,
            external_id=external,
            item_type=kind,
            title=title,
            summary=clean(raw.get("summary"), 400),
            occurs_on=_date_only(raw.get("date") or raw.get("occurs_on")),
            body_name=raw.get("body") or raw.get("body_name"),
            source_url=link,
            address=raw.get("address"),
            text=title,
            lat=float_or_none(raw.get("lat")),
            lng=float_or_none(raw.get("lng")),
            expires_at=raw.get("expires_at"),
            strict=True,
        ))
    return [row for row in rows if row]


def fetch_legistar(source: dict) -> str:
    client = source.get("client")
    if not client or not re.fullmatch(r"[A-Za-z0-9_-]{2,40}", str(client)):
        raise CivicError(f"source {source.get('id')} legistar needs a client slug")
    start = (datetime.now(timezone.utc) - timedelta(days=14)).strftime("%Y-%m-%d")
    query = urllib.parse.urlencode({
        "$filter": f"EventDate ge datetime'{start}'",
        "$orderby": "EventDate",
        "$top": "40",
    })
    url = f"https://webapi.legistar.com/v1/{client}/events?{query}"
    events = json.loads(http_text(url))
    time.sleep(1.1)
    for event in events:
        event_id = event.get("EventId")
        if not event_id:
            continue
        try:
            event["EventItems"] = json.loads(
                http_text(f"https://webapi.legistar.com/v1/{client}/events/{event_id}/eventitems?$top=40")
            )
        except (urllib.error.URLError, json.JSONDecodeError, TimeoutError):
            event["EventItems"] = []
        time.sleep(1.1)
    return json.dumps({"events": events})


# ---------------------------------------------------------------------------
# Records, addresses, geocoding
# ---------------------------------------------------------------------------

def make_item(
    source, region, coder, *,
    external_id, item_type, title, summary, occurs_on, body_name, source_url,
    address, text, lat=None, lng=None, expires_at=None, strict=False,
):
    if item_type not in TYPES or not title or not source_url:
        if strict:
            raise CivicError(f"bad civic item {external_id}")
        return None
    found = extract_addresses(f"{address or ''} {text or ''}")
    address_text = clean(address, 200) or (found[0] if found else None)
    if lat is None and lng is None and address_text and coder is not None:
        lat, lng = coder.locate(address_text, region)
    if (lat is None) ^ (lng is None):
        lat, lng = None, None
    bbox = region["region"]["bbox"]
    if lat is not None and not open_data.inside_bbox(lat, lng, bbox):
        lat, lng = None, None
    if not expires_at:
        expires_at = default_expiry(item_type, occurs_on)
    return {
        "region_id": region["region"]["id"],
        "source_slug": source["id"],
        "external_id": str(external_id)[:180],
        "item_type": item_type,
        "title": title,
        "summary": summary,
        "occurs_on": occurs_on,
        "body_name": clean(body_name, 120),
        "source_url": source_url,
        "address_text": address_text,
        "lat": None if lat is None else round(float(lat), 6),
        "lng": None if lng is None else round(float(lng), 6),
        "attribution": source["attribution"],
        "license": source["license"],
        "expires_at": expires_at,
    }


def extract_addresses(text: str) -> list[str]:
    """Pull street addresses out of a plain sentence. Numbered addresses win."""
    if not text:
        return []
    found: list[str] = []
    for match in NUMBERED_ADDRESS.finditer(text):
        found.append(clean(f"{match.group(1)} {match.group(2)}", 200) or "")
    if not found:
        for match in BARE_STREET.finditer(text):
            found.append(clean(match.group(1), 200) or "")
    unique = []
    for item in found:
        if item and item.lower() not in {row.lower() for row in unique}:
            unique.append(item)
    return unique


def address_key(value: str | None) -> str:
    """Same rules as Civic.addressKey in the shared Kotlin module."""
    if not value:
        return ""
    text = re.sub(r"[^a-z0-9]+", " ", value.lower().replace("&", " and "))
    text = f" {text} "
    swaps = (
        (" street ", " st "), (" avenue ", " ave "), (" road ", " rd "),
        (" boulevard ", " blvd "), (" drive ", " dr "), (" lane ", " ln "),
        (" court ", " ct "), (" place ", " pl "), (" parkway ", " pkwy "),
        (" highway ", " hwy "), (" terrace ", " ter "), (" circle ", " cir "),
        (" trail ", " trl "), (" first ", " 1st "), (" second ", " 2nd "),
        (" third ", " 3rd "), (" new york ", " "), (" ny ", " "), (" usa ", " "),
    )
    for old, new in swaps:
        text = text.replace(old, new)
    tokens = []
    for token in text.split():
        tokens.append({"e": "east", "w": "west", "n": "north", "s": "south"}.get(token, token))
    if tokens and re.fullmatch(r"\d{5}", tokens[-1]):
        tokens.pop()
    return " ".join(tokens)


def address_matches(follow: str | None, item: str | None) -> bool:
    left = address_key(follow)
    right = address_key(item)
    if not left or not right or len(left.split()) < 2:
        return False
    return f" {left} " in f" {right} "


def clean(value, limit: int):
    if value is None:
        return None
    text = html.unescape(str(value))
    text = re.sub(r"<[^>]+>", " ", text)
    text = text.replace("\u2013", "-").replace("\u2014", "-").replace("&#038;", "&").replace("&nbsp;", " ")
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return None
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip() + "…"


def guess_type(text: str, default: str) -> str:
    lowered = text.lower()
    if "hearing" in lowered:
        return "hearing"
    if "permit" in lowered:
        return "permit"
    if "notice" in lowered or "rfp" in lowered:
        return "notice"
    if default in TYPES:
        return default
    return "agenda_item"


def default_expiry(item_type: str, occurs_on: str | None) -> str | None:
    if not occurs_on:
        return None
    day = datetime.strptime(occurs_on, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    if item_type in {"agenda_item", "hearing"}:
        return (day + timedelta(days=1)).strftime("%Y-%m-%dT05:00:00Z")
    return (day + timedelta(days=180)).strftime("%Y-%m-%dT05:00:00Z")


def _date_only(value) -> str | None:
    if not value:
        return None
    text = str(value).strip()
    match = re.search(r"(\d{4})-(\d{2})-(\d{2})", text)
    if match:
        return "-".join(match.groups())
    match = re.search(r"(\d{8})", text)
    if match and text.upper().startswith("20"):
        raw = match.group(1)
        return f"{raw[0:4]}-{raw[4:6]}-{raw[6:8]}"
    return date_in_text(text)


def date_in_text(text: str) -> str | None:
    match = re.search(
        r"\b(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{1,2}),\s+(\d{4})",
        text,
        flags=re.I,
    )
    if not match:
        return None
    months = {
        "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6,
        "july": 7, "august": 8, "september": 9, "october": 10, "november": 11, "december": 12,
    }
    month = months[match.group(1).lower()]
    return f"{int(match.group(3)):04d}-{month:02d}-{int(match.group(2)):02d}"


class Geocoder:
    """Census first for US addresses. Nominatim otherwise, with a file cache."""

    def __init__(self):
        self.cache_path = ROOT / "supabase" / "seed" / "civic" / "geocode-cache.json"
        self.cache = {}
        if self.cache_path.exists():
            self.cache = json.loads(self.cache_path.read_text(encoding="utf-8"))
        self.last_nominatim = 0.0

    def locate(self, address: str, region: dict) -> tuple[float | None, float | None]:
        name = region["region"].get("name") or ""
        country = str(region["region"].get("country") or "")
        query = address if name.split(",")[0].lower() in address.lower() else f"{address}, {name}"
        key = query.lower()
        if key in self.cache:
            hit = self.cache[key]
            return hit.get("lat"), hit.get("lng")
        point = None
        if country.upper() == "US":
            point = self.census(query)
        if point is None:
            point = self.nominatim(query)
        self.cache[key] = {"lat": None if point is None else point[0], "lng": None if point is None else point[1]}
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(json.dumps(self.cache, indent=2) + "\n", encoding="utf-8")
        return (None, None) if point is None else point

    def census(self, query: str):
        url = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress?" + urllib.parse.urlencode({
            "address": query,
            "benchmark": "Public_AR_Current",
            "format": "json",
        })
        try:
            data = json.loads(http_text(url))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            return None
        matches = data.get("result", {}).get("addressMatches") or []
        if not matches:
            return None
        coords = matches[0]["coordinates"]
        return float(coords["y"]), float(coords["x"])

    def nominatim(self, query: str):
        wait = 1.1 - (time.time() - self.last_nominatim)
        if wait > 0:
            time.sleep(wait)
        url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({
            "q": query, "format": "jsonv2", "limit": "1",
        })
        self.last_nominatim = time.time()
        try:
            data = json.loads(http_text(url))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            return None
        if not data:
            return None
        return float(data[0]["lat"]), float(data[0]["lon"])


def http_text(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json, application/xml, text/xml, */*"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read().decode("utf-8", "replace")


def write_snapshot(path: Path, items: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(items, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    payload = json.dumps(items, ensure_ascii=False, separators=(",", ":"))
    if "$xhere_civic$" in payload:
        raise CivicError("snapshot collided with the SQL delimiter")
    sql = (
        "-- Generated by supabase/scripts/import_civic.py. Do not edit by hand.\n"
        "-- Safe to re-run: rows upsert on (region_id, source_slug, external_id).\n"
        "-- Apply after supabase/migrations/20261021180000_civic_items.sql.\n"
        "-- db push does not load this file.\n"
        "select private.upsert_civic_items(\n$xhere_civic$\n"
        + payload
        + "\n$xhere_civic$::jsonb);\n"
    )
    path.with_suffix(".sql").write_text(sql, encoding="utf-8")


def _canon(items: list) -> str:
    return json.dumps(items, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def pick(row: dict, fields: dict, *names):
    for name in names:
        key = fields.get(name) or name
        if key in row and row[key] not in (None, ""):
            return row[key]
    return None


def float_or_none(value):
    if value in (None, ""):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def https_or_none(value):
    if isinstance(value, str) and value.startswith("https://") and len(value) <= 500:
        return value
    return None


def slug_id(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return (slug or "item")[:80]


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def text_child(node, name: str) -> str | None:
    for child in list(node):
        if local_name(child.tag) == name and child.text:
            return child.text
    return None


def attr_child(node, name: str, attr: str) -> str | None:
    for child in list(node):
        if local_name(child.tag) == name and child.attrib.get(attr):
            return child.attrib[attr]
    return None


def unfold_ical(payload: str) -> str:
    return re.sub(r"\r?\n[ \t]", "", payload)


# ---------------------------------------------------------------------------
# Self-test. Fixture files only. No network.
# ---------------------------------------------------------------------------

def self_test() -> None:
    assert extract_addresses("Public hearing for 10 Oak Street variance") == ["10 Oak Street"]
    assert extract_addresses("Hall Avenue, Front Street to Palmer Street")[0] == "Hall Avenue"
    assert "200 east 3rd st jamestown" == address_key("200 East Third Street, Jamestown, NY 14701")
    assert address_key("200 East 3rd Street, Jamestown, New York") == address_key("200 E Third Street, Jamestown, NY 14701")
    assert address_matches("West Third Street, Jamestown", "10 West Third Street, Jamestown, NY 14701")
    assert not address_matches("st", "200 East Third Street")
    assert clean("  <b>Hello</b>   there ", 40) == "Hello there"
    assert clean("&#8211; Main", 40) == "- Main"

    region = {
        "region": {"id": "us-example", "name": "Example, New York, USA", "country": "US", "bbox": [-79.32, 42.05, -79.18, 42.14]},
    }
    source = {
        "id": "example",
        "publisher": "Example City",
        "url": "https://example.com/civic",
        "license": "Public record",
        "attribution": "Example City. Headline and link only.",
        "body": "City Council",
        "fields": {"id": "permit_number", "title": "work", "address": "address", "date": "issued", "url": "link"},
    }
    fixture = ROOT / "supabase" / "scripts" / "fixtures" / "civic"

    legistar = parse_legistar((fixture / "legistar.json").read_text(encoding="utf-8"), source, region, None)
    assert any(row["item_type"] == "hearing" and row["address_text"] == "10 Oak Street" for row in legistar)
    assert any(row["item_type"] == "agenda_item" for row in legistar)

    plus = parse_civicplus((fixture / "civicplus.rss").read_text(encoding="utf-8"), source, region, None)
    assert plus and plus[0]["source_url"].startswith("https://example.com/AgendaCenter/")
    html_rows = parse_civicplus((fixture / "civicplus.html").read_text(encoding="utf-8"), source, region, None)
    assert any("Agenda" in row["title"] for row in html_rows)

    permits = parse_socrata((fixture / "socrata.json").read_text(encoding="utf-8"), source, region, None)
    assert permits and permits[0]["item_type"] == "permit" and permits[0]["address_text"].startswith("15 Pine")

    arc = parse_arcgis((fixture / "arcgis.json").read_text(encoding="utf-8"), source, region, None)
    assert arc and arc[0]["lat"] == 42.09 and arc[0]["lng"] == -79.24

    feed = parse_rss_ical((fixture / "feed.rss").read_text(encoding="utf-8"), source, region, None)
    assert feed and feed[0]["item_type"] == "notice"
    ical = parse_rss_ical((fixture / "feed.ics").read_text(encoding="utf-8"), source, region, None)
    assert ical and ical[0]["address_text"] == "200 East Third Street" and ical[0]["occurs_on"] == "2026-11-09"

    manual_source = dict(source, file=str((fixture / "manual.yaml").relative_to(ROOT)))
    manual = parse_manual(manual_source, region, None)
    assert len(manual) == 1 and manual[0]["item_type"] == "hearing"
    csv_source = dict(source, file=str((fixture / "manual.csv").relative_to(ROOT)))
    csv_rows = parse_manual(csv_source, region, None)
    assert csv_rows and csv_rows[0]["external_id"] == "csv-1"

    outside = make_item(
        source, region, None,
        external_id="far", item_type="permit", title="Far away", summary="no",
        occurs_on="2026-10-01", body_name=None, source_url="https://example.com/far",
        address="1 Main Street", text="", lat=40.0, lng=-73.0,
    )
    assert outside["lat"] is None and outside["lng"] is None

    jamestown = _load(ROOT / "supabase" / "regions" / "us" / "jamestown-ny.yaml")
    assert jamestown["civic"]["sources"][0]["adapter"] == "manual"
    fresh = build(jamestown, geocode=False)
    snapshot = json.loads((ROOT / "supabase" / "seed" / "civic" / "jamestown-ny.json").read_text(encoding="utf-8"))
    assert _canon(fresh) == _canon(snapshot)
    assert any(row["external_id"] == "hall-ave-reconstruction-2026" and row["lat"] for row in fresh)
    assert any(row["external_id"] == "main-st-viaduct-rfp-2026" and row["lat"] is None for row in fresh)
    try:
        assert_civic_block({"civic": {"sources": [{"id": "x", "adapter": "paid", "url": "https://x", "license": "n", "attribution": "n"}]}})
    except CivicError:
        pass
    else:
        raise AssertionError("unknown adapter was accepted")


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except (CivicError, open_data.ImportError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
