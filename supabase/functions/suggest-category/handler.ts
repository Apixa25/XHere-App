// HTTP wrapper around suggest_categories (hash-v1) or gte-small + match_categories.
// Tests pass a SuggestDeps so they never need a database or the model weights.

import type { Ranked } from "../_shared/rank.ts";
import { embedQuery } from "../_shared/embed.ts";

export interface SuggestBody {
  text?: string;
  labels?: string[];
  lat?: number | null;
  lng?: number | null;
  hour?: number | null;
  recent_slugs?: string[];
}

export interface SuggestDeps {
  model(): Promise<string>;
  /** hash-v1 path: the database embeds the text and blends priors. */
  suggestHash(body: SuggestBody, text: string): Promise<Ranked[]>;
  /** gte-small path: cosine against categories.embedding, then the same blend. */
  match(vector: number[]): Promise<Array<{ slug: string; label: string; cosine: number }>>;
  nearby(lat: number, lng: number): Promise<Record<string, number>>;
  embed?(text: string, model: string): Promise<{ vector: number[]; model: string }>;
}

export async function handleSuggest(req: Request, deps: SuggestDeps): Promise<Response> {
  if (req.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }
  let body: SuggestBody;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  const text = [body.text ?? "", ...(body.labels ?? [])].map((part) => part.trim()).filter(Boolean).join(" ");
  const model = await deps.model();
  if (model === "gte-small") {
    const embedded = await (deps.embed ?? embedQuery)(text, model);
    if (embedded.model === "gte-small") {
      const matches = await deps.match(embedded.vector);
      const nearby = body.lat != null && body.lng != null ? await deps.nearby(body.lat, body.lng) : {};
      return json({ model: "gte-small", suggestions: blendGivenCosine(matches, nearby, body) });
    }
  }
  const suggestions = await deps.suggestHash(body, text);
  return json({
    model: "hash-v1",
    suggestions: suggestions.slice(0, 3).map(publicSuggestion),
  });
}

function blendGivenCosine(
  matches: Array<{ slug: string; label: string; cosine: number }>,
  nearby: Record<string, number>,
  body: SuggestBody,
): Array<{ slug: string; label: string; score: number }> {
  const counts = Object.values(nearby);
  const maxNearby = counts.length === 0 ? 1 : Math.max(1, ...counts);
  const recent = new Set(body.recent_slugs ?? []);
  const hour = body.hour;
  const scored = matches.map((match) => {
    const nearbyBoost = counts.length === 0 ? 0 : (nearby[match.slug] ?? 0) / maxNearby;
    const recentBoost = recent.has(match.slug) ? 1 : 0;
    const time = hour == null ? 0 : (timeBoost(match.slug, hour) ? 1 : 0);
    const score = match.cosine + 0.18 * nearbyBoost + 0.12 * recentBoost + 0.08 * time;
    return { slug: match.slug, label: match.label, score, cosine: match.cosine };
  });
  scored.sort((a, b) => b.score - a.score || a.slug.localeCompare(b.slug));
  const best = scored.reduce((max, row) => Math.max(max, row.cosine), 0);
  const blank = [body.text ?? "", ...(body.labels ?? [])].join("").trim() === "";
  const ordered = blank || best < 0.02
    ? [...scored.filter((row) => row.slug === "note"), ...scored.filter((row) => row.slug !== "note")]
    : scored;
  return ordered.slice(0, 3).map((row) => ({ slug: row.slug, label: row.label, score: row.score }));
}

function timeBoost(slug: string, hour: number): boolean {
  if (["food_truck", "market", "eatery_tip"].includes(slug)) {
    return (hour >= 11 && hour <= 14) || (hour >= 17 && hour <= 20);
  }
  if (["event", "popup", "music_spot"].includes(slug)) return hour >= 16 && hour <= 22;
  if (["yard_sale", "free_stuff", "for_sale"].includes(slug)) return hour >= 8 && hour <= 16;
  if (slug === "wildlife") return (hour >= 5 && hour <= 9) || (hour >= 17 && hour <= 20);
  return false;
}

function publicSuggestion(row: Ranked): { slug: string; label: string; score: number } {
  return { slug: row.slug, label: row.label, score: row.score };
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json" },
  });
}
