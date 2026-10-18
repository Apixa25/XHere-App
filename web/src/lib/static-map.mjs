import { VectorTile } from "@mapbox/vector-tile";
import Pbf from "pbf";

/**
 * Static map image for share pages.
 *
 * Tiles are OpenFreeMap's planet vector tiles, the same source the Android
 * app loads through MapLibre (Liberty / Dark styles). This draws a reduced
 * Positron palette (https://tiles.openfreemap.org/styles/positron) to SVG
 * so the page does not ship MapLibre GL. No API key.
 *
 * Attribution required by those tiles: OpenStreetMap contributors (ODbL),
 * OpenMapTiles, and OpenFreeMap.
 */

const TILEJSON_URL = "https://tiles.openfreemap.org/planet";
const USER_AGENT = "XHere-web/0.1 (+https://xhere.world)";
const ZOOM = 14;
const TILE_PX = 256;
const WIDTH = 1200;
const HEIGHT = 630;
const METERS_PER_PIXEL = 1.8;

const FILL = {
  wood: "#dce0dc",
  grass: "#e4ebe3",
  wetland: "#d5e0e0",
  residential: "#eaeae6",
  park: "#e6e9e5",
  water: "#c2c8ca",
  building: "#e4e2dc",
  background: "#f2f3f0",
};

const tileCache = new Map();
let templatePromise;

function worldPixel(lng, lat, zoom) {
  const scale = TILE_PX * 2 ** zoom;
  const x = ((lng + 180) / 360) * scale;
  const sin = Math.sin((lat * Math.PI) / 180);
  const clamped = Math.min(Math.max(sin, -0.9999), 0.9999);
  const y = (0.5 - Math.log((1 + clamped) / (1 - clamped)) / (4 * Math.PI)) * scale;
  return { x, y };
}

function nativeMetersPerPixel(lat, zoom) {
  return (156543.03392 * Math.cos((lat * Math.PI) / 180)) / 2 ** zoom;
}

async function tileTemplate() {
  if (!templatePromise) {
    templatePromise = fetchJson(TILEJSON_URL)
      .then((json) => {
        const template = json && json.tiles && json.tiles[0];
        if (!template || !template.includes("{z}")) {
          throw new Error("OpenFreeMap tilejson did not include a tile template");
        }
        return template;
      })
      .catch((error) => {
        templatePromise = null;
        throw error;
      });
  }
  return templatePromise;
}

async function fetchJson(url) {
  const response = await fetchRetry(url, { accept: "application/json" });
  return response.json();
}

async function fetchRetry(url, { accept }) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(url, {
        headers: {
          "user-agent": USER_AGENT,
          accept,
          referer: "https://xhere.world/",
        },
        signal: AbortSignal.timeout(12000),
      });
      if (response.ok) return response;
      if (response.status === 404) return response;
      lastError = new Error(`HTTP ${response.status} for ${url}`);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 350 * attempt));
  }
  throw lastError;
}

