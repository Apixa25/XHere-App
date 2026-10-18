import type { APIRoute } from "astro";
import { FIXTURES } from "../lib/drops.mjs";
import { categoryMeta } from "../lib/categories.mjs";
import { SITE } from "../lib/site.mjs";

export const GET: APIRoute = () => {
  const paths = [
    "/",
    "/privacy",
    "/terms",
    "/guidelines",
    "/delete-account",
    "/support",
    ...FIXTURES.filter((drop) => !categoryMeta(drop.categorySlug).sensitive).map((drop) => `/d/${drop.shortId}`),
  ];
  const body = paths
    .map((path) => `<url><loc>${SITE.origin}${path === "/" ? "/" : path}</loc></url>`)
    .join("");
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${body}</urlset>\n`;
  return new Response(xml, {
    headers: { "content-type": "application/xml; charset=utf-8" },
  });
};
