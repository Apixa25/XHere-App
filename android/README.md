# android/

Kotlin / Jetpack Compose app for XHere. Application id `world.xhere.app`. Milestones **M3** (shell and sign-in), **M4** (map and discovery), and **M5** (quick drop). PLAN §2, §4.4, and §7.

Reveal comes in M6. This module is the shell, the map, and the drop composer: theme, navigation, sign-in, onboarding, settings, MapLibre, category filters, teaser cards, and a photo-or-text post.

## Dependency injection

**Koin** (not Hilt). `shared/` exposes plain Kotlin interfaces (`XHereServices`). Koin binds `SupabaseXHereClient` in the app and `InMemoryXHere` in Robolectric tests, with no annotation processor. AGP 9 already compiles Kotlin itself, so kapt/ksp would be a second toolchain for a graph this small.

## Auth

* Email one-time code and Google ID token both go through `supabase-kt` in `shared/` (`signInWithEmailOtp`, `verifyEmailOtp`, `signInWithIdToken`).
* Google uses Credential Manager (`GetSignInWithGoogleOption`). The nonce sent to Google is the SHA-256 of the raw nonce; Supabase gets the raw nonce.
* No Supabase project is required to build or to run the tests. `SupabaseConfig.PLACEHOLDER` matches `.env.example`. With those placeholders the sign-in screen explains that, and it does not call the network.
* `XHereApplication` calls `prepareSupabaseSessionStore` before Koin builds the client. That is the same context the auth manifest initializers set on a device. Robolectric skips those providers.

To point a debug build at a real project, put these in gitignored `local.properties` (or in a gitignored `.env` at the repo root). Never put `SUPABASE_SERVICE_ROLE_KEY` in either file for the app to read — the Gradle script does not copy it into `BuildConfig`.

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_ANON_KEY=your-anon-key
GOOGLE_WEB_CLIENT_ID=your-google-web-client-id
```

## Map

MapLibre Native (OpenGL build, no API key) loads the OpenFreeMap Liberty style, or Dark when the phone is in dark mode. The visible line on the map credits OpenStreetMap, OpenMapTiles, and OpenFreeMap. MapLibre's own attribution control stays on as well.

The camera starts on downtown Jamestown, New York. A foreground location fix moves it to you; the puck is that fix. Background location is not requested. Panning waits until the camera is idle, then calls `drops_in_view` for that box only. Below zoom 15 the repository returns clusters. A group chip, a long-press sub-category sheet, and the built-in Weekend preset (Events, Food, Sales) are sent as the category argument on that call and on `drops_nearby`. Tapping a marker loads `drop_teaser`, which does not include the body.

With no `SUPABASE_URL`, the app binds the in-memory fake seeded with Jamestown demo drops, and the sign-in screen offers **Preview Jamestown**. Tiles still come from OpenFreeMap and need a network. There is no tile key to put in the repo.

## Location

Onboarding explains foreground location, then requests `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` only. "Not now" continues without a request. The map's location button asks for those same two permissions if they are still off. Background location is not in the manifest. That permission is M13 and stays opt-in.

## Settings

Sign out, and delete account. Delete calls `AccountRepository.deleteMyAccount()`, which `SupabaseXHereClient` implements as the `delete_my_account` RPC (soft-delete now, hard-delete within 30 days via the M1 cron) and then signs out.

## Quick drop

The map has a drop button, and the Drop tab opens the same composer (PLAN §7 M5). A photo is CameraX or the photo picker; a line of text works too. ML Kit labels the picture on the device. The top three categories come from `SuggestionRepository`: the Supabase client calls the `suggest-category` Edge Function and, if that call fails, ranks locally with the same hash the database uses. With no project, the in-memory fake ranks locally and never uploads.

Tapping a chip posts immediately when the category is not sensitive and its required fields are already filled (`occurred_at` is filled with now, so a hazard can post on the chip). An event still asks for start and end. `incident_report` and `safety_alert` always show the notice and a checkbox. The fields on the form are the category's `attributes_schema`. Posting calls `create_drop`. A photo is stored only after Exif/XMP is removed (`strip-exif` on the server, `ExifStrip` in the fake). Rate limits, a tier that is too low, and blocked descriptors come back as short sentences. Only `incident_report` blurs the pin, and the screen says so when `location_precision_m` is above 0. A matching title within 50 m shows a duplicate hint. The suggestion log is written after a successful post.

Tests bind a fake camera and a fake labeler, so Robolectric does not start CameraX. `assembleDebug` and `testDebugUnitTest` still pass with no Supabase keys.

## Build

Java 21. `ANDROID_HOME` with platforms `android-36` (shared) and `android-37.0` (this app; Compose 1.12). From the repo root:

```bash
./gradlew :android:app:assembleDebug :android:app:testDebugUnitTest
```

`:shared:jvmTest` is unchanged and still runs in the `android / shared` CI job.
