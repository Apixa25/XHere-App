import { assertEquals } from "jsr:@std/assert@1";
import { IMMUTABLE_YEAR, storageWriteHeaders } from "./media_cache.ts";

Deno.test("uploads are cached for a year because the object path never changes", () => {
  assertEquals(IMMUTABLE_YEAR, "public, max-age=31536000, immutable");
  const headers = storageWriteHeaders("image/jpeg", "anon", "Bearer user");
  assertEquals(headers["cache-control"], IMMUTABLE_YEAR);
  assertEquals(headers["content-type"], "image/jpeg");
});
