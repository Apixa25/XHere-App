// geo-proxy: the only door from the app to Nominatim and Overpass.
// Upstream hosts come from the caller (env, then private.geo_upstream).
// This file does not embed nominatim.openstreetmap.org or overpass-api.de.
// The phone sends a user JWT. The service-role key stays on the function.

export const USER_AGENT = "XHere/0.1 (https://xhere.world; public-places)";
export const NOMINATIM_TTL_SECONDS = 30 * 24 * 60 * 60;
export const OVERPASS_TTL_SECONDS = 7 * 24 * 60 * 60;
export const NOMINATIM_MISS_TTL_SECONDS = 24 * 60 * 60;
export const OVERPASS_EMPTY_TTL_SECONDS = 6 * 60 * 60;
const MAX_BODY = 262144;

export interface CachedGeo {
  body: unknown;
  fresh: boolean;
}

export interface GeoDeps {
  endpoint(kind: "nominatim" | "overpass"): Promise<string>;
  readCache(key: string): Promise<CachedGeo | null>;
  writeCache(key: string, kind: "nominatim" | "overpass", body: unknown, ttlSeconds: number): Promise<void>;
  claim(kind: "nominatim" | "overpass"): Promise<boolean>;
  fetchUpstream(url: string, init: RequestInit): Promise<Response>;
}

/** Half away from zero, three decimal places. Same rule as Here.cell. */
export function cell(value: number): number {
  const negative = value < 0;
  const scaled = Math.abs(value) * 1000;
  const nearest = Math.floor(scaled + 0.5 + 1e-6);
  return (negative ? -nearest : nearest) / 1000;
}

const STEP = 0.02;

function snap(value: number, up: boolean): number {
  const ticks = value / STEP;
  const k = up ? Math.ceil(ticks - 1e-9) : Math.floor(ticks + 1e-9);
  return Math.round(k * STEP * 1e6) / 1e6;
}

/** Neighbors in the same 0.02° cell share one Overpass entry. A huge view shrinks to the center. */
export function roundBox(south: number, west: number, north: number, east: number): {
  south: number;
  west: number;
  north: number;
  east: number;
  key: string;
} {
  let s = snap(south, false);
  let w = snap(west, false);
  let n = snap(north, true);
  let e = snap(east, true);
  if (n - s > 0.5 || e - w > 0.5 || n <= s || e <= w) {
    const lat = (south + north) / 2;
    const lng = (west + east) / 2;
    s = snap(lat - 0.1, false);
    n = snap(lat + 0.1, true);
    w = snap(lng - 0.1, false);
    e = snap(lng + 0.1, true);
  }
  return { south: s, west: w, north: n, east: e, key: `overpass:${s}:${w}:${n}:${e}` };
}

export function overpassQuery(box: { south: number; west: number; north: number; east: number }): string {
  const { south, west, north, east } = box;
  return "[out:json][timeout:12];(" +
    `node["historic"]["name"](${south},${west},${north},${east});` +
    `way["historic"]["name"](${south},${west},${north},${east});` +
    ");out center 20;";
}

export function httpsEndpoint(value: string): string | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.protocol !== "https:") return null;
  if (url.username || url.password) return null;
  return url.toString();
}

