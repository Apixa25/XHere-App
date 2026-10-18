import { readFile, readdir, stat } from "node:fs/promises";
import { join } from "node:path";
import { FIXTURES } from "../src/lib/drops.mjs";
import { SITE } from "../src/lib/site.mjs";

const dist = new URL("../dist/", import.meta.url);

async function readDist(relative) {
  return readFile(new URL(relative, dist), "utf8");
}

async function walk(dir, out = []) {
  const entries = await readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) await walk(path, out);
    else out.push(path);
  }
  return out;
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const secrets = FIXTURES.map((drop) => drop.secretBody);
const files = await walk(dist.pathname);
const texts = [];
for (const file of files) {
  if (/\.(html|svg|js|json|txt|xml)$/.test(file)) texts.push(await readFile(file, "utf8"));
}
const all = texts.join("\n");
for (const secret of secrets) {
  assert(!all.includes(secret), `Built site contains hidden body sentinel: ${secret}`);
}
assert(!all.includes("should_not_show_on_sensitive"), "Sensitive share page shows the author handle");

const historical = await readDist("d/JmtwnHist01a/index.html");
assert(historical.includes('property="og:title"'), "Historical share page is missing og:title");
assert(historical.includes('property="og:image"'), "Historical share page is missing og:image");
assert(historical.includes("https://xhere.world/maps/JmtwnHist01a.svg"), "og:image is not the static map");
assert(historical.includes("Fenton Mansion"), "Historical teaser title missing");
assert(historical.includes("Historical fact"), "Historical category missing");
assert(historical.includes("Open in XHere"), "Open in XHere link missing");
assert(!historical.includes("SENTINEL_HIDDEN_BODY"), "Historical page rendered a sentinel");
assert(!historical.includes("governor"), "Historical page rendered hidden body words");

const incident = await readDist("d/JmtwnInc03cd/index.html");
assert(incident.includes("Approximate") || incident.includes("approximate"), "Incident teaser should say the area is approximate");
assert(incident.includes("911"), "Incident teaser should keep the emergency line");
assert(incident.includes('content="noindex"'), "Sensitive share page should be noindex");
assert(!incident.includes("should_not_show_on_sensitive"), "Incident page leaked the author handle");
assert(!incident.includes("person_description"), "Incident page rendered the hidden body");

const map = await readDist("maps/JmtwnHist01a.svg");
assert(map.includes("OpenFreeMap"), "Static map is missing OpenFreeMap");
assert(map.includes("OpenStreetMap"), "Static map is missing OpenStreetMap attribution");
assert(map.includes("<path"), "Static map has no tile geometry");
const mapStat = await stat(new URL("maps/JmtwnInc03cd.svg", dist));
assert(mapStat.size > 1500, "Incident map image looks empty");
assert((await readDist("maps/JmtwnInc03cd.svg")).includes("stroke-dasharray"), "Sensitive map should show an area, not only a pin");

const assetLinks = JSON.parse(await readDist(".well-known/assetlinks.json"));
assert(assetLinks[0].target.package_name === SITE.packageName, "assetlinks package name");
assert(
  assetLinks[0].target.sha256_cert_fingerprints[0] === SITE.fingerprintPlaceholder,
  "assetlinks fingerprint placeholder missing",
);
assert(assetLinks[0].target.sha256_cert_fingerprints[0].includes("PLACEHOLDER"), "fingerprint is not marked as a placeholder");

for (const page of ["index.html", "privacy/index.html", "terms/index.html", "guidelines/index.html", "delete-account/index.html", "support/index.html"]) {
  const html = await readDist(page);
  assert(html.includes("<title>"), `${page} missing title`);
  assert(html.includes('name="description"'), `${page} missing description`);
}

const guidelines = await readDist("guidelines/index.html");
assert(guidelines.includes("Incident reports"), "Guidelines missing incident reports");
assert(guidelines.includes("911"), "Guidelines missing 911");
assert(guidelines.includes("100 meters"), "Guidelines missing the 100 meter blur");
assert(guidelines.includes("72 hours"), "Guidelines missing the 72 hour lifetime");
assert(guidelines.includes("Regular"), "Guidelines missing the Regular tier rule");
assert(guidelines.includes("Draft."), "Guidelines missing the draft banner");

const deletion = await readDist("delete-account/index.html");
assert(deletion.includes("30 days"), "Delete-account page missing the 30 day purge");
assert(deletion.includes("Settings"), "Delete-account page missing the in-app path");
assert(deletion.includes("Profile"), "Delete-account page missing Profile");
assert(deletion.includes(SITE.owner), "Delete-account page missing the owner");
assert(deletion.includes("Draft."), "Delete-account page missing the draft banner");

const privacy = await readDist("privacy/index.html");
assert(privacy.includes(SITE.owner), "Privacy page missing the owner");
assert(privacy.includes(SITE.contactEmail), "Privacy page missing the contact email");
assert(privacy.includes("Draft."), "Privacy page missing the draft banner");
assert(privacy.includes("not a company"), "Privacy page should refuse invented company facts");

console.log(`verify-share ok (${files.length} files in dist)`);
