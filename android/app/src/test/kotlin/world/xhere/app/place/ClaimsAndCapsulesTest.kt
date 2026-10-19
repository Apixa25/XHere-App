package world.xhere.app.place

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.app.FreshSessionRule
import world.xhere.app.MainActivity
import world.xhere.app.TestGraph
import world.xhere.app.TestXHereApplication
import world.xhere.app.ui.TestTags
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.CapsuleRequest
import world.xhere.shared.model.Profile
import world.xhere.shared.model.UserTier

/**
 * Official places and sealed memories on the map, the inbox, and the admin queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class ClaimsAndCapsulesTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun aClaimedPlaceShowsTheOfficialBadgeOnTheHereCard() {
        runBlocking {
            val services = TestGraph.services
            services.claimEmail = "ada@mill.example.test"
            val created = services.desk.createUserPlace(
                name = "Mill",
                point = JamestownDemo.center,
                website = "https://mill.example.test",
                signal = null,
            )
            val ticket = services.desk.startClaim(
                created.dropId,
                "https://www.mill.example.test",
                JamestownDemo.center,
                null,
            )
            services.desk.proveEmail(ticket.claimId)
        }
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_TAP_SPOT).performClick()
        rule.waitForTag(TestTags.HERE_OFFICIAL, unmerged = true)
        rule.onNodeWithText("Official · Mill", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun anOralHistoryCapsuleStaysSealedOnTheCard() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_TAP_SPOT).performClick()
        rule.waitForTag(TestTags.HERE_ORAL, unmerged = true)
        rule.onNodeWithTag(TestTags.HERE_ORAL, useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForTag(TestTags.CAPSULE_PROMPTS, unmerged = true)
        rule.onNodeWithText("What did this place look like when you were young?", substring = true, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.CAPSULE_BODY).performScrollTo().performTextInput("grandmother walked to the theater")
        rule.onNodeWithTag(TestTags.CAPSULE_SAVE).performScrollTo().performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodesWithTag(TestTags.CAPSULE_SHEET).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("grandmother walked to the theater", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Sealed until someone stands here", substring = true, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        val stored = TestGraph.services.store.drops.values.first { it.oralHistory }
        assertEquals(null, stored.body)
        assertEquals("grandmother walked to the theater", stored.capsuleSecret)
    }

    @Test
    fun theAuthorHearsWhenSomeoneOpensTheCapsule() {
        val opener = Profile(
            id = Uuid.parse("00000000-0000-0000-0000-0000000000b2"),
            handle = "opener",
            displayName = "Opener",
            createdAt = Instant.parse("2026-10-19T00:00:00Z"),
        )
        val shortId = runBlocking {
            val services = TestGraph.services
            val created = services.desk.createCapsule(
                CapsuleRequest(
                    title = "Neighborhood memory",
                    body = "the sealed neighborhood secret",
                    latitude = JamestownDemo.center.latitude,
                    longitude = JamestownDemo.center.longitude,
                    mode = "visit",
                ),
            )
            services.store.profiles[opener.id] = opener
            services.viewer = opener
            val opened = services.desk.openCapsulesHere(JamestownDemo.center, null)
            assertEquals(1, opened)
            services.viewer = TestGraph.profile
            created.shortId
        }
        assertFalse(TestGraph.services.store.drops.values.first { it.shortId == shortId }.body.isNullOrBlank())
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.impactCapsule(shortId), unmerged = true)
        rule.onNodeWithTag(TestTags.impactCapsule(shortId), useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("Opened by someone standing there", substring = true)
    }

    @Test
    fun aModeratorOpensTheQueueWithoutTheAdminClaimList() {
        TestGraph.services.viewer = moderator
        rule.openSettings()
        rule.onNodeWithTag(TestTags.SETTINGS_MODERATION).performScrollTo().performClick()
        rule.waitForTag(TestTags.SCREEN_MODERATION)
        rule.onNodeWithTag(TestTags.MOD_DENIED).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.CLAIM_QUEUE).assertDoesNotExist()
        rule.onNodeWithText("Couldn't load the queue.", substring = true).assertDoesNotExist()
    }

    @Test
    fun anAdminApprovesAPendingClaim() {
        val claimId = runBlocking {
            val services = TestGraph.services
            val created = services.desk.createUserPlace(
                name = "City hall",
                point = LatLng(10.0, 10.0),
                website = "https://hall.example.test",
                signal = null,
            )
            val ticket = services.desk.startClaim(created.dropId, "https://hall.example.test", LatLng(10.0, 10.0), null)
            services.desk.submitForReview(ticket.claimId, "Please check")
            ticket.claimId
        }
        TestGraph.services.viewer = admin
        val queued = runBlocking { TestGraph.services.desk.reviewQueue() }
        assertTrue(queued.any { it.id == claimId })
        rule.openSettings()
        rule.onNodeWithTag(TestTags.SETTINGS_MODERATION).performScrollTo().performClick()
        rule.waitForTag(TestTags.SCREEN_MODERATION)
        rule.waitForTag(TestTags.CLAIM_QUEUE, unmerged = true)
        rule.onNodeWithTag(TestTags.claimApprove(claimId.toString())).performScrollTo().performClick()
        rule.waitUntil(8_000) {
            TestGraph.services.store.placeClaims.any { it.id == claimId && it.status == "active" }
        }
    }

    private val moderator = Profile(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000ad"),
        handle = "mod_maria",
        displayName = "Maria",
        tier = UserTier.TRUSTED,
        role = AppRole.MODERATOR,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private val admin = Profile(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000ae"),
        handle = "admin_lee",
        displayName = "Lee",
        tier = UserTier.GUIDE,
        role = AppRole.ADMIN,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}

private fun AndroidComposeTestRule<*, MainActivity>.openMap() {
    waitForTag(TestTags.AUTH_EMAIL)
    onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput("ada@xhere.world")
    onNodeWithTag(TestTags.AUTH_SEND_CODE).performClick()
    waitForTag(TestTags.AUTH_CODE)
    onNodeWithTag(TestTags.AUTH_CODE).performTextInput("123456")
    onNodeWithTag(TestTags.AUTH_VERIFY).performClick()
    waitForTag(TestTags.ONBOARDING_SKIP)
    onNodeWithTag(TestTags.ONBOARDING_SKIP).performClick()
    waitForTag(TestTags.SCREEN_MAP)
    waitForTag(TestTags.MAP_TAP_SPOT)
}

private fun AndroidComposeTestRule<*, MainActivity>.openSettings() {
    openMap()
    onNodeWithTag(TestTags.NAV_PROFILE).performClick()
    waitForTag(TestTags.OPEN_SETTINGS)
    onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
    waitForTag(TestTags.SETTINGS_SIGN_OUT)
}

private fun AndroidComposeTestRule<*, MainActivity>.waitForTag(tag: String, unmerged: Boolean = false) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
    }
}
