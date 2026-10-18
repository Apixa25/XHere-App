import { CONTACT_NOTE, FOOTER_LINKS, NAV, SITE } from "./site.mjs";
import { escapeHtml } from "./escape.mjs";

function isCurrent(pathname, href) {
  const path = pathname.length > 1 && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname || "/";
  return path === href;
}

export function renderSkip() {
  return `<a class="skip" href="#content">Skip to content</a>`;
}

export function renderHeader(pathname) {
  const links = NAV.map(([href, label]) => {
    const current = isCurrent(pathname, href) ? ` aria-current="page"` : "";
    return `<a href="${href}"${current}>${escapeHtml(label)}</a>`;
  }).join("");
  return `<header class="site-header">
    <a class="wordmark" href="/">XHere</a>
    <nav class="nav" aria-label="Site">${links}</nav>
  </header>`;
}

export function renderFooter() {
  const links = FOOTER_LINKS.map(
    ([href, label]) => `<a href="${href}">${escapeHtml(label)}</a>`,
  ).join("");
  return `<footer class="site-footer">
    <nav class="footer-nav" aria-label="Footer">${links}</nav>
    <p>XHere is a project by ${escapeHtml(SITE.owner)}. Contact <a href="mailto:${SITE.contactEmail}">${escapeHtml(SITE.contactEmail)}</a>. ${escapeHtml(CONTACT_NOTE)}</p>
    <p>Maps use <a href="https://openfreemap.org/">OpenFreeMap</a> vector tiles in a MapLibre-compatible style. © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors · © <a href="https://openmaptiles.org/">OpenMapTiles</a> · <a href="https://openfreemap.org/">OpenFreeMap</a>.</p>
  </footer>`;
}

export function renderDraft() {
  return `<p class="draft" role="note"><strong>Draft.</strong> ${escapeHtml(SITE.owner)} has not approved this text. It is not in effect, and it is not legal advice.</p>`;
}
