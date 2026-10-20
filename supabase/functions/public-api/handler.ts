// HTTP surface for the public read API (research recommendation #8).
// Tests pass PublicApiDeps so they never open a database connection.
// The handler ignores any caller JWT. Widening a teaser is not this API.

export const API_TERMS = {
  note:
    "This is a read-only public API. Every item includes attribution and license. ODbL and CC BY-SA items are share-alike: if you adapt them, keep that license and the attribution. User drops are teasers only (license xhere-teaser). The response uses the signed-out view: display coordinates, no hidden text, and no pending, expired, removed, demo, or reduced-trust content.",
  share_alike: ["ODbL-1.0", "CC-BY-SA-4.0"],
  privacy:
    "PLAN section 5 applies. Coordinates are the display point. A blurred category returns the blurred point. This API does not keep the lat and lon you send. A handle is included only for a user teaser whose share page already shows that handle.",
} as const;

const MAX_RADIUS_M = 1000;
const DEFAULT_RADIUS_M = 300;

const FORBIDDEN_KEYS = new Set([
  "body",
  "attributes",
  "media",
  "tags",
  "author_id",
  // Community contributions are not part of this API. An author id stays forbidden.
  "geog",
  "email",
  "display_name",
  "author_badges",
  "storage_path",
  "location_trust",
  "counts_for_credit",
  "view_count",
  "confirm_count",
  "deny_count",
  "my_verdict",
  "is_mine",
  "started",
  "completed",
  "reached",
  "raw_user_meta_data",
  "embedding",
  "content_filters",
  "moderation_mode",
  "attributes_schema",
  "pattern",
  "min_tier_to_post",
  "premod_exempt_tier",
  "posting_notice",
]);

export interface RateDecision {
  allowed: boolean;
  limit: number;
  remaining: number;
  retry_after_seconds: number;
}

export interface PublicApiDeps {
  take(ipHash: string): Promise<RateDecision>;
  here(lat: number, lon: number, radiusM: number): Promise<unknown>;
  drop(id: string): Promise<unknown>;
  categories(): Promise<unknown>;
  walks(lat: number, lon: number): Promise<unknown>;
  thing(id: string): Promise<unknown>;
}

/** The database could not answer. The response stays generic. */
export class UnavailableError extends Error {
  constructor() {
    super("unavailable");
    this.name = "UnavailableError";
  }
}

/** A known validation failure from public_api_* (SQLSTATE 22023). */
export class QueryError extends Error {
  constructor(readonly code: string) {
    super(code);
    this.name = "QueryError";
  }
}

export type Route =
  | { kind: "index" }
  | { kind: "here" }
  | { kind: "categories" }
  | { kind: "walks" }
  | { kind: "drop"; id: string }
  | { kind: "thing"; id: string }
  | { kind: "unknown" }
  | { kind: "bad_id" }
  | { kind: "bad_thing" };

const SHORT_ID = /^[A-Za-z0-9_-]{12}$/;
const VOICE_ID = /^[0-9a-f]{12}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HANDLE = /^[a-z0-9_]{3,24}$/;

export function shareAlike(license: string): boolean {
  return /odbl|cc\s*by-sa|cc-by-sa/i.test(license);
}

export function routePath(url: string): string {
  const pathname = new URL(url).pathname.replace(/\/+$/, "") || "/";
  const marker = "/public-api";
  const at = pathname.lastIndexOf(marker);
  const rest = at >= 0 ? pathname.slice(at + marker.length) : pathname;
  if (rest === "" || rest === "/") return "/";
  return rest.startsWith("/") ? rest : `/${rest}`;
}

export function parseDropId(raw: string): string | null {
  let id: string;
  try {
    id = decodeURIComponent(raw);
  } catch {
    return null;
  }
  if (SHORT_ID.test(id)) return id;
  if (UUID.test(id)) return id.toLowerCase();
  return null;
}

/** A 12-hex voice id, or the thing's uuid. Advertising keys are not accepted here. */
export function parseThingId(raw: string): string | null {
  let id: string;
  try {
    id = decodeURIComponent(raw);
  } catch {
    return null;
  }
  if (VOICE_ID.test(id)) return id;
  if (UUID.test(id)) return id.toLowerCase();
  return null;
}

