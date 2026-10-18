import type { APIRoute } from "astro";
import { SITE } from "../../lib/site.mjs";

/**
 * Android Digital Asset Links. The fingerprint is a placeholder string, not a
 * certificate. Verified App Links stay unverified until Steven replaces it
 * with the Play App Signing SHA-256.
 */
export const GET: APIRoute = () => {
  const body = [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: SITE.packageName,
        sha256_cert_fingerprints: [SITE.fingerprintPlaceholder],
      },
    },
  ];
  return new Response(`${JSON.stringify(body, null, 2)}\n`, {
    headers: { "content-type": "application/json; charset=utf-8" },
  });
};
