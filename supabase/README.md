# supabase/

Postgres / PostGIS schema, Row-Level Security, RPCs, Edge Functions, and pgTAP tests for XHere.

- `config.toml`: local Supabase CLI config (`supabase init`, `project_id = "xhere"`). It holds no secrets; provider credentials are `env(...)` references.
- `migrations/`: SQL migrations (**M1**), applied in filename order.
- `seed.sql`: **local demo data** (4 demo users and 15 drops in Jamestown, New York). `supabase db reset` loads it, then `seed/open-data/jamestown-ny.sql` (the public-record places), then `seed/walks/jamestown-ny.sql` (the pilot walk), then `seed/live/us-ny-jamestown.sql` (which free weather provider covers that box). `supabase db push` does not load those files. The demo seed was applied by hand to the pilot project; see [Demo seed on the pilot](#demo-seed-on-the-pilot). The open-data file, the walk, and the live seed are applied with the steps in [Open-data seed (hosted)](#open-data-seed-hosted).
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

### Apply impact feedback

`20261018194500_impact_feedback.sql` is a normal migration. Do not paste it into the dashboard SQL editor, and do not change Auth, extensions, or other hosted settings for it. From a machine linked to project `jpeuldzegtbqfezijhuy`, after the history repair above if that has not been done yet:

```bash
supabase db push
```

That applies this file and any earlier repo migrations the host does not have yet (`20261018001200` through `20261018001500` if they are still pending). It does not load `seed.sql`. Local checks stay `supabase db reset` and `supabase test db`.

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
| `20261018001600_open_data.sql` | Open-data base layer: `open_data_sources`, `open_data_items`, provenance on `drop_card`, `drops_in_view.source`, `private.upsert_open_data_items`, and a trigger that rejects confirmations of `open_data` drops |
| `20261018194500_impact_feedback.sql` | Unique signed-in views (`drop_views`, `drops.view_count`), `view_count` and `my_verdict` on `drop_card`, and a batched in-app inbox (`impact_notices`, at most one row per drop per UTC day). Anonymous opens are not counted. A public record can be viewed. Confirming one is rejected, so it is not a contribution |
| `20261018210000_open_data_card.sql` | Puts provenance back on `to_drop_card` after impact feedback replaced that function. Public records stay revealed. `is_mine` is false when the author is null |
| `20261018235000_walks_and_quests.sql` | Walks (ordered stops that name existing places) and a global starter quest. Progress is `walk_progress`, `walk_stop_reaches`, `place_visits`, and `quest_dismissals`. Clients read through `walks_for_point`, `start_walk`, `check_in`, `my_quests`, and `dismiss_quest`. No location trail |
| `20261019041827_here_card.sql` | Here Card: `here_card` (tap distance, viewer reveal, no view counted), `place_labels` cache, and the Nominatim slot. Not tied to a city |
| `20261019180000_here_card_rpc_hardening.sql` | Per-user Nominatim pause (10 seconds) on top of the project 1.1 second slot, and place-label checks: 0.001° cell of the coordinates, length and character limits, first write wins for 90 days, one mutating write per user every 10 seconds and 30 per hour. `here_card` stays signed-in only |
| `20261019233041_live_conditions.sql` | Live conditions: `right_now` (1h / 4h / 24h), `civic_issue`, `region_live` plus `region_live_at`, and `expires_at` on open-data upserts. Not tied to a city |

The taxonomy lives in a migration rather than `seed.sql` so a hosted project gets it too. Categories are rows, so adding one doesn't need a new migration type. Every change bumps `categories.version`, and each drop records the version it was validated against (`category_versions`).

## Security model

- Clients never `insert` drops, confirmations, or reports directly. Writes go through `security definer` RPCs that validate against the category's rules. Table grants only allow narrow column updates on your own rows (profile fields; a drop's title, body, tags, and anonymity).
- The true location (`drops.geog`) is never returned to other users. Reads, reveal checks, and distances all use `display_geog`, which is snapped to a grid of `location_precision_m` (100 m for `incident_report`). That keeps the real spot from being triangulated.
- RLS is enabled on every table, including `private.tip_lines` (no client policy; the security-definer `create_drop` path still reads it).
- Functions use `set search_path = ''`. `private` functions are not executable by `PUBLIC`/`anon` (the pgTAP suite checks this).
- Errors raise a stable slug as the message with an SQLSTATE: `42501` auth/permission, `22023` validation, `P0002` not found, `PT429` rate limited (PostgREST returns HTTP 429).
- `here_card` is signed-in only. The Android app shows the map after sign-in and onboarding (`Gate.SignedOut` is the auth screen in `XHereApp`). The website (PLAN §1.5) is a landing page and share teasers, not a browsable map. Signed-out reads stay `drops_in_view` and `drop_teaser`. There is no anon Here Card, because a signed-out caller would only be offered public records and the app has no signed-out map to put them on.

