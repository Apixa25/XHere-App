# supabase/

Postgres / PostGIS schema, Row-Level Security, RPCs, Edge Functions, and pgTAP tests for XHere.

- `config.toml`: local Supabase CLI config (`supabase init`, `project_id = "xhere"`). It holds no secrets; provider credentials are `env(...)` references.
- `migrations/`: SQL migrations (**M1**), applied in filename order.
- `seed.sql`: **local demo data only** (4 demo users and 15 drops in Jamestown, New York). `supabase db reset` loads it. It is never pushed to a hosted project.
- `tests/database/`: pgTAP tests (**M1**), run by `supabase test db`.
- `functions/`: Supabase Edge Functions. **M5** adds `suggest-category` and `strip-exif`. Later milestones add open-data proxies.

No Supabase project has been created. The Supabase CLI's local Docker stack is the stand-in until Steven approves a free Supabase account ([`docs/PLAN.md`](../docs/PLAN.md) §8).

## Run it locally

You need Docker and the [Supabase CLI](https://supabase.com/docs/guides/local-development/cli/getting-started). CI pins CLI **2.121.0**.

```bash
# Database only: skips Auth/Storage/REST/Studio containers. The Auth and Storage schemas still exist.
supabase start -x gotrue,realtime,storage-api,imgproxy,kong,mailpit,postgrest,postgres-meta,studio,edge-runtime,logflare,vector,supavisor

supabase db reset                                                  # all migrations + seed.sql
supabase test db                                                   # pgTAP suite
supabase db lint --schema public,private --level warning --fail-on warning
supabase stop --no-backup

# Edge Functions (no Docker). CI pins Deno 2.1.4. Tests do not download gte-small.
deno test --allow-net supabase/functions
deno check supabase/functions/suggest-category/index.ts
deno check supabase/functions/strip-exif/index.ts
```

Postgres listens on `127.0.0.1:54322` (user `postgres`, password `postgres`, local only). If `supabase start` fails with a `DbSetupError` because containers can't reach each other (common in nested Docker/VMs), Docker's bridge traffic is probably being dropped by iptables. Running `sudo sysctl -w net.bridge.bridge-nf-call-iptables=0 net.bridge.bridge-nf-call-ip6tables=0` fixes it.

## Migrations

| File | Contents |
|---|---|
| `20261018000000_extensions.sql` | PostGIS, pg_trgm, pg_jsonschema, pgvector (all in `extensions`), pg_cron, and the locked-down `private` schema |
| `20261018000100_core_schema.sql` | Enums and tables: profiles, user_moderation, user_blocks, tier_limits, content_filters, category_groups, categories, category_versions, drops, drop_media, confirmations, reports, moderation_actions. Indexes (GiST on geography, trigram on text) and the profile-on-signup trigger |
| `20261018000200_rules.sql` | Rule helpers (location fuzzing, pre-moderation, content filters, visibility) and triggers (confirmation tallies, edit re-moderation, media limits) |
| `20261018000300_rls.sql` | RLS enabled on **every** table, explicit grants, and policies |
| `20261018000400_rpcs.sql` | Client RPCs (below) |
| `20261018000500_cron.sql` | pg_cron jobs: expire drops (every 5 min), purge deleted accounts after 30 days, recompute tiers nightly |
| `20261018000600_taxonomy.sql` | The PLAN §4 taxonomy as data: 11 groups, 30 categories with field schemas and rules, tier limits, content filters |
| `20261018000700_suggestions.sql` | Suggestion hints and hash-v1 embeddings, `category_suggestion_log`, `suggest_categories` / `match_categories` / `nearby_category_counts` / `log_category_suggestion`, and the private `drop-media` bucket |
| `20261018000800_reveal_payload.sql` | M6: tags stay hidden until a drop is revealed; changing an existing confirmation doesn't count against the hourly cap |
| `20261018000900_audit_actions.sql` | Adds `set_role` to `moderation_action_type` (enum values need their own migration) |
| `20261018001000_safety_compliance.sql` | M7: `hidden_drops` + hide/unhide, `my_blocks` / `my_hidden_drops` / `public_profile`, `moderation_queue`, append-only audit log, tier/role change auditing, `export_my_data`, and the `street_address` filter on `incident_report` |

The taxonomy lives in a migration rather than `seed.sql` so a hosted project gets it too. Categories are rows, so adding one doesn't need a new migration type. Every change bumps `categories.version`, and each drop records the version it was validated against (`category_versions`).

## Security model

- Clients never `insert` drops, confirmations, or reports directly. Writes go through `security definer` RPCs that validate against the category's rules. Table grants only allow narrow column updates on your own rows (profile fields; a drop's title, body, tags, and anonymity).
- The true location (`drops.geog`) is never returned to other users. Reads, reveal checks, and distances all use `display_geog`, which is snapped to a grid of `location_precision_m` (100 m for `incident_report`). That keeps the real spot from being triangulated.
- Functions use `set search_path = ''`. `private` functions are not executable by `PUBLIC`/`anon` (the pgTAP suite checks this).
- Errors raise a stable slug as the message with an SQLSTATE: `42501` auth/permission, `22023` validation, `P0002` not found, `PT429` rate limited (PostgREST returns HTTP 429).

