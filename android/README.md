# android/

Kotlin / Jetpack Compose app for XHere. Application id `world.xhere.app`. Milestones **M3** (shell and sign-in), **M4** (map and discovery), **M5** (quick drop), **M6** (drop detail), and **M7** (safety and compliance). PLAN §2, §4.4, and §7.

This module is the shell, the map, the drop composer, the detail screen, and the safety tools: theme, navigation, sign-in, onboarding, settings, MapLibre, category filters, teaser cards, a photo-or-text post, reveal, report, block, hide, and the moderator queue.

## Dependency injection

**Koin** (not Hilt). `shared/` exposes plain Kotlin interfaces (`XHereServices`). Koin binds `SupabaseXHereClient` in the app and `InMemoryXHere` in Robolectric tests, with no annotation processor. AGP 9 already compiles Kotlin itself, so kapt/ksp would be a second toolchain for a graph this small.

## Auth

* Email one-time code and Google ID token both go through `supabase-kt` in `shared/` (`signInWithEmailOtp`, `verifyEmailOtp`, `signInWithIdToken`).
* Google uses Credential Manager (`GetSignInWithGoogleOption`). The nonce sent to Google is the SHA-256 of the raw nonce; Supabase gets the raw nonce.
* No Supabase project is required to build or to run the tests. `SupabaseConfig.PLACEHOLDER` matches `.env.example`. With those placeholders the sign-in screen explains that, and it does not call the network.
* A hosted project exists for real sign-in: ref `jpeuldzegtbqfezijhuy` (see `supabase/README.md`, PLAN §8). `SUPABASE_ANON_KEY` is the publishable key, which starts with `sb_publishable_`. The variable name does not change. Do not commit the key. Do not put the service-role key in `local.properties` for the app to read.
* `XHereApplication` calls `prepareSupabaseSessionStore` before Koin builds the client. That is the same context the auth manifest initializers set on a device. Robolectric skips those providers.

