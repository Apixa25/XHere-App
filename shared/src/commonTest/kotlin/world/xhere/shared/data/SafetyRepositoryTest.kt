package world.xhere.shared.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import world.xhere.shared.SupabaseConfig
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.data.supabase.SupabaseXHereClient
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ModerateDropAction
import world.xhere.shared.model.ModerationActionType
import world.xhere.shared.model.ModerationQueueItem
import world.xhere.shared.model.Profile
import world.xhere.shared.model.QueueItemKind
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CreateDropRequest

class SafetyFakeTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    private fun person(n: Int, tier: UserTier = UserTier.NEW, role: AppRole = AppRole.USER) = Profile(
        id = Uuid.parse("00000000-0000-4000-8000-0000000007${n.toString().padStart(2, '0')}"),
        handle = "person$n",
        tier = tier,
        role = role,
        createdAt = now - 30.days,
    )

    private fun client(store: MemoryStore, profile: Profile) = InMemoryXHere(store, profile, clock = { now })

    private fun safetyAlert(title: String = "Power out", body: String = "Traffic lights are dark on Main.") = CreateDropRequest(
        categorySlug = "safety_alert",
        latitude = JamestownDemo.center.latitude,
        longitude = JamestownDemo.center.longitude,
        title = title,
        body = body,
        attributes = buildJsonObject { put("occurred_at", now.toString()) },
        tags = emptyList(),
        revealRadiusM = null,
        expiresAt = null,
        displayAnonymous = false,
        media = emptyList(),
        acknowledgeGuidelines = true,
    )

    @Test
    fun newUsersSafetyPostsWaitInTheQueueUntilApproved() = runTest {
        val store = JamestownDemo.store()
        val alice = person(1)
        val bob = person(2)
        val mo = person(3, UserTier.TRUSTED, AppRole.MODERATOR)
        val created = client(store, alice).drops.createDrop(safetyAlert())
        assertEquals(DropStatus.PENDING_REVIEW, created.status)
        assertTrue(client(store, bob).drops.dropsNearby(created.displayLocation, 200).none { it.id == created.id })

        val denied = assertFailsWith<XHereApiException> { client(store, bob).moderation.queue() }
        assertEquals("not_moderator", denied.code)

        val queue = client(store, mo).moderation.queue()
        val item = queue.single { it.targetId == created.id }
        assertEquals(QueueItemKind.DROP, item.kind)
        assertTrue(item.sensitive && item.isPending)
        assertEquals(alice.handle, item.subjectHandle)

        assertEquals(DropStatus.ACTIVE, client(store, mo).moderation.moderateDrop(created.id, ModerateDropAction.APPROVE, "ok"))
        assertTrue(client(store, bob).drops.dropsNearby(created.displayLocation, 200).any { it.id == created.id })
        assertEquals(ModerationActionType.APPROVE, store.auditLog.last().action)
        assertEquals(mo.id, store.auditLog.last().actorId)
    }

    @Test
    fun twoReportsHideASafetyAlertAndTheQueueShowsThem() = runTest {
        val store = JamestownDemo.store()
        val carol = person(4, UserTier.REGULAR)
        val created = client(store, carol).drops.createDrop(safetyAlert(title = "Road closed", body = "Bridge closed."))
        assertEquals(DropStatus.ACTIVE, created.status)
        client(store, person(1)).reports.report(ReportTarget.DROP, created.id, ReportReason.MISINFORMATION, "It's open")
        assertEquals(DropStatus.ACTIVE, store.drops.getValue(created.id).status)
        client(store, person(2)).reports.report(ReportTarget.DROP, created.id, ReportReason.SPAM)
        assertEquals(DropStatus.PENDING_REVIEW, store.drops.getValue(created.id).status)
        assertEquals(ModerationActionType.AUTO_HIDE, store.auditLog.single().action)
        assertNull(store.auditLog.single().actorId)

        val mo = client(store, person(3, UserTier.TRUSTED, AppRole.MODERATOR))
        val item = mo.moderation.queue().single { it.targetId == created.id }
        assertEquals(2, item.openReports)
        assertEquals(listOf(ReportReason.MISINFORMATION, ReportReason.SPAM), item.reasons)
        assertEquals("It's open", item.latestDetails)
        assertEquals(DropStatus.REMOVED, mo.moderation.moderateDrop(created.id, ModerateDropAction.REMOVE, "false"))
        assertEquals(ModerationActionType.REJECT, store.auditLog.last().action)
        assertTrue(store.reports.values.all { it.status == ReportStatus.ACTIONED })
    }

    @Test
    fun profileReportsSuspensionsAndReportResolutionsAreAudited() = runTest {
        val store = JamestownDemo.store()
        val bob = client(store, person(2))
        val guide = bob.profiles.publicProfile("Demo_Guide")
        assertEquals(JamestownDemo.guide.id, guide?.id)
        assertNull(bob.profiles.publicProfile("nobody_here"))
        val reportId = bob.reports.report(ReportTarget.PROFILE, JamestownDemo.guide.id, ReportReason.HARASSMENT, "Rude")

        val moProfile = person(3, UserTier.TRUSTED, AppRole.MODERATOR)
        val mo = client(store, moProfile)
        val item = mo.moderation.queue().single { it.kind == QueueItemKind.PROFILE }
        assertEquals("demo_guide", item.subjectHandle)
        assertEquals(listOf(reportId), item.reportIds)

        mo.moderation.resolveReport(reportId, ReportStatus.ACTIONED, "Warned")
        mo.moderation.suspendUser(JamestownDemo.guide.id, now + 1.days, "Harassment")
        assertEquals(now + 1.days, store.suspendedUntil[JamestownDemo.guide.id])
        mo.moderation.suspendUser(JamestownDemo.guide.id, null, "Appeal")
        assertEquals(
            listOf(ModerationActionType.ACTION_REPORT, ModerationActionType.SUSPEND_USER, ModerationActionType.UNSUSPEND_USER),
            store.auditLog.map { it.action },
        )
        assertTrue(store.auditLog.all { it.actorId == moProfile.id })
        assertTrue(mo.moderation.queue().none { it.kind == QueueItemKind.PROFILE })
    }

    @Test
    fun hidingIsPrivateAndBlocksAreListed() = runTest {
        val store = JamestownDemo.store()
        val bob = client(store, person(2))
        val ed = client(store, person(5))
        val lunch = bob.drops.dropsNearby(JamestownDemo.center, 5_000).first { it.shortId == "lunch" }

        bob.hidden.hideDrop(lunch.id)
        bob.hidden.hideDrop(lunch.id)
        assertTrue(bob.drops.dropsNearby(JamestownDemo.center, 5_000).none { it.id == lunch.id })
        assertEquals(listOf(lunch.id), bob.hidden.myHiddenDrops().map { it.dropId })
        assertTrue(ed.drops.dropsNearby(JamestownDemo.center, 5_000).any { it.id == lunch.id })
        assertEquals("drop_not_found", assertFailsWith<XHereApiException> { bob.drops.dropDetail(lunch.id) }.code)
        bob.hidden.unhideDrop(lunch.id)
        assertTrue(bob.hidden.myHiddenDrops().isEmpty())

        val mine = bob.drops.createDrop(safetyAlert()).id
        assertEquals("cannot_hide_own_drop", assertFailsWith<XHereApiException> { bob.hidden.hideDrop(mine) }.code)
        assertEquals("drop_not_found", assertFailsWith<XHereApiException> { bob.hidden.hideDrop(Uuid.random()) }.code)

        bob.blocks.blockUser(JamestownDemo.guide.id)
        assertEquals(listOf("demo_guide"), bob.blocks.myBlocks().map { it.handle })
        assertTrue(bob.drops.dropsNearby(JamestownDemo.center, 5_000).none { it.shortId == "lunch" })
        assertTrue(ed.blocks.myBlocks().isEmpty())
        bob.blocks.unblockUser(JamestownDemo.guide.id)
        assertTrue(bob.blocks.myBlocks().isEmpty())
    }

    @Test
    fun exportHasMyDataAndNothingElse() = runTest {
        val store = JamestownDemo.store()
        val bob = client(store, person(2))
        bob.auth.signInWithEmailOtp("bob@example.test")
        bob.auth.verifyEmailOtp("bob@example.test", "123456")
        bob.drops.createDrop(safetyAlert(title = "Bob's alert"))
        val lunch = bob.drops.dropsNearby(JamestownDemo.center, 5_000).first { it.shortId == "lunch" }
        bob.hidden.hideDrop(lunch.id)
        bob.reports.report(ReportTarget.PROFILE, JamestownDemo.guide.id, ReportReason.SPAM)
        bob.blocks.blockUser(JamestownDemo.guide.id)

        val text = bob.account.exportMyData()
        assertTrue(text.lines().size > 10 && text.contains("\"format\": \"xhere-export-v1\""), "export is pretty-printed")
        val export = XHereJson.parseToJsonElement(text).jsonObject
        assertEquals("xhere-export-v1", export.getValue("format").jsonPrimitive.content)
        assertEquals("bob@example.test", export.getValue("email").jsonPrimitive.content)
        assertEquals(listOf("Bob's alert"), export.getValue("drops").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content })
        assertEquals(1, export.getValue("hidden_drops").jsonArray.size)
        assertEquals(1, export.getValue("reports_filed").jsonArray.size)
        assertEquals(1, export.getValue("blocks").jsonArray.size)
        assertTrue(!text.contains("West 3rd at lunch"), "other people's drops are not exported")
    }

    @Test
    fun streetAddressesAreBlockedInIncidentReports() = runTest {
        val store = JamestownDemo.store()
        val regular = client(store, person(6, UserTier.REGULAR))
        val error = assertFailsWith<XHereApiException> {
            regular.drops.createDrop(safetyAlert(body = "Window smashed at 123 Main St.").copy(categorySlug = "incident_report"))
        }
        assertEquals("content_not_allowed", error.code)
        assertEquals("street_address", error.detail)
        val ok = regular.drops.createDrop(safetyAlert(body = "Window smashed on Main Street.").copy(categorySlug = "incident_report"))
        assertEquals(DropStatus.PENDING_REVIEW, ok.status)
    }

    @Test
    fun incidentReportsNeedATipLineWhereTheyArePosted() = runTest {
        val store = JamestownDemo.store()
        val regular = client(store, person(7, UserTier.REGULAR))
        val buffalo = safetyAlert(body = "Window smashed on Main Street.")
            .copy(categorySlug = "incident_report", latitude = 42.8864, longitude = -78.8784)
        val error = assertFailsWith<XHereApiException> { regular.drops.createDrop(buffalo) }
        assertEquals("incident_reports_unavailable", error.code)
        assertEquals(XHereApiException.SQL_PERMISSION, error.sqlState)
        assertEquals(DropStatus.ACTIVE, regular.drops.createDrop(safetyAlert().copy(latitude = 42.8864, longitude = -78.8784)).status, "safety alerts work anywhere")
    }
}