async function maybeGunzip(bytes) {
  if (!(bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b)) return bytes;
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function loadTile(z, x, y) {
  const key = `${z}/${x}/${y}`;
  if (!tileCache.has(key)) {
    const pending = readTile(z, x, y).catch((error) => {
      tileCache.delete(key);
      throw error;
    });
    tileCache.set(key, pending);
  }
  return tileCache.get(key);
}

async function readTile(z, x, y) {
  const n = 2 ** z;
  if (x < 0 || y < 0 || x >= n || y >= n) return null;
  const template = await tileTemplate();
  const url = template.replace("{z}", String(z)).replace("{x}", String(x)).replace("{y}", String(y));
  const response = await fetchRetry(url, { accept: "application/vnd.mapbox-vector-tile, application/x-protobuf, */*" });
  if (response.status === 404) return null;
  const raw = new Uint8Array(await response.arrayBuffer());
  if (raw.length === 0) return null;
  const bytes = await maybeGunzip(raw);
  return new VectorTile(new Pbf(bytes));
}

function roadStyle(roadClass) {
  switch (roadClass) {
    case "motorway":
      return { casing: 7, inner: 4.2, color: "#ffffff", casingColor: "#cfcfcf" };
    case "trunk":
    case "primary":
      return { casing: 6.2, inner: 3.6, color: "#ffffff", casingColor: "#d5d5d5" };
    case "secondary":
    case "tertiary":
      return { casing: 5.2, inner: 3, color: "#ffffff", casingColor: "#d8d8d8" };
    case "minor":
    case "service":
    case "track":
      return { casing: 0, inner: 1.8, color: "#e4e4e4", casingColor: "#e4e4e4" };
    case "path":
      return null;
    default:
      return null;
  }
}

const CLIP_MIN = -4;
const CLIP_MAX_X = WIDTH + 4;
const CLIP_MAX_Y = HEIGHT + 4;

function intersectX(a, b, x) {
  const dx = b.x - a.x;
  const t = dx === 0 ? 0 : (x - a.x) / dx;
  return { x, y: a.y + t * (b.y - a.y) };
}

function intersectY(a, b, y) {
  const dy = b.y - a.y;
  const t = dy === 0 ? 0 : (y - a.y) / dy;
  return { x: a.x + t * (b.x - a.x), y };
}

function clipAgainst(points, inside, cross) {
  if (points.length === 0) return [];
  const out = [];
  for (let i = 0; i < points.length; i += 1) {
    const current = points[i];
    const previous = points[(i + points.length - 1) % points.length];
    const currentIn = inside(current);
    const previousIn = inside(previous);
    if (currentIn) {
      if (!previousIn) out.push(cross(previous, current));
      out.push(current);
    } else if (previousIn) {
      out.push(cross(previous, current));
    }
  }
  return out;
}

function clipPolygon(points) {
  let clipped = points;
  clipped = clipAgainst(clipped, (p) => p.x >= CLIP_MIN, (a, b) => intersectX(a, b, CLIP_MIN));
  clipped = clipAgainst(clipped, (p) => p.x <= CLIP_MAX_X, (a, b) => intersectX(a, b, CLIP_MAX_X));
  clipped = clipAgainst(clipped, (p) => p.y >= CLIP_MIN, (a, b) => intersectY(a, b, CLIP_MIN));
  clipped = clipAgainst(clipped, (p) => p.y <= CLIP_MAX_Y, (a, b) => intersectY(a, b, CLIP_MAX_Y));
  return clipped;
}

function clipSegment(a, b) {
  let t0 = 0;
  let t1 = 1;
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const p = [-dx, dx, -dy, dy];
  const q = [a.x - CLIP_MIN, CLIP_MAX_X - a.x, a.y - CLIP_MIN, CLIP_MAX_Y - a.y];
  for (let i = 0; i < 4; i += 1) {
    if (p[i] === 0) {
      if (q[i] < 0) return null;
    } else {
      const r = q[i] / p[i];
      if (p[i] < 0) {
        if (r > t1) return null;
        if (r > t0) t0 = r;
      } else {
        if (r < t0) return null;
        if (r < t1) t1 = r;
      }
    }
  }
  return {
    a: { x: a.x + t0 * dx, y: a.y + t0 * dy },
    b: { x: a.x + t1 * dx, y: a.y + t1 * dy },
  };
}

function pathFrom(points, close) {
  if (points.length < 2) return "";
  const rounded = points.map((point) => ({ x: Math.round(point.x), y: Math.round(point.y) }));
  let d = `M${rounded[0].x} ${rounded[0].y}`;
  for (let i = 1; i < rounded.length; i += 1) {
    if (rounded[i].x === rounded[i - 1].x && rounded[i].y === rounded[i - 1].y) continue;
    d += `L${rounded[i].x} ${rounded[i].y}`;
  }
  if (d.indexOf("L") === -1) return "";
  return close ? `${d}Z` : d;
}

function ringPath(ring, project, close) {
  const points = [];
  for (const point of ring) {
    const projected = project(point.x, point.y);
    const prev = points[points.length - 1];
    if (!prev || Math.abs(projected.x - prev.x) + Math.abs(projected.y - prev.y) >= 1.5) {
      points.push(projected);
    }
  }
  if (points.length < 2) return "";
  if (close) return pathFrom(clipPolygon(points), false);
  let d = "";
  let open = false;
  let last = null;
  for (let i = 1; i < points.length; i += 1) {
    const segment = clipSegment(points[i - 1], points[i]);
    if (!segment) {
      open = false;
      last = null;
      continue;
    }
    const startNew = !open || !last || Math.abs(segment.a.x - last.x) > 0.5 || Math.abs(segment.a.y - last.y) > 0.5;
    if (startNew) d += `M${Math.round(segment.a.x)} ${Math.round(segment.a.y)}`;
    d += `L${Math.round(segment.b.x)} ${Math.round(segment.b.y)}`;
    last = segment.b;
    open = true;
  }
  return d;
}

function projectFactory(tileX, tileY, extent, center, scale) {
  return (x, y) => {
    const worldX = tileX * TILE_PX + (x / extent) * TILE_PX;
    const worldY = tileY * TILE_PX + (y / extent) * TILE_PX;
    return {
      x: (worldX - center.x) * scale + WIDTH / 2,
      y: (worldY - center.y) * scale + HEIGHT / 2,
    };
  };
}

function pushPath(bucket, d, close) {
  if (!d) return;
  bucket.push(close ? `${d}Z` : d);
}

export async function renderStaticMapSvg(drop) {
  if (!Number.isFinite(drop.lat) || !Number.isFinite(drop.lng)) {
    throw new Error("Map needs a latitude and longitude");
  }

  const center = worldPixel(drop.lng, drop.lat, ZOOM);
  const scale = nativeMetersPerPixel(drop.lat, ZOOM) / METERS_PER_PIXEL;
  const halfW = WIDTH / 2 / scale;
  const halfH = HEIGHT / 2 / scale;
  const minTileX = Math.floor((center.x - halfW) / TILE_PX);
  const maxTileX = Math.floor((center.x + halfW) / TILE_PX);
  const minTileY = Math.floor((center.y - halfH) / TILE_PX);
  const maxTileY = Math.floor((center.y + halfH) / TILE_PX);
  const tileCount = (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1);
  if (tileCount > 16) throw new Error(`Map requested ${tileCount} tiles`);

  const fills = {
    wood: [],
    grass: [],
    residential: [],
    park: [],
    water: [],
  };
  const waterways = [];
  const casings = new Map();
  const inners = new Map();
  let features = 0;
  let tilesOk = 0;

  for (let tileX = minTileX; tileX <= maxTileX; tileX += 1) {
    for (let tileY = minTileY; tileY <= maxTileY; tileY += 1) {
      const tile = await loadTile(ZOOM, tileX, tileY);
      if (!tile) continue;
      tilesOk += 1;
      for (const layerName of Object.keys(tile.layers)) {
        const layer = tile.layers[layerName];
        const project = projectFactory(tileX, tileY, layer.extent || 4096, center, scale);
        for (let i = 0; i < layer.length; i += 1) {
          const feature = layer.feature(i);
          if (feature.type !== 2 && feature.type !== 3) continue;
          const props = feature.properties || {};
          if (props.brunnel === "tunnel") continue;
          const rings = feature.loadGeometry();
          const close = feature.type === 3;
          if (layerName === "landcover" && (props.class === "wood" || props.class === "grass")) {
            const bucket = props.class === "wood" ? fills.wood : fills.grass;
            for (const ring of rings) pushPath(bucket, ringPath(ring, project, close), close);
            features += 1;
          } else if (layerName === "landuse" && props.class === "residential") {
            for (const ring of rings) pushPath(fills.residential, ringPath(ring, project, close), close);
            features += 1;
          } else if (layerName === "park" && feature.type === 3) {
            for (const ring of rings) pushPath(fills.park, ringPath(ring, project, close), close);
            features += 1;
          } else if (layerName === "water" && feature.type === 3) {
            for (const ring of rings) pushPath(fills.water, ringPath(ring, project, close), close);
            features += 1;
          } else if (layerName === "waterway" && feature.type === 2) {
            for (const ring of rings) pushPath(waterways, ringPath(ring, project, close), false);
            features += 1;
          } else if (layerName === "transportation" && feature.type === 2) {
            const style = roadStyle(props.class);
            if (!style) continue;
            for (const ring of rings) {
              const d = ringPath(ring, project, close);
              if (!d) continue;
              if (style.casing) {
                const key = `${style.casing}|${style.casingColor}`;
                if (!casings.has(key)) casings.set(key, []);
                casings.get(key).push(d);
              }
              const innerKey = `${style.inner}|${style.color}`;
              if (!inners.has(innerKey)) inners.set(innerKey, []);
              inners.get(innerKey).push(d);
            }
            features += 1;
          }
        }
      }
    }
  }

  if (tilesOk === 0 || features < 8) {
    throw new Error(`OpenFreeMap returned no usable geometry (${tilesOk} tiles, ${features} features)`);
  }

  const parts = [
    `<?xml version="1.0" encoding="UTF-8"?>`,
    `<svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${HEIGHT}" viewBox="0 0 ${WIDTH} ${HEIGHT}" role="img">`,
    `<title>Static map from OpenFreeMap vector tiles</title>`,
    `<desc>Drawn from OpenFreeMap planet vector tiles, the MapLibre source used by XHere. © OpenStreetMap contributors, © OpenMapTiles, OpenFreeMap.</desc>`,
    `<rect width="${WIDTH}" height="${HEIGHT}" fill="${FILL.background}"/>`,
  ];

  const fillOrder = [
    ["grass", FILL.grass],
    ["wood", FILL.wood],
    ["residential", FILL.residential],
    ["park", FILL.park],
    ["water", FILL.water],
  ];
  for (const [name, color] of fillOrder) {
    if (fills[name].length === 0) continue;
    const stroke = name === "building" ? ` stroke="#d4d2cc" stroke-width="0.6"` : "";
    parts.push(`<path fill="${color}" fill-rule="evenodd"${stroke} d="${fills[name].join("")}"/>`);
  }
  if (waterways.length) {
    parts.push(
      `<path fill="none" stroke="#b7c5c9" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="${waterways.join("")}"/>`,
    );
  }
  for (const [key, paths] of casings) {
    const [width, color] = key.split("|");
    parts.push(
      `<path fill="none" stroke="${color}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round" d="${paths.join("")}"/>`,
    );
  }
  for (const [key, paths] of inners) {
    const [width, color] = key.split("|");
    parts.push(
      `<path fill="none" stroke="${color}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round" d="${paths.join("")}"/>`,
    );
  }

  if (drop.approximate) {
    const radius = Math.max(36, Math.round((drop.locationPrecisionM || 100) / METERS_PER_PIXEL));
    parts.push(
      `<circle cx="${WIDTH / 2}" cy="${HEIGHT / 2}" r="${radius}" fill="rgba(15,110,86,0.18)" stroke="#0F6E56" stroke-width="3" stroke-dasharray="8 6"/>`,
    );
  } else {
    parts.push(
      `<g transform="translate(${WIDTH / 2} ${HEIGHT / 2})">`,
      `<circle r="20" fill="#ffffff"/>`,
      `<circle r="13" fill="#0F6E56"/>`,
      `<circle r="5" fill="#ffffff"/>`,
      `</g>`,
    );
  }

  parts.push(
    `<rect x="16" y="${HEIGHT - 48}" width="760" height="32" rx="8" fill="rgba(244,247,245,0.94)"/>`,
    `<text x="28" y="${HEIGHT - 27}" fill="#14221c" font-family="ui-sans-serif, system-ui, sans-serif" font-size="16">© OpenStreetMap contributors · © OpenMapTiles · OpenFreeMap</text>`,
    `</svg>`,
  );

  return parts.join("");
}

export const MAP_WIDTH = WIDTH;
export const MAP_HEIGHT = HEIGHT;
