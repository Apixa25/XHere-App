// Stores one contribution file at a path create_contribution already reserved.
// The path is two random ids. It must not contain the caller's user id.
// Images are stripped with the same helper strip-exif uses.

import { stripMetadata } from "../_shared/exif.ts";

const PHOTO_MAX = 512000;
const THUMB_MAX = 512000;
const VIDEO_MAX = 22 * 1024 * 1024;
/** Encoder rounding can land a 60s clip a little over the declared cap. */
const VIDEO_MAX_SECONDS = 61;
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
  const limit = thumb ? THUMB_MAX : image ? PHOTO_MAX : VIDEO_MAX;
  if (raw.length === 0 || raw.length > limit) return json({ error: "invalid_media" }, 400);
  if (!image && mime === "video/mp4") {
    const duration = mp4DurationSeconds(raw);
    if (duration == null || !(duration > 0) || duration > VIDEO_MAX_SECONDS) {
      return json({ error: "invalid_media" }, 400);
    }
  }
  const bytes = image ? stripMetadata(raw) : raw;
  if (thumb && bytes.length > THUMB_MAX) return json({ error: "invalid_media" }, 400);
  if (!thumb && image && bytes.length > PHOTO_MAX) return json({ error: "invalid_media" }, 400);
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

/**
 * Seconds from the first mvhd atom. Null when the file is not a simple MP4.
 * WebM is not parsed here. The row still stores the duration the phone declared.
 */
export function mp4DurationSeconds(bytes: Uint8Array): number | null {
  const moov = findBox(bytes, 0, bytes.length, "moov");
  if (!moov) return null;
  const mvhd = findBox(bytes, moov.start, moov.end, "mvhd");
  if (!mvhd) return null;
  return readMvhd(bytes, mvhd.start, mvhd.end);
}

interface Span {
  start: number;
  end: number;
}

function findBox(bytes: Uint8Array, from: number, to: number, type: string): Span | null {
  let offset = from;
  while (offset + 8 <= to) {
    let size = u32(bytes, offset);
    let header = 8;
    if (size === 1) {
      if (offset + 16 > to) return null;
      const wide = u64(bytes, offset + 8);
      if (wide > Number.MAX_SAFE_INTEGER) return null;
      size = Number(wide);
      header = 16;
    } else if (size === 0) {
      size = to - offset;
    }
    if (size < header || offset + size > to) return null;
    const boxType = ascii(bytes, offset + 4);
    const payload = offset + header;
    const end = offset + size;
    if (boxType === type) return { start: payload, end };
    offset = end;
  }
  return null;
}

function readMvhd(bytes: Uint8Array, start: number, end: number): number | null {
  if (start >= end) return null;
  const version = bytes[start];
  if (version === 0) {
    if (start + 20 > end) return null;
    const timescale = u32(bytes, start + 12);
    const duration = u32(bytes, start + 16);
    if (timescale === 0) return null;
    return duration / timescale;
  }
  if (version === 1) {
    if (start + 32 > end) return null;
    const timescale = u32(bytes, start + 20);
    const duration = u64(bytes, start + 24);
    if (timescale === 0) return null;
    return Number(duration) / timescale;
  }
  return null;
}

function u32(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0;
}

function u64(bytes: Uint8Array, offset: number): bigint {
  let value = 0n;
  for (let i = 0; i < 8; i++) value = (value << 8n) + BigInt(bytes[offset + i]);
  return value;
}

function ascii(bytes: Uint8Array, offset: number): string {
  return String.fromCharCode(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]);
}
