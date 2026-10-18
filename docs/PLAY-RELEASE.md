# Play closed test (M9)

How to get a closed-testing build of XHere onto Google Play. This is PLAN §7 M9 and the cost row in PLAN §8. Nothing here creates an account or spends money. The privacy policy URL is **https://xhere.world/privacy**.

The engineering is in the repo. The account, the upload key, the 12 testers, and the DNS change are Steven's.

## Version

`android/app/build.gradle.kts` sets:

| | Default | Meaning |
| --- | --- | --- |
| `versionName` | `0.9.0` | What testers see. `major.minor.patch`. 0.9.0 is the M9 closed test, before a public 1.0. |
| `versionCode` | `900` | What Play compares. `major * 10000 + minor * 100 + patch`. 0.9.0 → 900. 0.9.1 → 901. 1.0.0 → 10000. |

Play rejects an upload unless `versionCode` is higher than the last one on that app. Bump the patch (and the code) for every upload. Override either value for one build with `XHERE_VERSION_NAME` or `XHERE_VERSION_CODE` in the environment or `local.properties`. Do not commit a one-off override.

## Signing

The upload keystore is not in git. `.gitignore` ignores `*.jks`, `*.keystore`, and `android/keystore/`.

GitHub Actions secret names, exact spelling:

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The `.jks` file, base64 on one line (`base64 -w 0 release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Store password |
| `ANDROID_KEY_ALIAS` | Key alias. The command below uses `xhere` |
| `ANDROID_KEY_PASSWORD` | Key password |

The release job decodes `ANDROID_KEYSTORE_BASE64` to `android/keystore/release.jks` and passes the other three to Gradle. Gradle also accepts `ANDROID_KEYSTORE_FILE` (a path, for a laptop) instead of the base64 secret.

When any of those four is missing, Gradle signs the release with the Android debug keystore and the job still goes green. That AAB installs on an emulator, which is how CI proves the minified build starts. **Do not upload the debug-signed AAB to Play.** Play would treat the public debug certificate as your upload key. Add the secrets, re-run **android / release**, and download `xhere-release-aab`.

Create the keystore on your own machine. Do not commit it and do not paste the passwords into the repo.

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias xhere \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 release.jks
```

Put the four values in the GitHub repo under **Settings → Secrets and variables → Actions**. The keystore file stays on your machine (and in GitHub's secret store). You will also need it if you ever sign a build by hand.

Play App Signing is separate. When you upload, accept Google's app signing. The certificate testers' phones see is the **App signing key**, not this upload key. The SHA-256 you copy into the website is the app signing key. See the checklist.

## Minify

Release builds run R8 with resource shrinking (`isMinifyEnabled`, `isShrinkResources`). Rules are in `android/app/proguard-rules.pro`: kotlinx.serialization, Ktor, supabase-kt, MapLibre, ML Kit, CameraX, Koin, and Sentry, plus the serialization rules from kotlinx.serialization 1.11. The libraries' own consumer rules are merged as well.

`ReleaseStartupTest` opens the real `XHereApplication` under Robolectric (sign-in, placeholder config, crash reporting left off). The **android / release** job installs the minified APK on an API 34 emulator and checks that `world.xhere.app` is still running, that `XHere: process started` was logged, and that logcat has no fatal exception for that package. The mapping file is uploaded next to the AAB so a crash can be retraced later.

## Crash reporting

Sentry (`io.sentry:sentry-android-core`), no NDK, no account. `io.sentry.auto-init` is false in the manifest. `CrashReporting` calls `SentryAndroid.init` only when `SENTRY_DSN` is an http(s) URL and not the `your-…` placeholder. CI does not set it, so the closed-test AAB does not send crashes.

The free Sentry developer plan is enough at this scale (PLAN §8). Creating that account still needs your OK. If you set `SENTRY_DSN` as a GitHub Actions secret or in `local.properties` and rebuild, update the Data safety form: crash logs become collected, and Sentry is the processor. Until then, answer **No** for crash logs.

## Store listing files

| Play field | File | Limit |
| --- | --- | --- |
| App name | `android/play/title.txt` | 30 characters. The file is `XHere`. |
| Short description | `android/play/short-description.txt` | 80 characters. |
| Full description | `android/play/full-description.txt` | 4000 characters. Includes the privacy URL. |
| Feature graphic | `android/play/graphics/feature-graphic.png` | 1024×500 |
| Phone screenshots | `android/play/graphics/phone-map.png`, `phone-near.png`, `phone-drop.png`, `phone-detail.png` | 1080×1920 |
| Hi-res icon | `android/play/graphics/icon-512.png` | 512×512 |

The phone shots and the feature graphic are rendered from the Compose previews in `android/app/src/main/kotlin/world/xhere/app/play/PlayPreviews.kt`. The drops are the Jamestown preview set (Fenton, West 3rd, Rosa Alley, Tracy Plaza, the Riverwalk, the benches). MapLibre does not draw under the unit-test renderer, so the map in those pictures is a schematic of the same coordinates. The chips, the teaser, the near-me sheet, and the copy are the app's. Regenerate with `XHERE_CAPTURE_PLAY=1` and `:android:app:testDebugUnitTest --tests world.xhere.app.play.PlayPhoneCaptureTest --tests world.xhere.app.play.PlayFeatureCaptureTest --tests world.xhere.app.play.PlayIconCaptureTest`.

## Data safety

Answer this for the AAB you actually upload. The AAB this repo builds in CI has no Supabase project and no Sentry DSN. It runs the Jamestown preview on the phone. It does not transmit an account, a photo, or a drop.

Use the **preview build** column for that AAB. Use the **with Supabase** column only after you rebuild with `SUPABASE_URL` and `SUPABASE_ANON_KEY` baked in. Do not declare data you have not started sending, and do not leave the form on "not collected" after you turn the backend on.

| Question | Preview build (CI AAB today) | After a Supabase rebuild |
| --- | --- | --- |
| Does the app collect or share user data? | Yes, location is used on the device. Nothing is sold. | Yes. |
| Data sold? | No | No |
| Data shared with third parties for their own use? | No | No. Supabase processes it for you. That is not "sharing" in Play's sense. |
| Encrypted in transit? | Yes for map tiles (HTTPS). App data is not sent. | Yes (HTTPS to Supabase). |
| Users can request deletion? | Yes. In the app: Profile → Settings → Delete account. Web: https://xhere.world/delete-account | Same. |
| Approximate location | Collected, optional, app functionality, not shared. Foreground only. Not stored as a trail. | Same, and a drop stores the place it was posted. |
| Precise location | Same as approximate. The app asks for fine and coarse together, foreground only. | Same. Incident reports store a point blurred to about 100 m for other people. |
| Email | Not collected. Preview sign-in stays on the phone. | Collected if they use the email code. Optional (Google is the other path). Account management. Not shared. |
| Name | The preview handle stays on the phone. | Handle and display name. Other people can see the handle. Not sold. |
| User IDs | Not sent. | The account id in your database. Not shared. |
| Photos | On-device only. ML Kit labels the picture on the phone. The code does not upload it to Google. | Uploaded to your private `drop-media` bucket after Exif is stripped. App functionality. Not shared. |
| App interactions and other user content | Drops, confirmations, and reports stay in the preview on the phone. | Stored in your database. Other people see a drop under the category rules. Not shared with a third party. |
| Crash logs | **No.** Sentry is off. | **No**, unless you also set `SENTRY_DSN`. If you do, change this to collected, and name Sentry. |
| Device or other IDs / advertising ID | No. The app does not use the advertising id. | No. |
| Map tiles | Loading the map fetches tiles from OpenFreeMap over HTTPS. That connection reveals the usual IP and the tile area to that service. XHere does not add an account or an API key, and it does not get an identifier back. | Same. |

Location is optional. Onboarding has **Not now**. The app still opens the map.

The in-app deletion path and the web URL are both required. The web page is `web/src/pages/delete-account.astro`. It is not on the public internet until the site is deployed (checklist step 2).

## Location permission declaration

Background location is **not** in the manifest. Do not add it for this test. That is M13. You do **not** need the background-location video.

In Play Console, under App content, for the location permissions:

- Does the app access location in the background? **No.**
- Foreground location: **Yes**, approximate and precise, optional.

Paste this as the purpose:

> XHere uses location only while the app is open, and only if the person allows it. It uses that position to show drops near them, to place a drop they create, to show how far away a drop is, and to unlock a drop when they are inside its reveal radius. "Still true?" is accepted only when they are close enough. The app does not use location for ads. It does not ask for background location, and it does not keep a history of where someone walked. The in-app disclosure is the screen titled "While you're here", and it is shown before the system permission dialog. "Not now" skips the request. They can turn location off later in the phone's settings.

That matches `onboarding_body` and `onboarding_background` in `android/app/src/main/res/values/strings.xml`.

## Other Play Console declarations

These are not the Data safety form. COMPLIANCE.md maps the UGC rules to the code.

- **Privacy policy URL:** https://xhere.world/privacy
- **Account deletion URL:** https://xhere.world/delete-account
- **Child safety standards:** the app has user-generated content. Standards URL `https://xhere.world/guidelines#child-safety`. Point of contact: Steven Sills II, via hello@xhere.world once that mailbox exists. The page says zero tolerance, removal, and a report to the NCMEC CyberTipline.
- **Ads:** No.
- **Target audience:** not designed for children. For the closed test, set it so the Families program does not apply (18 and up is the simple choice).
- **Content rating:** say that users can post text and photos, that you moderate, that there are no ads and no purchases, and that people can share a place. The questionnaire's wording changes; answer from what the app does, not from a guessed score.
- **News app / government / financial:** No.

## Checklist

Do these in order. Steps 1 and 2 unblock the URLs Play checks. Step 3 is the $25. The closed test itself can start as soon as the AAB is uploaded and the testers opt in. The 14 days is what Play asks for before you can apply for production, not a wait before the closed test starts.

1. **Read the drafts** at `web/src/pages/privacy.astro`, `terms.astro`, and `guidelines.astro`, including the child-safety section and the 24-hour review line. They say you have not approved them. Change what you want changed before anyone treats them as in effect.
2. **Put the site on https://xhere.world.** Approve a free Cloudflare account and the DNS change in `web/CLOUDFLARE.md`. Do not reactivate the old Render services. `xhere.world` still points at them until you move DNS. Play fetches the privacy policy from that URL, so this has to work before you submit. GoDaddy registration runs through December 2026 (PLAN §8).
3. **Approve the Google Play developer account, $25 once** (PLAN §8). There is no free way to run a Play closed test. Create the app with package name `world.xhere.app`.
4. **Create the upload keystore** (command above) and add the four Actions secrets. Re-run the **android / release** workflow. Download the artifact `xhere-release-aab`. Confirm it was signed with your upload key (the job log says "upload keystore", not "debug keystore").
5. **Fill in the store listing** from `android/play/`. Paste the title, the short description, and the full description. Upload the feature graphic, the four phone screenshots, and the 512 icon.
6. **Paste the Data safety answers and the location declaration** from this file. Set the privacy policy URL to https://xhere.world/privacy and the deletion URL to https://xhere.world/delete-account. Complete the content rating, the ads question (no), the target audience, and the child-safety declaration (COMPLIANCE.md).
7. **Upload the signed AAB** to the **Closed testing** track. Add at least **12 testers**. Send them the opt-in link. They have to install from Play, not from a sideloaded file. Keep 12 of them opted in for **14 days** before you apply for production. You can start the closed test as soon as they opt in.
8. **After the first upload, open Play Console → App signing.** Copy the **App signing key certificate** SHA-256 (not the upload key). Replace `fingerprintPlaceholder` in `web/src/lib/site.mjs`. That string is what `/.well-known/assetlinks.json` serves. It is not a certificate today. Redeploy the site. App Links for `https://xhere.world/d/…` stay unverified until that value is the real fingerprint.
9. **Give hello@xhere.world a mailbox**, or change the address on the site and in the app guidelines. The address in the repo is a placeholder and may not receive mail.
10. **Turn on a free Supabase project** when you want sign-in, photo upload, and live drops (PLAN §8). Put the URL and the anon key in `local.properties` and, for the site, in the Pages environment. Do not put the service-role key in the app or the site. Apply every migration through `20261018001100_tip_lines.sql`. Deploy the `suggest-category` and `strip-exif` functions. Leave embeddings on hash-v1. Then rebuild the AAB so the closed test talks to that project, bump `versionCode`, and update Data safety to the "with Supabase" column.
11. **Add a free Google Cloud OAuth web client id** before Google sign-in works. The same id goes in `GOOGLE_WEB_CLIENT_ID` and in the Supabase Google provider.
12. **Make your account the admin** once the project exists: `update public.profiles set role = 'admin' where handle = '<your handle>';` (`supabase/README.md`). Check **Settings → Moderation queue** about once a day. The terms say reports are reviewed, aiming for within 24 hours.
13. **Optional:** a free Sentry project, only if you want crash reports. Set `SENTRY_DSN`, rebuild, and update Data safety. Skip this for the first upload.

Branch protection, the license, and the old-repo cleanup are in the MVP status section of `docs/PROGRESS.md`. They are not Play Console clicks, and they are still yours.
