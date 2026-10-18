import { assertEquals } from "jsr:@std/assert@1";
import { containsExifMarker, stripMetadata } from "../_shared/exif.ts";
import { handleStrip } from "./handler.ts";

Deno.test("jpeg exif is removed and the scan payload stays", () => {
  const jpeg = jpegWithExif();
  if (!containsExifMarker(jpeg)) throw new Error("fixture should contain Exif");
  const stripped = stripMetadata(jpeg);
  assertEquals(stripped[0], 0xff);
  assertEquals(stripped[1], 0xd8);
  assertEquals(containsExifMarker(stripped), false);
  assertEquals(stripped[stripped.length - 1], 0xd9);
  assertEquals(stripped.includes(0xda), true);
});

Deno.test("the upload handler stores the stripped bytes under the user id", async () => {
  let stored: Uint8Array | null = null;
  const response = await handleStrip(
    new Request("http://local/strip-exif", {
      method: "POST",
      headers: { "content-type": "image/jpeg", authorization: "Bearer test" },
      body: jpegWithExif(),
    }),
    {
      userId: async () => "11111111-1111-4111-8111-111111111111",
      store: async (userId, bytes, mime) => {
        stored = bytes;
        return {
          storage_path: `${userId}/photo.jpg`,
          mime_type: mime,
          byte_size: bytes.length,
        };
      },
    },
  );
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.storage_path, "11111111-1111-4111-8111-111111111111/photo.jpg");
  if (!stored) throw new Error("store was not called");
  assertEquals(containsExifMarker(stored), false);
});

Deno.test("a missing user is rejected before anything is stored", async () => {
  const response = await handleStrip(
    new Request("http://local/strip-exif", {
      method: "POST",
      headers: { "content-type": "image/jpeg" },
      body: jpegWithExif(),
    }),
    {
      userId: async () => null,
      store: async () => {
        throw new Error("should not store");
      },
    },
  );
  assertEquals(response.status, 401);
});

function jpegWithExif(): Uint8Array {
  const payload = new TextEncoder().encode("Exif\0\0GPS");
  const length = payload.length + 2;
  return Uint8Array.from([
    0xff,
    0xd8,
    0xff,
    0xe1,
    length >> 8,
    length & 0xff,
    ...payload,
    0xff,
    0xda,
    0x00,
    0x02,
    0x00,
    0xff,
    0xd9,
  ]);
}
