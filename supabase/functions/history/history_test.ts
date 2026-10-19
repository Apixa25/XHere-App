import { assertEquals } from "jsr:@std/assert@1";
import { handleHistory, sanbornUrl, sheetsFromSanborn, type HistoryBundle } from "./handler.ts";

const REGION: HistoryBundle = {
  region_id: "us-ny-jamestown",
  name: "Jamestown, New York, USA",
  country: "US",
  town: "Jamestown",
  state: "New York",
  west: -79.32,
  south: 42.05,
  east: -79.18,
  north: 42.14,
  updated_at: "2020-01-01T00:00:00Z",
  sheets: [
    {
      id: "sanborn06011_001-2",
      title: "sheet 2",
      year: 1886,
      date_label: "1886-03",
      item_url: "https://www.loc.gov/resource/g3804jm.g3804jm_g060111886/?sp=2",
      iiif_service: "https://tile.loc.gov/image-services/iiif/service:example:06011_1886-0002",
      thumbnail_url: null,
      sheet_index: 2,
      digitized: true,
      attribution: "Library of Congress",
      license: "Public domain",
    },
  ],
  photos: [{ id: "loc-1" }],
  georef: [],
  clippings: [{ id: "paper-1" }],
};

const LOC = {
  results: [
    {
      id: "http://www.loc.gov/item/sanborn06011_001/",
      title: "Sanborn Fire Insurance Map from Jamestown, Chautauqua County, New York.",
      date: "1886-03",
      url: "https://www.loc.gov/item/sanborn06011_001/",
      digitized: true,
      location: ["new york", "jamestown"],
      location_state: ["new york"],
      image_url: [
        "https://tile.loc.gov/image-services/iiif/service:gmd:example:06011_1886-0001/full/pct:12.5/0/default.jpg",
      ],
    },
    {
      id: "http://www.loc.gov/item/sanborn00000_001/",
      title: "Sanborn Fire Insurance Map from Jamestown, North Dakota.",
      date: "1898",
      url: "https://www.loc.gov/item/sanborn00000_001/",
      digitized: false,
      location: ["north dakota", "jamestown"],
      location_state: ["north dakota"],
    },
  ],
};

Deno.test("the Sanborn search is the free loc.gov collection", () => {
  const url = sanbornUrl("Jamestown", "New York");
  assertEquals(url.includes("collections/sanborn-maps"), true);
  assertEquals(url.includes("location%3Ajamestown"), true);
  assertEquals(url.includes("fo=json"), true);
});

Deno.test("a North Dakota volume is not a New York sheet", () => {
  const sheets = sheetsFromSanborn(LOC, "Jamestown", "New York");
  assertEquals(sheets.map((sheet) => sheet.id), ["sanborn06011_001"]);
  assertEquals(sheets[0].year, 1886);
  assertEquals(sheets[0].license, "Public domain");
  assertEquals(sheets[0].iiif_service?.endsWith("06011_1886-0001"), true);
});

Deno.test("a fresh cache does not call loc.gov", async () => {
  let fetches = 0;
  const response = await handleHistory(post("us-ny-jamestown"), {
    load: async () => ({ ...REGION, updated_at: new Date().toISOString() }),
    claim: async () => true,
    fetchLoc: async () => {
      fetches += 1;
      return LOC;
    },
    upsert: async () => {
      throw new Error("should not write");
    },
  });
  assertEquals(response.status, 200);
  assertEquals(fetches, 0);
  assertEquals((await response.json()).reason, "fresh");
});

Deno.test("a stale cache fetches once and keeps sheet rows", async () => {
  let fetches = 0;
  type Written = { sheets: { id: string; sheet_index: number | null }[]; photos: unknown[] };
  const box: { doc: Written | null } = { doc: null };
  const response = await handleHistory(post("us-ny-jamestown"), {
    load: async () => REGION,
    claim: async () => true,
    fetchLoc: async () => {
      fetches += 1;
      return LOC;
    },
    upsert: async (doc) => {
      box.doc = doc as Written;
    },
    now: Date.parse("2026-10-19T00:00:00Z"),
  });
  assertEquals(response.status, 200);
  assertEquals(fetches, 1);
  assertEquals(box.doc?.photos, REGION.photos);
  assertEquals(box.doc?.sheets.map((sheet) => sheet.id), ["sanborn06011_001", "sanborn06011_001-2"]);
});

Deno.test("the rate limit stops the fetch", async () => {
  let fetches = 0;
  const response = await handleHistory(post("us-ny-jamestown"), {
    load: async () => REGION,
    claim: async () => false,
    fetchLoc: async () => {
      fetches += 1;
      return LOC;
    },
    upsert: async () => {
      throw new Error("should not write");
    },
    now: Date.parse("2026-10-19T00:00:00Z"),
  });
  assertEquals(response.status, 429);
  assertEquals(fetches, 0);
});

function post(regionId: string): Request {
  return new Request("http://local/history", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ region_id: regionId }),
  });
}
