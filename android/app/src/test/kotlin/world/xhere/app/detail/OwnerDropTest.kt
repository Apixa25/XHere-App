package world.xhere.app.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
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
import world.xhere.shared.data.fake.StoredDrop
import world.xhere.shared.model.BadgeAward
import world.xhere.shared.model.DropMedia
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MediaKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class OwnerDropTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAuthorCanEditAndUndoADelete() {
        val sample = TestGraph.services.store.drops.values.first { it.shortId == "lunch" }
        val id = Uuid.parse("00000000-0000-0000-0000-0000000000d1")
        TestGraph.services.store.drops[id] = StoredDrop(
            id = id,
            shortId = "minephoto1",
            authorId = TestGraph.profile.id,
            authorHandle = TestGraph.profile.handle,
            groupSlug = sample.groupSlug,
            category = sample.category,
            title = "Blue door on Pine",
            body = "Still painted.",
            attributes = JsonObject(emptyMap()),
            tags = emptyList(),
            trueLocation = sample.trueLocation,
            displayLocation = sample.displayLocation,
            revealRadiusM = sample.revealRadiusM,
            status = DropStatus.ACTIVE,
            displayAnonymous = false,
            expiresAt = null,
            createdAt = Clock.System.now(),
            media = listOf(
                DropMedia(
                    id = Uuid.parse("00000000-0000-0000-0000-0000000000d2"),
                    storagePath = "aa/door.jpg",
                    mediaKind = MediaKind.IMAGE,
                    mimeType = "image/jpeg",
                    altText = "Blue door",
                ),
            ),
            extraBadges = listOf(
                BadgeAward(slug = "first_drop", name = "First drop", icon = "①"),
            ),
        )
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.myDrop("minephoto1"))
        rule.onNodeWithTag(TestTags.myDrop("minephoto1")).assertTextContains("Blue door on Pine")
        rule.onNodeWithTag(TestTags.myDropMenu("minephoto1")).performClick()
        rule.onNodeWithTag(TestTags.myDropEdit("minephoto1")).performClick()
        rule.waitForTag(TestTags.DETAIL_EDIT_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_EDIT_TITLE).performTextReplacement("Blue door, repainted")
        rule.onNodeWithTag(TestTags.DETAIL_EDIT_SAVE).performClick()
        rule.waitForTag(TestTags.DETAIL_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("repainted", substring = true)
        rule.onNodeWithText("You posted this.").assertIsDisplayed()
        rule.onNodeWithText("by Steven").assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DETAIL_FOUNDING).assertTextContains("Founding Local", substring = true)
        rule.onNodeWithTag(TestTags.detailBadge("first_drop")).assertTextContains("First drop", substring = true)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithContentDescription("Blue door").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Blue door").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Photo").assertDoesNotExist()
        rule.onNodeWithText("Blue door").assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_IMPACT).assertTextContains("No confirmations yet", substring = true)
        rule.onNodeWithTag(TestTags.DETAIL_OWNER_MENU).performClick()
        rule.onNodeWithTag(TestTags.DETAIL_DELETE).performClick()
        rule.onNodeWithTag(TestTags.DETAIL_DELETE_CONFIRM).performClick()
        rule.waitForTag(TestTags.DETAIL_UNDO)
        rule.onNodeWithTag(TestTags.DETAIL_UNDO).performClick()
        rule.waitForTag(TestTags.DETAIL_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("repainted", substring = true)
        rule.onNodeWithTag(TestTags.DETAIL_OWNER_MENU).assertIsDisplayed()
    }

    @Test
    fun someoneElsesDropHasNoOwnerMenu() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.marker("lunch")).performClick()
        rule.waitForTag(TestTags.TEASER_OPEN)
        rule.onNodeWithTag(TestTags.TEASER_OPEN).performClick()
        rule.waitForTag(TestTags.DETAIL_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_OWNER_MENU).assertDoesNotExist()
    }

    @Test
    fun aGeneratedHandleAsksForADisplayName() {
        TestGraph.services.viewer = TestGraph.profile.copy(
            handle = "user_abc123",
            displayName = null,
        )
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.PROFILE_NAME)
        rule.onNodeWithText("@user_abc123").assertDoesNotExist()
        rule.onNodeWithTag(TestTags.PROFILE_NAME).performTextInput("Ada")
        rule.onNodeWithTag(TestTags.PROFILE_NAME_SAVE).performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Ada", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("@user_abc123").assertDoesNotExist()
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
