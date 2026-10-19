import { assertEquals } from "jsr:@std/assert@1";
import {
  API_TERMS,
  clientAddress,
  handlePublicApi,
  hashClient,
  matchRoute,
  presentItem,
  QueryError,
  readPoint,
  readRadius,
  routePath,
  shareAlike,
  UnavailableError,
  type PublicApiDeps,
  type RateDecision,
} from "./handler.ts";

const ALLOWED: RateDecision = { allowed: true, limit: 30, remaining: 29, retry_after_seconds: 60 };

Deno.test("paths survive the functions prefix and a trailing slash", () => {
  assertEquals(routePath("http://local/functions/v1/public-api/v1/here/"), "/v1/here");
  assertEquals(routePath("http://local/v1/categories"), "/v1/categories");
  assertEquals(matchRoute("/v1/drops/Abcdefghij12"), { kind: "drop", id: "Abcdefghij12" });
  assertEquals(matchRoute("/v1/drops/not-an-id"), { kind: "bad_id" });
  assertEquals(matchRoute("/v1/drops/11111111-1111-4111-8111-111111111111"), {
    kind: "drop",
    id: "11111111-1111-4111-8111-111111111111",
  });
  assertEquals(matchRoute("/v1/secret"), { kind: "unknown" });
});

Deno.test("share-alike follows ODbL and CC BY-SA only", () => {
  assertEquals(shareAlike("ODbL 1.0"), true);
  assertEquals(shareAlike("CC BY-SA 4.0"), true);
  assertEquals(shareAlike("CC-BY-SA-4.0"), true);
  assertEquals(shareAlike("CC0 1.0"), false);
  assertEquals(shareAlike("Public domain"), false);
  assertEquals(shareAlike("xhere-teaser"), false);
});

Deno.test("coordinates and radius stay inside the public bounds", () => {
  assertEquals(readPoint("http://local/v1/here?lat=42.1&lon=-79.24"), { ok: true, lat: 42.1, lon: -79.24 });
  assertEquals(readPoint("http://local/v1/here?lat=42.1&lng=-79.24"), { ok: true, lat: 42.1, lon: -79.24 });
  assertEquals(readPoint("http://local/v1/here?lat=95&lon=0").ok, false);
  assertEquals(readPoint("http://local/v1/here?lat=42.1").ok, false);
  assertEquals(readPoint("http://local/v1/here?lat=42.123456789&lon=1").ok, false);
  assertEquals(readRadius("http://local/v1/here?lat=1&lon=2"), { ok: true, radiusM: 300 });
  assertEquals(readRadius("http://local/v1/here?radius=1000"), { ok: true, radiusM: 1000 });
  assertEquals(readRadius("http://local/v1/here?radius=1001").ok, false);
  assertEquals(readRadius("http://local/v1/here?radius=0").ok, false);
  assertEquals(readRadius("http://local/v1/here?radius=1e3").ok, false);
});

Deno.test("the client address is the last forwarding hop, else x-real-ip", () => {
  const forwarded = new Request("http://local/v1/here", { headers: { "x-forwarded-for": "1.2.3.4, 5.6.7.8" } });
  assertEquals(clientAddress(forwarded), "5.6.7.8");
  const real = new Request("http://local/v1/here", { headers: { "x-real-ip": "8.8.8.8" } });
  assertEquals(clientAddress(real), "8.8.8.8");
  const spoofed = new Request("http://local/v1/here", {
    headers: { "x-real-ip": "9.9.9.9", "x-forwarded-for": "1.1.1.1, 203.0.113.5" },
  });
  assertEquals(clientAddress(spoofed), "203.0.113.5");
  assertEquals(clientAddress(new Request("http://local/v1/here", { headers: { "x-real-ip": "not an ip" } })), "unknown");
  assertEquals(clientAddress(new Request("http://local/v1/here")), "unknown");
});

Deno.test("the client hash is stable and is not the address", async () => {
  const once = await hashClient("203.0.113.5");
  const twice = await hashClient("203.0.113.5");
  assertEquals(once, twice);
  assertEquals(once.length, 64);
  assertEquals(/^[a-f0-9]{64}$/.test(once), true);
  assertEquals(once.includes("203"), false);
});

