# XHere-App

Rebuild of the location-based application **XHere**.

A lens on the world: see what's known about *here*. People attach data ("drops") to real places, and XHere combines them with open data that already exists, all discoverable by being there.

Planning docs live in [`docs/`](docs/). Start with [`docs/PLAN.md`](docs/PLAN.md).

## Layout

Monorepo skeleton from [PLAN §6](docs/PLAN.md). Directories below are placeholders until the milestone that fills them. Gradle and the Astro app are not scaffolded yet.

| Path | Purpose | Filled in |
|---|---|---|
| [`android/`](android/) | Kotlin / Jetpack Compose app | M3 (shell), after the shared core in M2 |
| [`shared/`](shared/) | Kotlin Multiplatform core (models, rules, data) | M2 |
| [`ios/`](ios/) | SwiftUI app, phase 2 | M15 |
| [`supabase/`](supabase/) | Migrations, Edge Functions, pgTAP tests | M1 |
| [`web/`](web/) | Astro site for xhere.world | M8 |
| [`docs/`](docs/) | Plan, progress, security notes, ADRs | ongoing (ADRs from M0) |
| [`.github/workflows/`](.github/workflows/) | CI: gitleaks now; Android, SQL, and web builds later | M0, then M1+ |

Decisions: [ADR-001 platform](docs/adr/ADR-001-platform.md), [ADR-002 backend](docs/adr/ADR-002-backend.md). How to contribute: [CONTRIBUTING.md](CONTRIBUTING.md).
