# XHere-App

Rebuild of the location-based application **XHere**.

A lens on the world: see what's known about *here*. People attach data ("drops") to real places, and XHere combines them with open data that already exists, all discoverable by being there.

Planning docs live in [`docs/`](docs/). Start with [`docs/PLAN.md`](docs/PLAN.md). Parked ideas: [`docs/IDEAS.md`](docs/IDEAS.md).

## Layout

Monorepo from [PLAN §6](docs/PLAN.md). The shared core (M2) and the Android shell (M3) build with the root Gradle wrapper. The Astro site is still a placeholder until M8.

| Path | Purpose | Filled in |
|---|---|---|
| [`android/`](android/) | Kotlin / Jetpack Compose app (`world.xhere.app`) | M3 |
| [`shared/`](shared/) | Kotlin Multiplatform core (models, rules, data) | M2 |
| [`ios/`](ios/) | SwiftUI app, phase 2 | M15 |
| [`supabase/`](supabase/) | Migrations, Edge Functions, pgTAP tests | M1 |
| [`web/`](web/) | Astro site for xhere.world | M8 |
| [`docs/`](docs/) | Plan, progress, security notes, ADRs, [ideas](docs/IDEAS.md) | ongoing (ADRs from M0) |
| [`.github/workflows/`](.github/workflows/) | gitleaks, Android shared + app, SQL; web still a placeholder | M0–M3 |

## Android app

Java 21 and Android SDK platforms 36 and 37. Debug build and UI tests do not need a Supabase project. Keys stay in gitignored `local.properties` or `.env` (see [`.env.example`](.env.example) and [`android/README.md`](android/README.md)).

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew :android:app:assembleDebug :android:app:testDebugUnitTest
```

Decisions: [ADR-001 platform](docs/adr/ADR-001-platform.md), [ADR-002 backend](docs/adr/ADR-002-backend.md). How to contribute: [CONTRIBUTING.md](CONTRIBUTING.md).
