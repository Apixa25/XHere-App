import { renderFooter, renderHeader, renderSkip } from "./chrome.mjs";
import { escapeHtml } from "./escape.mjs";
import { MAP_HEIGHT, MAP_WIDTH } from "./static-map.mjs";
import { SITE, canonicalUrl } from "./site.mjs";
import { siteCss } from "../styles/site-css.mjs";

export function sharePath(shortId) {
  return `/d/${shortId}`;
}

export function mapPath(shortId) {
  return `/maps/${shortId}.svg`;
}

function metaDescription(drop) {
  if (drop.sensitive) {
    return `${drop.categoryLabel} on XHere. This page is a teaser and an approximate area. The hidden text is not on the website.`;
  }
  return `${drop.categoryLabel} on XHere. This page is the teaser. The rest of the drop opens in the app when you are close enough.`;
}

export function renderShareDocument(drop) {
  const path = sharePath(drop.shortId);
  const pageUrl = canonicalUrl(path);
  const imageUrl = new URL(mapPath(drop.shortId), SITE.origin).href;
  const description = metaDescription(drop);
  const title = `${drop.title} · ${drop.categoryLabel} · XHere`;
  const imageAlt = drop.approximate
    ? `Static OpenFreeMap image showing an approximate area for this ${drop.categoryLabel}, not an exact address.`
    : `Static OpenFreeMap image of the area around ${drop.title}.`;
  const radius = Number.isFinite(drop.revealRadiusM) && drop.revealRadiusM > 0
    ? `about ${Math.round(drop.revealRadiusM)} meters`
    : "the distance the category asks for";
  const status = drop.status === "confirmed" ? `<p class="badge">Confirmed by people nearby</p>` : "";
  const author = drop.authorHandle
    ? `<p class="meta">Posted by @${escapeHtml(drop.authorHandle)}</p>`
    : "";
  const lock = drop.sensitive
    ? `<p class="lock">This is a sensitive drop. The website shows the title and an approximate area only. The hidden text is not here, even if you are standing on the spot. In the app, the location is blurred and the rest stays locked until you are close. In an emergency call 911. XHere is not a way to reach the police.</p>`
    : `<p class="lock">This is the teaser. The website does not include the rest of the drop. Open XHere and go within ${escapeHtml(radius)} to read it.</p>`;
  const robots = drop.sensitive ? `<meta name="robots" content="noindex">` : "";

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <meta name="description" content="${escapeHtml(description)}">
  <link rel="canonical" href="${escapeHtml(pageUrl)}">
  <link rel="icon" href="/favicon.svg" type="image/svg+xml">
  <meta name="theme-color" content="#0F6E56">
  <meta name="color-scheme" content="light">
  ${robots}
  <meta property="og:type" content="website">
  <meta property="og:site_name" content="XHere">
  <meta property="og:title" content="${escapeHtml(drop.title)}">
  <meta property="og:description" content="${escapeHtml(description)}">
  <meta property="og:url" content="${escapeHtml(pageUrl)}">
  <meta property="og:image" content="${escapeHtml(imageUrl)}">
  <meta property="og:image:width" content="${MAP_WIDTH}">
  <meta property="og:image:height" content="${MAP_HEIGHT}">
  <meta property="og:image:alt" content="${escapeHtml(imageAlt)}">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="${escapeHtml(drop.title)}">
  <meta name="twitter:description" content="${escapeHtml(description)}">
  <meta name="twitter:image" content="${escapeHtml(imageUrl)}">
  <style>${siteCss}</style>
</head>
<body>
  ${renderSkip()}
  ${renderHeader(path)}
  <main id="content" class="wrap share">
    <article class="share-card">
      <p class="eyebrow">${escapeHtml(drop.groupLabel)} · ${escapeHtml(drop.categoryLabel)}</p>
      <h1>${escapeHtml(drop.title)}</h1>
      ${status}
      ${author}
      ${lock}
      <img class="map" src="${escapeHtml(mapPath(drop.shortId))}" width="${MAP_WIDTH}" height="${MAP_HEIGHT}" alt="${escapeHtml(imageAlt)}">
      <p class="caption">Static map drawn from <a href="https://openfreemap.org/">OpenFreeMap</a> vector tiles (the same tiles MapLibre uses; Positron colors). © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors · © <a href="https://openmaptiles.org/">OpenMapTiles</a> · OpenFreeMap.</p>
      <div class="actions">
        <a class="button" href="${escapeHtml(pageUrl)}">Open in XHere</a>
        <a class="text-link" href="/support">App not installed?</a>
      </div>
      <p class="meta">On a phone with the Android app installed, this address opens the drop. The Play listing is not published yet.</p>
    </article>
  </main>
  ${renderFooter()}
</body>
</html>`;
}

export function renderNotFoundDocument() {
  const description = "That XHere link does not match a drop this site can show.";
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Drop not found · XHere</title>
  <meta name="description" content="${description}">
  <link rel="canonical" href="${canonicalUrl("/404")}">
  <link rel="icon" href="/favicon.svg" type="image/svg+xml">
  <meta name="robots" content="noindex">
  <style>${siteCss}</style>
</head>
<body>
  ${renderSkip()}
  ${renderHeader("/404")}
  <main id="content" class="wrap">
    <h1>That drop is not here</h1>
    <p>The link may be wrong, or the drop may have expired or been removed. The website never shows the hidden text of a drop.</p>
    <p><a class="button" href="/">Back to XHere</a></p>
  </main>
  ${renderFooter()}
</body>
</html>`;
}