## RPCs

| RPC | Who | What |
|---|---|---|
| `drops_in_view(min_lng, min_lat, max_lng, max_lat, categories, zoom)` | anon, user | Map viewport: teasers at zoom ≥ 15, grid clusters with per-category counts below that |
| `drops_nearby(lat, lng, radius_m, categories, limit)` | user | Distance-ordered `drop_card`s. Body, attributes, and media are filled only inside the drop's reveal radius |
| `drop_detail(drop_id, lat, lng)` | user | One `drop_card`; counts a view |
| `drop_teaser(short_id)` | anon, user | Share-page teaser; never reveals content |
| `create_drop(category, lat, lng, title, body, attributes, tags, reveal_radius_m, expires_at, display_anonymous, media, acknowledge_guidelines)` | user | Validates tier, guidelines, JSON-schema attributes, content filters, reveal radius, lifetime caps, media, and per-tier rate limits. Fuzzes the location, applies pre-moderation, and returns possible duplicates |
| `confirm_drop(drop_id, verdict, lat, lng, note)` | user | "Still true?" vote: one per user per drop, must be on site, renews the lifetime where the category allows |
| `report(target_type, target_id, reason, details)` | user | Idempotent. Auto-hides a drop at the category's report threshold |
| `block_user` / `unblock_user` | user | Blocks hide drops in both directions |
| `delete_my_account()` | user | Scrubs the profile immediately; the nightly cron hard-deletes after 30 days |
| `moderate_drop(drop_id, action, reason)` | moderator | `approve` / `remove` / `restore`, written to the audit log |
| `resolve_report(report_id, status, note)` | moderator | Action or dismiss a report |
| `suspend_user(user_id, until, reason)` | moderator | Suspended users can't post or confirm, but can still report and block |
| `moderation_queue(limit)` | moderator | Pending drops plus drops and profiles with open reports. Sensitive categories first, then oldest. Never your own drops |
| `hide_drop(drop_id)` / `unhide_drop(drop_id)` | user | Hide one drop for yourself only, everywhere (map, list, detail, share page) |
| `my_hidden_drops()` / `my_blocks()` | user | The Settings lists, with undo |
| `public_profile(handle)` | user | Id, handle, display name, and join date, so a profile can be reported or blocked from a drop |
| `export_my_data()` | user | One JSON document (`xhere-export-v1`) with your profile, drops (true locations included), confirmations, reports filed, blocks, hidden drops, suggestion log, and moderation of your content (moderators not named) |
| `embedding_model()` | user | `hash-v1` until categories are re-embedded with gte-small |
| `suggest_categories(text, lat, lng, hour, recent, limit)` | user | Top categories from the hash embedding, nearby counts, recent picks, and a small time-of-day prior |
| `match_categories(embedding, limit)` | user | Nearest categories for a vector the Edge Function already built (the gte-small path) |
| `nearby_category_counts(lat, lng, radius_m)` | user | Visible drops within 500 m, counted per category |
| `log_category_suggestion(inputs, suggested, chosen)` | user | Writes `category_suggestion_log`. Direct inserts are not granted |

## Edge Functions

| Function | What |
|---|---|
| `suggest-category` | POST `{text, labels, lat, lng, hour, recent_slugs}`. Calls `suggest_categories` for hash-v1. Calls `match_categories` only when `XHERE_EMBEDDING_MODEL=gte-small` and the on-platform gte-small session is actually available. |
| `strip-exif` | POST raw JPEG, PNG, or WebP (10 MB max). Strips Exif/XMP, then stores `drop-media/<user id>/<uuid>.<ext>` with the caller's JWT. No service-role key. |

Moderator/admin is `profiles.role`. There's no UI to grant it yet. On a hosted project, the first admin is set once with SQL from the dashboard: `update public.profiles set role = 'admin' where handle = '...';`. Since M7 that edit is itself logged (`set_role` in `moderation_actions`), and the audit log rejects updates and deletes.

## Tests

`tests/database/*.test.sql` (pgTAP): schema/privileges, RLS, nearby/reveal/blur/expiry/blocks, `create_drop` validation, crime (`incident_report`) rules, confirmations, moderation/cron, and the M5 suggestion log, hash embeddings, and fixture score (`07_suggestions.test.sql`, 19 assertions), the M6 reveal payload, and M7 safety/compliance (`09_safety_compliance.test.sql`: Safety pre-moderation, auto-hide threshold, blocks both ways, hide-for-me, moderator-only access, append-only audit log, export). Each file runs in a transaction and rolls back. `supabase/functions/**/*_test.ts` is the Deno suite for the two Edge Functions, including the same 32-phrase fixture set.
