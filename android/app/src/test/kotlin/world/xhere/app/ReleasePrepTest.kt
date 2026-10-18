package world.xhere.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.app.crash.CrashReporting
import world.xhere.app.ui.TestTags

class ReleasePrepTest {
    @Test
    fun version_scheme_is_0_9_0_code_900() {
        assertEquals("0.9.0", BuildConfig.VERSION_NAME)
        assertEquals(900, BuildConfig.VERSION_CODE)
    }

    @Test
    fun sentry_stays_off_without_a_real_dsn() {
        assertFalse(CrashReporting.isUsableDsn(""))
        assertFalse(CrashReporting.isUsableDsn("   "))
        assertFalse(CrashReporting.isUsableDsn("your-sentry-dsn"))
        assertFalse(CrashReporting.isUsableDsn(BuildConfig.SENTRY_DSN))
        assertTrue(CrashReporting.isUsableDsn("https://example.ingest.sentry.io/1"))
    }

    @Test
    fun proguard_keeps_the_release_libraries() {
        val rules = File("proguard-rules.pro").readText()
        listOf(
            "kotlinx.serialization",
            "io.ktor",
            "io.github.jan.supabase",
            "org.maplibre",
            "com.google.mlkit",
            "androidx.camera",
            "io.sentry",
        ).forEach { name ->
            assertTrue("missing keep for $name", name in rules)
        }
    }
}

/**
 * The production application class, not the test graph. Proves sign-in
 * appears when Koin, the placeholder config, and crash reporting start together.
 * The minified APK is launched separately in the android / release job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = XHereApplication::class, qualifiers = "w411dp-h914dp")
class ReleaseStartupTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun the_release_application_opens_on_sign_in() {
        rule.waitForIdle()
        rule.onNodeWithText("See what's known about here.").assertIsDisplayed()
        rule.onNodeWithTag(TestTags.PREVIEW_JAMESTOWN).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Preview Jamestown").assertIsDisplayed()
    }
}
