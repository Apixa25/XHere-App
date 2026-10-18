package world.xhere.app.safety

import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ModerationActionType
import world.xhere.shared.model.Profile
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CreateDropRequest

/** M7: report, block, hide, guidelines, data export, and the moderator queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class, qualifiers = "w411dp-h914dp")
class SafetyFlowsTest {
    @get:Rule(order = 1)
    val fresh: FreshSessionRule = FreshSessionRule()

    @get:Rule(order = 0)
    val rule = createAndroidComposeRule<MainActivity>()

    private val store get() = TestGraph.services.store
    private val me get() = TestGraph.profile.id
    private val guide get() = JamestownDemo.guide.id

    @Test
    fun signInStatesTheTermsAndZeroToleranceBeforeAnyonePosts() {
        rule.waitForTag(TestTags.AUTH_EMAIL)
        rule.onNodeWithTag(TestTags.AUTH_AGREEMENT).performScrollTo()
            .assertTextContains("agree to the XHere Terms and Community guidelines", substring = true)
            .assertTextContains("no tolerance for objectionable content or abusive users", substring = true)
    }

    @Test
    fun reportingADropWithAlsoHideFilesTheReportAndHidesItForMeOnly() {
        rule.openDetail("lunch")
        rule.onNodeWithTag(TestTags.DETAIL_REPORT).performScrollTo().performClick()
        rule.waitForTag(TestTags.REPORT_FORM)
        rule.onNodeWithTag(TestTags.REPORT_SEND).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(TestTags.reportReason("spam")).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.REPORT_DETAILS).performScrollTo().performTextInput("Ad for a store across town")
        rule.onNodeWithTag(TestTags.REPORT_ALSO_HIDE).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.REPORT_SEND).performScrollTo().performClick()
        rule.waitForTag(TestTags.DETAIL_SAFETY_MESSAGE)
        rule.onNodeWithTag(TestTags.DETAIL_SAFETY_MESSAGE).assertTextContains("won't see this drop again", substring = true)

        val lunch = dropId("lunch")
        val report = store.reports.values.single()
        assertEquals(ReportTarget.DROP, report.targetType)
        assertEquals(lunch, report.targetId)
        assertEquals(ReportReason.SPAM, report.reason)
        assertEquals("Ad for a store across town", report.details)
        assertTrue((me to lunch) in store.hidden)
        assertFalse(store.canView(drop("lunch"), me, Clock.System.now()))
        assertTrue("hiding is for me only", store.canView(drop("lunch"), Uuid.random(), Clock.System.now()))

        rule.onNodeWithTag(TestTags.DETAIL_UNDO_HIDE).performClick()
        rule.waitForTag(TestTags.DETAIL_TITLE)
        rule.onNodeWithTag(TestTags.DETAIL_TITLE).assertTextContains("West 3rd at lunch")
        assertTrue(store.hidden.isEmpty())
    }

    @Test
    fun reportingTheAuthorFilesAProfileReport() {
        rule.openDetail("lunch")
        rule.onNodeWithTag(TestTags.DETAIL_REPORT_AUTHOR).performScrollTo().performClick()
        rule.waitForTag(TestTags.REPORT_FORM)
        rule.onNodeWithTag(TestTags.REPORT_ALSO_HIDE).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.reportReason("harassment")).performScrollTo().performClick()
        rule.onNodeWithTag(TestTags.REPORT_SEND).performScrollTo().performClick()
        rule.waitForTag(TestTags.DETAIL_SAFETY_MESSAGE)
        rule.onNodeWithTag(TestTags.DETAIL_SAFETY_MESSAGE).assertTextContains("A moderator will look at it", substring = true)

        val report = store.reports.values.single()
        assertEquals(ReportTarget.PROFILE, report.targetType)
        assertEquals(guide, report.targetId)
        assertEquals(ReportReason.HARASSMENT, report.reason)
        assertTrue(store.hidden.isEmpty())
    }

    @Test
    fun blockingTheAuthorHidesDropsBothWaysAndUnblockUndoesIt() {
        val mine = runBlocking { TestGraph.services.drops.createDrop(note("Bench by the library")) }
        rule.openDetail("lunch")
        rule.onNodeWithTag(TestTags.DETAIL_BLOCK_AUTHOR).performScrollTo().performClick()
        rule.waitForTag(TestTags.BLOCK_CONFIRM)
        rule.onNodeWithTag(TestTags.BLOCK_CONFIRM).performClick()
        rule.waitForTag(TestTags.DETAIL_SAFETY_MESSAGE)
        rule.onNodeWithTag(TestTags.DETAIL_SAFETY_MESSAGE).assertTextContains("Blocked @demo_guide", substring = true)

        val now = Clock.System.now()
        assertTrue((me to guide) in store.blocks)
        assertFalse("I no longer see their drops", store.canView(drop("lunch"), me, now))
        assertFalse("they no longer see mine", store.canView(store.drops.getValue(mine.id), guide, now))

        rule.openSettingsFromDetail()
        rule.onNodeWithTag(TestTags.SETTINGS_BLOCKED).performScrollTo().performClick()
        rule.waitForTag(TestTags.unblock("demo_guide"))
        rule.onNodeWithTag(TestTags.LISTS_HIDDEN_EMPTY).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.unblock("demo_guide")).performClick()
        rule.waitForTag(TestTags.LISTS_BLOCKED_EMPTY)
        assertTrue(store.blocks.isEmpty())
        assertTrue(store.canView(drop("lunch"), me, Clock.System.now()))
    }

    @Test
    fun hideForMeShowsUpInBlockedAndHiddenAndShowAgainRestoresIt() {
        rule.openDetail("lunch")
        rule.onNodeWithTag(TestTags.DETAIL_HIDE).performScrollTo().performClick()
        rule.waitForTag(TestTags.DETAIL_SAFETY_MESSAGE)
        rule.onNodeWithTag(TestTags.DETAIL_SAFETY_MESSAGE).assertTextContains("Hidden", substring = true)
        assertTrue((me to dropId("lunch")) in store.hidden)

        rule.openSettingsFromDetail()
        rule.onNodeWithTag(TestTags.SETTINGS_BLOCKED).performScrollTo().performClick()
        rule.waitForTag(TestTags.unhide("lunch"))
        rule.onNodeWithText("West 3rd at lunch").assertIsDisplayed()
        rule.onNodeWithTag(TestTags.unhide("lunch")).performClick()
        rule.waitForTag(TestTags.LISTS_HIDDEN_EMPTY)
        assertTrue(store.hidden.isEmpty())
    }

    @Test
    fun aSafetyDropShowsTheTipLineFooter() {
        rule.openDetail("hazard")
        rule.onNodeWithTag(TestTags.SAFETY_FOOTER).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("(716) 483-8477", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.SAFETY_TIP_CALL).assertIsDisplayed()
    }

    @Test
    fun guidelinesMatchTheWebsiteAndShowTheTipLine() {
        rule.openSettings()
        rule.onNodeWithTag(TestTags.SETTINGS_GUIDELINES).performScrollTo().performClick()
        rule.waitForTag(TestTags.SCREEN_GUIDELINES)
        rule.onNodeWithText("Describe the place and what happened", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Blocking hides drops both ways", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TestTags.SAFETY_FOOTER).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun downloadMyDataHandsTheExportToTheShareSheet() {
        rule.openSettings()
        rule.onNodeWithTag(TestTags.SETTINGS_EXPORT).performScrollTo().performClick()
        rule.waitForTag(TestTags.SETTINGS_EXPORT_STATUS)
        rule.onNodeWithTag(TestTags.SETTINGS_EXPORT_STATUS).assertTextContains("ready", substring = true)
        assertNotNull(TestGraph.exporter.json)
        val json = TestGraph.exporter.json!!
        assertTrue(json.contains("xhere-export-v1"))
        assertTrue(json.contains("\"steven\""))
        assertTrue(TestGraph.exporter.fileName!!.matches(Regex("""xhere-export-\d{4}-\d{2}-\d{2}\.json""")))
    }

    @Test
    fun theQueueIsNotOfferedToRegularAccounts() {
        rule.openSettings()
        rule.onNodeWithTag(TestTags.SETTINGS_EXPORT).performScrollTo()
        rule.onNodeWithTag(TestTags.SETTINGS_MODERATION).assertDoesNotExist()
        val denied = runCatching { runBlocking { TestGraph.services.moderation.queue() } }.exceptionOrNull()
        assertTrue(denied is world.xhere.shared.data.XHereApiException && denied.code == "not_moderator")
    }

    @Test
    fun aModeratorApprovesAHeldSafetyAlertAndEveryActionIsAudited() {
        val held = postAs(newcomer, safetyAlert("Downed wires across Foote Ave"))
        assertEquals(DropStatus.PENDING_REVIEW, held.status)
        runBlocking { TestGraph.services.reports.report(ReportTarget.PROFILE, guide, ReportReason.SPAM, null) }
        TestGraph.services.viewer = moderator

        rule.openSettings()
        rule.onNodeWithTag(TestTags.SETTINGS_MODERATION).performScrollTo().performClick()
        rule.waitForTag(TestTags.queueItem(held.shortId))
        rule.onNodeWithTag(TestTags.queueItem("demo_guide")).performScrollTo().assertIsDisplayed()

        rule.onNodeWithTag(TestTags.modAction("approve", held.shortId)).performScrollTo().performClick()
        rule.waitForTag(TestTags.MOD_MESSAGE)
        rule.onNodeWithTag(TestTags.MOD_MESSAGE).assertTextContains("audit log", substring = true)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(TestTags.queueItem(held.shortId)).fetchSemanticsNodes().isEmpty() }
        assertEquals(DropStatus.ACTIVE, store.drops.getValue(held.id).status)

        rule.onNodeWithTag(TestTags.modAction("suspend", "demo_guide")).performScrollTo().performClick()
        rule.waitUntil(5_000) { store.suspendedUntil.containsKey(guide) }
        rule.onNodeWithTag(TestTags.modAction("action", "demo_guide")).performScrollTo().performClick()
        rule.waitForTag(TestTags.MOD_EMPTY)
        assertTrue(store.reports.values.all { it.status == ReportStatus.ACTIONED })

        val actions = store.auditLog.map { it.action }
        assertEquals(
            listOf(ModerationActionType.APPROVE, ModerationActionType.SUSPEND_USER, ModerationActionType.ACTION_REPORT),
            actions,
        )
        assertTrue(store.auditLog.all { it.actorId == moderator.id })
    }

    @Test
    fun aModeratorRemovesAReportedDrop() {
        runBlocking {
            TestGraph.services.reports.report(ReportTarget.DROP, dropId("lunch"), ReportReason.MISINFORMATION, "Closed for winter")
        }
        TestGraph.services.viewer = moderator

        rule.openSettings()
        rule.onNodeWithTag(TestTags.SETTINGS_MODERATION).performScrollTo().performClick()
        rule.waitForTag(TestTags.queueItem("lunch"))
        rule.onNodeWithText("Closed for winter", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.modAction("remove", "lunch")).performScrollTo().performClick()
        rule.waitForTag(TestTags.MOD_EMPTY)

        assertEquals(DropStatus.REMOVED, drop("lunch").status)
        assertEquals(ReportStatus.ACTIONED, store.reports.values.single().status)
        val audit = store.auditLog.single()
        assertEquals(ModerationActionType.REMOVE, audit.action)
        assertEquals(moderator.id, audit.actorId)
    }

    private val moderator = Profile(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000ad"),
        handle = "mod_maria",
        displayName = "Maria",
        tier = UserTier.TRUSTED,
        role = AppRole.MODERATOR,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private val newcomer = Profile(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000ee"),
        handle = "new_neighbor",
        displayName = "New neighbor",
        createdAt = Instant.parse("2026-10-18T05:00:00Z"),
    )

    private fun postAs(author: Profile, request: CreateDropRequest): world.xhere.shared.model.CreateDropResult {
        TestGraph.services.viewer = author
        return try {
            runBlocking { TestGraph.services.drops.createDrop(request) }
        } finally {
            TestGraph.services.viewer = TestGraph.profile
        }
    }

    private fun drop(shortId: String) = store.drops.values.first { it.shortId == shortId }

    private fun dropId(shortId: String): Uuid = drop(shortId).id
}

internal fun note(title: String) = CreateDropRequest(
    categorySlug = "note",
    latitude = JamestownDemo.center.latitude,
    longitude = JamestownDemo.center.longitude,
    title = title,
    body = null,
    attributes = kotlinx.serialization.json.JsonObject(emptyMap()),
    tags = emptyList(),
    revealRadiusM = null,
    expiresAt = null,
    displayAnonymous = false,
    media = emptyList(),
    acknowledgeGuidelines = false,
)

internal fun safetyAlert(title: String) = note(title).copy(
    categorySlug = "safety_alert",
    attributes = kotlinx.serialization.json.buildJsonObject {
        put("occurred_at", kotlinx.serialization.json.JsonPrimitive(Clock.System.now().toString()))
    },
    acknowledgeGuidelines = true,
)

private typealias ComposeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private fun ComposeRule.signIn() {
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

private fun ComposeRule.openDetail(shortId: String) {
    signIn()
    waitForTag(TestTags.marker(shortId))
    onNodeWithTag(TestTags.marker(shortId)).performClick()
    waitForTag(TestTags.TEASER_OPEN)
    onNodeWithTag(TestTags.TEASER_OPEN).performClick()
    waitForTag(TestTags.SCREEN_DETAIL)
    waitForTag(TestTags.DETAIL_TITLE)
}

private fun ComposeRule.openSettings() {
    signIn()
    goToSettings()
}

private fun ComposeRule.openSettingsFromDetail() {
    onNodeWithTag(TestTags.DETAIL_BACK).performClick()
    waitForTag(TestTags.NAV_PROFILE)
    goToSettings()
}

private fun ComposeRule.goToSettings() {
    onNodeWithTag(TestTags.NAV_PROFILE).performClick()
    waitForTag(TestTags.OPEN_SETTINGS)
    onNodeWithTag(TestTags.OPEN_SETTINGS).performClick()
    waitForTag(TestTags.SETTINGS_SIGN_OUT)
}

private fun ComposeRule.waitForTag(tag: String) {
    waitUntil(timeoutMillis = 8_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