To point a debug build at a real project, put these in gitignored `local.properties` (or in a gitignored `.env` at the repo root). Never put `SUPABASE_SERVICE_ROLE_KEY` in either file for the app to read — the Gradle script does not copy it into `BuildConfig`.

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
# Publishable key (sb_publishable_…). Not the service-role key. Not committed.
SUPABASE_ANON_KEY=your-anon-key
GOOGLE_WEB_CLIENT_ID=your-google-web-client-id
```

## Map

MapLibre Native (OpenGL build, no API key) loads the OpenFreeMap Liberty style, or Dark when the phone is in dark mode. The visible line on the map credits OpenStreetMap, OpenMapTiles, and OpenFreeMap. MapLibre's own attribution control stays on as well.

The camera starts on downtown Jamestown, New York. A foreground location fix moves it to you; the puck is that fix. Background location is not requested. Panning waits until the camera is idle, then calls `drops_in_view` for that box only. Below zoom 15 the repository returns clusters. A group chip, a long-press sub-category sheet, and the built-in Weekend preset (Events, Food, Sales) are sent as the category argument on that call and on `drops_nearby`. Tapping a marker loads `drop_teaser`, which does not include the body.

With no `SUPABASE_URL`, the app binds the in-memory fake seeded with Jamestown demo drops, and the sign-in screen offers **Preview Jamestown**. Tiles still come from OpenFreeMap and need a network. There is no tile key to put in the repo.

## Location

Onboarding explains foreground location, then requests `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` only. "Not now" continues without a request. The map's location button asks for those same two permissions if they are still off. Background location is not in the manifest. That permission is M13 and stays opt-in.

## AR view

PLAN §3 lists an AR view as later work. The Android app now has the first cut: an **AR** button on the map, and a camera screen of nearby drops. It uses ARCore 1.56 Geospatial directly (no SceneView, no Filament). Labels are Compose. The camera image is a small OpenGL texture. Play Services for AR is installed on the phone by Google; it is not inside the APK.

The button is shown only when ARCore is already installed, Geospatial is supported, and VPS covers the latest fine-location fix. Otherwise it stays hidden. The manifest marks `com.google.ar.core` as `optional` and `android.hardware.camera.ar` as not required, so a phone without ARCore can still install XHere. The map check does not prompt an install.

Authorization is keyless. There is no `com.google.android.ar.API_KEY` and no key in `BuildConfig`. Google matches package `world.xhere.app` to an Android OAuth client in Cloud project `xhere-512103` (number `1059257603719`). The debug keystore's SHA-1 is already registered. A release build, and a Play install, need another Android OAuth client for the certificate the phone actually verifies. See `docs/PLAY-RELEASE.md`. Do not put an API key in the manifest to "make release work."

Before the camera session, the screen shows Google's required notice, including the sentences about Play Services for AR and sensor data, with links to Google's privacy policy and the AR data page. XHere does not keep the frames or a pose trail. The session needs the camera permission and a precise location. Coarse location alone cannot run Geospatial.

Labels are the nearest 25 drops inside 500 m. Terrain anchors are preferred, then rooftop, then an eye-level anchor. Size and opacity fade with distance. Color comes from the category, or from its group. A locked body is never drawn. A user drop that the reveal radius would open still hides its body when the location-integrity check would withhold that reveal. Public records stay readable. Tapping a label opens the existing drop detail. **Drop here in AR** posts at the Geospatial pose and sends that pose's accuracy as a device fix, not a map pin.

`ArRuntime` is the seam. Robolectric binds a fake, so CI does not start ARCore. The ranking and the projection are `ArView` in `shared/` and run under `:shared:jvmTest`.

## Settings

Sign out, Community guidelines, Blocked and hidden, Download my data, and delete account. Moderators and admins also see Moderation queue. Delete calls `AccountRepository.deleteMyAccount()`, which `SupabaseXHereClient` implements as the `delete_my_account` RPC (soft-delete now, hard-delete within 30 days via the M1 cron) and then signs out.

## Quick drop

The map has a drop button, and the Drop tab opens the same composer (PLAN §7 M5). A photo is CameraX or the photo picker; a line of text works too. ML Kit labels the picture on the device. The top three categories come from `SuggestionRepository`: the Supabase client calls the `suggest-category` Edge Function and, if that call fails, ranks locally with the same hash the database uses. With no project, the in-memory fake ranks locally and never uploads.

Tapping a chip posts immediately when the category is not sensitive and its required fields are already filled (`occurred_at` is filled with now, so a hazard can post on the chip). An event still asks for start and end. `incident_report` and `safety_alert` always show the notice and a checkbox. The fields on the form are the category's `attributes_schema`. Posting calls `create_drop`. A photo is stored only after Exif/XMP is removed (`strip-exif` on the server, `ExifStrip` in the fake). Rate limits, a tier that is too low, and blocked descriptors come back as short sentences. Only `incident_report` blurs the pin, and the screen says so when `location_precision_m` is above 0. A matching title within 50 m shows a duplicate hint. The suggestion log is written after a successful post.

## Safety and moderation (M7)

See `docs/COMPLIANCE.md` for how each store rule maps to these screens.

* **Drop screen.** Report drop (reason, details, optional "also hide for me"), Hide for me with Undo, Report author, and Block author. None of these show on your own drops. The report form is inline, not a dialog, because a text field inside a dialog window never goes idle under Robolectric. Safety drops show the tip line and 911 (`SafetyFooter`).
* **Composer.** A Safety category opens a panel with the reminder, the disclaimer, "held for review" for New accounts, and the 100 m blur line for `incident_report`. The person, contact, and street-address filters run as you type and disable Post. `incident_report` is only offered where `SafetyDirectory` has a police tip line; the server enforces the same rule.
* **Sign-in.** States that continuing means agreeing to the Terms and Community guidelines (zero tolerance), with links to both.
* **Settings screens.** Community guidelines (the same rules as `web/src/pages/guidelines.astro`). Blocked and hidden, with Unblock and Show again. Moderation queue, shown only when `canModerate()` is true: approve, remove, restore, dismiss, action, and suspend for 7 days or lift it. Each action is one RPC, and the server writes the audit row.
* **Download my data.** Calls `export_my_data`, writes `xhere-export-YYYY-MM-DD.json` to `cacheDir/exports/`, and opens the share sheet through a non-exported `FileProvider` (`${applicationId}.exports`) that maps only that folder.

Tests bind a fake camera and a fake labeler, so Robolectric does not start CameraX. `assembleDebug` and `testDebugUnitTest` still pass with no Supabase keys.

## Build

Java 21. `ANDROID_HOME` with platforms `android-36` (shared) and `android-37.0` (this app; Compose 1.12). From the repo root:

```bash
./gradlew :android:app:assembleDebug :android:app:testDebugUnitTest
```

`:shared:jvmTest` is unchanged and still runs in the `android / shared` CI job.

## Release (M9)

`versionName` `0.9.0`, `versionCode` `900`. See `docs/PLAY-RELEASE.md` for the scheme, the Play Console checklist, and the Data safety answers.

Release builds are minified (R8) and resource-shrunk. Keep rules for supabase-kt, Ktor, kotlinx.serialization, MapLibre, ML Kit, CameraX, ARCore, and Sentry are in `proguard-rules.pro`.

```bash
./gradlew :android:app:bundleRelease
```

The AAB is signed with the upload key only when all four of these are present: `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and a keystore at `android/keystore/release.jks` (CI decodes the `ANDROID_KEYSTORE_BASE64` secret into that path) or `ANDROID_KEYSTORE_FILE`. Otherwise the release is debug-signed so CI still produces an AAB. Do not upload the debug-signed AAB. None of those values belong in git.

Sentry is compiled in and left off unless `SENTRY_DSN` is a real DSN. No crash-reporting account is required to build.
