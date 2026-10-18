// Blend weights match public.suggest_categories and CategoryRanker.kt.
import { CATALOG, documentOf } from "./catalog.ts";
import { cosine, hashEmbed, tokenize } from "./embed.ts";

export const NEARBY_WEIGHT = 0.18;
export const RECENT_WEIGHT = 0.12;
export const TIME_WEIGHT = 0.08;
export const NOTE_FLOOR = 0.02;

export interface Ranked {
  slug: string;
  label: string;
  score: number;
  cosine: number;
}

export interface RankInput {
  text: string;
  nearby?: Record<string, number>;
  recent?: string[];
  hour?: number | null;
  limit?: number;
}

export function timeMatch(slug: string, hour: number): boolean {
  if (["food_truck", "market", "eatery_tip"].includes(slug)) {
    return (hour >= 11 && hour <= 14) || (hour >= 17 && hour <= 20);
  }
  if (["event", "popup", "music_spot"].includes(slug)) return hour >= 16 && hour <= 22;
  if (["yard_sale", "free_stuff", "for_sale"].includes(slug)) return hour >= 8 && hour <= 16;
  if (slug === "wildlife") return (hour >= 5 && hour <= 9) || (hour >= 17 && hour <= 20);
  return false;
}

export function rankCategories(input: RankInput): Ranked[] {
  const documents = CATALOG.map((entry) => ({
    slug: entry.slug,
    label: entry.label,
    vector: hashEmbed(documentOf(entry)),
  }));
  return rankVectors({ ...input, documents });
}

export function rankVectors(input: RankInput & {
  documents: { slug: string; label: string; vector: number[] }[];
}): Ranked[] {
  const limit = Math.min(10, Math.max(1, input.limit ?? 3));
  const blank = tokenize(input.text).length === 0;
  const query = hashEmbed(input.text);
  const nearby = input.nearby ?? {};
  const counts = Object.values(nearby);
  const maxNearby = counts.length === 0 ? 1 : Math.max(1, ...counts);
  const recent = new Set(input.recent ?? []);
  const scored: Ranked[] = input.documents.map((document) => {
    const similarity = blank ? 0 : cosine(query, document.vector);
    const nearbyBoost = counts.length === 0 ? 0 : (nearby[document.slug] ?? 0) / maxNearby;
    const recentBoost = recent.has(document.slug) ? 1 : 0;
    const time = input.hour == null ? 0 : (timeMatch(document.slug, input.hour) ? 1 : 0);
    return {
      slug: document.slug,
      label: document.label,
      cosine: similarity,
      score: similarity + NEARBY_WEIGHT * nearbyBoost + RECENT_WEIGHT * recentBoost + TIME_WEIGHT * time,
    };
  });
  scored.sort((a, b) => b.score - a.score || a.slug.localeCompare(b.slug));
  const best = scored.reduce((max, row) => Math.max(max, row.cosine), 0);
  if (blank || best < NOTE_FLOOR) {
    const note = scored.filter((row) => row.slug === "note");
    const rest = scored.filter((row) => row.slug !== "note");
    return [...note, ...rest].slice(0, limit);
  }
  return scored.slice(0, limit);
}

export const FIXTURES: Array<[string, string]> = [
  ["food truck street food", "food_truck"],
  ["monument sculpture statue", "public_art"],
  ["flood on the road", "hazard"],
  ["farmers market stalls", "market"],
  ["owl in the oak tree", "wildlife"],
  ["mural on the alley wall", "street_art"],
  ["garage sale this weekend", "yard_sale"],
  ["drinking fountain by the path", "water_source"],
  ["busker with a guitar", "music_spot"],
  ["church service on sunday", "place_of_worship"],
  ["tunnel under the railroad", "underground"],
  ["construction crane blocking the lane", "construction"],
  ["hidden courtyard easy to miss", "hidden_gem"],
  ["museum says this happened in 1890", "historical_fact"],
  ["popup shop until friday", "popup"],
  ["restaurant menu what to order", "eatery_tip"],
  ["muddy trail after the turn", "trail_note"],
  ["free couch on the curb", "free_stuff"],
  ["public library community hall", "community_space"],
  ["scenic overlook of the lake", "viewpoint"],
  ["theft break-in last night", "incident_report"],
  ["bridge utility work", "infrastructure"],
  ["memorial plaque for the town", "memorial"],
  ["local shortcut only locals know", "tip"],
  ["community flyer on the notice board", "notice_board"],
  ["a general note about the benches", "note"],
  ["personal tale my grandmother told", "story"],
  ["item for sale pickup here", "for_sale"],
  ["live festival happening tonight", "event"],
  ["ice warning sidewalk closed", "hazard"],
  ["safety alert caution nearby", "safety_alert"],
  ["taco trailer lunch vehicle", "food_truck"],
];

export function fixtureScore(): { hits: number; total: number; top1: number } {
  let hits = 0;
  let top1 = 0;
  for (const [text, expected] of FIXTURES) {
    const ranked = rankCategories({ text });
    const slugs = ranked.map((row) => row.slug);
    if (slugs.includes(expected)) hits++;
    if (slugs[0] === expected) top1++;
  }
  return { hits, total: FIXTURES.length, top1 };
}
