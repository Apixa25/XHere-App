/** Immutable Storage objects. One year. Keep this string in sync with MediaCache.CACHE_CONTROL. */
export const IMMUTABLE_YEAR = "public, max-age=31536000, immutable";

export function storageWriteHeaders(
  mime: string,
  apikey: string,
  authorization: string,
): Record<string, string> {
  return {
    apikey,
    Authorization: authorization,
    "content-type": mime,
    "cache-control": IMMUTABLE_YEAR,
  };
}
