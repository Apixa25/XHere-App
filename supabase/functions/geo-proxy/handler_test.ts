import { assertEquals } from "jsr:@std/assert@1";
import {
  USER_AGENT,
  cell,
  handleGeo,
  httpsEndpoint,
  overpassQuery,
  roundBox,
  type GeoDeps,
} from "./handler.ts";

const ENDPOINT = "https://geo.example.test/reverse";
const OVERPASS = "https://geo.example.test/interpreter";

Deno.test("cells match the phone and neighbors share a box", () => {
  assertEquals(cell(51.5004), 51.5);
  assertEquals(cell(-0.1204), -0.12);
  const a = roundBox(51.501, 10.001, 51.509, 10.009);
  const b = roundBox(51.503, 10.003, 51.507, 10.007);
  assertEquals(a.key, b.key);
  assertEquals(overpassQuery(a).includes("historic"), true);
  assertEquals(overpassQuery(a).includes("Jamestown"), false);
  assertEquals(httpsEndpoint("http://insecure.example/x"), null);
  assertEquals(httpsEndpoint("https://user:pass@example.test/x"), null);
});

Deno.test("a fresh cache does not call Nominatim", async () => {
  let claims = 0;
  let fetches = 0;
  const response = await handleGeo(request({ op: "reverse", lat: 51.5004, lng: -0.1204 }), {
    endpoint: async () => ENDPOINT,
    readCache: async () => ({ body: { name: "Bridge" }, fresh: true }),
    writeCache: async () => {
      throw new Error("write");
    },
    claim: async () => {
      claims += 1;
      return true;
    },
    fetchUpstream: async () => {
      fetches += 1;
      return new Response("{}", { status: 200 });
    },
  });
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.body.name, "Bridge");
  assertEquals(body.cached, true);
  assertEquals(claims, 0);
  assertEquals(fetches, 0);
});

Deno.test("a miss fetches with the app User-Agent and ignores a client URL", async () => {
  let fetched = "";
  let agent = "";
  let ttl = 0;
  const response = await handleGeo(
    request({ op: "reverse", lat: 51.5, lng: -0.12, url: "https://evil.example/steal" }),
    deps({
      fetchUpstream: async (url, init) => {
        fetched = url;
        agent = new Headers(init.headers).get("user-agent") ?? "";
        return new Response(JSON.stringify({ name: "Bridge", display_name: "Bridge, London" }), { status: 200 });
      },
      writeCache: async (_key, _kind, _body, seconds) => {
        ttl = seconds;
      },
    }),
  );
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.body.name, "Bridge");
  assertEquals(body.cached, false);
  assertEquals(agent, USER_AGENT);
  assertEquals(fetched.startsWith(ENDPOINT), true);
  assertEquals(fetched.includes("evil.example"), false);
  assertEquals(fetched.includes("lat=51.5"), true);
  assertEquals(ttl > 24 * 60 * 60, true);
});

Deno.test("a rate limit returns the stale cache or an empty body", async () => {
  const stale = await handleGeo(request({ op: "reverse", lat: 10, lng: 20 }), deps({
    readCache: async () => ({ body: { name: "Old" }, fresh: false }),
    claim: async () => false,
    fetchUpstream: async () => {
      throw new Error("upstream");
    },
  }));
  assertEquals(stale.status, 200);
  const staleBody = await stale.json();
  assertEquals(staleBody.limited, true);
  assertEquals(staleBody.body.name, "Old");

  const empty = await handleGeo(request({ op: "overpass", south: 1, west: 2, north: 1.01, east: 2.01 }), deps({
    claim: async () => false,
  }));
  assertEquals(empty.status, 200);
  const emptyBody = await empty.json();
  assertEquals(emptyBody.body, null);
  assertEquals(emptyBody.limited, true);
});

Deno.test("overpass is posted to the configured host and an empty answer uses a short ttl", async () => {
  let posted = "";
  let ttl = 0;
  const response = await handleGeo(
    request({ op: "overpass", south: 51.4, west: -0.2, north: 51.42, east: -0.18 }),
    deps({
      endpoint: async () => OVERPASS,
      fetchUpstream: async (url, init) => {
        posted = `${url}\n${init.body}`;
        return new Response(JSON.stringify({ elements: [] }), { status: 200 });
      },
      writeCache: async (_key, _kind, _body, seconds) => {
        ttl = seconds;
      },
    }),
  );
  assertEquals(response.status, 200);
  assertEquals(posted.startsWith(OVERPASS), true);
  assertEquals(posted.includes("historic"), true);
  assertEquals(posted.includes("openstreetmap.org"), false);
  assertEquals(ttl, 6 * 60 * 60);
});

Deno.test("a missing token is rejected", async () => {
  const response = await handleGeo(
    new Request("https://example.test/geo-proxy", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ op: "reverse", lat: 1, lng: 2 }),
    }),
    deps({}),
  );
  assertEquals(response.status, 401);
});

function request(body: unknown): Request {
  return new Request("https://example.test/geo-proxy", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: "Bearer user-token",
    },
    body: JSON.stringify(body),
  });
}

function deps(partial: Partial<GeoDeps>): GeoDeps {
  return {
    endpoint: async () => ENDPOINT,
    readCache: async () => null,
    writeCache: async () => undefined,
    claim: async () => true,
    fetchUpstream: async () => new Response("{}", { status: 200 }),
    ...partial,
  };
}
