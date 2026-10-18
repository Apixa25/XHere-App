// Accepts a photo, strips Exif/XMP, and stores it under the caller's id.
// Tests pass a fake user and a fake store so CI does not need Storage.

import { stripMetadata } from "../_shared/exif.ts";

const MAX_BYTES = 10 * 1024 * 1024;

export interface StoredPhoto {
  storage_path: string;
  mime_type: string;
  byte_size: number;
}

export interface StripDeps {
  userId(req: Request): Promise<string | null>;
  store(userId: string, bytes: Uint8Array, mime: string): Promise<StoredPhoto>;
}

export async function handleStrip(req: Request, deps: StripDeps): Promise<Response> {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const userId = await deps.userId(req);
  if (!userId) return json({ error: "not_authenticated" }, 401);
  const mime = (req.headers.get("content-type") ?? "image/jpeg").split(";")[0].trim().toLowerCase();
  if (mime !== "image/jpeg" && mime !== "image/png" && mime !== "image/webp") {
    return json({ error: "unsupported_type" }, 415);
  }
  const raw = new Uint8Array(await req.arrayBuffer());
  if (raw.length === 0 || raw.length > MAX_BYTES) return json({ error: "invalid_image" }, 400);
  const stripped = stripMetadata(raw);
  const stored = await deps.store(userId, stripped, mime);
  if (!stored.storage_path.startsWith(`${userId}/`)) {
    return json({ error: "invalid_media" }, 500);
  }
  return json(stored);
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json" },
  });
}
