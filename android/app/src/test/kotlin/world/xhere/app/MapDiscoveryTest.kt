package world.xhere.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import world.xhere.app.map.MapFeaturesSemantics
import world.xhere.app.map.NearMeOrderSemantics
import world.xhere.shared.model.UserTier
import world.xhere.shared.reputation.Reputation
import world.xhere.app.map.ViewportQuery
import world.xhere.app.map.ViewportQuerySemantics
import world.xhere.app.ui.TestTags
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.BoundingBox

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class MapDiscoveryTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chipBarShowsGroupsAndAttribution() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.CHIP_BAR).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.CHIP_ALL).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.preset("Weekend")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TestTags.group("history_stories")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TestTags.MAP_ATTRIBUTION).assertTextContains("OpenStreetMap", substring = true)
        rule.onNodeWithTag(TestTags.MAP_ATTRIBUTION).assertTextContains("OpenMapTiles", substring = true)
        rule.onNodeWithTag(TestTags.MAP_ATTRIBUTION).assertTextContains("OpenFreeMap", substring = true)
        val chips = rule.onNodeWithTag(TestTags.CHIP_BAR).captureToImage()
        assertTrue(chips.width > 40)
        assertTrue(chips.height > 20)
    }

    @Test
    fun panningLoadsOnlyTheNewViewport() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.marker("buffalo")).assertDoesNotExist()
        rule.awaitQuery(JamestownDemo.downtown, JamestownDemo.STREET_ZOOM, null)

        rule.onNodeWithTag(TestTags.MAP_PAN_AWAY).performClick()
        rule.waitForTag(TestTags.marker("buffalo"))
        rule.onNodeWithTag(TestTags.marker("fenton")).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.marker("lunch")).assertDoesNotExist()
        rule.awaitQuery(JamestownDemo.buffalo, JamestownDemo.STREET_ZOOM, null)
        assertEquals("buffalo:note", rule.features())
        rule.onNodeWithText("Steps at Niagara Square", substring = true).assertIsDisplayed()
    }

    @Test
    fun groupFilterIsPassedToTheRepository() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.group("food_drink")).performScrollTo().performClick()
        rule.awaitQuery(JamestownDemo.downtown, JamestownDemo.STREET_ZOOM, listOf("food_drink"))
        rule.onNodeWithTag(TestTags.marker("fenton")).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.marker("lunch")).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.marker("truck")).assertIsDisplayed()
        val features = rule.features().split(";").filter { it.isNotEmpty() }
        assertTrue(features.isNotEmpty())
        assertTrue(features.all { it.endsWith(":food_drink") })
    }

    @Test
    fun weekendPresetPassesTheThreeGroups() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.preset("Weekend")).performScrollTo().performClick()
        rule.awaitQuery(
            JamestownDemo.downtown,
            JamestownDemo.STREET_ZOOM,
            listOf("events", "food_drink", "sales_free"),
        )
        rule.onNodeWithTag(TestTags.marker("fenton")).assertDoesNotExist()
        rule.waitForTag(TestTags.marker("concert"))
        rule.onNodeWithTag(TestTags.marker("lunch")).assertIsDisplayed()
    }

    @Test
    fun subcategorySheetNarrowsToOneCategory() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.group("history_stories")).performScrollTo().performTouchInput { longClick() }
        rule.waitForTag(TestTags.SUBCATEGORY_SHEET)
        rule.onNodeWithText("Historical fact", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Story", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.subcategory("story")).performClick()
        rule.onNodeWithTag(TestTags.subcategory("memorial")).performClick()
        rule.onNodeWithTag(TestTags.SUBCATEGORY_APPLY).performClick()
        rule.awaitQuery(JamestownDemo.downtown, JamestownDemo.STREET_ZOOM, listOf("historical_fact"))
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.marker("lunch")).assertDoesNotExist()
    }

    @Test
    fun teaserCardHidesTheBody() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("lunch"))
        rule.onNodeWithTag(TestTags.marker("lunch")).performClick()
        rule.waitForTag(TestTags.TEASER_CARD)
        rule.onNodeWithTag(TestTags.TEASER_TITLE).assertTextContains("West 3rd at lunch")
        rule.onNodeWithTag(TestTags.TEASER_CATEGORY).assertTextContains("Eatery tip")
        rule.onNodeWithTag(TestTags.TEASER_LOCKED).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.TEASER_BODY).assertDoesNotExist()
        rule.onNodeWithText("outdoor tables", substring = true).assertDoesNotExist()
        val card = rule.onNodeWithTag(TestTags.TEASER_CARD).captureToImage()
        assertTrue(card.width > 40)
        assertTrue(card.height > 20)
    }

    @Test
    fun nearMeIsDistanceSortedInsideFiveKilometres() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.NEAR_ME).performClick()
        rule.waitForTag(TestTags.NEAR_ME_LIST)
        rule.waitUntil(timeoutMillis = 8_000) {
            rule.property(NearMeOrderSemantics, TestTags.NEAR_ME_LIST)?.contains("West 3rd at lunch") == true
        }
        val rows = rule.property(NearMeOrderSemantics, TestTags.NEAR_ME_LIST).orEmpty().split(";")
        val titles = rows.map { it.substringBefore("|") }
        val adjusted = rows.map { row ->
            val parts = row.split("|")
            val raw = parts[1].toDouble()
            val tier = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "none" }?.let { UserTier.valueOf(it) }
            Reputation.adjustedMeters(raw, tier)
        }
        assertEquals("West 3rd at lunch", titles.first())
        assertTrue("Lake view from Celoron" in titles)
        assertFalse("Steps at Niagara Square" in titles)
        assertTrue(adjusted.zipWithNext().all { (a, b) -> a <= b + 0.01 })
        rule.onNodeWithTag(TestTags.nearRow("lunch")).assertIsDisplayed()
    }

    @Test
    fun zoomingOutClustersTheDowntownDrops() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.MAP_ZOOM_OUT).performClick()
        rule.waitForTag(TestTags.MARKER_CLUSTER)
        rule.onNodeWithText("Fenton Mansion", substring = true).assertDoesNotExist()
        rule.awaitQuery(JamestownDemo.downtown, JamestownDemo.CLUSTER_ZOOM, null)
        assertEquals("cluster:7", rule.features())
        rule.onNodeWithTag(TestTags.MARKER_CLUSTER).assertTextContains("7", substring = true)
    }

    @Test
    fun savedPresetRoundTrips() {
        rule.openMap()
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.group("food_drink")).performScrollTo().performClick()
        rule.waitForTag(TestTags.SAVE_PRESET)
        rule.onNodeWithTag(TestTags.SAVE_PRESET).performScrollTo().performClick()
        rule.waitForTag(TestTags.PRESET_NAME)
        rule.onNodeWithTag(TestTags.PRESET_NAME).performTextInput("Lunch")
        rule.onNodeWithTag(TestTags.PRESET_SAVE).performClick()
        rule.onNodeWithTag(TestTags.CHIP_ALL).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForTag(TestTags.marker("fenton"))
        rule.onNodeWithTag(TestTags.preset("Lunch")).performScrollTo().performClick()
        rule.awaitQuery(JamestownDemo.downtown, JamestownDemo.STREET_ZOOM, listOf("food_drink"))
        rule.onNodeWithTag(TestTags.marker("fenton")).assertDoesNotExist()
        rule.waitForTag(TestTags.marker("lunch"))
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

private fun AndroidComposeTestRule<*, MainActivity>.awaitQuery(
    box: BoundingBox,
    zoom: Int,
    categories: List<String>?,
) {
    val expected = ViewportQuery(box, zoom, categories).describe()
    waitUntil(timeoutMillis = 8_000) { property(ViewportQuerySemantics) == expected }
}

private fun AndroidComposeTestRule<*, MainActivity>.features(): String =
    property(MapFeaturesSemantics).orEmpty()

private fun AndroidComposeTestRule<*, MainActivity>.property(
    key: SemanticsPropertyKey<String>,
    tag: String = TestTags.SCREEN_MAP,
): String? {
    val config = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.config ?: return null
    return if (key in config) config[key] else null
}
