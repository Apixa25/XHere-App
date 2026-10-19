package world.xhere.app

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import world.xhere.app.map.WalkRouteSemantics
import world.xhere.app.ui.TestTags
import world.xhere.shared.demo.JamestownHistoryWalk
import world.xhere.shared.geo.LatLng

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class WalkQuestTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mapOffersTheWalkAndTheStarterQuest() {
        rule.openMap()
        rule.waitForTag(TestTags.EXPLORE_PILL)
        rule.onNodeWithTag(TestTags.EXPLORE_PILL).assertTextContains("Explore: 3 nearby")
        rule.onNodeWithTag(TestTags.WALK_CARD).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.QUEST_CARD).assertDoesNotExist()
        val route = rule.route()
        assertTrue(route.startsWith("route:"))
        assertFalse(route.contains("guide:"))
        assertTrue(route.contains("-79.244603"))
        assertTrue(route.contains("-79.238704"))
        rule.onNodeWithTag(TestTags.marker("fenton")).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.SCREEN_EXPLORE)
        rule.onNodeWithTag(TestTags.exploreFilter("walks")).performScrollTo().performClick()
        rule.waitForTag(TestTags.WALK_TITLE)
        rule.onNodeWithTag(TestTags.WALK_TITLE).assertTextContains("Downtown Jamestown history walk")
        rule.onNodeWithTag(TestTags.WALK_META).assertTextContains("about 14 min", substring = true)
        rule.onNodeWithTag(TestTags.WALK_START).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.exploreFilter("quests")).performScrollTo().performClick()
        rule.waitForTag(TestTags.QUEST_CARD)
        rule.onNodeWithTag(TestTags.QUEST_PROGRESS).assertTextContains("0 of 3", substring = true)
    }

    @Test
    fun panningAwayHidesAWalkThatHasNotStarted() {
        rule.openMap()
        rule.waitForTag(TestTags.EXPLORE_PILL)
        rule.onNodeWithTag(TestTags.EXPLORE_PILL).assertTextContains("Explore: 3 nearby")
        rule.onNodeWithTag(TestTags.MAP_PAN_AWAY).performClick()
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithText("Explore: 1 nearby", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("none", rule.route())
    }

    @Test
    fun nextStopChecksOffInsideTheRevealRadiusAndTheWalkCanBeDismissedFromTheQuest() {
        TestGraph.location.setPermission(true)
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.exploreFilter("walks"))
        rule.onNodeWithTag(TestTags.exploreFilter("walks")).performScrollTo().performClick()
        rule.waitForTag(TestTags.WALK_START)
        rule.onNodeWithTag(TestTags.WALK_START).performClick()
        rule.waitForTag(TestTags.WALK_NEXT)
        rule.onNodeWithTag(TestTags.WALK_NEXT).assertTextContains("Erie Railroad Station", substring = true)

        TestGraph.location.emit(LatLng(42.094509, -79.244603))
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithText("2 of 8", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(TestTags.WALK_PROGRESS).assertTextContains("2 of 8")
        rule.onNodeWithTag(TestTags.WALK_NEXT).assertTextContains("The Wellman Building", substring = true)
        assertTrue(rule.route().contains("guide:"))

        rule.onNodeWithTag(TestTags.MAP_PAN_AWAY).performClick()
        rule.waitForTag(TestTags.marker("buffalo"))
        rule.onNodeWithTag(TestTags.WALK_CARD).assertIsDisplayed()
        val cardBottom = rule.onNodeWithTag(TestTags.WALK_CARD).fetchSemanticsNode().boundsInRoot.bottom
        val controlsTop = rule.onNodeWithTag(TestTags.MY_LOCATION).fetchSemanticsNode().boundsInRoot.top
        assertTrue(cardBottom <= controlsTop)

        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.exploreFilter("quests"))
        rule.onNodeWithTag(TestTags.exploreFilter("quests")).performScrollTo().performClick()
        rule.waitForTag(TestTags.QUEST_DISMISS)
        rule.onNodeWithTag(TestTags.QUEST_DISMISS).performClick()
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithTag(TestTags.QUEST_CARD).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun finishingTheWalkCelebratesOnce() {
        TestGraph.location.setPermission(true)
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_EXPLORE).performClick()
        rule.waitForTag(TestTags.exploreFilter("walks"))
        rule.onNodeWithTag(TestTags.exploreFilter("walks")).performScrollTo().performClick()
        rule.waitForTag(TestTags.WALK_START)
        rule.onNodeWithTag(TestTags.WALK_START).performClick()
        rule.waitForTag(TestTags.WALK_NEXT)
        listOf(1, 3, 5, 6, 7).forEach { position ->
            val stop = JamestownHistoryWalk.stops.first { it.position == position }
            TestGraph.location.emit(LatLng(stop.latitude, stop.longitude))
        }
        rule.waitForTag(TestTags.WALK_CELEBRATION)
        rule.onNodeWithTag(TestTags.WALK_CELEBRATION).assertTextContains("Downtown Jamestown history walk", substring = true)
        rule.onNodeWithTag(TestTags.CELEBRATION_OK).performClick()
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.onAllNodesWithTag(TestTags.WALK_CELEBRATION).fetchSemanticsNodes().isEmpty()
        }
        rule.waitForTag(TestTags.WALK_DONE)
        TestGraph.location.emit(LatLng(42.098818, -79.238704))
        rule.waitUntil(timeoutMillis = 1_000) { true }
        rule.onNodeWithTag(TestTags.WALK_CELEBRATION).assertDoesNotExist()
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

private fun AndroidComposeTestRule<*, MainActivity>.route(): String =
    property(WalkRouteSemantics).orEmpty()

private fun AndroidComposeTestRule<*, MainActivity>.property(key: SemanticsPropertyKey<String>): String? =
    onNodeWithTag(TestTags.SCREEN_MAP).fetchSemanticsNode().config.find { it.key == key }?.value as? String