export async function handleGeo(req: Request, deps: GeoDeps): Promise<Response> {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const authorization = req.headers.get("Authorization") ?? "";
  if (!authorization.toLowerCase().startsWith("bearer ") || authorization.trim().length < 16) {
    return json({ error: "not_authenticated" }, 401);
  }
  let raw: Record<string, unknown>;
  try {
    raw = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  const op = raw.op;
  try {
    if (op === "reverse") return await reverse(raw, deps);
    if (op === "overpass") return await overpass(raw, deps);
    return json({ error: "invalid_op" }, 400);
  } catch {
    return json({ ok: true, cached: false, limited: false, body: null });
  }
}

async function reverse(raw: Record<string, unknown>, deps: GeoDeps): Promise<Response> {
  const lat = finite(raw.lat);
  const lng = finite(raw.lng);
  if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
    return json({ error: "invalid_location" }, 400);
  }
  const roundedLat = cell(lat);
  const roundedLng = cell(lng);
  const key = `nominatim:${roundedLat}:${roundedLng}`;
  return await lookup(deps, "nominatim", key, async (endpoint) => {
    const url = new URL(endpoint);
    url.searchParams.set("format", "jsonv2");
    url.searchParams.set("lat", String(roundedLat));
    url.searchParams.set("lon", String(roundedLng));
    url.searchParams.set("zoom", "17");
    url.searchParams.set("addressdetails", "1");
    const response = await deps.fetchUpstream(url.toString(), {
      headers: {
        accept: "application/json",
        "user-agent": USER_AGENT,
        referer: "https://xhere.world",
      },
    });
    const parsed = await readJson(response);
    if (parsed == null) return null;
    const miss = typeof parsed === "object" && parsed !== null && "error" in parsed;
    return { body: parsed, ttl: miss ? NOMINATIM_MISS_TTL_SECONDS : NOMINATIM_TTL_SECONDS };
  });
}

async function overpass(raw: Record<string, unknown>, deps: GeoDeps): Promise<Response> {
  const south = finite(raw.south);
  const west = finite(raw.west);
  const north = finite(raw.north);
  const east = finite(raw.east);
  if (
    south == null || west == null || north == null || east == null ||
    south < -90 || north > 90 || west < -180 || east > 180 || south >= north
  ) {
    return json({ error: "invalid_location" }, 400);
  }
  const box = roundBox(south, west, north, east);
  return await lookup(deps, "overpass", box.key, async (endpoint) => {
    const response = await deps.fetchUpstream(endpoint, {
      method: "POST",
      headers: {
        accept: "application/json",
        "content-type": "application/x-www-form-urlencoded",
        "user-agent": USER_AGENT,
      },
      body: `data=${encodeURIComponent(overpassQuery(box))}`,
    });
    const parsed = await readJson(response);
    if (parsed == null || typeof parsed !== "object") return null;
    const elements = "elements" in parsed && Array.isArray(parsed.elements) ? parsed.elements : null;
    if (elements == null) return null;
    return {
      body: parsed,
      ttl: elements.length === 0 ? OVERPASS_EMPTY_TTL_SECONDS : OVERPASS_TTL_SECONDS,
    };
  });
}

async function lookup(
  deps: GeoDeps,
  kind: "nominatim" | "overpass",
  key: string,
  load: (endpoint: string) => Promise<{ body: unknown; ttl: number } | null>,
): Promise<Response> {
  const cached = await deps.readCache(key);
  if (cached?.fresh) return json({ ok: true, cached: true, limited: false, body: cached.body });
  const allowed = await deps.claim(kind);
  if (!allowed) {
    if (cached) return json({ ok: true, cached: true, limited: true, body: cached.body });
    return json({ ok: true, cached: false, limited: true, body: null });
  }
  const endpoint = httpsEndpoint(await deps.endpoint(kind));
  if (!endpoint) {
    if (cached) return json({ ok: true, cached: true, limited: false, body: cached.body });
    return json({ ok: true, cached: false, limited: false, body: null });
  }
  const loaded = await load(endpoint);
  if (!loaded) {
    if (cached) return json({ ok: true, cached: true, limited: false, body: cached.body });
    return json({ ok: true, cached: false, limited: false, body: null });
  }
  await deps.writeCache(key, kind, loaded.body, loaded.ttl);
  return json({ ok: true, cached: false, limited: false, body: loaded.body });
}

async function readJson(response: Response): Promise<unknown | null> {
  if (!response.ok) return null;
  const text = await response.text();
  if (text.length === 0 || text.length > MAX_BODY) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function finite(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}
