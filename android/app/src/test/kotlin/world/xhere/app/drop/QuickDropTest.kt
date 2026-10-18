package world.xhere.app.drop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.media.ExifStrip
import world.xhere.shared.model.DropStatus
import world.xhere.shared.rules.CreateDropRequest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class QuickDropTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun photo_posts_a_food_truck_in_two_taps_after_the_map() {
        rule.openDrop()
        rule.onNodeWithText("three taps", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DROP_SHUTTER).performClick()
        rule.waitForTag(TestTags.suggestion("food_truck"))
        rule.onNodeWithTag(TestTags.suggestion("food_truck")).performScrollTo().performClick()
        rule.waitForTag(TestTags.DROP_SUCCESS)
        val posted = TestGraph.services.store.drops.values.filter {
            it.authorId == TestGraph.profile.id && it.category.slug == "food_truck"
        }
        assertEquals(1, posted.size)
        assertEquals("Food truck", posted.single().title)
        val bytes = TestGraph.services.store.mediaBytes.values.single()
        assertFalse(ExifStrip.containsExifMarker(bytes))
        assertEquals(1, TestGraph.services.store.suggestionLogs.size)
    }

    @Test
    fun event_chip_renders_the_schema_fields() {
        rule.openDrop()
        rule.onNodeWithTag(TestTags.DROP_TEXT).performTextInput("live festival happening tonight")
        rule.onNodeWithTag(TestTags.DROP_TEXT_CONTINUE).performClick()
        rule.waitForTag(TestTags.suggestion("event"))
        rule.onNodeWithTag(TestTags.suggestion("event")).performScrollTo().performClick()
        rule.waitForTag(TestTags.field("starts_at"))
        rule.onNodeWithTag(TestTags.field("ends_at")).performScrollTo().assertIsDisplayed()
        assertTrue(TestGraph.services.store.drops.none { it.value.authorId == TestGraph.profile.id })
    }

    @Test
    fun incident_report_explains_the_tier_and_the_911_notice() {
        rule.openDrop()
        // Top-3 suggestions add a time-of-day prior. From 17:00 to 20:00 the
        // nearby food categories outrank this phrase, so pick it from the full list.
        rule.chooseFromAll("theft break-in last night", "incident_report")
        rule.waitForTag(TestTags.DROP_NOTICE)
        rule.onNodeWithTag(TestTags.DROP_NOTICE).assertTextContains("911", substring = true)
        rule.onNodeWithTag(TestTags.DROP_NOTICE).assertTextContains("Describe what happened, not who", substring = true)
        rule.onNodeWithTag(TestTags.DROP_GUIDELINES).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.DROP_POST).performScrollTo().performClick()
        rule.waitForTag(TestTags.DROP_ERROR)
        rule.onNodeWithText("Regular account", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_new_accounts_safety_alert_walks_the_safety_steps_and_is_held_for_review() {
        rule.openDrop()
        rule.chooseFromAll("safety alert caution danger nearby", "safety_alert")
        rule.waitForTag(TestTags.DROP_SAFETY_PANEL)
        rule.onNodeWithTag(TestTags.DROP_SAFETY_PANEL).performScrollTo()
            .assertTextContains("not official records", substring = true)
            .assertTextContains("A moderator reviews this", substring = true)
        rule.onNodeWithTag(TestTags.SAFETY_FOOTER).performScrollTo().assertTextContains("483-8477", substring = true)
        rule.onNodeWithTag(TestTags.DROP_GUIDELINES).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.DROP_POST).performScrollTo().performClick()
        rule.waitForTag(TestTags.DROP_SAFETY_HELD)
        val posted = TestGraph.services.store.drops.values.single {
            it.authorId == TestGraph.profile.id && it.category.slug == "safety_alert"
        }
        assertEquals(DropStatus.PENDING_REVIEW, posted.status)
    }

    @Test
    fun describing_a_person_is_caught_before_posting_and_the_blur_is_explained() {
        rule.openDrop()
        rule.chooseFromAll("tall man broke a car window", "incident_report")
        rule.waitForTag(TestTags.DROP_SAFETY_FILTER)
        rule.onNodeWithTag(TestTags.DROP_SAFETY_FILTER).assertTextContains("Describe what happened, not who", substring = true)
        rule.onNodeWithTag(TestTags.DROP_SAFETY_PANEL).assertTextContains("about 100 m", substring = true)
        rule.onNodeWithTag(TestTags.DROP_POST).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(TestTags.DROP_TEXT).performScrollTo().performTextReplacement("car window smashed overnight")
        rule.onNodeWithTag(TestTags.DROP_SAFETY_FILTER).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DROP_POST).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TestTags.DROP_TEXT).performTextReplacement("window smashed at 12 Main St")
        rule.waitForTag(TestTags.DROP_SAFETY_FILTER)
        rule.onNodeWithTag(TestTags.DROP_POST).assertIsNotEnabled()
        assertTrue(TestGraph.services.store.drops.none { it.value.authorId == TestGraph.profile.id })
    }

    @Test
    fun a_second_identical_title_shows_the_duplicate_hint() {
        rule.openDrop()
        rule.postNote("a general note about the benches")
        rule.onNodeWithTag(TestTags.DROP_ANOTHER).performScrollTo().performClick()
        rule.waitForTag(TestTags.DROP_TEXT)
        rule.postNote("a general note about the benches")
        rule.waitForTag(TestTags.DROP_DUPLICATE)
        rule.onNodeWithText("already nearby", substring = true).assertIsDisplayed()
    }

    @Test
    fun the_fourth_drop_in_an_hour_says_to_wait() {
        runBlocking {
            repeat(3) { index ->
                TestGraph.services.drops.createDrop(noteRequest("Seeded note $index"))
            }
        }
        rule.openDrop()
        rule.onNodeWithTag(TestTags.DROP_TEXT).performTextInput("a general note about the benches")
        rule.onNodeWithTag(TestTags.DROP_TEXT_CONTINUE).performClick()
        rule.waitForTag(TestTags.suggestion("note"))
        rule.onNodeWithTag(TestTags.suggestion("note")).performScrollTo().performClick()
        rule.waitForTag(TestTags.DROP_ERROR)
        rule.onNodeWithText("Wait a bit", substring = true).assertIsDisplayed()
    }

    private fun AndroidComposeTestRule<*, *>.openDrop() {
        signIn()
        waitForTag(TestTags.SCREEN_MAP)
        onNodeWithTag(TestTags.MAP_DROP_FAB).performClick()
        waitForTag(TestTags.SCREEN_DROP)
    }

    private fun AndroidComposeTestRule<*, *>.chooseFromAll(text: String, slug: String) {
        onNodeWithTag(TestTags.DROP_TEXT).performTextInput(text)
        onNodeWithTag(TestTags.DROP_TEXT_CONTINUE).performClick()
        waitForTag(TestTags.DROP_MORE)
        onNodeWithTag(TestTags.DROP_MORE).performScrollTo().performClick()
        waitForTag(TestTags.category(slug))
        onNodeWithTag(TestTags.category(slug)).performScrollTo().performClick()
    }

    private fun AndroidComposeTestRule<*, *>.postNote(title: String) {
        onNodeWithTag(TestTags.DROP_TEXT).performTextInput(title)
        onNodeWithTag(TestTags.DROP_TEXT_CONTINUE).performClick()
        waitForTag(TestTags.suggestion("note"))
        onNodeWithTag(TestTags.suggestion("note")).performScrollTo().performClick()
        waitForTag(TestTags.DROP_SUCCESS)
    }

    private fun AndroidComposeTestRule<*, *>.signIn() {
        waitForTag(TestTags.AUTH_EMAIL)
        onNodeWithTag(TestTags.AUTH_EMAIL).performTextInput("ada@xhere.world")
        onNodeWithTag(TestTags.AUTH_SEND_CODE).performClick()
        waitForTag(TestTags.AUTH_CODE)
        onNodeWithTag(TestTags.AUTH_CODE).performTextInput("123456")
        onNodeWithTag(TestTags.AUTH_VERIFY).performClick()
        waitForTag(TestTags.ONBOARDING_SKIP)
        onNodeWithTag(TestTags.ONBOARDING_SKIP).performClick()
    }

    private fun AndroidComposeTestRule<*, *>.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