Deno.test("what's here returns teasers, attribution, terms, and a cache header", async () => {
  let saw: { lat: number; lon: number; radius: number } | null = null;
  const response = await handlePublicApi(get("/functions/v1/public-api/v1/here?lat=40&lon=-100&radius=300"), deps({
    here: async (lat, lon, radius) => {
      saw = { lat, lon, radius };
      return {
        items: [
          publicRecord(),
          {
            ...userTeaser(),
            summary: "SECRET_BODY_should_not_leak",
            body: "SECRET_BODY_should_not_leak",
            attributes: { year: 1890 },
            media: [{ storage_path: "11111111-1111-4111-8111-111111111111/secret.jpg" }],
            author_id: "22222222-2222-4222-8222-222222222222",
            geog: "POINT(-100 40.00001)",
            location_trust: "reduced",
            email: "alice@example.test",
          },
        ],
        live: {
          region_id: "zz-test",
          name: "Test",
          country: "ZZ",
          weather_provider: "none",
          attribution: "XHere region configuration. This API does not copy weather-alert text.",
          license: "xhere-config",
          share_alike: false,
        },
      };
    },
  }));
  assertEquals(response.status, 200);
  assertEquals(response.headers.get("cache-control"), "public, max-age=60");
  assertEquals(response.headers.get("x-ratelimit-remaining"), "29");
  assertEquals(response.headers.get("referrer-policy"), "no-referrer");
  const body = await response.json();
  assertEquals(saw, { lat: 40, lon: -100, radius: 300 });
  assertEquals(body.terms.note, API_TERMS.note);
  assertEquals(body.terms.share_alike, ["ODbL-1.0", "CC-BY-SA-4.0"]);
  assertEquals(body.items.length, 2);
  assertEquals(body.items[0].kind, "public_record");
  assertEquals(body.items[0].summary, "A public library summary.");
  assertEquals(body.items[0].license, "CC BY-SA 4.0");
  assertEquals(body.items[0].share_alike, true);
  assertEquals(body.items[0].attribution.includes("Wikipedia"), true);
  assertEquals(body.items[1].kind, "user_teaser");
  assertEquals(body.items[1].summary, null);
  assertEquals(body.items[1].author_handle, "alice");
  assertEquals(body.items[1].license, "xhere-teaser");
  assertEquals(body.items[1].share_alike, false);
  assertEquals(body.live.region_id, "zz-test");
  assertEquals(body.live.license, "xhere-config");
  const text = JSON.stringify(body);
  assertEquals(text.includes("SECRET_BODY"), false);
  assertEquals(text.includes("author_id"), false);
  assertEquals(text.includes("storage_path"), false);
  assertEquals(text.includes("location_trust"), false);
  assertEquals(text.includes("alice@example.test"), false);
  assertEquals(text.includes("POINT("), false);
});

Deno.test("a public record without attribution is omitted", () => {
  assertEquals(presentItem({ ...publicRecord(), license: "", attribution: "" }), null);
});

Deno.test("a sensitive teaser does not gain a handle the handler was not given", () => {
  const item = presentItem({ ...userTeaser(), author_handle: null, approximate: true });
  assertEquals(item?.author_handle, null);
  assertEquals(item?.summary, null);
});

Deno.test("an over-wide radius is rejected before the read", async () => {
  let reads = 0;
  const response = await handlePublicApi(get("/v1/here?lat=40&lon=-100&radius=50000"), deps({
    here: async () => {
      reads += 1;
      return { items: [] };
    },
  }));
  assertEquals(response.status, 400);
  assertEquals((await response.json()).error, "invalid_radius");
  assertEquals(reads, 0);
  assertEquals(response.headers.get("cache-control"), "no-store");
});

Deno.test("the drop route returns one teaser and 404 for a null row", async () => {
  const ok = await handlePublicApi(get("/v1/drops/Abcdefghij12"), deps({
    drop: async (id) => {
      assertEquals(id, "Abcdefghij12");
      return userTeaser();
    },
  }));
  assertEquals(ok.status, 200);
  assertEquals(ok.headers.get("cache-control"), "public, max-age=120");
  const body = await ok.json();
  assertEquals(body.item.short_id, "Abcdefghij12");
  assertEquals(body.item.summary, null);
  assertEquals(body.terms.privacy.includes("PLAN section 5"), true);

  const missing = await handlePublicApi(get("/v1/drops/Abcdefghij12"), deps({
    drop: async () => null,
  }));
  assertEquals(missing.status, 404);
  assertEquals((await missing.json()).error, "not_found");
});

Deno.test("categories keep labels and drop embeddings", async () => {
  const response = await handlePublicApi(get("/functions/v1/public-api/v1/categories"), deps({
    categories: async () => ({
      groups: [{
        slug: "history_stories",
        label: "History & Stories",
        description: "What happened here.",
        embedding: [0.1, 0.2],
        content_filters: ["person_descriptors"],
      }],
      categories: [{
        slug: "historical_fact",
        label: "Historical fact",
        description: "A sourced fact.",
        group_slug: "history_stories",
        sensitive: false,
        default_reveal_m: 150,
        location_precision_m: 0,
        embedding: [0.2],
        moderation_mode: "post",
      }],
    }),
  }));
  assertEquals(response.status, 200);
  assertEquals(response.headers.get("cache-control"), "public, max-age=3600");
  const body = await response.json();
  assertEquals(body.categories[0].slug, "historical_fact");
  assertEquals(body.categories[0].license, "xhere-catalog");
  assertEquals(body.categories[0].attribution, "XHere category list");
  assertEquals(body.groups[0].share_alike, false);
  const text = JSON.stringify(body);
  assertEquals(text.includes("embedding"), false);
  assertEquals(text.includes("person_descriptors"), false);
  assertEquals(text.includes("moderation_mode"), false);
  assertEquals(body.terms.note, API_TERMS.note);
});

