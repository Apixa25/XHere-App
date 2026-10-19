// Time Machine refresh. One loc.gov call when the region cache is stale.
// The phone does not call this. CI does not deploy it.
// A fresh cache returns without a fetch. A claimed slot fetches once.
// Existing sheet rows stay. Volume rows from the search are merged by id.

export type HistorySheet = {
  id: string;
  title: string;
  year: number | null;
  date_label: string;
  item_url: string;
  iiif_service: string | null;
  thumbnail_url: string | null;
  sheet_index: number | null;
  digitized: boolean;
  attribution: string;
  license: string;
};

export type HistoryBundle = {
  region_id: string;
  name: string;
  country: string;
  town: string;
  state: string;
  west: number;
  south: number;
  east: number;
  north: number;
  updated_at: string;
  sheets: HistorySheet[];
  photos: unknown[];
  georef: unknown[];
  clippings: unknown[];
};

export type HistoryDeps = {
  load: (regionId: string) => Promise<HistoryBundle | null>;
  claim: () => Promise<boolean>;
  fetchLoc: (url: string) => Promise<unknown>;
  upsert: (doc: unknown) => Promise<void>;
  now?: number;
};

const ATTRIBUTION =
  "Library of Congress, Geography and Map Division, Sanborn Maps Collection. Public domain.";
const FRESH_MS = 24 * 60 * 60 * 1000;

export function sanbornUrl(town: string, state: string): string {
  const token = (value: string) => value.trim().toLowerCase().replace(/\s+/g, "+");
  const fa = `location:${token(town)}|location:${token(state)}`;
  return `https://www.loc.gov/collections/sanborn-maps/?fa=${encodeURIComponent(fa)}&fo=json&c=40`;
}

export function sheetsFromSanborn(payload: unknown, town: string, state: string): HistorySheet[] {
  const results = payload && typeof payload === "object" && "results" in payload
    ? (payload as { results: unknown }).results
    : null;
  if (!Array.isArray(results)) return [];
  const townKey = norm(town);
  const stateKey = norm(state);
  const sheets: HistorySheet[] = [];
  for (const item of results) {
    if (!item || typeof item !== "object") continue;
    const row = item as Record<string, unknown>;
    const places = asStrings(row.location).map(norm);
    const states = asStrings(row.location_state).map(norm);
    if (!places.some((place) => place.includes(townKey))) continue;
    if (states.length > 0 && !states.some((place) => place === stateKey || place.startsWith(stateKey))) continue;
    if (!places.some((place) => place.includes(stateKey)) && states.length === 0) continue;
    const itemUrl = typeof row.url === "string" ? row.url : "";
    if (!itemUrl.startsWith("https://")) continue;
    const id = sanbornId(String(row.id ?? itemUrl));
    if (!id) continue;
    const date = typeof row.date === "string" ? row.date : "";
    const digitized = row.digitized === true;
    const service = digitized ? iiifService(row.image_url) : null;
    sheets.push({
      id,
      title: String(row.title ?? "Sanborn map").trim().slice(0, 180),
      year: yearOf(date),
      date_label: date,
      item_url: itemUrl,
      iiif_service: service,
      thumbnail_url: service ? `${service}/full/200,/0/default.jpg` : null,
      sheet_index: null,
      digitized,
      attribution: ATTRIBUTION,
      license: "Public domain",
    });
  }
  return sheets;
}

export async function handleHistory(request: Request, deps: HistoryDeps): Promise<Response> {
  if (request.method !== "POST") return json(405, { error: "method" });
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return json(400, { error: "invalid_region" });
  }
  const regionId = body && typeof body === "object" && "region_id" in body
    ? (body as { region_id: unknown }).region_id
    : null;
  if (typeof regionId !== "string" || !/^[a-z0-9]+(-[a-z0-9]+)*$/.test(regionId)) {
    return json(400, { error: "invalid_region" });
  }
  const region = await deps.load(regionId);
  if (!region) return json(404, { error: "unknown_region" });
  const updated = Date.parse(region.updated_at);
  const now = deps.now ?? Date.now();
  if (Number.isFinite(updated) && now - updated < FRESH_MS) {
    return json(200, { refreshed: false, reason: "fresh", fetches: 0 });
  }
  const claimed = await deps.claim();
  if (!claimed) return json(429, { error: "rate_limited", fetches: 0 });
  const payload = await deps.fetchLoc(sanbornUrl(region.town, region.state));
  const incoming = sheetsFromSanborn(payload, region.town, region.state);
  const incomingIds = new Set(incoming.map((sheet) => sheet.id));
  const kept = (region.sheets ?? []).filter((sheet) => sheet.sheet_index != null && !incomingIds.has(sheet.id));
  await deps.upsert({
    region: {
      region_id: region.region_id,
      name: region.name,
      country: region.country,
      town: region.town,
      state: region.state,
      west: region.west,
      south: region.south,
      east: region.east,
      north: region.north,
    },
    sheets: incoming.concat(kept),
    photos: region.photos ?? [],
    georef: region.georef ?? [],
    clippings: region.clippings ?? [],
  });
  return json(200, { refreshed: true, sheets: incoming.length, fetches: 1 });
}

function sanbornId(value: string): string | null {
  const match = value.match(/sanborn\d+_\d+/);
  return match ? match[0] : null;
}

function iiifService(images: unknown): string | null {
  if (!Array.isArray(images)) return null;
  for (const image of images) {
    if (typeof image !== "string" || !image.includes("/iiif/") || !image.includes("/full/")) continue;
    const base = image.split("/full/")[0].split("#")[0];
    if (base.startsWith("https://")) return base;
  }
  return null;
}

function yearOf(value: string): number | null {
  const match = value.match(/1[7-9]\d{2}|20\d{2}/);
  return match ? Number(match[0]) : null;
}

function asStrings(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === "string");
}

function norm(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}
