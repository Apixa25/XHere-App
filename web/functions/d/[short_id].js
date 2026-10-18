import { loadPublicDrop } from "../../src/lib/drops.mjs";
import { renderNotFoundDocument, renderShareDocument } from "../../src/lib/share-document.mjs";

function supabaseEnv(context) {
  const env = context.env || {};
  return {
    url: env.PUBLIC_SUPABASE_URL || "",
    key: env.PUBLIC_SUPABASE_ANON_KEY || "",
  };
}

export async function onRequest(context) {
  const shortId = context.params.short_id;
  try {
    const drop = await loadPublicDrop(shortId, supabaseEnv(context));
    if (!drop) {
      return new Response(renderNotFoundDocument(), {
        status: 404,
        headers: { "content-type": "text/html; charset=utf-8" },
      });
    }
    return new Response(renderShareDocument(drop), {
      headers: {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "public, max-age=60",
      },
    });
  } catch {
    return new Response("XHere could not load that drop.", {
      status: 503,
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  }
}
