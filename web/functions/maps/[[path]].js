import { loadPublicDrop } from "../../src/lib/drops.mjs";
import { renderStaticMapSvg } from "../../src/lib/static-map.mjs";

function supabaseEnv(context) {
  const env = context.env || {};
  return {
    url: env.PUBLIC_SUPABASE_URL || "",
    key: env.PUBLIC_SUPABASE_ANON_KEY || "",
  };
}

export async function onRequest(context) {
  const raw = context.params.path;
  const name = Array.isArray(raw) ? raw.join("/") : String(raw || "");
  const shortId = name.replace(/\.svg$/, "");
  try {
    const drop = await loadPublicDrop(shortId, supabaseEnv(context));
    if (!drop) return new Response("Not found", { status: 404 });
    const svg = await renderStaticMapSvg(drop);
    return new Response(svg, {
      headers: {
        "content-type": "image/svg+xml; charset=utf-8",
        "cache-control": "public, max-age=86400",
      },
    });
  } catch {
    return new Response("Map unavailable", { status: 503 });
  }
}
