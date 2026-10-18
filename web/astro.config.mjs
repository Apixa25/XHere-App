import { defineConfig } from "astro/config";

// Static site. Share pages for live short ids are a Cloudflare Pages Function
// (web/functions), which is not deployed until Steven approves the account.
export default defineConfig({
  site: "https://xhere.world",
  output: "static",
  trailingSlash: "never",
  build: {
    format: "directory",
  },
});
