// Time Machine refresh door. verify_jwt = true. Not deployed by CI.
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are injected on the hosted function.
// The user JWT only gets the function to run. Writes use the service role,
// which is the only role that can call history_upsert.

import { handleHistory, type HistoryBundle } from "./handler.ts";

const USER_AGENT = "XHereHistoryRefresh/1.0 (https://xhere.world; history cache)";

Deno.serve((request) => handleHistory(request, {
  load: (regionId) => rpc("history_bundle", { p_region_id: regionId }) as Promise<HistoryBundle | null>,
  claim: async () => {
    const allowed = await rpc("history_claim_fetch", {});
    return allowed === true;
  },
  fetchLoc: async (url) => {
    const response = await fetch(url, {
      headers: { accept: "application/json", "user-agent": USER_AGENT },
    });
    if (!response.ok) throw new Error("loc.gov unavailable");
    return await response.json();
  },
  upsert: (doc) => rpc("history_upsert", { p_doc: doc }).then(() => undefined),
}));

async function rpc(fn: string, args: Record<string, unknown>): Promise<unknown> {
  const base = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!base || !key) throw new Error("history cache is not configured");
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
  if (!response.ok) throw new Error("history cache is not configured");
  if (text === "") return null;
  return JSON.parse(text);
}
