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
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.xhere.shared.Fixtures
import world.xhere.shared.SupabaseConfig
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.data.remote.CreateDropParams
import world.xhere.shared.data.remote.toParams
import world.xhere.shared.data.supabase.SupabaseXHereClient
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CategoryRules

class InMemoryXHereTest {
    @Test
    fun bodyStaysHiddenUntilYouAreInsideTheRevealRadius() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        val created = author.drops.createDrop(CategoryRules.check(Fixtures.draft(), Fixtures.note, Fixtures.context(), emptyList()).toRequest(Fixtures.draft()))
        assertEquals(DropStatus.ACTIVE, created.status)
        assertEquals(Fixtures.note.defaultRevealM, created.revealRadiusM)

        val stranger = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        val far = stranger.drops.dropsNearby(LatLng(40.72, -74.0060), radiusM = 5_000)
        assertEquals(1, far.size)
        assertTrue(!far.first().revealed)
        assertNull(far.first().body)
        assertNull(far.first().tags)
        val farJson = XHereJson.encodeToString(world.xhere.shared.model.DropCard.serializer(), far.first())
        assertTrue(!farJson.contains("Something worth knowing"), farJson)
        assertTrue(!farJson.contains("local"), farJson)

        val near = stranger.drops.dropDetail(created.id, created.displayLocation)
        assertTrue(near.revealed)
        assertEquals("Something worth knowing.", near.body)
        assertEquals(listOf("local"), near.tags)

        val teaser = stranger.drops.dropTeaser(created.shortId)
        assertTrue(!teaser.revealed)
        assertNull(teaser.body)
        assertNull(teaser.tags)
    }

    @Test
    fun blocksHideDropsInBothDirectionsAndReportsAutoHide() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        val created = author.drops.createDrop(validNote())
        val other = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        other.blocks.blockUser(Fixtures.id(1))
        assertEquals(emptyList(), other.drops.dropsNearby(created.displayLocation, radiusM = 100))
        assertEquals(emptyList(), author.drops.dropsNearby(created.displayLocation, radiusM = 100).filter { !it.isMine })

        other.blocks.unblockUser(Fixtures.id(1))
        assertEquals(1, other.drops.dropsNearby(created.displayLocation, radiusM = 100).size)

        val first = other.reports.report(ReportTarget.DROP, created.id, ReportReason.SPAM, "dup")
        val again = other.reports.report(ReportTarget.DROP, created.id, ReportReason.SPAM, "dup")
        assertEquals(first, again)
    }

    @Test
    fun confirmFromTooFarIsRejectedAndTwoStillTrueVotesConfirmANote() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        val created = author.drops.createDrop(validNote())
        val own = assertFailsWith<XHereApiException> {
            author.drops.confirmDrop(created.id, ConfirmationVerdict.STILL_TRUE, created.displayLocation)
        }
        assertEquals("cannot_confirm_own_drop", own.code)

        val witness = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        val away = LatLng(created.displayLocation.latitude + 0.01, created.displayLocation.longitude)
        val tooFar = assertFailsWith<XHereApiException> {
            witness.drops.confirmDrop(created.id, ConfirmationVerdict.STILL_TRUE, away)
        }
        assertEquals("too_far", tooFar.code)

        val first = witness.drops.confirmDrop(created.id, ConfirmationVerdict.STILL_TRUE, created.displayLocation)
        assertEquals(DropStatus.ACTIVE, first.status)
        val second = InMemoryXHere(store, Fixtures.profile(3), clock = { Fixtures.now })
        val confirmed = second.drops.confirmDrop(created.id, ConfirmationVerdict.STILL_TRUE, created.displayLocation)
        assertEquals(DropStatus.CONFIRMED, confirmed.status)
        assertNull(confirmed.expiresAt)
    }

    @Test
    fun viewportClustersBelowTeaserZoomAndRejectsABadBox() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        author.drops.createDrop(validNote())
        author.drops.createDrop(validNote(title = "Second note"))
        val box = Geo.boundingBox(LatLng(40.7128, -74.0060), 2_000.0)
        val teasers = author.drops.dropsInView(box, zoom = 16)
        assertTrue(teasers.all { it.kind == "drop" })
        val clusters = author.drops.dropsInView(box, zoom = 10)
        assertEquals(1, clusters.size)
        assertEquals("cluster", clusters.first().kind)
        assertEquals(2, clusters.first().pointCount)

        val bad = assertFailsWith<XHereApiException> {
            author.drops.dropsInView(box.copy(maxLongitude = box.minLongitude))
        }
        assertEquals("invalid_bbox", bad.code)
    }

    @Test
    fun deleteAccountSignsOutAndAModeratorCanRemove() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        author.auth.signInWithEmailOtp("ada@example.com")
        val session = author.auth.verifyEmailOtp("ada@example.com", "123456")
        assertEquals(Fixtures.id(1), session.userId)
        val created = author.drops.createDrop(validNote())
        author.account.deleteMyAccount()
        assertNull(author.auth.currentSession())

        val mod = InMemoryXHere(
            store,
            Fixtures.profile(9, role = AppRole.MODERATOR),
            clock = { Fixtures.now },
        )
        assertEquals(DropStatus.REMOVED, mod.moderation.moderateDrop(created.id, world.xhere.shared.model.ModerateDropAction.REMOVE, "spam"))
        val denied = assertFailsWith<XHereApiException> {
            author.moderation.moderateDrop(created.id, world.xhere.shared.model.ModerateDropAction.RESTORE, null)
        }
        assertEquals("not_moderator", denied.code)
    }

    private fun validNote(title: String = "A note about here") =
        CategoryRules.check(
            Fixtures.draft(title = title),
            Fixtures.note,
            Fixtures.context(),
            emptyList(),
        ).toRequest(Fixtures.draft(title = title))
}