Deno.test("walks keep stop licenses and drop personal progress", async () => {
  const response = await handlePublicApi(get("/v1/walks?lat=42.09&lon=-79.24"), deps({
    walks: async () => ({
      walks: [{
        id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        region_id: "us-ny-test",
        slug: "test-walk",
        title: "Test walk",
        description: "A short walk.",
        distance_m: 100,
        duration_min: 10,
        started: true,
        completed: true,
        attribution: "XHere walk. Wikipedia contributors, CC BY-SA 4.0",
        license: "CC BY-SA 4.0",
        share_alike: true,
        stops: [{
          ...publicRecord(),
          position: 1,
          reached: true,
        }],
      }],
    }),
  }));
  assertEquals(response.status, 200);
  assertEquals(response.headers.get("cache-control"), "public, max-age=60");
  const body = await response.json();
  assertEquals(body.walks.length, 1);
  assertEquals(body.walks[0].license, "CC BY-SA 4.0");
  assertEquals(body.walks[0].share_alike, true);
  assertEquals(body.walks[0].stops[0].position, 1);
  assertEquals(body.walks[0].stops[0].summary, "A public library summary.");
  const text = JSON.stringify(body);
  assertEquals(text.includes("started"), false);
  assertEquals(text.includes("reached"), false);
  assertEquals(text.includes("completed"), false);
});

Deno.test("a full window returns 429 and does not read", async () => {
  let reads = 0;
  const response = await handlePublicApi(get("/v1/here?lat=40&lon=-100"), deps({
    take: async () => ({ allowed: false, limit: 30, remaining: 0, retry_after_seconds: 12 }),
    here: async () => {
      reads += 1;
      return { items: [] };
    },
  }));
  assertEquals(response.status, 429);
  assertEquals(response.headers.get("retry-after"), "12");
  assertEquals(response.headers.get("cache-control"), "no-store");
  const body = await response.json();
  assertEquals(body.error, "rate_limited");
  assertEquals(body.terms.share_alike, ["ODbL-1.0", "CC-BY-SA-4.0"]);
  assertEquals(reads, 0);
});

Deno.test("OPTIONS is a CORS preflight and does not spend a request", async () => {
  let takes = 0;
  const response = await handlePublicApi(new Request("http://local/v1/here", { method: "OPTIONS" }), deps({
    take: async () => {
      takes += 1;
      return ALLOWED;
    },
  }));
  assertEquals(response.status, 204);
  assertEquals(response.headers.get("access-control-allow-origin"), "*");
  assertEquals(takes, 0);
});

Deno.test("POST is refused", async () => {
  const response = await handlePublicApi(new Request("http://local/v1/here", { method: "POST" }), deps());
  assertEquals(response.status, 405);
  assertEquals(response.headers.get("allow"), "GET, OPTIONS");
});

Deno.test("a database outage is a generic 503", async () => {
  const response = await handlePublicApi(get("/v1/categories"), deps({
    take: async () => {
      throw new UnavailableError();
    },
  }));
  assertEquals(response.status, 503);
  assertEquals(await response.json(), { error: "unavailable", terms: API_TERMS });
});

Deno.test("a database validation error stays a 400 slug", async () => {
  const response = await handlePublicApi(get("/v1/categories"), deps({
    categories: async () => {
      throw new QueryError("invalid_id");
    },
  }));
  assertEquals(response.status, 400);
  assertEquals((await response.json()).error, "invalid_id");
});

function get(path: string): Request {
  return new Request(`http://local${path}`);
}

function deps(over: Partial<PublicApiDeps> = {}): PublicApiDeps {
  return {
    take: async () => ALLOWED,
    here: async () => ({ items: [], live: null }),
    drop: async () => null,
    categories: async () => ({ groups: [], categories: [] }),
    walks: async () => ({ walks: [] }),
    ...over,
  };
}

function publicRecord(): Record<string, unknown> {
  return {
    id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab",
    short_id: "Pubrecord01x",
    kind: "public_record",
    title: "Test Library",
    category_slug: "historical_fact",
    lat: 40,
    lon: -100,
    approximate: false,
    summary: "A public library summary.",
    source_name: "Wikipedia",
    source_url: "https://en.wikipedia.org/wiki/Test_Library",
    attribution: "Wikipedia contributors, CC BY-SA 4.0",
    license: "CC BY-SA 4.0",
    share_alike: true,
    author_handle: "should_not_stick",
  };
}

function userTeaser(): Record<string, unknown> {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    short_id: "Abcdefghij12",
    kind: "user_teaser",
    title: "Old mill",
    category_slug: "historical_fact",
    lat: 40,
    lon: -100,
    approximate: false,
    summary: null,
    author_handle: "alice",
    attribution: "Teaser posted on XHere by @alice. Hidden text is not included.",
    license: "xhere-teaser",
    share_alike: false,
  };
}
