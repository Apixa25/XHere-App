package world.xhere.app.map

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlin.time.Instant
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
import world.xhere.shared.live.AlertSeverity
import world.xhere.shared.live.WeatherAlert

/**
 * Official alerts on the map, and a right-now drop with a short lifetime.
 * The scripted feed stays empty until a test fills it, so other map tests
 * do not see a banner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class LiveConditionsUiTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun anOfficialAlertIsDismissibleAndTheNextOneShows() {
        val storm = alert("storm", "Severe Thunderstorm Warning", AlertSeverity.SEVERE)
        val watch = alert("watch", "Flood Watch", AlertSeverity.MODERATE)
        // Set before the map's first camera idle. The quest card covers the zoom
        // button, and a touch click there does not reach it.
        TestGraph.conditions.alerts = listOf(storm, watch)
        rule.openMap()
        rule.onNodeWithTag(TestTags.WEATHER_BANNER).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.WEATHER_SEVERITY).assertTextContains("Severe")
        rule.onNodeWithTag(TestTags.WEATHER_EVENT).assertTextContains("Severe Thunderstorm Warning")
        rule.onNodeWithTag(TestTags.WEATHER_SOURCE).assertTextContains("National Weather Service", substring = true)
        rule.onNodeWithText("2026-10-18 18:30 UTC", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.WEATHER_MORE).assertTextContains("+ 1 more")
        val bannerBottom = rule.onNodeWithTag(TestTags.WEATHER_BANNER).fetchSemanticsNode().boundsInRoot.bottom
        val controlsTop = rule.onNodeWithTag(TestTags.MY_LOCATION).fetchSemanticsNode().boundsInRoot.top
        assertTrue(bannerBottom <= controlsTop)
        // The walk and quest cards cover this control. The action still runs.
        rule.onNodeWithTag(TestTags.MAP_LONG_PRESS).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForTag(TestTags.HERE_SHEET)
        rule.onNodeWithTag(TestTags.HERE_CONDITIONS)
            .assertTextContains("Severe Thunderstorm Warning", substring = true)
            .assertTextContains("National Weather Service", substring = true)
        rule.onNodeWithTag(TestTags.HERE_CLOSE).performClick()
        rule.onNodeWithTag(TestTags.WEATHER_DISMISS).performClick()
        rule.onNodeWithTag(TestTags.WEATHER_EVENT).assertTextContains("Flood Watch")
        rule.onNodeWithTag(TestTags.WEATHER_MORE).assertDoesNotExist()
    }

    @Test
    fun rightNowPostsForOneHour() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.MAP_DROP_FAB).performClick()
        rule.waitForTag(TestTags.SCREEN_DROP)
        rule.onNodeWithTag(TestTags.DROP_TEXT).performTextInput("Queue at the door")
        rule.onNodeWithTag(TestTags.DROP_TEXT_CONTINUE).performClick()
        rule.waitForTag(TestTags.DROP_MORE)
        rule.onNodeWithTag(TestTags.DROP_MORE).performScrollTo().performClick()
        rule.waitForTag(TestTags.category("right_now"))
        rule.onNodeWithTag(TestTags.category("right_now")).performScrollTo().performClick()
        rule.waitForTag(TestTags.field("kind"))
        rule.onNodeWithText("closed").performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.dropTtl("1h")).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.DROP_POST).performScrollTo().performClick()
        rule.waitForTag(TestTags.DROP_SUCCESS)
        val posted = TestGraph.services.store.drops.values.single { it.title == "Queue at the door" }
        assertEquals("right_now", posted.category.slug)
        val span = posted.expiresAt!! - kotlin.time.Clock.System.now()
        assertTrue(span.inWholeMinutes in 50..70)
    }

    private fun alert(id: String, event: String, severity: AlertSeverity) = WeatherAlert(
        id = id,
        event = event,
        severity = severity,
        expiresAt = Instant.parse("2026-10-18T18:30:00Z"),
        headline = null,
        attribution = "National Weather Service",
        senderName = "NWS Buffalo",
        sourceUrl = null,
    )
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
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
