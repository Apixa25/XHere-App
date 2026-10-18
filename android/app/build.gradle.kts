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

android {
    namespace = "world.xhere.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "world.xhere.app"
        minSdk = 26
        // compileSdk 37 is what Compose 1.12 requires. targetSdk stays 36 until
        // a release milestone reviews the newer platform behavior.
        targetSdk = 36
        versionCode = 1
        versionName = "0.5.0"

        // Placeholders match .env.example and SupabaseConfig.PLACEHOLDER.
        // A real anon key is public by design, but it is still account-specific
        // and stays out of git. The service-role key is never a BuildConfig field.
        buildConfigField("String", "SUPABASE_URL", configString("SUPABASE_URL", "https://your-project-ref.supabase.co"))
        buildConfigField("String", "SUPABASE_ANON_KEY", configString("SUPABASE_ANON_KEY", "your-anon-key"))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", configString("GOOGLE_WEB_CLIENT_ID", "your-google-web-client-id"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

/**
 * local.properties, then a gitignored `.env`, then the environment, then [default].
 * Only the three public client keys are read. `SUPABASE_SERVICE_ROLE_KEY` is ignored.
 */
fun configString(name: String, default: String): String {
    val value = localProperty(name) ?: dotenv(name) ?: System.getenv(name) ?: default
    return asBuildConfigString(value.ifBlank { default })
}

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