export function matchRoute(path: string): Route {
  if (path === "/" || path === "/v1") return { kind: "index" };
  if (path === "/v1/here") return { kind: "here" };
  if (path === "/v1/categories") return { kind: "categories" };
  if (path === "/v1/walks") return { kind: "walks" };
  const thing = path.match(/^\/v1\/things\/([^/]+)$/);
  if (thing) {
    const id = parseThingId(thing[1]);
    return id ? { kind: "thing", id } : { kind: "bad_thing" };
  }
  const drop = path.match(/^\/v1\/drops\/([^/]+)$/);
  if (!drop) return { kind: "unknown" };
  const id = parseDropId(drop[1]);
  return id ? { kind: "drop", id } : { kind: "bad_id" };
}

/** Last proxy hop, then x-real-ip. A client-supplied list cannot pick a fresh bucket by itself. */
export function clientAddress(req: Request): string {
  const forwarded = req.headers.get("x-forwarded-for") ?? "";
  const hops = forwarded.split(",").map((part) => normalizeIp(part)).filter((part): part is string => part !== null);
  if (hops.length > 0) return hops[hops.length - 1];
  const real = normalizeIp(req.headers.get("x-real-ip"));
  if (real) return real;
  return "unknown";
}

export async function hashClient(address: string): Promise<string> {
  const bytes = new TextEncoder().encode(`xhere-public-api-v1\n${address.toLowerCase()}`);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function handlePublicApi(req: Request, deps: PublicApiDeps): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: baseHeaders("none") });
  }
  if (req.method !== "GET") {
    return json({ error: "method_not_allowed", terms: API_TERMS }, 405, "none", null, "GET, OPTIONS");
  }
  if (req.url.length > 2000) {
    return json({ error: "invalid_query", detail: "url is too long", terms: API_TERMS }, 400, "none");
  }

  const route = matchRoute(routePath(req.url));
  let decision: RateDecision;
  try {
    decision = await deps.take(await hashClient(clientAddress(req)));
  } catch (error) {
    if (error instanceof QueryError) {
      return json({ error: error.code, terms: API_TERMS }, 400, "none");
    }
    if (error instanceof UnavailableError) {
      return json({ error: "unavailable", terms: API_TERMS }, 503, "none");
    }
    throw error;
  }
  if (!decision.allowed) {
    return json(
      { error: "rate_limited", limit: decision.limit, retry_after_seconds: decision.retry_after_seconds, terms: API_TERMS },
      429,
      "none",
      decision,
    );
  }

  try {
    if (route.kind === "bad_id" || route.kind === "bad_thing") {
      const detail = route.kind === "bad_thing"
        ? "id must be a 12-character voice id or a uuid"
        : "id must be a 12-character short id or a uuid";
      return json({ error: "invalid_id", detail, terms: API_TERMS }, 400, "none", decision);
    }
    if (route.kind === "unknown") {
      return json({ error: "not_found", terms: API_TERMS }, 404, "short", decision);
    }
    if (route.kind === "index") {
      return json({
        api: "xhere",
        version: "v1",
        endpoints: [
          { method: "GET", path: "/v1/here", query: ["lat", "lon", "radius"] },
          { method: "GET", path: "/v1/drops/{id}" },
          { method: "GET", path: "/v1/things/{id}" },
          { method: "GET", path: "/v1/categories" },
          { method: "GET", path: "/v1/walks", query: ["lat", "lon"] },
        ],
        terms: API_TERMS,
      }, 200, "index", decision);
    }
    if (route.kind === "categories") {
      const body = presentCatalog(await deps.categories());
      return json({ ...body, terms: API_TERMS }, 200, "categories", decision);
    }
    if (route.kind === "here") {
      const point = readPoint(req.url);
      if (!point.ok) return json({ error: point.error, detail: point.detail, terms: API_TERMS }, 400, "none", decision);
      const radius = readRadius(req.url);
      if (!radius.ok) return json({ error: radius.error, detail: radius.detail, terms: API_TERMS }, 400, "none", decision);
      const raw = await deps.here(point.lat, point.lon, radius.radiusM);
      return json(presentHere(raw, point.lat, point.lon, radius.radiusM), 200, "here", decision);
    }
    if (route.kind === "walks") {
      const point = readPoint(req.url);
      if (!point.ok) return json({ error: point.error, detail: point.detail, terms: API_TERMS }, 400, "none", decision);
      const raw = await deps.walks(point.lat, point.lon);
      return json(presentWalks(raw, point.lat, point.lon), 200, "walks", decision);
    }
    if (route.kind === "thing") {
      const raw = await deps.thing(route.id);
      const item = presentThing(raw);
      if (!item) return json({ error: "not_found", terms: API_TERMS }, 404, "short", decision);
      return json({ item, terms: API_TERMS }, 200, "thing", decision);
    }
    if (route.kind !== "drop") {
      return json({ error: "not_found", terms: API_TERMS }, 404, "short", decision);
    }
    const raw = await deps.drop(route.id);
    const item = presentItem(raw);
    if (!item) return json({ error: "not_found", terms: API_TERMS }, 404, "short", decision);
    return json({ item, terms: API_TERMS }, 200, "drop", decision);
  } catch (error) {
    if (error instanceof QueryError) {
      return json({ error: error.code, terms: API_TERMS }, 400, "none", decision);
    }
    if (error instanceof UnavailableError) {
      return json({ error: "unavailable", terms: API_TERMS }, 503, "none", decision);
    }
    throw error;
  }
}

