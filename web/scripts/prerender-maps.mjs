import { mkdir, rm, writeFile } from "node:fs/promises";
import { FIXTURES, publicFromFixture } from "../src/lib/drops.mjs";
import { renderStaticMapSvg } from "../src/lib/static-map.mjs";

const dir = new URL("../public/maps/", import.meta.url);
await rm(dir, { recursive: true, force: true });
await mkdir(dir, { recursive: true });

for (const fixture of FIXTURES) {
  const drop = publicFromFixture(fixture);
  const svg = await renderStaticMapSvg(drop);
  if (!svg.includes("OpenFreeMap") || !svg.includes("OpenStreetMap")) {
    throw new Error(`Map for ${drop.shortId} is missing OpenFreeMap attribution`);
  }
  if (svg.includes(fixture.secretBody)) {
    throw new Error(`Map for ${drop.shortId} includes the hidden body`);
  }
  if (!svg.includes("<path")) {
    throw new Error(`Map for ${drop.shortId} has no geometry`);
  }
  const file = new URL(`${drop.shortId}.svg`, dir);
  await writeFile(file, svg);
  console.log(`map ${drop.shortId} ${svg.length} bytes`);
}
