/**
 * Public site facts. The contact address is a placeholder mailbox.
 * Steven has not confirmed that it receives mail.
 */
export const SITE = {
  origin: "https://xhere.world",
  name: "XHere",
  owner: "Steven Sills II",
  contactEmail: "hello@xhere.world",
  packageName: "world.xhere.app",
  /**
   * Not a certificate fingerprint. Play App Signing's SHA-256 replaces this
   * before Android can verify https://xhere.world App Links.
   */
  fingerprintPlaceholder:
    "PLACEHOLDER_NOT_A_REAL_CERT__REPLACE_WITH_PLAY_APP_SIGNING_SHA256",
};

export const CONTACT_NOTE =
  "Placeholder mailbox. It is not confirmed to exist yet. Steven will replace it before this site goes live.";

export function canonicalUrl(pathname) {
  const path = pathname.length > 1 && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname || "/";
  return new URL(path, SITE.origin).href;
}

export const NAV = [
  ["/", "Home"],
  ["/guidelines", "Guidelines"],
  ["/support", "Support"],
];

export const FOOTER_LINKS = [
  ["/privacy", "Privacy"],
  ["/terms", "Terms"],
  ["/guidelines", "Guidelines"],
  ["/delete-account", "Delete account"],
  ["/support", "Support"],
];