## RPCs

| RPC | Who | What |
|---|---|---|
| `drops_in_view(min_lng, min_lat, max_lng, max_lat, categories, zoom)` | anon, user | Map viewport: teasers at zoom ≥ 15, grid clusters with per-category counts below that |
| `drops_nearby(lat, lng, radius_m, categories, limit)` | user | Distance-ordered `drop_card`s. Body, attributes, and media are filled only inside the drop's reveal radius |
| `drop_detail(drop_id, lat, lng)` | user | One `drop_card`. Counts one unique signed-in view (not the author, not anonymous, not a repeat). A public record includes attribution and stays readable |
| `drop_teaser(short_id)` | anon, user | Share-page teaser. User drops stay locked. An `open_data` place keeps its short summary and attribution |
| `create_drop(category, lat, lng, title, body, attributes, tags, reveal_radius_m, expires_at, display_anonymous, media, acknowledge_guidelines)` | user | Validates tier, guidelines, JSON-schema attributes, content filters, reveal radius, lifetime caps, media, and per-tier rate limits. Fuzzes the location, applies pre-moderation, and returns possible duplicates |
| `confirm_drop(drop_id, verdict, lat, lng, note)` | user | "Still accurate?" vote: one per user per drop, must be on site, not your own drop, renews the lifetime where the category allows. A new `still_true` batches a "your drop helped" inbox row for the author |
| `my_impact_notices(limit)` | user | Inbox: at most one note per drop per UTC day, with that day's seen and confirmed totals. No viewer ids |
| `mark_impact_notice_read(notice_id)` | user | Marks one note read, or every unread note when `notice_id` is null |
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
| `here_card(lat, lng, viewer_lat, viewer_lng, radius_m, limit)` | user | Drops and public records within a short walk of a tapped spot (default 300 m). Distance is from the tap. A user drop's body stays hidden until `viewer_lat`/`viewer_lng` is inside the reveal radius. Public records stay readable, with attribution. Does not count a view. Signed-in only: the Android map is behind sign-in, and the site is not a map |
| `place_label(lat, lng)` | user | Cached OpenStreetMap name for the 0.001° cell, or no row. Attribution is the one the database stored |
| `remember_place_label(lat, lng, name, address)` | user | Saves a Nominatim result on the 0.001° cell of the coordinates. The database stamps © OpenStreetMap contributors / ODbL. The name and address are length- and character-limited. First write wins for 90 days. One mutating write per user every 10 seconds and 30 per hour. No user id on the label |
| `claim_nominatim_slot()` | user | True at most once every 1.1 seconds for the whole project, and the same user cannot take another slot for 10 seconds. A busy project slot does not start that pause. The client calls Nominatim only after this returns true |

## Edge Functions

| Function | What |
|---|---|
| `suggest-category` | POST `{text, labels, lat, lng, hour, recent_slugs}`. Calls `suggest_categories` for hash-v1. Calls `match_categories` only when `XHERE_EMBEDDING_MODEL=gte-small` and the on-platform gte-small session is actually available. A caller who is not signed in (no user JWT, or the RPC returns 401/403) gets **401** `{"error":"not_authenticated"}`, the same JSON as `strip-exif`. |
| `strip-exif` | POST raw JPEG, PNG, or WebP (10 MB max). Strips Exif/XMP, then stores `drop-media/<user id>/<uuid>.<ext>` with the caller's JWT. No service-role key. |

Moderator/admin is `profiles.role`. There's no UI to grant it yet. On a hosted project, the first admin is set once with SQL from the dashboard: `update public.profiles set role = 'admin' where handle = '...';`. Since M7 that edit is itself logged (`set_role` in `moderation_actions`), and the audit log rejects updates and deletes.

