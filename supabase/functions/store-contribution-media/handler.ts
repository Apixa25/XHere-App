// Stores one contribution file at a path create_contribution already reserved.
// The path is two random ids. It must not contain the caller's user id.
// Images are stripped with the same helper strip-exif uses.

import { stripMetadata } from "../_shared/exif.ts";

const MAX_BYTES = 8 * 1024 * 1024;
const THUMB_MAX = 512 * 1024;
const PATH =
  /^c\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\.thumb\.jpg|\.(jpg|png|webp|mp4|webm))$/;

const IMAGE = new Set(["image/jpeg", "image/png", "image/webp"]);
const VIDEO = new Set(["video/mp4", "video/webm"]);

export interface StoredContributionFile {
  storage_path: string;
  mime_type: string;
  byte_size: number;
}

export interface StoreDeps {
  userId(req: Request): Promise<string | null>;
  store(req: Request, path: string, bytes: Uint8Array, mime: string): Promise<void>;
}

export async function handleStore(req: Request, deps: StoreDeps): Promise<Response> {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const userId = await deps.userId(req);
  if (!userId) return json({ error: "not_authenticated" }, 401);
  const path = (req.headers.get("x-storage-path") ?? "").trim();
  if (!PATH.test(path) || path.includes(userId)) return json({ error: "invalid_media" }, 400);
  const mime = (req.headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();
  const thumb = path.endsWith(".thumb.jpg");
  const image = path.endsWith(".jpg") || path.endsWith(".png") || path.endsWith(".webp") || thumb;
  if (thumb && mime !== "image/jpeg") return json({ error: "unsupported_type" }, 415);
  if (image && !IMAGE.has(mime)) return json({ error: "unsupported_type" }, 415);
  if (!image && !VIDEO.has(mime)) return json({ error: "unsupported_type" }, 415);
  const raw = new Uint8Array(await req.arrayBuffer());
  if (raw.length === 0 || raw.length > MAX_BYTES) return json({ error: "invalid_media" }, 400);
  const bytes = image ? stripMetadata(raw) : raw;
  if (thumb && bytes.length > THUMB_MAX) return json({ error: "invalid_media" }, 400);
  if (!thumb && image && bytes.length > THUMB_MAX) return json({ error: "invalid_media" }, 400);
  await deps.store(req, path, bytes, mime);
  const stored: StoredContributionFile = {
    storage_path: path,
    mime_type: mime,
    byte_size: bytes.length,
  };
  return json(stored);
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json" },
  });
}