class FriendlyDropErrorTest {
    @Test
    fun incident_tier_and_descriptors_use_plain_language() {
        val tier = XHereApiException(
            code = "tier_too_low",
            sqlState = XHereApiException.SQL_PERMISSION,
            detail = "incident_report requires tier regular",
        )
        assertTrue(friendlyDropError(tier, "incident_report").contains("Regular"))
        val descriptors = XHereApiException(
            code = "content_not_allowed",
            detail = "person_descriptors",
            hint = "Describe what happened, not who. Do not name or describe people.",
        )
        assertEquals("Describe what happened, not who.", friendlyDropError(descriptors, "incident_report"))
        val limited = XHereApiException(code = "rate_limited", sqlState = XHereApiException.SQL_RATE_LIMITED)
        assertTrue(friendlyDropError(limited, "note").contains("Wait a bit"))
        assertEquals(null, fuzzNotice(0))
        assertTrue(fuzzNotice(100)!!.contains("100"))
    }
}

private fun noteRequest(title: String) = CreateDropRequest(
    categorySlug = "note",
    latitude = JamestownDemo.center.latitude,
    longitude = JamestownDemo.center.longitude,
    title = title,
    body = "Something worth knowing.",
    attributes = JsonObject(emptyMap()),
    tags = emptyList(),
    revealRadiusM = null,
    expiresAt = null,
    displayAnonymous = false,
    media = emptyList(),
    acknowledgeGuidelines = false,
)
