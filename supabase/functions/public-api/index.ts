// public-api: read-only "what's here?" for other apps and assistants.
// verify_jwt is false in config.toml. The caller JWT is ignored.
// Reads go through service_role to public_api_* , which anon cannot execute.
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are injected on the hosted function.
// This file does not deploy itself. CI does not call `functions deploy`.

import { handlePublicApi, QueryError, UnavailableError, type PublicApiDeps, type RateDecision } from "./handler.ts";

Deno.serve((req) => handlePublicApi(req, liveDeps()));

function liveDeps(): PublicApiDeps {
  return {
    take: (ipHash) => rpcRate("public_api_take", { p_ip_hash: ipHash }),
    here: (lat, lon, radiusM) => rpc("public_api_here", { p_lat: lat, p_lon: lon, p_radius_m: radiusM }),
    drop: (id) => rpc("public_api_drop", { p_id: id }),
    categories: () => rpc("public_api_categories", {}),
    walks: (lat, lon) => rpc("public_api_walks", { p_lat: lat, p_lon: lon }),
  };
}

async function rpcRate(fn: string, args: Record<string, unknown>): Promise<RateDecision> {
  const payload = await rpc(fn, args);
  if (!payload || typeof payload !== "object") throw new UnavailableError();
  const row = payload as Record<string, unknown>;
  if (typeof row.allowed !== "boolean" || typeof row.limit !== "number") throw new UnavailableError();
  return {
    allowed: row.allowed,
    limit: row.limit,
    remaining: typeof row.remaining === "number" ? row.remaining : 0,
    retry_after_seconds: typeof row.retry_after_seconds === "number" ? row.retry_after_seconds : 60,
  };
}

async function rpc(fn: string, args: Record<string, unknown>): Promise<unknown> {
  const base = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!base || !key) throw new UnavailableError();
  let response: Response;
  try {
    response = await fetch(`${base.replace(/\/$/, "")}/rest/v1/rpc/${fn}`, {
      method: "POST",
      headers: {
        apikey: key,
        authorization: `Bearer ${key}`,
        "content-type": "application/json",
        accept: "application/json",
      },
      body: JSON.stringify(args),
    });
  } catch {
    throw new UnavailableError();
  }
  const text = await response.text();
  let payload: unknown = null;
  if (text !== "") {
    try {
      payload = JSON.parse(text);
    } catch {
      throw new UnavailableError();
    }
  }
  if (!response.ok) {
    const message = payload && typeof payload === "object" && "message" in payload
      ? String((payload as { message: unknown }).message)
      : "";
    const code = payload && typeof payload === "object" && "code" in payload
      ? String((payload as { code: unknown }).code)
      : "";
    if (code === "22023" && /^(invalid_radius|invalid_location|invalid_id|invalid_client)$/.test(message)) {
      throw new QueryError(message);
    }
    throw new UnavailableError();
  }
  return payload;
}
