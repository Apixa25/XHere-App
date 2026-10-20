import { assertEquals } from "jsr:@std/assert@1";
import { containsExifMarker } from "../_shared/exif.ts";
import { handleStore, mp4DurationSeconds } from "./handler.ts";

const USER = "11111111-1111-4111-8111-111111111111";
const PATH = "c/22222222-2222-4222-8222-222222222222/33333333-3333-4333-8333-333333333333.jpg";

Deno.test("a jpeg loses exif and is stored at the reserved path", async () => {
  let stored: Uint8Array | null = null;
  let storedPath = "";
  const response = await handleStore(request(PATH, "image/jpeg", jpegWithExif()), {
    userId: async () => USER,
    store: async (_req, path, bytes) => {
      stored = bytes;
      storedPath = path;
    },
  });
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.storage_path, PATH);
  assertEquals(storedPath, PATH);
  assertEquals(JSON.stringify(body).includes(USER), false);
  if (!stored) throw new Error("store was not called");
  assertEquals(containsExifMarker(stored), false);
});

Deno.test("a path that contains the user id is refused", async () => {
  const leaked = `c/${USER}/33333333-3333-4333-8333-333333333333.jpg`;
  const response = await handleStore(request(leaked, "image/jpeg", jpegWithExif()), {
    userId: async () => USER,
    store: async () => {
      throw new Error("store should not run");
    },
  });
  assertEquals(response.status, 400);
  const body = await response.json();
  assertEquals(body.error, "invalid_media");
});

Deno.test("a photo over 500 KB is refused", async () => {
  const response = await handleStore(request(PATH, "image/jpeg", new Uint8Array(512001)), {
    userId: async () => USER,
    store: async () => {
      throw new Error("store should not run");
    },
  });
  assertEquals(response.status, 400);
});

Deno.test("a thumbnail over 500 KB is refused", async () => {
  const thumb = PATH.replace(/\.jpg$/, ".thumb.jpg");
  const response = await handleStore(request(thumb, "image/jpeg", new Uint8Array(512001)), {
    userId: async () => USER,
    store: async () => {
      throw new Error("store should not run");
    },
  });
  assertEquals(response.status, 400);
});

Deno.test("an mp4 longer than a minute is refused", async () => {
  const clip = mp4(90);
  assertEquals(mp4DurationSeconds(clip), 90);
  const response = await handleStore(request(PATH.replace(/\.jpg$/, ".mp4"), "video/mp4", clip), {
    userId: async () => USER,
    store: async () => {
      throw new Error("store should not run");
    },
  });
  assertEquals(response.status, 400);
});

Deno.test("a short mp4 is stored", async () => {
  const clip = mp4(2);
  let storedPath = "";
  const response = await handleStore(request(PATH.replace(/\.jpg$/, ".mp4"), "video/mp4", clip), {
    userId: async () => USER,
    store: async (_req, path) => {
      storedPath = path;
    },
  });
  assertEquals(response.status, 200);
  assertEquals(storedPath.endsWith(".mp4"), true);
});

Deno.test("a missing session is rejected", async () => {
  const response = await handleStore(request(PATH, "image/jpeg", jpegWithExif()), {
    userId: async () => null,
    store: async () => {
      throw new Error("store should not run");
    },
  });
  assertEquals(response.status, 401);
});

function request(path: string, mime: string, body: Uint8Array): Request {
  return new Request("http://local/store-contribution-media", {
    method: "POST",
    headers: {
      "content-type": mime,
      authorization: "Bearer test",
      "x-storage-path": path,
    },
    body: copyBytes(body),
  });
}

function copyBytes(bytes: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(copy).set(bytes);
  return copy;
}

function mp4(seconds: number): Uint8Array {
  const mvhd = box("mvhd", [
    0, 0, 0, 0,
    0, 0, 0, 0,
    0, 0, 0, 0,
    ...u32(1000),
    ...u32(seconds * 1000),
  ]);
  return concat(box("ftyp", [0x69, 0x73, 0x6f, 0x6d, 0, 0, 0, 0]), box("moov", [...mvhd]), box("mdat", [0, 0]));
}

function box(type: string, payload: number[]): Uint8Array {
  const bytes = new Uint8Array(8 + payload.length);
  const size = bytes.length;
  bytes[0] = (size >>> 24) & 255;
  bytes[1] = (size >>> 16) & 255;
  bytes[2] = (size >>> 8) & 255;
  bytes[3] = size & 255;
  bytes.set(new TextEncoder().encode(type), 4);
  bytes.set(payload, 8);
  return bytes;
}

function u32(value: number): number[] {
  return [(value >>> 24) & 255, (value >>> 16) & 255, (value >>> 8) & 255, value & 255];
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function jpegWithExif(): Uint8Array {
  const exif = new TextEncoder().encode("Exif\0\0GPS");
  const length = exif.length + 2;
  return new Uint8Array([
    0xff,
    0xd8,
    0xff,
    0xe1,
    (length >> 8) & 0xff,
    length & 0xff,
    ...exif,
    0xff,
    0xd9,
  ]);
}
