package world.xhere.app.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.robolectric.Robolectric
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.Confirmation
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ImpactNotice
import world.xhere.shared.share.ShareLinks

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class DropDetailTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun teaserOpensALockedDropAndShareUsesThePublicLink() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.marker("lunch")).performClick()
        rule.waitForTag(TestTags.TEASER_OPEN)
        rule.onNodeWithTag(TestTags.TEASER_OPEN).performClick()
        rule.waitForTag(TestTags.SCREEN_DETAIL)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("West 3rd at lunch")
        rule.onNodeWithTag(TestTags.DETAIL_CATEGORY).assertTextContains("Eatery tip")
        rule.onNodeWithTag(TestTags.DETAIL_LOCKED).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DETAIL_LOCKED).assertTextContains("Walk closer to reveal", substring = true)
        rule.onNodeWithText("outdoor tables", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_BODY).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_STILL_TRUE).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_IMPACT).assertTextContains("1 person checked · 0 said still there")
        rule.onNodeWithTag(TestTags.DETAIL_FRESHNESS).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_SHARE).performScrollTo().performClick()
        assertEquals(ShareLinks.url("lunch"), TestGraph.sharer.last)
    }

    @Test
    fun nearMeRowOpensTheSameScreen() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.NEAR_ME).performClick()
        rule.waitForTag(TestTags.nearRow("lunch"))
        rule.onNodeWithTag(TestTags.nearRow("lunch")).performClick()
        rule.waitForTag(TestTags.DETAIL_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("West 3rd at lunch")
        rule.onNodeWithText("outdoor tables", substring = true).assertDoesNotExist()
    }

    @Test
    fun distanceUpdatesInTheForegroundAndConfirmChangesTheCount() {
        rule.openMap()
        val lunch = dropAt("lunch")
        val metersPerDegree = Geo.distanceMeters(lunch, LatLng(lunch.latitude + 1.0, lunch.longitude))
        val outside = LatLng(lunch.latitude + 620.0 / metersPerDegree, lunch.longitude)
        assertEquals(120, Geo.revealGapMeters(Geo.distanceMeters(lunch, outside), 500))
        TestGraph.location.setPermission(true)
        rule.onNodeWithTag(TestTags.marker("lunch")).performClick()
        rule.waitForTag(TestTags.TEASER_OPEN)
        rule.onNodeWithTag(TestTags.TEASER_OPEN).performClick()
        rule.waitForTag(TestTags.SCREEN_DETAIL)
        rule.waitUntil(timeoutMillis = 5_000) { TestGraph.location.collectionCount >= 1 }
        TestGraph.location.emit(outside)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Walk 120 m closer to reveal", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(TestTags.DETAIL_BODY).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_STILL_TRUE).assertDoesNotExist()
        TestGraph.location.emit(lunch)
        rule.waitForTag(TestTags.DETAIL_BODY)
        rule.onNodeWithTag(TestTags.DETAIL_BODY).assertTextContains("outdoor tables", substring = true)
        rule.onNodeWithTag(TestTags.DETAIL_LOCKED).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_STILL_TRUE).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("said still there", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(TestTags.DETAIL_NOT_TRUE).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("1 said this isn't true", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(TestTags.DETAIL_BADGE).assertDoesNotExist()
    }

    @Test
    fun listeningStopsWhenTheAppLeavesTheForeground() {
        rule.openMap()
        TestGraph.location.setPermission(true)
        rule.onNodeWithTag(TestTags.marker("fenton")).performClick()
        rule.waitForTag(TestTags.TEASER_OPEN)
        rule.onNodeWithTag(TestTags.TEASER_OPEN).performClick()
        rule.waitForTag(TestTags.SCREEN_DETAIL)
        rule.waitUntil(timeoutMillis = 5_000) { TestGraph.location.collectionCount == 1 }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitUntil(timeoutMillis = 5_000) { TestGraph.location.collectionCount == 0 }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(timeoutMillis = 5_000) { TestGraph.location.collectionCount == 1 }
        rule.onNodeWithText("public museum", substring = true).assertDoesNotExist()
    }

    @Test
    fun seenAndConfirmedCountsStayOnTheDropAndConfirmIsOneTap() {
        val lunch = TestGraph.services.store.drops.values.first { it.shortId == "lunch" }
        // Counts are derived from rows on the next confirm, the same way the
        // confirmations trigger recounts. Seed the earlier "still true" so
        // this tap moves Confirmed by 1 to Confirmed by 2.
        val earlier = Uuid.parse("00000000-0000-0000-0000-0000000000cc")
        TestGraph.services.store.confirmations[lunch.id to earlier] = Confirmation(
            dropId = lunch.id,
            userId = earlier,
            verdict = ConfirmationVerdict.STILL_TRUE,
            note = null,
            distanceM = 12,
            createdAt = Clock.System.now(),
        )
        lunch.viewCount = 4
        lunch.confirmCount = 1
        rule.openMap()
        TestGraph.location.setPermission(true)
        rule.onNodeWithTag(TestTags.marker("lunch")).performClick()
        rule.waitForTag(TestTags.TEASER_OPEN)
        rule.onNodeWithTag(TestTags.DETAIL_IMPACT).assertTextContains("Seen by 4, Confirmed by 1")
        rule.onNodeWithTag(TestTags.TEASER_OPEN).performClick()
        rule.waitForTag(TestTags.DETAIL_IMPACT)
        rule.onNodeWithTag(TestTags.DETAIL_IMPACT).assertTextContains("5 people checked · 1 said still there")
        rule.waitUntil(timeoutMillis = 5_000) { TestGraph.location.collectionCount >= 1 }
        TestGraph.location.emit(lunch.displayLocation)
        rule.waitForTag(TestTags.DETAIL_STILL_TRUE)
        rule.onNodeWithTag(TestTags.DETAIL_STILL_TRUE).performScrollTo().assertTextContains("Confirm")
        rule.onNodeWithTag(TestTags.DETAIL_STILL_TRUE).performClick()
        rule.waitForTag(TestTags.DETAIL_YOU_CONFIRMED)
        rule.onNodeWithTag(TestTags.DETAIL_STILL_TRUE).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_IMPACT).assertTextContains("5 people checked · 2 said still there")
    }

    @Test
    fun profileInboxShowsOneHelpedNoteAndOpensTheDrop() {
        val lunch = TestGraph.services.store.drops.values.first { it.shortId == "lunch" }
        TestGraph.services.store.impactNotices += ImpactNotice(
            id = Uuid.parse("00000000-0000-0000-0000-0000000000bb"),
            dropId = lunch.id,
            shortId = lunch.shortId,
            title = lunch.title,
            noticeDay = "2026-10-18",
            viewEvents = 3,
            confirmEvents = 1,
            createdAt = Clock.System.now(),
            recipientId = TestGraph.profile.id,
        )
        rule.openMap()
        rule.onNodeWithTag(TestTags.NAV_PROFILE).performClick()
        rule.waitForTag(TestTags.IMPACT_INBOX)
        rule.onNodeWithTag(TestTags.impactRow("lunch")).assertTextContains("Seen by 3, Confirmed by 1")
        rule.onNodeWithTag(TestTags.impactRow("lunch")).performClick()
        rule.waitForTag(TestTags.DETAIL_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("West 3rd at lunch")
    }

    @Test
    fun confirmedBadgeUsesTheCategoryBar() {
        val fenton = TestGraph.services.store.drops.values.first { it.shortId == "fenton" }
        fenton.status = DropStatus.CONFIRMED
        fenton.confirmCount = 3
        fenton.lastConfirmedAt = Clock.System.now() - 5.hours
        rule.openMap()
        rule.onNodeWithTag(TestTags.marker("fenton")).performClick()
        rule.waitForTag(TestTags.TEASER_OPEN)
        rule.onNodeWithTag(TestTags.TEASER_OPEN).performClick()
        rule.waitForTag(TestTags.DETAIL_BADGE)
        rule.onNodeWithTag(TestTags.DETAIL_BADGE).assertTextContains("Confirmed", substring = true)
        rule.onNodeWithTag(TestTags.DETAIL_IMPACT).assertTextContains("1 person checked · 3 said still there")
        rule.onNodeWithTag(TestTags.DETAIL_PROGRESS).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_FRESHNESS).assertTextContains("5 hours ago", substring = true)
        rule.onNodeWithText("public museum", substring = true).assertDoesNotExist()
    }

    @Test
    fun publicRecordShowsTheBadgeAndAttributionWithoutAConfirmation() {
        rule.openMap()
        rule.onNodeWithTag(TestTags.marker("records")).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForTag(TestTags.TEASER_PUBLIC)
        rule.onNodeWithTag(TestTags.TEASER_PUBLIC).assertTextContains("From public records")
        rule.onNodeWithTag(TestTags.TEASER_OPEN).performClick()
        rule.waitForTag(TestTags.DETAIL_PUBLIC)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("Prendergast Library")
        rule.onNodeWithTag(TestTags.DETAIL_PUBLIC).assertTextContains("From public records")
        rule.onNodeWithTag(TestTags.DETAIL_BODY).assertTextContains("Public library", substring = true)
        rule.onNodeWithTag(TestTags.DETAIL_ATTRIBUTION).performScrollTo().assertTextContains("National Park Service", substring = true)
        rule.onNodeWithTag(TestTags.DETAIL_SOURCE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DETAIL_LOCKED).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_STILL_TRUE).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_PROGRESS).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.DETAIL_FRESHNESS).assertDoesNotExist()
    }

    @Test
    fun aPendingLinkOpensThatDrop() {
        rule.openMap()
        TestGraph.links.offer(ShareLinks.url("fenton"))
        rule.waitForTag(TestTags.DETAIL_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("Fenton Mansion")
        rule.onNodeWithText("public museum", substring = true).assertDoesNotExist()
        assertNull(TestGraph.links.pending.value)
    }

    private fun dropAt(shortId: String): LatLng {
        val drop = TestGraph.services.store.drops.values.first { it.shortId == shortId }
        return drop.displayLocation
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class DropLinkColdStartTest {
    @get:Rule
    val fresh: FreshSessionRule = FreshSessionRule()

    @Test
    fun viewIntentIsHeldForTheDetailScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(ShareLinks.url("fenton"))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        assertEquals("fenton", TestGraph.links.pending.value)
        controller.destroy()
    }

    @Test
    fun aSecondLinkReplacesThePendingDrop() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        assertNull(TestGraph.links.pending.value)
        controller.newIntent(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(ShareLinks.url("lunch"))
            },
        )
        assertEquals("lunch", TestGraph.links.pending.value)
        controller.destroy()
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
    waitForTag(TestTags.marker("lunch"))
}

private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
