import { categoryMeta } from "./categories.mjs";

/**
 * Demo drops used when PUBLIC_SUPABASE_URL is unset or still the placeholder.
 * short_id matches the database check: 12 characters from [A-Za-z0-9_-].
 *
 * secretBody is the hidden text. Share pages must never render it.
 * It exists so the build check can prove the teaser stayed a teaser.
 * Coordinates are the public display points (Jamestown, NY), the same
 * spots as supabase/seed.sql. They are not private exact locations.
 */
export const FIXTURES = [
  {
    shortId: "JmtwnHist01a",
    title: "Fenton Mansion",
    categorySlug: "historical_fact",
    lat: 42.09122,
    lng: -79.2392,
    revealRadiusM: 150,
    locationPrecisionM: 0,
    status: "confirmed",
    authorHandle: "jamestown_demo",
    secretBody: "SENTINEL_HIDDEN_BODY_fenton_1863_governor_do_not_render",
  },
  {
    shortId: "JmtwnNote02b",
    title: "Benches in the shade",
    categorySlug: "note",
    lat: 42.09368,
    lng: -79.24289,
    revealRadiusM: 100,
    locationPrecisionM: 0,
    status: "active",
    authorHandle: "jamestown_demo",
    secretBody: "SENTINEL_HIDDEN_BODY_benches_west_third_do_not_render",
  },
  {
    shortId: "JmtwnInc03cd",
    title: "Bike stolen from a rack",
    categorySlug: "incident_report",
    lat: 42.09824,
    lng: -79.24382,
    revealRadiusM: 2000,
    locationPrecisionM: 100,
    status: "active",
    authorHandle: "should_not_show_on_sensitive",
    secretBody: "SENTINEL_HIDDEN_BODY_bike_person_description_do_not_render",
  },
];

const SHORT_ID = /^[A-Za-z0-9_-]{12}$/;
const PLACEHOLDER_KEY = "your-anon-key";

export function isShortId(value) {
  return typeof value === "string" && SHORT_ID.test(value);
}

export function isSupabaseConfigured(env = {}) {
  const url = String(env.url || "").trim().replace(/\/$/, "");
  const key = String(env.key || "").trim();
  if (!url || !key) return false;
  if (!/^https:\/\//.test(url)) return false;
  if (url.includes("your-project-ref")) return false;
  if (key === PLACEHOLDER_KEY || key.startsWith("your-")) return false;
  return true;
}

export function fixtureById(shortId) {
  return FIXTURES.find((drop) => drop.shortId === shortId) || null;
}

export function fixtureParams() {
  return FIXTURES.map((drop) => ({ params: { short_id: drop.shortId } }));
}

/**
 * The only drop shape the website is allowed to render.
 * body, attributes, media, and tags are not fields here.
 */
export function toPublicDrop(row) {
  if (!row || !isShortId(row.shortId || row.short_id)) return null;
  const slug = row.categorySlug || row.category_slug || "note";
  const meta = categoryMeta(slug);
  const precision = Number(row.locationPrecisionM ?? row.location_precision_m ?? 0);
  const sensitive = meta.sensitive;
  const author = row.authorHandle ?? row.author_handle ?? null;
  return {
    shortId: row.shortId || row.short_id,
    title: String(row.title || "A drop on XHere").slice(0, 140),
    categorySlug: slug,
    categoryLabel: meta.label,
    groupLabel: meta.group,
    lat: Number(row.lat),
    lng: Number(row.lng),
    revealRadiusM: Number(row.revealRadiusM ?? row.reveal_radius_m ?? 0),
    locationPrecisionM: precision,
    sensitive,
    approximate: sensitive || precision > 0,
    status: String(row.status || "active"),
    authorHandle: sensitive ? null : author || null,
  };
}

export function publicFromFixture(fixture) {
  if (!fixture) return null;
  return toPublicDrop(fixture);
}

/**
 * drop_teaser never reveals for a signed-out caller. This client also
 * drops body, attributes, and media if a response ever included them.
 */
export async function fetchTeaser(shortId, env) {
  const url = String(env.url).trim().replace(/\/$/, "");
  const key = String(env.key).trim();
  const response = await fetch(`${url}/rest/v1/rpc/drop_teaser`, {
    method: "POST",
    headers: {
      apikey: key,
      authorization: `Bearer ${key}`,
      "content-type": "application/json",
      accept: "application/json",
    },
    body: JSON.stringify({ p_short_id: shortId }),
    signal: AbortSignal.timeout(8000),
  });

  if (response.status === 404) return null;
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const message = payload && (payload.message || payload.code);
    if (message === "drop_not_found" || response.status === 406) return null;
    throw new Error(`drop_teaser failed (${response.status})`);
  }
  const row = Array.isArray(payload) ? payload[0] : payload;
  if (!row || !row.short_id) return null;
  return toPublicDrop(row);
}

export async function loadPublicDrop(shortId, env = {}) {
  if (!isShortId(shortId)) return null;
  if (isSupabaseConfigured(env)) {
    try {
      const live = await fetchTeaser(shortId, env);
      if (live) return live;
    } catch (error) {
      const fixture = publicFromFixture(fixtureById(shortId));
      if (fixture) return fixture;
      throw error;
    }
  }
  return publicFromFixture(fixtureById(shortId));
}
