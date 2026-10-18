import { assertAlmostEquals, assertEquals, assertGreater, assertRejects } from "jsr:@std/assert@1";
import { cosine, hashEmbed } from "../_shared/embed.ts";
import { fixtureScore, rankCategories } from "../_shared/rank.ts";
import { errorForRpcStatus, handleSuggest, NotAuthenticatedError } from "./handler.ts";

Deno.test("fixture top-3 is at least 70 percent", () => {
  const score = fixtureScore();
  const ratio = score.hits / score.total;
  console.log(
    `suggestion fixture top-3 ${score.hits}/${score.total} = ${(ratio * 100).toFixed(1)}% (top-1 ${score.top1}/${score.total})`,
  );
  assertGreater(ratio, 0.69);
  assertEquals(score.hits, score.total);
});

Deno.test("empty text defaults to note", () => {
  const ranked = rankCategories({ text: "" });
  assertEquals(ranked[0].slug, "note");
});

Deno.test("a nearby prior lifts food trucks", () => {
  const plain = rankCategories({ text: "lunch cart" });
  const boosted = rankCategories({ text: "lunch cart", nearby: { food_truck: 8 } });
  assertEquals(boosted[0].slug, "food_truck");
  const plainTruck = plain.find((row) => row.slug === "food_truck");
  const boostedTruck = boosted.find((row) => row.slug === "food_truck");
  if (!plainTruck || !boostedTruck) throw new Error("food_truck missing");
  assertGreater(boostedTruck.score, plainTruck.score);
});

Deno.test("hash embeddings are normalized and stable", () => {
  const once = hashEmbed("food truck");
  const twice = hashEmbed("food truck");
  assertEquals(once.length, 384);
  assertEquals(once, twice);
  const norm = Math.sqrt(once.reduce((sum, value) => sum + value * value, 0));
  assertAlmostEquals(norm, 1, 1e-9);
  const close = cosine(once, hashEmbed("food truck cart"));
  const far = cosine(once, hashEmbed("church mosque temple"));
  assertGreater(close, far);
});

Deno.test("the handler returns the hash-v1 top 3", async () => {
  const response = await handleSuggest(jsonRequest({ text: "mural on the alley wall", labels: ["Graffiti"] }), {
    model: async () => "hash-v1",
    suggestHash: async (_body, text) => rankCategories({ text }).map((row) => ({ ...row })),
    match: async () => {
      throw new Error("hash path must not match vectors");
    },
    nearby: async () => ({}),
  });
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.model, "hash-v1");
  assertEquals(body.suggestions[0].slug, "street_art");
  assertEquals(body.suggestions.length, 3);
});

Deno.test("gte-small blends the pgvector cosine with a nearby prior", async () => {
  let sawText = "";
  const response = await handleSuggest(jsonRequest({
    text: "water on the road",
    labels: ["Flood"],
    lat: 42.1,
    lng: -79.2,
    hour: 18,
  }), {
    model: async () => "gte-small",
    embed: async (text) => {
      sawText = text;
      return { vector: [0.2, 0.3], model: "gte-small" };
    },
    suggestHash: async () => {
      throw new Error("gte path must not hash");
    },
    match: async () => [
      { slug: "note", label: "Note", cosine: 0.4 },
      { slug: "hazard", label: "Hazard", cosine: 0.42 },
    ],
    nearby: async () => ({ hazard: 4 }),
  });
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(sawText.includes("Flood"), true);
  assertEquals(body.model, "gte-small");
  assertEquals(body.suggestions[0].slug, "hazard");
});

Deno.test("rpc 401 and 403 are not_authenticated; other statuses still throw", () => {
  const denied = errorForRpcStatus("suggest_categories", 401);
  const forbidden = errorForRpcStatus("match_categories", 403);
  if (!(denied instanceof NotAuthenticatedError)) throw new Error("401 should be NotAuthenticatedError");
  if (!(forbidden instanceof NotAuthenticatedError)) throw new Error("403 should be NotAuthenticatedError");
  const failed = errorForRpcStatus("suggest_categories", 500);
  assertEquals(failed instanceof NotAuthenticatedError, false);
  assertEquals(failed.message, "rpc_suggest_categories_500");
});

Deno.test("a signed-out caller gets 401 not_authenticated", async () => {
  const response = await handleSuggest(jsonRequest({ text: "statue in the park" }), {
    model: async () => "hash-v1",
    suggestHash: async () => {
      throw new NotAuthenticatedError();
    },
    match: async () => {
      throw new Error("hash path must not match vectors");
    },
    nearby: async () => ({}),
  });
  assertEquals(response.status, 401);
  assertEquals(response.headers.get("content-type"), "application/json");
  assertEquals(await response.json(), { error: "not_authenticated" });
});

Deno.test("a signed-out gte-small caller gets 401 before nearby counts", async () => {
  const response = await handleSuggest(jsonRequest({ text: "water on the road", lat: 42.1, lng: -79.2 }), {
    model: async () => "gte-small",
    embed: async () => ({ vector: [0.1], model: "gte-small" }),
    suggestHash: async () => {
      throw new Error("gte path must not hash");
    },
    match: async () => {
      throw errorForRpcStatus("match_categories", 401);
    },
    nearby: async () => {
      throw new Error("should not count nearby after auth failure");
    },
  });
  assertEquals(response.status, 401);
  assertEquals(await response.json(), { error: "not_authenticated" });
});

Deno.test("a non-auth rpc failure still surfaces", async () => {
  await assertRejects(
    () =>
      handleSuggest(jsonRequest({ text: "statue" }), {
        model: async () => "hash-v1",
        suggestHash: async () => {
          throw errorForRpcStatus("suggest_categories", 500);
        },
        match: async () => [],
        nearby: async () => ({}),
      }),
    Error,
    "rpc_suggest_categories_500",
  );
});

function jsonRequest(payload: unknown): Request {
  return new Request("http://local/suggest-category", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
}
