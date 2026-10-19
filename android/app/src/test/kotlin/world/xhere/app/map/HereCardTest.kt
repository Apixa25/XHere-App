package world.xhere.app.map

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import kotlin.math.abs
import org.junit.Assert.assertEquals
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

/**
 * Here Card on the map (research §4.1 #3). Location permission stays off, so
 * user-drop bodies stay hidden. The library long-press is a different place
 * from the default camera, which is also the no-GPS composer fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class HereCardTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tapAndLongPressOpenThePlaceWithoutRevealingLockedDrops() {
        rule.openMap()
        val lunch = TestGraph.services.store.drops.values.first { it.shortId == "lunch" }
        lunch.viewCount = 37
        lunch.confirmCount = 4
        rule.onNodeWithTag(TestTags.MAP_TAP_SPOT).performClick()
        rule.awaitHere(
            "West 3rd Street|eatery_tip:lunch:false:user;food_truck:truck:false:user;event:concert:false:user",
        )
        rule.here(TestTags.HERE_PLACE).assertTextContains("West 3rd Street")
        rule.here(TestTags.HERE_ADDRESS).assertTextContains("Jamestown, New York", substring = true)
        rule.here(TestTags.HERE_ATTRIBUTION).assertTextContains("OpenStreetMap", substring = true)
        rule.onNodeWithText("Seen by 37, Confirmed by 4", useUnmergedTree = true).assertIsDisplayed()
        rule.here(TestTags.hereRow("lunch")).assertExists()
        rule.onNodeWithText("West 3rd at lunch", useUnmergedTree = true).assertIsDisplayed()
        rule.here(TestTags.hereBody("lunch")).assertTextContains("Walk closer", substring = true)
        rule.onNodeWithText("outdoor tables", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Brass quintet", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Public library building", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Reuben Fenton", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Niagara Square", substring = true).assertDoesNotExist()
        rule.here(TestTags.hereRow("records")).assertDoesNotExist()
        rule.here(TestTags.HERE_DROP).assertIsDisplayed()
        rule.here(TestTags.HERE_CLOSE).performClick()
        rule.onNodeWithTag(TestTags.HERE_SHEET).assertDoesNotExist()

        rule.onNodeWithTag(TestTags.MAP_TAP_SPOT).performTouchInput { longClick() }
        rule.awaitHere(
            "West 3rd Street|eatery_tip:lunch:false:user;food_truck:truck:false:user;event:concert:false:user",
        )
        rule.here(TestTags.HERE_CLOSE).performClick()

        rule.onNodeWithTag(TestTags.marker("lunch")).performClick()
        rule.waitForTag(TestTags.TEASER_CARD)
        rule.onNodeWithTag(TestTags.HERE_SHEET).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.TEASER_TITLE).assertTextContains("West 3rd at lunch")
    }

    @Test
    fun longPressAtTheLibraryShowsThePublicRecordAndPrefillsTheComposer() {
        rule.openMap()
        // The walk and quest cards cover this control. The action still runs.
        rule.onNodeWithTag(TestTags.MAP_LONG_PRESS).performSemanticsAction(SemanticsActions.OnClick)
        rule.awaitHere("West 3rd Street|historical_fact:records:true:open_data,fenton:false:user")
        rule.here(TestTags.hereRow("records")).performScrollTo().assertExists()
        rule.onNodeWithText("Prendergast Library", useUnmergedTree = true).assertIsDisplayed()
        rule.here(TestTags.hereBody("records")).performScrollTo()
            .assertTextContains("Public library building", substring = true)
        rule.here(TestTags.hereAttribution("records")).performScrollTo()
            .assertTextContains("National Park Service", substring = true)
        rule.here(TestTags.hereSource("records")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("View source", useUnmergedTree = true).assertIsDisplayed()
        rule.here(TestTags.hereRow("fenton")).performScrollTo().assertExists()
        rule.onNodeWithText("Fenton Mansion", useUnmergedTree = true).assertIsDisplayed()
        rule.here(TestTags.hereBody("fenton")).assertTextContains("Walk closer", substring = true)
        rule.onNodeWithText("Reuben Fenton", substring = true).assertDoesNotExist()

        rule.here(TestTags.HERE_DROP).performClick()
        rule.waitForTag(TestTags.SCREEN_DROP)
        rule.onNodeWithTag(TestTags.DROP_PINNED).assertTextContains("West 3rd Street", substring = true)
        rule.onNodeWithTag(TestTags.DROP_PINNED).assertTextContains("42.09050", substring = true)
        rule.onNodeWithTag(TestTags.DROP_TEXT).performTextInput("A bench by the library")
        rule.onNodeWithTag(TestTags.DROP_TEXT_CONTINUE).performClick()
        rule.waitForTag(TestTags.DROP_MORE)
        rule.onNodeWithTag(TestTags.DROP_MORE).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.category("note")).performScrollTo().performClick()
        rule.waitForTag(TestTags.DROP_SUCCESS)
        val posted = TestGraph.services.store.drops.values.single { it.authorId == TestGraph.profile.id }
        assertEquals("note", posted.category.slug)
        assertEquals(JamestownDemo.library.latitude, posted.trueLocation.latitude, 1e-6)
        assertEquals(JamestownDemo.library.longitude, posted.trueLocation.longitude, 1e-6)
        assertTrue(abs(posted.trueLocation.latitude - JamestownDemo.center.latitude) > 0.001)
    }

    @Test
    fun timeMachineAndThisDayUseTheCachedHistory() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_TIME).performClick()
        rule.awaitTime("1905|1886,1905|none")
        rule.onNodeWithText("Third Street, Jamestown, N.Y.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Library of Congress", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.timeYear(1886)).performClick()
        rule.awaitTime("1886|1886,1905|${world.xhere.shared.demo.HistoryDemo.SHEET_ID}")
        rule.onNodeWithTag(TestTags.TIME_OPACITY).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TestTags.TIME_OVERLAY_NOTE, useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("Demo alignment", substring = true)
        rule.onNodeWithTag(TestTags.TIME_MAP_CREDIT).assertTextContains("Demo alignment", substring = true)
        rule.onNodeWithTag(TestTags.TIME_CLOSE).performClick()
        rule.onNodeWithTag(TestTags.TIME_SHEET).assertDoesNotExist()

        rule.onNodeWithTag(TestTags.MAP_TAP_SPOT).performClick()
        rule.awaitHere(
            "West 3rd Street|eatery_tip:lunch:false:user;food_truck:truck:false:user;event:concert:false:user",
        )
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithTag(TestTags.HERE_THIS_DAY, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.here(TestTags.HERE_THIS_DAY).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("pavement on West 3rd Street", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithText("Chronicling America", substring = true, useUnmergedTree = true).assertIsDisplayed()
        rule.here(TestTags.HERE_TIME).performClick()
        rule.waitForTag(TestTags.TIME_SHEET)
        rule.onNodeWithTag(TestTags.HERE_SHEET).assertDoesNotExist()
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

private fun AndroidComposeTestRule<*, MainActivity>.here(tag: String) =
    onNodeWithTag(tag, useUnmergedTree = true)

private fun AndroidComposeTestRule<*, MainActivity>.awaitTime(expected: String) {
    waitForTag(TestTags.TIME_SHEET)
    var seen = ""
    try {
        waitUntil(timeoutMillis = 8_000) {
            seen = property(world.xhere.app.map.TimeSemantics, TestTags.TIME_SHEET) ?: "null"
            seen == expected
        }
    } catch (error: Throwable) {
        throw AssertionError("Time Machine was [$seen]", error)
    }
}

private fun AndroidComposeTestRule<*, MainActivity>.awaitHere(expected: String) {
    waitForTag(TestTags.HERE_SHEET)
    var seen = ""
    try {
        waitUntil(timeoutMillis = 8_000) {
            seen = property(HereSemantics, TestTags.HERE_SHEET) ?: "null"
            seen == expected
        }
    } catch (error: Throwable) {
        throw AssertionError("Here Card was [$seen]", error)
    }
}

private fun AndroidComposeTestRule<*, MainActivity>.property(
    key: SemanticsPropertyKey<String>,
    tag: String,
): String? {
    val config = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.config ?: return null
    return if (key in config) config[key] else null
}