type PointResult =
  | { ok: true; lat: number; lon: number }
  | { ok: false; error: string; detail: string };

type RadiusResult =
  | { ok: true; radiusM: number }
  | { ok: false; error: string; detail: string };

export function readPoint(url: string): PointResult {
  const params = new URL(url).searchParams;
  const lat = readCoord(params.get("lat"));
  const lonRaw = params.get("lon");
  const lon = readCoord(lonRaw === null || lonRaw === "" ? params.get("lng") : lonRaw);
  if (lat === null || lon === null) {
    return { ok: false, error: "invalid_query", detail: "lat and lon are required" };
  }
  if (Number.isNaN(lat) || Number.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    return { ok: false, error: "invalid_location", detail: "lat must be -90..90 and lon must be -180..180" };
  }
  return { ok: true, lat, lon };
}

export function readRadius(url: string): RadiusResult {
  const raw = new URL(url).searchParams.get("radius");
  if (raw === null || raw === "") return { ok: true, radiusM: DEFAULT_RADIUS_M };
  if (!/^\d{1,4}$/.test(raw)) {
    return { ok: false, error: "invalid_radius", detail: "radius must be a whole number of meters, 1..1000" };
  }
  const radiusM = Number(raw);
  if (radiusM < 1 || radiusM > MAX_RADIUS_M) {
    return { ok: false, error: "invalid_radius", detail: "radius must be 1..1000 m" };
  }
  return { ok: true, radiusM };
}

function readCoord(raw: string | null): number | null {
  if (raw === null || raw === "") return null;
  if (!/^-?\d{1,3}(\.\d{1,8})?$/.test(raw)) return Number.NaN;
  return Number(raw);
}

function normalizeIp(raw: string | null): string | null {
  if (raw === null) return null;
  const value = raw.trim();
  if (value.length === 0 || value.length > 64) return null;
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(value)) {
    const parts = value.split(".").map((part) => Number(part));
    if (parts.every((part) => part >= 0 && part <= 255)) return value;
    return null;
  }
  if (/^[0-9a-f:.]{2,64}$/i.test(value) && value.includes(":")) return value.toLowerCase();
  return null;
}

function presentHere(raw: unknown, lat: number, lon: number, radiusM: number): Record<string, unknown> {
  const row = asRecord(raw) ?? {};
  const items = Array.isArray(row.items) ? row.items.map(presentItem).filter((item) => item !== null) : [];
  return {
    lat,
    lon,
    radius_m: radiusM,
    items,
    live: presentLive(row.live),
    terms: API_TERMS,
  };
}