class SafetyContractTest {
    private class Captured {
        val calls = mutableListOf<Pair<String, String>>()
    }

    private fun client(captured: Captured, reply: (String) -> String) = SupabaseXHereClient(
        SupabaseConfig(url = "https://example.supabase.co", anonKey = "test-anon-key"),
        engine = MockEngine { request ->
            val body = when (val content = request.body) {
                is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                else -> ""
            }
            captured.calls += request.url.encodedPath to body
            respond(
                content = ByteReadChannel(reply(request.url.encodedPath)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    @Test
    fun safetyRpcsUseTheSqlNamesAndDecodeTheirRows() = runTest {
        val captured = Captured()
        val dropId = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val userId = Uuid.parse("22222222-2222-4222-8222-222222222222")
        val api = client(captured) { path ->
            when {
                path.endsWith("my_blocks") ->
                    """[{"user_id":"$userId","handle":"ed","display_name":null,"blocked_at":"2026-10-18T12:00:00+00:00"}]"""
                path.endsWith("my_hidden_drops") ->
                    """[{"drop_id":"$dropId","short_id":"abc","title":"T","category_slug":"note","hidden_at":"2026-10-18 12:00:00+00"}]"""
                path.endsWith("public_profile") ->
                    """[{"id":"$userId","handle":"ed","display_name":"Ed","created_at":"2026-10-01T00:00:00Z"}]"""
                path.endsWith("moderation_queue") ->
                    """[{"kind":"drop","target_id":"$dropId","short_id":"abc","title":"T","body":null,"category_slug":"safety_alert",
                        "status":"pending_review","sensitive":true,"subject_id":"$userId","subject_handle":"ed",
                        "subject_suspended_until":null,"open_reports":2,"reasons":["spam","hate"],"latest_details":"x",
                        "report_ids":["$dropId"],"queued_at":"2026-10-18T12:00:00Z"}]"""
                path.endsWith("export_my_data") -> """{"format":"xhere-export-v1","drops":[]}"""
                else -> "null"
            }
        }

        api.hidden.hideDrop(dropId)
        api.hidden.unhideDrop(dropId)
        assertEquals("abc", api.hidden.myHiddenDrops().single().shortId)
        assertEquals("ed", api.blocks.myBlocks().single().handle)
        assertEquals("Ed", api.profiles.publicProfile("ed")?.displayName)
        val item: ModerationQueueItem = api.moderation.queue(25).single()
        assertEquals(listOf(ReportReason.SPAM, ReportReason.HATE), item.reasons)
        assertTrue(item.isPending && !item.isSubjectSuspended(Instant.parse("2026-10-18T12:00:00Z")))
        api.moderation.suspendUser(userId, null, "appeal")
        val export = api.account.exportMyData()
        assertTrue(export.contains("\"format\": \"xhere-export-v1\""), export)

        val byName = captured.calls.associate { it.first.substringAfterLast('/') to it.second }
        assertTrue(byName.getValue("hide_drop").contains("\"p_drop_id\":\"$dropId\""))
        assertTrue(byName.getValue("unhide_drop").contains("\"p_drop_id\""))
        assertTrue(byName.getValue("public_profile").contains("\"p_handle\":\"ed\""))
        assertTrue(byName.getValue("moderation_queue").contains("\"p_limit\":25"))
        assertTrue(byName.getValue("suspend_user").contains("\"p_until\":null"), byName.getValue("suspend_user"))
    }
}
