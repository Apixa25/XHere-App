// hash-v1. Same tokens, FNV-1a, and L2 norm as private.hash_embed and HashEmbedding.kt.
// gte-small is optional and only used when the category rows were embedded with it.

export const DIMENSIONS = 384;

export function tokenize(text: string): string[] {
  const normalized = text.toLowerCase().replaceAll("_", " ").replaceAll("-", " ");
  const parts = normalized.split(/[^a-z0-9]+/);
  const out: string[] = [];
  for (const part of parts) {
    if (part.length < 2) continue;
    if (part.length > 4 && part.endsWith("s") && !part.endsWith("ss")) {
      out.push(part.slice(0, -1));
    } else {
      out.push(part);
    }
  }
  return out;
}

function fnv1a(token: string): number {
  let hash = 2166136261;
  for (let i = 0; i < token.length; i++) {
    hash ^= token.charCodeAt(i);
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  return hash >>> 0;
}

export function hashEmbed(text: string): number[] {
  const vector = new Array<number>(DIMENSIONS).fill(0);
  for (const token of tokenize(text)) {
    const hash = fnv1a(token);
    const index = hash % DIMENSIONS;
    const sign = ((hash >> 9) & 1) === 1 ? 1 : -1;
    vector[index] += sign;
  }
  let sum = 0;
  for (const value of vector) sum += value * value;
  const norm = Math.sqrt(sum);
  if (norm === 0) return vector;
  return vector.map((value) => value / norm);
}

export function cosine(left: number[], right: number[]): number {
  let sum = 0;
  const n = Math.min(left.length, right.length);
  for (let i = 0; i < n; i++) sum += left[i] * right[i];
  return sum;
}

export interface GteSession {
  run(text: string, options: { mean_pool: boolean; normalize: boolean }): Promise<ArrayLike<number>>;
}

/** Supabase Edge Runtime exposes this. Plain Deno (CI) does not, and that is expected. */
export function tryGteSession(): (new (name: string) => GteSession) | null {
  const ai = (globalThis as { Supabase?: { ai?: { Session?: new (name: string) => GteSession } } }).Supabase?.ai;
  return ai?.Session ?? null;
}

export async function embedQuery(text: string, model: string): Promise<{ vector: number[]; model: string }> {
  if (model === "gte-small") {
    const Session = tryGteSession();
    if (Session) {
      const session = new Session("gte-small");
      const output = await session.run(text, { mean_pool: true, normalize: true });
      return { vector: Array.from(output), model: "gte-small" };
    }
  }
  return { vector: hashEmbed(text), model: "hash-v1" };
}
