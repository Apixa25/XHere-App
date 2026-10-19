import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// AGP 9 compiles Kotlin. Do not apply org.jetbrains.kotlin.android beside it.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Closed-test scheme (docs/PLAY-RELEASE.md, PLAN §7 M9):
// versionName major.minor.patch, starting at 0.9.0.
// versionCode = major * 10000 + minor * 100 + patch, so 0.9.0 is 900.
// Play rejects an upload unless versionCode is higher than the last one.
// XHERE_VERSION_NAME and XHERE_VERSION_CODE override this without a code change.
val xhereVersionName = configRaw("XHERE_VERSION_NAME") ?: "0.9.0"
val xhereVersionCode = (configRaw("XHERE_VERSION_CODE") ?: "900").toIntOrNull()
    ?: error("XHERE_VERSION_CODE must be a positive integer")
check(xhereVersionCode > 0) { "XHERE_VERSION_CODE must be a positive integer" }

// Upload key. Absent in CI until Steven adds the four Actions secrets.
// Missing anything falls back to the debug keystore so bundleRelease still succeeds.
val uploadKey = resolveUploadKey()

android {
    namespace = "world.xhere.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "world.xhere.app"
        minSdk = 26
        // compileSdk 37 is what Compose 1.12 requires. targetSdk stays 36 until
        // a release milestone reviews the newer platform behavior.
        targetSdk = 36
        versionCode = xhereVersionCode
        versionName = xhereVersionName

        // Placeholders match .env.example and SupabaseConfig.PLACEHOLDER.
        // A real anon key is public by design, but it is still account-specific
        // and stays out of git. The service-role key is never a BuildConfig field.
        buildConfigField("String", "SUPABASE_URL", configString("SUPABASE_URL", "https://your-project-ref.supabase.co"))
        buildConfigField("String", "SUPABASE_ANON_KEY", configString("SUPABASE_ANON_KEY", "your-anon-key"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", configString("GOOGLE_WEB_CLIENT_ID", "your-google-web-client-id"))
        // Empty unless SENTRY_DSN is set. CrashReporting will not init Sentry for a blank or placeholder value.
        buildConfigField("String", "SENTRY_DSN", configString("SENTRY_DSN", ""))
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = uploadKey.file
                storePassword = uploadKey.storePassword
                keyAlias = uploadKey.alias
                keyPassword = uploadKey.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Debug signing keeps the AAB installable when the upload key is absent.
            // Play will not accept that artifact. docs/PLAY-RELEASE.md says so.
            signingConfig = if (uploadKey != null) {
                signingConfigs.getByName("upload")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.image.description)
    implementation(libs.sentry.android.core)
    // Keyless ARCore Geospatial. Play Services Location is required by that API.
    // Play Services for AR is a device install, not these two artifacts.
    implementation(libs.arcore)
    implementation(libs.play.services.location)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}

/**
 * local.properties, then a gitignored `.env`, then the environment, then [default].
 * Only the public client keys and the optional Sentry DSN are copied into BuildConfig.
 * `SUPABASE_SERVICE_ROLE_KEY` is ignored. Keystore passwords are read by
 * [resolveUploadKey] and are never written into BuildConfig.
 */
fun configString(name: String, default: String): String {
    val value = configRaw(name) ?: default
    return asBuildConfigString(value.ifBlank { default })
}

/** Unquoted value. Blank is treated as missing. */
fun configRaw(name: String): String? =
    localProperty(name) ?: dotenv(name) ?: System.getenv(name)?.trim()?.ifBlank { null }

/**
 * GitHub Actions secret names, also accepted from the environment or local.properties:
 * `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`,
 * and either a file at `android/keystore/release.jks` (CI decodes `ANDROID_KEYSTORE_BASE64`
 * into that path before Gradle) or `ANDROID_KEYSTORE_FILE`.
 * Returns null when any piece is missing. The password values are not logged.
 */
fun resolveUploadKey(): UploadKey? {
    val file = uploadKeystoreFile()
    val storePassword = configRaw("ANDROID_KEYSTORE_PASSWORD")
    val alias = configRaw("ANDROID_KEY_ALIAS")
    val keyPassword = configRaw("ANDROID_KEY_PASSWORD")
    val missing = buildList {
        if (file == null) add("keystore file (android/keystore/release.jks or ANDROID_KEYSTORE_FILE)")
        if (storePassword == null) add("ANDROID_KEYSTORE_PASSWORD")
        if (alias == null) add("ANDROID_KEY_ALIAS")
        if (keyPassword == null) add("ANDROID_KEY_PASSWORD")
    }
    if (missing.isNotEmpty()) {
        logger.lifecycle(
            "XHere release signing: debug keystore. Missing ${missing.joinToString(", ")}. No secret was printed.",
        )
        return null
    }
    val keystore = requireNotNull(file)
    val password = requireNotNull(storePassword)
    val keyAlias = requireNotNull(alias)
    val keyPass = requireNotNull(keyPassword)
    logger.lifecycle("XHere release signing: upload keystore ${keystore.name}. Passwords are not logged.")
    return UploadKey(keystore, password, keyAlias, keyPass)
}

fun uploadKeystoreFile(): File? {
    val explicit = configRaw("ANDROID_KEYSTORE_FILE")
    if (explicit != null) {
        val path = File(explicit)
        val file = if (path.isAbsolute) path else rootProject.file(explicit)
        if (file.isFile) return file
    }
    val conventional = rootProject.file("android/keystore/release.jks")
    return conventional.takeIf { it.isFile }
}

data class UploadKey(
    val file: File,
    val storePassword: String,
    val alias: String,
    val keyPassword: String,
)

fun localProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    return properties.getProperty(name)?.trim()?.ifBlank { null }
}

fun dotenv(name: String): String? {
    val file = rootProject.file(".env")
    if (!file.exists()) return null
    file.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val idx = trimmed.indexOf('=')
        if (idx <= 0) return@forEach
        if (trimmed.substring(0, idx).trim() != name) return@forEach
        var value = trimmed.substring(idx + 1).trim()
        if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))) {
            value = value.substring(1, value.length - 1)
        }
        return value.ifBlank { null }
    }
    return null
}

fun asBuildConfigString(raw: String): String {
    val escaped = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\$", "\\\$")
        .replace("\r", "")
        .replace("\n", "\\n")
    return "\"$escaped\""
}