function presentWalks(raw: unknown, lat: number, lon: number): Record<string, unknown> {
  const row = asRecord(raw) ?? {};
  const walks = Array.isArray(row.walks) ? row.walks.map(presentWalk).filter((walk) => walk !== null) : [];
  return { lat, lon, walks, terms: API_TERMS };
}

function presentCatalog(raw: unknown): Record<string, unknown> {
  const row = asRecord(raw) ?? {};
  const groups = Array.isArray(row.groups) ? row.groups.map(presentCatalogRow).filter((item) => item !== null) : [];
  const categories = Array.isArray(row.categories)
    ? row.categories.map(presentCatalogRow).filter((item) => item !== null)
    : [];
  return { groups, categories };
}

function presentWalk(raw: unknown): Record<string, unknown> | null {
  const row = asRecord(stripForbidden(raw));
  if (!row || typeof row.slug !== "string" || typeof row.title !== "string") return null;
  const stops: Record<string, unknown>[] = [];
  if (Array.isArray(row.stops)) {
    for (const stop of row.stops) {
      const item = presentItem(stop);
      const position = asRecord(stop)?.position;
      if (!item || typeof position !== "number") continue;
      stops.push({ ...item, position });
    }
  }
  if (stops.length === 0) return null;
  const attribution = typeof row.attribution === "string" && row.attribution.trim() !== ""
    ? row.attribution
    : stops.map((stop) => String(stop.attribution)).join(" · ");
  const license = typeof row.license === "string" && row.license.trim() !== "" ? row.license : "mixed";
  return {
    id: row.id ?? null,
    region_id: row.region_id ?? null,
    slug: row.slug,
    title: row.title,
    description: typeof row.description === "string" ? row.description : "",
    distance_m: row.distance_m ?? null,
    duration_min: row.duration_min ?? null,
    attribution,
    license,
    share_alike: shareAlike(license) || stops.some((stop) => stop.share_alike === true),
    stops,
  };
}

function presentCatalogRow(raw: unknown): Record<string, unknown> | null {
  const row = asRecord(stripForbidden(raw));
  if (!row || typeof row.slug !== "string" || typeof row.label !== "string") return null;
  const license = typeof row.license === "string" && row.license.trim() !== "" ? row.license : "xhere-catalog";
  const attribution = typeof row.attribution === "string" && row.attribution.trim() !== ""
    ? row.attribution
    : "XHere category list";
  return { ...row, attribution, license, share_alike: shareAlike(license) };
}

function presentLive(raw: unknown): Record<string, unknown> | null {
  const row = asRecord(stripForbidden(raw));
  if (!row || typeof row.region_id !== "string") return null;
  const license = typeof row.license === "string" && row.license.trim() !== "" ? row.license : "xhere-config";
  const attribution = typeof row.attribution === "string" && row.attribution.trim() !== ""
    ? row.attribution
    : "XHere region configuration. This API does not copy weather-alert text.";
  return {
    region_id: row.region_id,
    name: row.name ?? null,
    country: row.country ?? null,
    weather_provider: row.weather_provider ?? null,
    attribution,
    license,
    share_alike: shareAlike(license),
  };
}

/** Drops a row that is not a public record or a user teaser, and strips hidden fields. */
export function presentItem(raw: unknown): Record<string, unknown> | null {
  const row = asRecord(stripForbidden(raw));
  if (!row) return null;
  if (row.kind !== "public_record" && row.kind !== "user_teaser") return null;
  if (typeof row.title !== "string" || typeof row.short_id !== "string") return null;
  if (typeof row.lat !== "number" || typeof row.lon !== "number") return null;
  if (row.lat < -90 || row.lat > 90 || row.lon < -180 || row.lon > 180) return null;
  const kind = row.kind;
  let license = typeof row.license === "string" ? row.license.trim() : "";
  let attribution = typeof row.attribution === "string" ? row.attribution.trim() : "";
  if (kind === "public_record" && (license === "" || attribution === "")) return null;
  if (kind === "user_teaser") {
    if (license === "") license = "xhere-teaser";
    if (attribution === "") attribution = "XHere user teaser. Hidden text is not included.";
  }
  const handle = typeof row.author_handle === "string" && HANDLE.test(row.author_handle) ? row.author_handle : null;
  return {
    ...row,
    kind,
    summary: kind === "user_teaser" ? null : (typeof row.summary === "string" ? row.summary : null),
    author_handle: kind === "public_record" ? null : handle,
    attribution,
    license,
    share_alike: shareAlike(license),
  };
}

