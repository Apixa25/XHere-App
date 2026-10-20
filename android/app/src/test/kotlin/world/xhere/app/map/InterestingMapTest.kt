package world.xhere.app.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import world.xhere.shared.places.PublicPlace
import world.xhere.shared.places.PublicPlaceKind
import world.xhere.shared.places.PublicSources

/**
 * The default map leads with interesting places. Practical layers and the
 * extra tools stay behind a sheet the person can close.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class InterestingMapTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theMapHidesPracticalPinsAndTheExtraButtons() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.marker("hazard")).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.MAP_TIME).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.MAP_AR).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.MAP_DROP_FAB).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.NEAR_ME).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.MY_LOCATION).assertIsDisplayed()

        rule.onNodeWithTag(TestTags.MAP_MORE).performClick()
        rule.waitForTag(TestTags.MAP_MORE_SHEET)
        rule.onNodeWithTag(TestTags.MAP_TIME).assertIsDisplayed()
        rule.pressBack()
        rule.assertGone(TestTags.MAP_MORE_SHEET)
        rule.onNodeWithTag(TestTags.MAP_DROP_FAB).assertIsDisplayed()

        rule.onNodeWithTag(TestTags.MAP_MORE).performClick()
        rule.onNodeWithTag(TestTags.MAP_LAYERS).performClick()
        rule.waitForTag(TestTags.MAP_LAYER_SAFETY)
        rule.onNodeWithTag(TestTags.MAP_LAYER_SAFETY).performClick()
        rule.onNodeWithTag(TestTags.SHEET_CLOSE).performClick()
        rule.waitForTag(TestTags.marker("hazard"))
    }

    @Test
    fun aSparseViewFillsFromAPublicPlaceThePersonCanClose() {
        TestGraph.publicPlaces.places = listOf(
            PublicPlace(
                id = "enwiki:77",
                title = "Riverside Hall",
                summary = "A hall by the river.",
                latitude = 42.90,
                longitude = -78.90,
                imageUrl = "https://upload.wikimedia.org/example.jpg",
                pageUrl = "https://en.wikipedia.org/wiki/Riverside_Hall",
                attribution = PublicSources.WIKI_CREDIT,
                license = PublicSources.WIKI_LICENSE,
                kind = PublicPlaceKind.ARTICLE,
            ),
        )
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.marker("enwiki:77")).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.MAP_PAN_AWAY).performClick()
        rule.waitForTag(TestTags.marker("enwiki:77"))
        rule.onNodeWithTag(TestTags.marker("enwiki:77")).performClick()
        rule.waitForTag(TestTags.PUBLIC_PLACE_SHEET)
        rule.onNodeWithTag(TestTags.PUBLIC_PLACE_TITLE, useUnmergedTree = true).assertTextContains("Riverside Hall")
        rule.onNodeWithTag(TestTags.PUBLIC_PLACE_SUMMARY, useUnmergedTree = true).assertTextContains("hall by the river", substring = true)
        rule.onNodeWithTag(TestTags.PUBLIC_PLACE_CREDIT, useUnmergedTree = true).assertTextContains("Wikipedia contributors", substring = true)
        rule.onNodeWithTag(TestTags.PUBLIC_PLACE_CREDIT, useUnmergedTree = true).assertTextContains("CC BY-SA", substring = true)
        rule.onNodeWithTag(TestTags.PUBLIC_PLACE_LINK, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Add your contribution", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.PUBLIC_PLACE_ADD, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.SHEET_CLOSE).performClick()
        rule.assertGone(TestTags.PUBLIC_PLACE_SHEET)
        rule.onNodeWithTag(TestTags.SCREEN_MAP).assertIsDisplayed()
    }

    @Test
    fun profileSettingsRememberAlwaysShowAccessibility() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.OPEN_SETTINGS)
        rule.onNodeWithTag(TestTags.OPEN_SETTINGS).performScrollTo().performClick()
        rule.waitForTag(TestTags.SETTINGS_ALWAYS_ACCESS)
        rule.onNodeWithTag(TestTags.SETTINGS_ALWAYS_ACCESS).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.SETTINGS_BACK).performClick()
        rule.onNodeWithTag(TestTags.NAV_MAP).performClick()
        rule.waitForTag(TestTags.MAP_MORE)
        rule.onNodeWithTag(TestTags.MAP_MORE).performClick()
        rule.onNodeWithTag(TestTags.MAP_LAYERS).performClick()
        rule.waitForTag(TestTags.MAP_LAYER_ACCESS)
        rule.onNodeWithTag(TestTags.MAP_LAYER_ACCESS).assertIsOn()
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
    }

    private fun AndroidComposeTestRule<*, MainActivity>.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 8_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun AndroidComposeTestRule<*, MainActivity>.assertGone(tag: String) {
        waitUntil(timeoutMillis = 8_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun AndroidComposeTestRule<*, MainActivity>.pressBack() {
        activity.onBackPressedDispatcher.onBackPressed()
        waitForIdle()
    }
}
