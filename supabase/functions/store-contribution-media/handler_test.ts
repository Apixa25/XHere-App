import { assertEquals } from "jsr:@std/assert@1";
import { containsExifMarker } from "../_shared/exif.ts";
import { handleStore } from "./handler.ts";

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
