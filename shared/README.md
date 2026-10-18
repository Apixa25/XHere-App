# shared/

Kotlin Multiplatform core for XHere: domain models, the category rules engine, JSON-schema validation, geo helpers, and `supabase-kt` repositories. PLAN §1.3, §2, and §6. Milestone **M2**.

The UI stays native. This module has no Compose and no SwiftUI. Android consumes it as an AAR (`assembleAndroidMain`). iOS will consume the `Shared` framework in **M15**.

## Targets

| Target | Role |
|---|---|
| `android` | Library the Compose app links. `compileSdk` 36, `minSdk` 26. |
| `iosArm64`, `iosSimulatorArm64` | Declared, static framework named `Shared`. Linux CI does not compile them (no Xcode). |
| `jvm` | Host for `commonTest` on the Linux runner. Not a desktop product. |

`kotlin.native.ignoreDisabledTargets=true` keeps a Linux `build` from treating the missing Apple toolchain as a hard failure when those tasks are not requested. CI runs the JVM tests, the Kover check, and `assembleAndroidMain`. It does not invoke the iOS compile tasks.

## What lives here

* `model` — profiles, tiers, categories (field schema + rules), drops, media, confirmations, reports, blocks. Enums use the Postgres labels.
* `rules` — the same checks `create_drop` enforces, so the UI can fail before the round trip. The database remains the source of truth.
* `validation` — the JSON Schema subset stored on categories, plus the POSIX content-filter patterns (`[[:alnum:]]`, `\m` / `\M`).
* `geo` — distance, bounding boxes, and the same grid fuzz as `private.fuzz_point`.
* `data` — repositories over the M1 RPCs (`drops_in_view`, `drops_nearby`, `drop_detail`, `drop_teaser`, `create_drop`, `confirm_drop`, `report`, `block_user`, `unblock_user`, `delete_my_account`, and the moderator RPCs). An in-memory fake implements the same interfaces for tests.
* `TaxonomyCache` — categories in memory for the process. Room and offline map tiles are **v1.2**, not this milestone.

`SupabaseConfig.PLACEHOLDER` matches `.env.example`. No Supabase project exists yet. Calls with the placeholder fail at the network until Steven approves a free project (PLAN §8).

## Tests

```bash
./gradlew :shared:jvmTest :shared:koverVerify :shared:assembleAndroidMain
```

Kover checks `world.xhere.shared.rules` and `world.xhere.shared.validation` and fails the build under 80% line coverage. Java 21 and Android SDK platform 36 are required (`ANDROID_HOME`). The anon key in tests is the fake string `test-anon-key`.
