// geo-proxy: Nominatim and Overpass for signed-in phones.
// verify_jwt = true. The user JWT only opens the door.
// Cache and rate-limit writes use SUPABASE_SERVICE_ROLE_KEY, injected on the
// hosted function. Do not put that key in the app, the website, or git.
// Upstream hosts: GEO_NOMINATIM_URL / GEO_OVERPASS_URL, else private.geo_upstream.
// CI tests this file and does not deploy it.

import { handleGeo, httpsEndpoint, type CachedGeo, type GeoDeps } from "./handler.ts";

Deno.serve((request) => handleGeo(request, liveDeps()));

function liveDeps(): GeoDeps {
  return {
    endpoint: (kind) => endpoint(kind),
    readCache: (key) => readCache(key),
    writeCache: (key, kind, body, ttlSeconds) => writeCache(key, kind, body, ttlSeconds),
    claim: (kind) => claim(kind),
    fetchUpstream: (url, init) => fetch(url, init),
  };
}

async function endpoint(kind: "nominatim" | "overpass"): Promise<string> {
  const envName = kind === "nominatim" ? "GEO_NOMINATIM_URL" : "GEO_OVERPASS_URL";
  const fromEnv = httpsEndpoint(Deno.env.get(envName) ?? "");
  if (fromEnv) return fromEnv;
  const fromDb = await rpc("geo_upstream_endpoint", { p_kind: kind });
  if (typeof fromDb === "string") return fromDb;
  throw new Error("geo_upstream_missing");
}

async function readCache(key: string): Promise<CachedGeo | null> {
  const row = await rpc("geo_cache_read", { p_key: key });
  if (!row || typeof row !== "object") return null;
  const record = row as { body?: unknown; fresh?: unknown };
  if (record.body == null || typeof record.fresh !== "boolean") return null;
  return { body: record.body, fresh: record.fresh };
}

async function writeCache(
  key: string,
  kind: "nominatim" | "overpass",
  body: unknown,
  ttlSeconds: number,
): Promise<void> {
  const stored = await rpc("geo_cache_write", {
    p_key: key,
    p_kind: kind,
    p_body: body,
    p_ttl_seconds: ttlSeconds,
  });
  if (stored !== true) throw new Error("geo_cache_write_failed");
}

async function claim(kind: "nominatim" | "overpass"): Promise<boolean> {
  return (await rpc("geo_claim_upstream", { p_kind: kind })) === true;
}

async function rpc(fn: string, args: Record<string, unknown>): Promise<unknown> {
  const base = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!base || !key) throw new Error("geo proxy is not configured");
  const response = await fetch(`${base.replace(/\/$/, "")}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: {
      apikey: key,
      authorization: `Bearer ${key}`,
      "content-type": "application/json",
      accept: "application/json",
    },
    body: JSON.stringify(args),
  });
  const text = await response.text();
  if (!response.ok) throw new Error("geo proxy is not configured");
  if (text === "") return null;
  return JSON.parse(text);
}
