package world.xhere.app.profile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.rules.ActivityScenarioRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class ProfileScreensTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun editProfileSavesTheNameHandleBioTownAndLink() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.PROFILE_EDIT)
        rule.onNodeWithTag(TestTags.PROFILE_EDIT).performScrollTo().performClick()
        rule.waitForTag(TestTags.SCREEN_EDIT_PROFILE)
        rule.onNodeWithTag(TestTags.PROFILE_EDIT_NAME).performTextReplacement("Steven S")
        rule.onNodeWithTag(TestTags.PROFILE_EDIT_HANDLE).performTextReplacement("steven_s")
        rule.onNodeWithTag(TestTags.PROFILE_EDIT_BIO).performTextReplacement("Walks the lake")
        rule.onNodeWithTag(TestTags.PROFILE_EDIT_HOME).performTextReplacement("Jamestown")
        rule.onNodeWithTag(TestTags.PROFILE_EDIT_WEBSITE).performTextReplacement("https://xhere.world")
        rule.onNodeWithTag(TestTags.PROFILE_EDIT_SAVE).performScrollTo().performClick()
        rule.waitForTag(TestTags.PROFILE_BIO)
        rule.onNodeWithTag(TestTags.PROFILE_BIO).performScrollTo().assertTextContains("Walks the lake")
        rule.onNodeWithTag(TestTags.PROFILE_HEADLINE).assertTextContains("Steven S")
    }

    @Test
    fun tappingAnAuthorOpensTheirPublicProfileAndReport() {
        val guide = JamestownDemo.guide
        TestGraph.services.store.profiles[guide.id] = guide.copy(bio = "Walks the lake")
        rule.openMap()
        rule.onNodeWithTag(TestTags.marker("lunch")).performClick()
        rule.waitForTag(TestTags.TEASER_AUTHOR)
        rule.onNodeWithTag(TestTags.TEASER_AUTHOR).assertTextContains("Demo guide", substring = true)
        rule.onNodeWithTag(TestTags.TEASER_AUTHOR).performClick()
        rule.waitForTag(TestTags.SCREEN_PUBLIC_PROFILE)
        rule.onNodeWithTag(TestTags.PUBLIC_NAME).assertTextContains("Demo guide")
        rule.onNodeWithTag(TestTags.PUBLIC_BIO).assertTextContains("Walks the lake")
        rule.onNodeWithTag(TestTags.PUBLIC_TIER).assertTextContains("Trusted", substring = true)
        rule.onNodeWithTag(TestTags.PUBLIC_DROP_COUNT).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.PUBLIC_REPORT).performScrollTo().performClick()
        rule.waitForTag(TestTags.REPORT_FORM)
    }
}

private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openMap() {
    waitForTag(TestTags.AUTH_EMAIL)
    onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput("ada@xhere.world")
    onNodeWithTag(TestTags.AUTH_SEND_CODE).performClick()
    waitForTag(TestTags.AUTH_CODE)
    onNodeWithTag(TestTags.AUTH_CODE).performTextInput("123456")
    onNodeWithTag(TestTags.AUTH_VERIFY).performClick()
    waitForTag(TestTags.ONBOARDING_SKIP)
    onNodeWithTag(TestTags.ONBOARDING_SKIP).performClick()
    waitForTag(TestTags.SCREEN_MAP)
}

private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