/**
 * Open lookup for one object. Allowlisted fields only.
 * `owner_id` is dropped even when the SQL row includes it.
 * Media is the https url and alt, never a storage path.
 */
export function presentThing(raw: unknown): Record<string, unknown> | null {
  const row = asRecord(raw);
  if (!row) return null;
  if (typeof row.voice_id !== "string" || !VOICE_ID.test(row.voice_id)) return null;
  if (typeof row.title !== "string" || row.title.trim() === "") return null;
  if (typeof row.story !== "string" || row.story.trim() === "") return null;
  const voiceId = row.voice_id;
  return {
    id: typeof row.id === "string" ? row.id : null,
    voice_id: voiceId,
    title: row.title,
    story: row.story,
    category_slug: typeof row.category_slug === "string" ? row.category_slug : null,
    category_label: typeof row.category_label === "string" ? row.category_label : null,
    place_name: typeof row.place_name === "string" ? row.place_name : null,
    lat: typeof row.lat === "number" ? row.lat : null,
    lon: typeof row.lon === "number" ? row.lon : null,
    media: presentThingMedia(row.media),
    seen_count: typeof row.seen_count === "number" ? row.seen_count : 0,
    url: typeof row.url === "string" && row.url.startsWith("https://") ? row.url : `https://xhere.world/t/${voiceId}`,
    attribution: "Story posted on XHere. This API does not say who heard the object.",
    license: "xhere-thing",
    share_alike: false,
  };
}

function presentThingMedia(raw: unknown): { url: string; alt: string | null }[] {
  if (!Array.isArray(raw)) return [];
  const media: { url: string; alt: string | null }[] = [];
  for (const entry of raw) {
    const row = asRecord(entry);
    if (!row || typeof row.url !== "string") continue;
    if (!row.url.startsWith("https://") || row.url.length > 288) continue;
    const alt = typeof row.alt === "string" && row.alt.length <= 140 ? row.alt : null;
    media.push({ url: row.url, alt });
    if (media.length >= 8) break;
  }
  return media;
}

function stripForbidden(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripForbidden);
  if (!value || typeof value !== "object") return value;
  const out: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
    if (FORBIDDEN_KEYS.has(key)) continue;
    out[key] = stripForbidden(child);
  }
  return out;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

type CacheKind = "here" | "drop" | "thing" | "categories" | "walks" | "index" | "short" | "none";

function cacheControl(kind: CacheKind): string {
  switch (kind) {
    case "here":
    case "walks":
      return "public, max-age=60";
    case "drop":
    case "thing":
      return "public, max-age=120";
    case "categories":
      return "public, max-age=3600";
    case "index":
      return "public, max-age=300";
    case "short":
      return "public, max-age=30";
    default:
      return "no-store";
  }
}

function baseHeaders(kind: CacheKind, decision: RateDecision | null = null, allow: string | null = null): Headers {
  const headers = new Headers({
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, OPTIONS",
    "access-control-allow-headers": "content-type, apikey, authorization",
    "access-control-expose-headers": "x-ratelimit-limit, x-ratelimit-remaining, retry-after",
    "access-control-max-age": "86400",
    "cache-control": cacheControl(kind),
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
  });
  if (allow) headers.set("allow", allow);
  if (decision) {
    headers.set("x-ratelimit-limit", String(decision.limit));
    headers.set("x-ratelimit-remaining", String(decision.remaining));
    if (!decision.allowed) headers.set("retry-after", String(decision.retry_after_seconds));
  }
  return headers;
}

function json(
  payload: unknown,
  status: number,
  kind: CacheKind,
  decision: RateDecision | null = null,
  allow: string | null = null,
): Response {
  const headers = baseHeaders(kind, decision, allow);
  headers.set("content-type", "application/json; charset=utf-8");
  return new Response(JSON.stringify(payload), { status, headers });
}
