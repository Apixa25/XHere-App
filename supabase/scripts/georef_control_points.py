#!/usr/bin/env python3
"""Add surveyed control points for one Sanborn sheet.

Pixels are native image pixels, origin at the top left, on the main map.
Do not click an inset. The map overlay stays off until a sheet has at least
three points. This script does not guess pixels.

    python3 supabase/scripts/georef_control_points.py --check supabase/regions/us/jamestown-ny.georef.json
    python3 supabase/scripts/georef_control_points.py \\
        --file supabase/regions/us/jamestown-ny.georef.json \\
        --sheet sanborn06011_001-2 \\
        --pixel-x 1200 --pixel-y 3400 \\
        --longitude -79.241835 --latitude 42.099927 \\
        --label "North Main and East 7th"
    python3 supabase/scripts/georef_control_points.py \\
        --file supabase/regions/us/jamestown-ny.georef.json \\
        --sheet sanborn06011_001-2 --corners
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    if "--check" in argv:
        check(Path(argv[argv.index("--check") + 1]))
        print("georef file ok")
        return 0
    path = Path(_arg(argv, "--file"))
    sheet_id = _arg(argv, "--sheet")
    doc = json.loads(path.read_text(encoding="utf-8"))
    sheet = next((row for row in doc.get("sheets") or [] if row.get("sheet_id") == sheet_id), None)
    if sheet is None:
        raise SystemExit(f"no sheet {sheet_id}")
    if "--corners" in argv:
        points = sheet.get("control_points") or []
        print(f"{sheet_id}: {len(points)} control point(s); overlay needs 3")
        for point in points:
            print(
                f"  {point.get('label') or 'point'} pixel {point['pixel_x']},{point['pixel_y']} "
                f"-> {point['latitude']},{point['longitude']}"
            )
        return 0
    point = {
        "pixel_x": float(_arg(argv, "--pixel-x")),
        "pixel_y": float(_arg(argv, "--pixel-y")),
        "longitude": float(_arg(argv, "--longitude")),
        "latitude": float(_arg(argv, "--latitude")),
        "label": _arg(argv, "--label"),
    }
    if not (-180 <= point["longitude"] <= 180 and -90 <= point["latitude"] <= 90):
        raise SystemExit("longitude or latitude is out of range")
    if point["pixel_x"] < 0 or point["pixel_y"] < 0:
        raise SystemExit("pixels start at the top left and cannot be negative")
    if point["pixel_x"] > sheet["width"] or point["pixel_y"] > sheet["height"]:
        raise SystemExit("pixel is outside the native image")
    sheet.setdefault("control_points", []).append(point)
    path.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"added {point['label']} ({len(sheet['control_points'])} points)")
    return 0


def check(path: Path) -> None:
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not doc.get("region_id"):
        raise SystemExit("region_id is required")
    if not doc.get("sheets"):
        raise SystemExit("sheets is required")
    for sheet in doc["sheets"]:
        for key in ("sheet_id", "width", "height", "image_url", "iiif_service", "item_url"):
            if key not in sheet:
                raise SystemExit(f"{sheet.get('sheet_id')} missing {key}")
        if not str(sheet["image_url"]).startswith("https://"):
            raise SystemExit("image_url must be https")
        if not str(sheet["iiif_service"]).startswith("https://"):
            raise SystemExit("iiif_service must be https")
        for point in sheet.get("control_points") or []:
            for key in ("pixel_x", "pixel_y", "longitude", "latitude"):
                if key not in point:
                    raise SystemExit(f"control point missing {key}")
        for guide in sheet.get("guides") or []:
            if "latitude" not in guide or "longitude" not in guide or "label" not in guide:
                raise SystemExit("a guide needs label, latitude, and longitude")


def _arg(argv: list[str], flag: str) -> str:
    if flag not in argv:
        raise SystemExit(f"missing {flag}")
    return argv[argv.index(flag) + 1]


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except Exception as error:
        print(f"georef_control_points: {error}", file=sys.stderr)
        raise SystemExit(1) from error
