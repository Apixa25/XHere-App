package world.xhere.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import world.xhere.app.onboarding.OnboardingStore
import world.xhere.app.session.SignOutNotice
import world.xhere.shared.model.DropStatus
import world.xhere.app.ui.TestTags

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class AuthAndOnboardingTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bad_email_stays_on_the_sign_in_screen() {
        rule.waitForTag(TestTags.AUTH_EMAIL)
        rule.onNodeWithTag(TestTags.AUTH_GOOGLE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput("not-an-email")
        rule.onNodeWithTag(TestTags.AUTH_SEND_CODE).performClick()
        rule.onNodeWithText("Enter the email address you want the code sent to.").assertIsDisplayed()
        rule.onNodeWithTag(TestTags.AUTH_EMAIL).assertIsDisplayed()
    }

    @Test
    fun email_otp_reaches_onboarding_and_skip_opens_the_shell() {
        rule.signInWithEmail()
        rule.onNodeWithText("while the app is open", substring = true).assertIsDisplayed()
        rule.onNodeWithText("never ask for background location", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.ONBOARDING_SKIP).performClick()
        rule.waitForTag(TestTags.SCREEN_MAP)
        rule.onNodeWithTag(TestTags.NAV_DROP).performClick()
        rule.waitForTag(TestTags.SCREEN_DROP)
        rule.onNodeWithText("three taps", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.SCREEN_PROFILE)
        rule.onNodeWithText("@steven").assertIsDisplayed()
        rule.onNodeWithText("Steven").assertIsDisplayed()
    }

    @Test
    fun continue_asks_only_for_foreground_location_and_opens_the_map() {
        rule.signInWithEmail()
        val app = rule.activity.application
        Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        rule.onNodeWithTag(TestTags.ONBOARDING_ALLOW).performClick()
        rule.waitForTag(TestTags.SCREEN_MAP)
        val requested = rule.activity.packageManager
            .getPackageInfo(rule.activity.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
        assertTrue(android.Manifest.permission.ACCESS_FINE_LOCATION in requested)
        assertTrue(android.Manifest.permission.ACCESS_COARSE_LOCATION in requested)
        assertFalse(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION in requested)
    }

    @Test
    fun google_sign_in_reaches_onboarding() {
        rule.waitForTag(TestTags.AUTH_GOOGLE)
        rule.onNodeWithTag(TestTags.AUTH_GOOGLE).performScrollTo().performClick()
        rule.waitForTag(TestTags.ONBOARDING_SKIP)
        rule.onNodeWithText("never ask for background location", substring = true).assertIsDisplayed()
    }

    @Test
    fun sign_out_returns_to_auth() {
        rule.signInWithEmail()
        rule.onNodeWithTag(TestTags.ONBOARDING_SKIP).performClick()
        rule.openSettings()
        rule.onNodeWithTag(TestTags.SETTINGS_SIGN_OUT).performScrollTo().performClick()
        rule.waitForTag(TestTags.AUTH_EMAIL)
        assertNull(runBlocking { TestGraph.services.auth.currentSession() })
    }

    @Test
    fun delete_account_calls_the_repository_and_returns_to_auth() {
        rule.signInWithEmail()
        rule.onNodeWithTag(TestTags.ONBOARDING_SKIP).performClick()
        rule.openSettings()
        rule.onNodeWithText("Background location is never requested.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithTag(TestTags.SETTINGS_DELETE).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.SETTINGS_DELETE_CONFIRM).performClick()
        rule.waitForTag(TestTags.SIGNED_OUT_NOTICE)
        rule.onNodeWithText("scheduled for deletion", substring = true).assertIsDisplayed()
        assertTrue(TestGraph.profile.id in TestGraph.services.store.deleted)
        assertNull(runBlocking { TestGraph.services.auth.currentSession() })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = XHereApplication::class, qualifiers = "w411dp-h914dp")
class AppBootsWithoutSupabaseTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun boots_on_the_supabase_client_with_placeholder_config() {
        val koin = GlobalContext.get()
        val services = koin.get<world.xhere.shared.data.XHereServices>()
        val config = koin.get<world.xhere.app.config.ClientConfig>()
        rule.waitForTag(TestTags.AUTH_EMAIL)
        if (config.googleReady) {
            rule.onNodeWithTag(TestTags.AUTH_GOOGLE).performScrollTo().assertIsDisplayed()
        } else {
            rule.onNodeWithTag(TestTags.AUTH_GOOGLE).assertDoesNotExist()
        }
        if (!config.serverReady) {
            rule.onNodeWithTag(TestTags.AUTH_OFFLINE_NOTICE).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag(TestTags.PREVIEW_JAMESTOWN).assertIsDisplayed()
            assertEquals(world.xhere.shared.SupabaseConfig.PLACEHOLDER, config.supabase)
            assertEquals(world.xhere.shared.data.fake.InMemoryXHere::class.java.name, services.javaClass.name)
            val memory = services as world.xhere.shared.data.fake.InMemoryXHere
            assertTrue(memory.store.drops.isNotEmpty())
        } else {
            assertEquals(world.xhere.shared.data.supabase.SupabaseXHereClient::class.java.name, services.javaClass.name)
        }
    }
}

/** The application object is reused across tests, so the in-memory session has to be cleared before the activity starts. */
class FreshSessionRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            app.getSharedPreferences(OnboardingStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            app.getSharedPreferences(world.xhere.app.map.FilterPresetStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            runBlocking {
                runCatching { org.koin.core.context.GlobalContext.get().get<world.xhere.app.explore.ExperienceMemory>().reset() }
            }
            if (TestGraph.isReady()) {
                TestGraph.conditions.alerts = emptyList()
            }
            app.getSharedPreferences(world.xhere.app.auth.PrefsAuthDraft.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            app.getSharedPreferences("xhere_voice", Context.MODE_PRIVATE).edit().clear().commit()
            if (TestGraph.isReady()) {
                runBlocking {
                    TestGraph.services.auth.signOut()
                    TestGraph.services.store.deleted.clear()
                    TestGraph.services.store.pendingEmail = null
                    TestGraph.services.claimEmail = null
                    TestGraph.services.store.claimedPlaces.clear()
                    TestGraph.services.store.placeTeams.clear()
                    TestGraph.services.store.placeMembers.clear()
                    TestGraph.services.store.placeClaims.clear()
                    TestGraph.services.store.placeOfficial.clear()
                    TestGraph.services.store.ownerReplies.clear()
                    TestGraph.services.store.claimDisputes.clear()
                    val demoShortIds = setOf(
                        "fenton", "lunch", "truck", "concert", "hazard", "benches", "lake", "buffalo",
                        "records",
                    )
                    TestGraph.services.store.drops.entries.removeAll { it.value.shortId !in demoShortIds }
                    TestGraph.services.store.suggestionLogs.clear()
                    TestGraph.services.store.accessChecks.clear()
                    TestGraph.services.store.mediaBytes.clear()
                    TestGraph.services.store.confirmations.clear()
                    TestGraph.services.store.reports.clear()
                    TestGraph.services.store.blocks.clear()
                    TestGraph.services.store.blockedAt.clear()
                    TestGraph.services.store.hidden.clear()
                    TestGraph.services.store.auditLog.clear()
                    TestGraph.services.store.suspendedUntil.clear()
                    TestGraph.services.viewer = TestGraph.profile
                    TestGraph.services.store.drops.values.forEach { drop ->
                        drop.confirmCount = 0
                        drop.denyCount = 0
                        drop.viewCount = 0
                        drop.lastConfirmedAt = null
                        drop.status = DropStatus.ACTIVE
                        drop.expiresAt = null
                        drop.removedReason = null
                        drop.deletedAt = null
                        drop.statusBeforeDelete = null
                        drop.extraBadges = emptyList()
                        drop.capsuleMode = null
                        drop.capsuleUnlockAt = null
                        drop.capsuleOpenedAt = null
                        drop.capsuleSecret = null
                        drop.capsuleSecretMedia = emptyList()
                        drop.oralHistory = false
                    }
                }
            }
            runCatching { GlobalContext.get().get<SignOutNotice>().clear() }
            runCatching { GlobalContext.get().get<world.xhere.app.map.MapPins>().clear() }
            runCatching { GlobalContext.get().get<world.xhere.app.drop.PendingDropSpot>().clear() }
            if (TestGraph.isReady()) {
                TestGraph.location.reset()
                TestGraph.speech.next = null
                TestGraph.speaker.last = null
                TestGraph.ar.reset()
                TestGraph.sharer.last = null
                TestGraph.exporter.fileName = null
                TestGraph.exporter.json = null
                TestGraph.links.consume()
            }
            base.evaluate()
        }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.signInWithEmail() {
    waitForTag(TestTags.AUTH_EMAIL)
    onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput("ada@xhere.world")
    onNodeWithTag(TestTags.AUTH_SEND_CODE).performClick()
    waitForTag(TestTags.AUTH_CODE)
    onNodeWithTag(TestTags.AUTH_CODE).performTextInput("123456")
    onNodeWithTag(TestTags.AUTH_VERIFY).performClick()
    waitForTag(TestTags.ONBOARDING_SKIP)
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.openSettings() {
    waitForTag(TestTags.NAV_PROFILE)
    onNodeWithTag(TestTags.NAV_PROFILE).performClick()
    waitForTag(TestTags.OPEN_SETTINGS)
    onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
    waitForTag(TestTags.SETTINGS_SIGN_OUT)
}