class SupabaseContractTest {
    @Test
    fun createDropParamsUseTheSqlArgumentNames() {
        val request = CategoryRules.check(Fixtures.draft(), Fixtures.note, Fixtures.context(), emptyList()).toRequest(Fixtures.draft())
        val encoded = XHereJson.encodeToString(CreateDropParams.serializer(), request.toParams())
        assertTrue(encoded.contains("\"p_category\":\"note\""))
        assertTrue(encoded.contains("\"p_lat\""))
        assertTrue(encoded.contains("\"p_acknowledge_guidelines\""))
        assertTrue(!encoded.contains("\"categorySlug\""))
    }

    @Test
    fun nearbyPostsToTheRpcAndMapsAValidationError() = runTest {
        var path = ""
        var body = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            body = when (val content = request.body) {
                is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                else -> ""
            }
            if (request.url.encodedPath.endsWith("create_drop")) {
                respond(
                    content = ByteReadChannel(
                        """{"code":"22023","message":"invalid_title","details":"title must be 1..120 characters","hint":null}""",
                    ),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = ByteReadChannel("[]"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = SupabaseXHereClient(
            SupabaseConfig(url = "https://example.supabase.co", anonKey = "test-anon-key"),
            engine = engine,
        )
        val cards = client.drops.dropsNearby(LatLng(40.7, -74.0), radiusM = 500, categories = listOf("note"))
        assertEquals(emptyList(), cards)
        assertTrue(path.endsWith("/rest/v1/rpc/drops_nearby"), path)
        assertTrue(body.contains("\"p_lat\":40.7"), body)
        assertTrue(body.contains("\"p_radius_m\":500"), body)
        assertTrue(body.contains("\"p_categories\":[\"note\"]"), body)

        val error = assertFailsWith<XHereApiException> {
            client.drops.createDrop(
                CategoryRules.check(Fixtures.draft(), Fixtures.note, Fixtures.context(), emptyList()).toRequest(Fixtures.draft()),
            )
        }
        assertEquals("invalid_title", error.code)
        assertEquals("22023", error.sqlState)
        assertEquals("title must be 1..120 characters", error.detail)
    }

    @Test
    fun placeholderConfigIsNotConfigured() {
        assertTrue(!SupabaseConfig.PLACEHOLDER.isConfigured)
        assertTrue(SupabaseConfig("https://abc.supabase.co", "publishable-key").isConfigured)
    }
}

class ReadModelTest {
    @Test
    fun dropCardRoundTripsAndHidesNothingTheServerMarkedRevealed() {
        val raw = """
            {
              "id": "00000000-0000-0000-0000-000000000001",
              "short_id": "abcdefghijkl",
              "category_slug": "note",
              "group_slug": "note",
              "title": "Here",
              "lat": 40.7,
              "lng": -74.0,
              "location_precision_m": 0,
              "distance_m": 12.5,
              "reveal_radius_m": 100,
              "revealed": false,
              "body": null,
              "attributes": null,
              "media": null,
              "tags": null,
              "status": "active",
              "is_confirmed": false,
              "confirm_count": 0,
              "deny_count": 0,
              "expires_at": "2026-10-25T04:00:00+00:00",
              "created_at": "2026-10-18T04:00:00Z",
              "is_mine": false
            }
        """.trimIndent()
        val card = XHereJson.decodeFromString(world.xhere.shared.model.DropCard.serializer(), raw)
        assertEquals(false, card.revealed)
        assertNull(card.body)
        assertNull(card.tags)
        assertEquals(12.5, card.distanceM)
        val drop = card.toDrop(categoryId = Uuid.parse("00000000-0000-0000-0000-000000000099"), authorId = null)
        assertEquals("note", drop.categorySlug)
        assertTrue(!drop.revealed)
    }
}
