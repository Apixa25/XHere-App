---
status: accepted
date: 2026-10-17
decision-makers: Steven Sills II
---

# Native Android first, with a Kotlin Multiplatform shared core

## Context and Problem Statement

XHere's value is location, camera, proximity, and later AR. The previous app was a web wrapper, which held it back from the stores. Linux cloud agents can build Android and cannot build native iOS. Steven's machine is Windows. How should the apps be built?

## Decision Drivers

* Store acceptance (Apple guideline 4.2 rejects thin web wrappers).
* Background location, camera, and a future AR path.
* Linux agents must be able to build and test the first app.
* One shared domain layer so iOS does not start from zero.
* Steven's preference for native Kotlin.

## Considered Options

* Native Kotlin/Compose and native SwiftUI, with no shared code.
* Kotlin Multiplatform shared core, Compose on Android, SwiftUI on iOS.
* React Native / Expo.
* Flutter.
* A PWA (no real App Store listing).

## Decision Outcome

Chosen option: **native Android in Kotlin and Jetpack Compose, with a Kotlin Multiplatform shared core, and SwiftUI on iOS later using that core**, because it matches the native preference, the location/camera/AR needs, and the Linux build constraint. Expo (React Native, TypeScript) is the runner-up if iOS must ship at the same time as Android and there is still no Mac. It is the only option that builds iOS from Linux.

### Consequences

* Good, because Android can be built, tested, and shipped from CI without a Mac.
* Good, because models, category rules, validation, and `supabase-kt` live once in `shared/`.
* Bad, because iOS waits until a Mac and the Apple Developer Program are available (PLAN §8).
* Bad, because two native UIs are still written. Only the core is shared.
