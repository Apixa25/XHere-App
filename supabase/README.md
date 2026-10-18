# supabase/

Postgres / PostGIS schema, Row-Level Security, RPCs, Edge Functions, and pgTAP tests for XHere.

- `config.toml`: local Supabase CLI config (`supabase init`, `project_id = "xhere"`). It holds no secrets; provider credentials are `env(...)` references.
- `migrations/`: SQL migrations (**M1**), applied in filename order.
- `seed.sql`: **local demo data** (4 demo users and 15 drops in Jamestown, New York). `supabase db reset` loads it. `supabase db push` does not. It was applied by hand to the pilot project; see [Demo seed on the pilot](#demo-seed-on-the-pilot).
- `tests/database/`: pgTAP tests (**M1**), run by `supabase test db`.
- `functions/`: Supabase Edge Functions. **M5** adds `suggest-category` and `strip-exif`. Later milestones add open-data proxies.

## Hosted project

The pilot project exists (PLAN §1.4 and §8, free tier). Local Docker is still what CI and `supabase test db` use. They do not talk to this project.

| | |
|---|---|
| Name | XHere |
| Project ref | `jpeuldzegtbqfezijhuy` |
| Region | us-east-1 |
| API URL (`SUPABASE_URL`, `PUBLIC_SUPABASE_URL`) | `https://jpeuldzegtbqfezijhuy.supabase.co` |
| Dashboard | https://supabase.com/dashboard/project/jpeuldzegtbqfezijhuy |

The **publishable** key (it starts with `sb_publishable_`) is what the clients send. Put it in `SUPABASE_ANON_KEY` (Android `local.properties`) and `PUBLIC_SUPABASE_ANON_KEY` (Cloudflare Pages, when that account exists). The variable names stay the same. Do not commit the key, and do not commit the legacy anon JWT. The service-role / secret key never goes in the repo, the app, or the site.

The twelve migrations through `20261018001100_tip_lines.sql`, `seed.sql`, and both Edge Functions (`strip-exif`, `suggest-category`, `verify_jwt = true`) were applied from `main` at `0d2796a`. `XHERE_EMBEDDING_MODEL` is unset, so suggestions stay on `hash-v1`.

### Before `supabase db push`

Those twelve files were applied under hosted version numbers (the apply timestamps), not the repo prefixes. Link the CLI, then repair history, or `db push` will try to run them again:

```bash
supabase migration repair --status reverted 20261018113225 20261018113233 20261018113243 20261018113248 20261018113255 20261018113300 20261018113306 20261018113310 20261018113315 20261018113322 20261018113327 20261018113332
supabase migration repair --status applied 20261018000000 20261018000100 20261018000200 20261018000300 20261018000400 20261018000500 20261018000600 20261018000700 20261018000800 20261018000900 20261018001000 20261018001100
```

After that, `supabase db push` applies only migrations newer than `20261018001100`.

### Demo seed on the pilot

`seed.sql` is still not part of `db push`. It was run once, unchanged, so the Jamestown pilot has the demo drops. The four accounts use reserved `@example.com` addresses (`guide.demo`, `trusted.demo`, `regular.demo`, `new.demo`), have no password, cannot sign in, and are marked `raw_user_meta_data = {"demo": true}`. `demo_trusted` is a moderator, which does nothing until someone can sign in as that account. Incident reports hit the 72-hour cap and events age out, so after about three days the expiry job leaves the evergreen drops.

To remove every demo row (profiles and drops cascade from the user delete):

```sql
delete from auth.users where raw_user_meta_data->>'demo' = 'true';
```

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
| `20261018001100_tip_lines.sql` | M7: `private.tip_lines` (seeded with the Jamestown Police anonymous tip line over Chautauqua County) and a trigger that refuses `incident_report` drops outside every tip-line area (App Review 1.7) |
| `20261018001200_rls_auth_uid_initplan.sql` | Lint 0003: the 18 policies that call `auth.uid()` now call `(select auth.uid())`. Same allow/deny result |
| `20261018001300_reference_admin_policies.sql` | Lint 0006: `tier_limits` and `content_filters` admin policies are insert/update/delete only, so SELECT has one policy |
| `20261018001400_foreign_key_indexes.sql` | Lint 0001: indexes for `category_suggestion_log.chosen_category_id`, `drop_media.owner_id`, `drops (category_id, category_version)`, `moderation_actions.actor_id`, `reports.resolved_by`, `user_moderation.updated_by` |
| `20261018001500_tip_lines_rls.sql` | RLS enabled on `private.tip_lines`. No client policies. `create_drop` still reads it because that function is security definer |

The taxonomy lives in a migration rather than `seed.sql` so a hosted project gets it too. Categories are rows, so adding one doesn't need a new migration type. Every change bumps `categories.version`, and each drop records the version it was validated against (`category_versions`).

## Security model

- Clients never `insert` drops, confirmations, or reports directly. Writes go through `security definer` RPCs that validate against the category's rules. Table grants only allow narrow column updates on your own rows (profile fields; a drop's title, body, tags, and anonymity).
- The true location (`drops.geog`) is never returned to other users. Reads, reveal checks, and distances all use `display_geog`, which is snapped to a grid of `location_precision_m` (100 m for `incident_report`). That keeps the real spot from being triangulated.
- RLS is enabled on every table, including `private.tip_lines` (no client policy; the security-definer `create_drop` path still reads it).
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
| `suggest-category` | POST `{text, labels, lat, lng, hour, recent_slugs}`. Calls `suggest_categories` for hash-v1. Calls `match_categories` only when `XHERE_EMBEDDING_MODEL=gte-small` and the on-platform gte-small session is actually available. A caller who is not signed in (no user JWT, or the RPC returns 401/403) gets **401** `{"error":"not_authenticated"}`, the same JSON as `strip-exif`. |
| `strip-exif` | POST raw JPEG, PNG, or WebP (10 MB max). Strips Exif/XMP, then stores `drop-media/<user id>/<uuid>.<ext>` with the caller's JWT. No service-role key. |

Moderator/admin is `profiles.role`. There's no UI to grant it yet. On a hosted project, the first admin is set once with SQL from the dashboard: `update public.profiles set role = 'admin' where handle = '...';`. Since M7 that edit is itself logged (`set_role` in `moderation_actions`), and the audit log rejects updates and deletes.

## Tests

`tests/database/*.test.sql` (pgTAP): schema/privileges, RLS, nearby/reveal/blur/expiry/blocks, `create_drop` validation, crime (`incident_report`) rules, confirmations, moderation/cron, and the M5 suggestion log, hash embeddings, and fixture score (`07_suggestions.test.sql`, 19 assertions), the M6 reveal fixes, and M7 safety/compliance (`09_safety_compliance.test.sql`: Safety pre-moderation, auto-hide threshold, blocks both ways, hide-for-me, moderator-only access, append-only audit log, export) plus `10_tip_lines.test.sql` (incident reports only inside a tip-line area) and `11_advisor_followups.test.sql` (the `(select auth.uid())` policies, one SELECT policy on the reference tables, the foreign-key indexes, tip-line RLS, and the same read/write access as before). Each file runs in a transaction and rolls back. `supabase/functions/**/*_test.ts` is the Deno suite for the two Edge Functions, including the same 32-phrase fixture set and a 401 for a signed-out suggestion.
