import { assertAlmostEquals, assertEquals, assertGreater } from "jsr:@std/assert@1";
import { cosine, hashEmbed } from "../_shared/embed.ts";
import { fixtureScore, rankCategories } from "../_shared/rank.ts";
import { handleSuggest } from "./handler.ts";

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

function jsonRequest(payload: unknown): Request {
  return new Request("http://local/suggest-category", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
}
