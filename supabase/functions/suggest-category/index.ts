// suggest-category: photo labels + text -> top 3 categories.
// Default model is hash-v1 via public.suggest_categories (pgvector).
// Set XHERE_EMBEDDING_MODEL=gte-small only after categories.embedding was
// rebuilt with that model. Supabase injects SUPABASE_URL and SUPABASE_ANON_KEY.

import { handleSuggest, type SuggestBody, type SuggestDeps } from "./handler.ts";
import type { Ranked } from "../_shared/rank.ts";

Deno.serve((req) => {
  const authorization = req.headers.get("Authorization") ?? "";
  const deps: SuggestDeps = {
    model: async () => Deno.env.get("XHERE_EMBEDDING_MODEL") ?? "hash-v1",
    suggestHash: (body, text) => rpcSuggest(body, text, authorization),
    match: (vector) => rpcMatch(vector, authorization),
    nearby: (lat, lng) => rpcNearby(lat, lng, authorization),
  };
  return handleSuggest(req, deps);
});

async function rpcSuggest(body: SuggestBody, text: string, authorization: string): Promise<Ranked[]> {
  const rows = await rpc<Ranked[]>("suggest_categories", {
    p_text: text,
    p_lat: body.lat ?? null,
    p_lng: body.lng ?? null,
    p_hour: body.hour ?? null,
    p_recent: body.recent_slugs ?? [],
    p_limit: 3,
  }, authorization);
  return rows ?? [];
}

async function rpcMatch(
  vector: number[],
  authorization: string,
): Promise<Array<{ slug: string; label: string; cosine: number }>> {
  const literal = `[${vector.join(",")}]`;
  return await rpc("match_categories", { p_embedding: literal, p_limit: 8 }, authorization) ?? [];
}

async function rpcNearby(lat: number, lng: number, authorization: string): Promise<Record<string, number>> {
  const rows = await rpc<Array<{ slug: string; n: number }>>("nearby_category_counts", {
    p_lat: lat,
    p_lng: lng,
    p_radius_m: 500,
  }, authorization);
  const counts: Record<string, number> = {};
  for (const row of rows ?? []) counts[row.slug] = row.n;
  return counts;
}

async function rpc<T>(fn: string, args: unknown, authorization: string): Promise<T> {
  const base = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_ANON_KEY");
  if (!base || !key) throw new Error("supabase_not_configured");
  const response = await fetch(`${base}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: {
      apikey: key,
      Authorization: authorization || `Bearer ${key}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(args),
  });
  if (!response.ok) throw new Error(`rpc_${fn}_${response.status}`);
  return await response.json();
}
