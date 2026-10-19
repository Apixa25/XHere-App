package world.xhere.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import world.xhere.app.ui.TestTags

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class ReputationUiTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun profileChipOpensTheLevelUpSheetAndADropShowsTheAuthorTier() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.marker("fenton")).performClick()
        rule.waitForTag(TestTags.TEASER_TIER)
        rule.onNodeWithTag(TestTags.TEASER_TIER).assertTextContains("Trusted", substring = true)
        rule.onNodeWithTag(TestTags.TEASER_OPEN).performClick()
        rule.waitForTag(TestTags.DETAIL_TIER)
        rule.onNodeWithTag(TestTags.DETAIL_TIER).assertTextContains("Trusted", substring = true)
        rule.onNodeWithTag(TestTags.DETAIL_BACK).performClick()
        rule.waitForTag(TestTags.SCREEN_MAP)

        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.TIER_CHIP)
        rule.onNodeWithTag(TestTags.TIER_CHIP).assertTextContains("New", substring = true)
        rule.onNodeWithTag(TestTags.TIER_CHIP).performClick()
        rule.waitForTag(TestTags.LEVEL_UP)
        rule.onNodeWithText("Local · 24", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Trusted · 80", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Guide · 200", substring = true).assertIsDisplayed()
        rule.onNodeWithText("still true is 8 points", substring = true).assertIsDisplayed()
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
    waitForTag(TestTags.marker("fenton"))
}

private fun AndroidComposeTestRule<*, MainActivity>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
