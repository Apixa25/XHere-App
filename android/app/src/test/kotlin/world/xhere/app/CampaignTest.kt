package world.xhere.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import world.xhere.app.ui.TestTags
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.rules.CreateDropRequest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class CampaignTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theMapShowsJamestownProgressAndPanningAwayHidesIt() {
        rule.openMap()
        rule.waitForTag(TestTags.EXPLORE_PILL)
        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.exploreFilter("campaigns"))
        rule.onNodeWithTag(TestTags.exploreFilter("campaigns")).performScrollTo().performClick()
        rule.waitForTag(TestTags.CAMPAIGN_CARD)
        rule.onNodeWithTag(TestTags.CAMPAIGN_TITLE).assertTextContains("Map Jamestown", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_PROGRESS).assertTextContains("7 of 200", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_MINE).assertTextContains("You haven't added one yet", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_BOARD).assertTextContains("Demo guide", substring = true)
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.waitForTag(TestTags.SCREEN_MAP)
        rule.onNodeWithTag(TestTags.marker("fenton")).performClick()
        rule.waitForTag(TestTags.TEASER_FOUNDING)
        rule.onNodeWithTag(TestTags.TEASER_FOUNDING).assertTextContains("Founding Local", substring = true)
        rule.onNodeWithTag(TestTags.TEASER_CLOSE).performClick()

        rule.onNodeWithTag(TestTags.MAP_PAN_AWAY).performClick()
        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.exploreFilter("campaigns"))
        rule.onNodeWithTag(TestTags.exploreFilter("campaigns")).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithTag(TestTags.CAMPAIGN_CARD).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun aDropEarnsFoundingLocalAndTheBoardCanHideAName() {
        rule.openMap()
        rule.waitForTag(TestTags.EXPLORE_PILL)
        runBlocking {
            TestGraph.services.drops.createDrop(
                CreateDropRequest(
                    categorySlug = "note",
                    latitude = JamestownDemo.center.latitude,
                    longitude = JamestownDemo.center.longitude,
                    title = "Shade on the riverwalk",
                    body = "A bench in the shade.",
                    attributes = JsonObject(emptyMap()),
                    tags = emptyList(),
                    revealRadiusM = null,
                    expiresAt = null,
                    displayAnonymous = false,
                    media = emptyList(),
                    acknowledgeGuidelines = false,
                ),
            )
        }
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.PROFILE_BADGE)
        rule.onNodeWithTag(TestTags.PROFILE_BADGE).assertTextContains("Founding Local", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_TITLE).assertTextContains("Map Jamestown", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_PROGRESS).assertTextContains("8 of 200", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_MINE).assertTextContains("You added 1", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_BOARD).assertTextContains("Steven", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_BOARD).assertTextContains("Demo guide", substring = true)

        rule.onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
        rule.waitForTag(TestTags.SETTINGS_LEADERBOARD)
        rule.onNodeWithTag(TestTags.SETTINGS_LEADERBOARD).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.SETTINGS_BACK).performClick()
        rule.waitForTag(TestTags.SCREEN_PROFILE)
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithTag(TestTags.CAMPAIGN_BOARD).fetchSemanticsNodes().isNotEmpty() &&
                !rule.textOf(TestTags.CAMPAIGN_BOARD).contains("Steven")
        }
        rule.onNodeWithTag(TestTags.CAMPAIGN_BOARD).assertTextContains("Demo guide", substring = true)
        rule.onNodeWithTag(TestTags.CAMPAIGN_MINE).assertTextContains("You added 1")
        rule.onNodeWithText("Your name is hidden on this board.", substring = true).assertIsDisplayed()
    }
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
    waitForTag(TestTags.CHIP_BAR)
}

private fun AndroidComposeTestRule<*, MainActivity>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

private fun AndroidComposeTestRule<*, MainActivity>.textOf(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        ?.joinToString(" ") { it.text }
        .orEmpty()