## Tests

`tests/database/*.test.sql` (pgTAP): schema/privileges, RLS, nearby/reveal/blur/expiry/blocks, `create_drop` validation, crime (`incident_report`) rules, confirmations, moderation/cron, and the M5 suggestion log, hash embeddings, and fixture score (`07_suggestions.test.sql`, 19 assertions), the M6 reveal fixes, and M7 safety/compliance (`09_safety_compliance.test.sql`: Safety pre-moderation, auto-hide threshold, blocks both ways, hide-for-me, moderator-only access, append-only audit log, export) plus `10_tip_lines.test.sql` (incident reports only inside a tip-line area), `11_advisor_followups.test.sql` (the `(select auth.uid())` policies, one SELECT policy on the reference tables, the foreign-key indexes, tip-line RLS, and the same read/write access as before), `12_open_data.test.sql` (upsert/dedupe, attribution on a far-away card, confirmations rejected, the import is not the caller's drop or export), `13_here_card.test.sql` (32 assertions: reveal stays on the viewer point, public records stay readable, the place cache, and the Nominatim slot), `13_walks_quests.test.sql` (the global starter quest, the Jamestown walk's public-record stops, reveal-radius check-off, and progress that a client cannot insert), `14_here_card_abuse.test.sql` (36 assertions: one user cannot hold the Nominatim slot, place labels stay on the 0.001° grid with length and character limits, first write wins, and the per-user write cap), and `14_live_conditions.test.sql` (right-now windows, civic-issue expiry, and per-region weather config). Each file runs in a transaction and rolls back. `supabase/functions/**/*_test.ts` is the Deno suite for the two Edge Functions, including the same 32-phrase fixture set and a 401 for a signed-out suggestion.

## Open-data seed (hosted)

Migration `20261018001600_open_data.sql` is applied with the other migrations. The Jamestown snapshot is not. `supabase db push` does not run `seed.sql`, `supabase/seed/open-data/jamestown-ny.sql`, `supabase/seed/walks/jamestown-ny.sql`, or `supabase/seed/live/us-ny-jamestown.sql`.

Do this from a machine that already has the Supabase CLI linked, after the history repair in [Before `supabase db push`](#before-supabase-db-push) if that repair has not been done yet. This does not change Auth, SMTP, or any other hosted setting.

```bash
supabase link --project-ref jpeuldzegtbqfezijhuy
supabase db push
```

`db push` applies every migration that is not on the project yet. That includes `20261018001600_open_data.sql`, `20261018194500_impact_feedback.sql` when impact feedback is not already there, `20261018210000_open_data_card.sql` (provenance on the card after impact feedback), `20261018235000_walks_and_quests.sql` (the walk and quest tables, the RPCs, and the global starter quest), `20261019041827_here_card.sql` (the Here Card read, the place-name cache, and the Nominatim slot), `20261019180000_here_card_rpc_hardening.sql` (per-user Nominatim and place-label limits), and `20261019233041_live_conditions.sql` (right now, civic issues, and per-region weather config). Do not paste those files into the SQL editor. They do not change Auth. Then load the snapshot, and after that the walk. The walk seed only names places, so the open-data file has to be applied first. The live seed can run in either order. `DATABASE_URL` is the Postgres connection string from the dashboard (Project Settings → Database). It is not in git. The SQL runs as the database owner and calls `private.upsert_open_data_items` and `private.upsert_region_live`. The anon key cannot run either. The live seed does not insert a Jamestown 311 feed. None was found (see `docs/OPEN-DATA-PLAYBOOK.md` §9.2).

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/open-data/jamestown-ny.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/walks/jamestown-ny.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/live/us-ny-jamestown.sql
```

Refresh later (no paid service, no new migration unless the schema changes):

```bash
python3 supabase/scripts/import_open_data.py \
  --region supabase/regions/us/jamestown-ny.yaml \
  --out supabase/seed/open-data/jamestown-ny.json
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/seed/open-data/jamestown-ny.sql
```

The second command updates the same external ids and hides rows that left the snapshot. A new city is a new YAML under `supabase/regions/` plus the same script. See `docs/OPEN-DATA-PLAYBOOK.md` §8.
