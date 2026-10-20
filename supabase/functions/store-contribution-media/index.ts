// store-contribution-media: strip image metadata, then upload into the
// private contribution-media bucket. The path comes from create_contribution
// and looks like c/<contribution id>/<media id>.<ext>. The user's JWT is
// forwarded, so the storage insert policy is what authorizes the write.
// No service-role key is read here.

import { handleStore } from "./handler.ts";

Deno.serve((req) => handleStore(req, {
  userId: (request) => currentUserId(request),
  store: (request, path, bytes, mime) => upload(request, path, bytes, mime),
}));

async function currentUserId(req: Request): Promise<string | null> {
  const base = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_ANON_KEY");
  const authorization = req.headers.get("Authorization");
  if (!base || !key || !authorization) return null;
  const response = await fetch(`${base}/auth/v1/user`, {
    headers: { apikey: key, Authorization: authorization },
  });
  if (!response.ok) return null;
  const body = await response.json();
  return typeof body.id === "string" ? body.id : null;
}

async function upload(req: Request, path: string, bytes: Uint8Array, mime: string): Promise<void> {
  const base = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_ANON_KEY");
  const authorization = req.headers.get("Authorization") ?? "";
  if (!base || !key) throw new Error("supabase_not_configured");
  const response = await fetch(`${base}/storage/v1/object/contribution-media/${path}`, {
    method: "POST",
    headers: {
      apikey: key,
      Authorization: authorization,
      "content-type": mime,
      "cache-control": "max-age=31536000",
    },
    body: copyBytes(bytes),
  });
  if (!response.ok) throw new Error(`storage_${response.status}`);
}

function copyBytes(bytes: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(copy).set(bytes);
  return copy;
}
