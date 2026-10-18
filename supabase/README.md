# supabase/

Postgres / PostGIS schema, Row-Level Security, RPCs, Edge Functions, and pgTAP tests for XHere.

Layout reserved now:

- `migrations/` — SQL migrations (**M1**)
- `functions/` — Supabase Edge Functions (**M5** suggestion, later open-data proxies)
- `tests/` — pgTAP tests, run in CI on a `postgis/postgis` image (**M1**)

No Supabase project has been created. Local Postgres/PostGIS in Docker is the stand-in until Steven approves a free Supabase account ([`docs/PLAN.md`](../docs/PLAN.md) §8).
