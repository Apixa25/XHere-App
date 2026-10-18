// strip-exif: drop Exif/XMP, then upload into the private drop-media bucket
// under "<user id>/<uuid>.jpg". The user's JWT is forwarded, so the storage
// policy (folder must match auth.uid()) is what authorizes the write.
// No service-role key is read here.

import { handleStrip } from "./handler.ts";

Deno.serve((req) => handleStrip(req, {
  userId: (request) => currentUserId(request),
  store: (userId, bytes, mime) => upload(requestOf(req), userId, bytes, mime),
}));

function requestOf(req: Request): Request {
  return req;
}

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

async function upload(req: Request, userId: string, bytes: Uint8Array, mime: string): Promise<{
  storage_path: string;
  mime_type: string;
  byte_size: number;
}> {
  const base = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_ANON_KEY");
  const authorization = req.headers.get("Authorization") ?? "";
  if (!base || !key) throw new Error("supabase_not_configured");
  const extension = mime === "image/png" ? "png" : mime === "image/webp" ? "webp" : "jpg";
  const storagePath = `${userId}/${crypto.randomUUID()}.${extension}`;
  const response = await fetch(`${base}/storage/v1/object/drop-media/${storagePath}`, {
    method: "POST",
    headers: {
      apikey: key,
      Authorization: authorization,
      "content-type": mime,
      "cache-control": "max-age=31536000",
    },
    body: bytes,
  });
  if (!response.ok) throw new Error(`storage_${response.status}`);
  return { storage_path: storagePath, mime_type: mime, byte_size: bytes.length };
}
