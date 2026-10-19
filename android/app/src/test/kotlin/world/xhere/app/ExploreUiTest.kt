package world.xhere.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import world.xhere.app.ui.TestTags
import world.xhere.shared.demo.JamestownHistoryWalk

/**
 * The map starts with a count, not the big cards. Hiding a walk is local,
 * survives a restart, and the walk stays listed in Explore.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class ExploreUiTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dismissingAWalkSurvivesRestartAndExploreStillListsIt() {
        rule.openMap()
        rule.waitForTag(TestTags.EXPLORE_PILL)
        rule.onNodeWithTag(TestTags.EXPLORE_PILL).assertTextContains("Explore: 3 nearby")

        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.exploreFilter("walks"))
        rule.onNodeWithTag(TestTags.exploreFilter("walks")).performScrollTo().performClick()
        rule.waitForTag(TestTags.WALK_DISMISS)
        rule.onNodeWithTag(TestTags.WALK_DISMISS).performClick()
        rule.waitForTag(TestTags.EXPLORE_UNDO)
        rule.waitForTag(TestTags.exploreDismissed(JamestownHistoryWalk.id.toString()))

        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithText("Explore: 2 nearby", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.waitForTag(TestTags.NAV_MAP)
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.waitForTag(TestTags.CHIP_BAR)
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithText("Explore: 2 nearby", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(TestTags.WALK_CARD).assertDoesNotExist()

        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.exploreFilter("walks"))
        rule.onNodeWithTag(TestTags.exploreFilter("walks")).performScrollTo().performClick()
        rule.waitForTag(TestTags.exploreDismissed(JamestownHistoryWalk.id.toString()))
        rule.onNodeWithTag(TestTags.exploreDismissed(JamestownHistoryWalk.id.toString())).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.WALK_TITLE).assertTextContains("Downtown Jamestown history walk")
    }

    @Test
    fun settingsCanHideTheExplorePill() {
        rule.openMap()
        rule.waitForTag(TestTags.EXPLORE_PILL)
        rule.onNodeWithTag(TestTags.EXPLORE_PILL).assertTextContains("Explore: 3 nearby")

        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.OPEN_SETTINGS)
        rule.onNodeWithTag(TestTags.OPEN_SETTINGS).performScrollTo().performClick()
        rule.waitForTag(TestTags.SETTINGS_EXPLORE_HINT)
        rule.onNodeWithTag(TestTags.SETTINGS_EXPLORE_HINT).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithTag(TestTags.SETTINGS_EXPLORE_HINT).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
        }
        rule.onNodeWithTag(TestTags.SETTINGS_BACK).performClick()
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.waitForTag(TestTags.CHIP_BAR)
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithTag(TestTags.CHIP_BAR).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag(TestTags.EXPLORE_PILL).fetchSemanticsNodes().isEmpty()
        }

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.waitForTag(TestTags.NAV_MAP)
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.waitForTag(TestTags.CHIP_BAR)
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithTag(TestTags.CHIP_BAR).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag(TestTags.EXPLORE_PILL).fetchSemanticsNodes().isEmpty()
        }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>.openMap() {
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

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
