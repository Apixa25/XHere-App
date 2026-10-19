package world.xhere.shared.here

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import world.xhere.shared.Fixtures
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.data.fake.StoredPlaceLabel
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.PlaceLabel
import world.xhere.shared.rules.CategoryRules

/**
 * Here Card (research §4.1 #3). Distance is from the tap. Reveal still uses
 * the viewer's own location, and a public record stays readable either way.
 */
class HereCardTest {
    @Test
    fun tapDistanceDoesNotRevealAUserDrop() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        val created = author.drops.createDrop(noteAt(40.0, -100.0, "secret tables at the mill"))
        val drop = store.drops.getValue(created.id)
        drop.viewCount = 4
        drop.confirmCount = 2

        val tap = LatLng(40.0 + (50.0 / 111_320.0), -100.0)
        val bob = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        val locked = bob.drops.hereCard(tap, viewer = null).single()
        assertEquals(created.shortId, locked.shortId)
        assertEquals(false, locked.revealed)
        assertNull(locked.body)
        assertNull(locked.tags)
        val encoded = XHereJson.encodeToString(DropCard.serializer(), locked)
        assertTrue(!encoded.contains("secret tables"), encoded)
        val walked = Geo.distanceMeters(tap, drop.displayLocation)
        val lockedDistance = checkNotNull(locked.distanceM)
        assertEquals(walked, lockedDistance, 0.2)
        assertTrue(lockedDistance > 40.0)
        assertEquals(4, locked.viewCount)
        assertEquals(2, locked.confirmCount)
        assertEquals(4, drop.viewCount)

        val standing = bob.drops.hereCard(tap, viewer = drop.displayLocation).single()
        assertEquals(true, standing.revealed)
        assertEquals("secret tables at the mill", standing.body)
        assertEquals(listOf("local"), standing.tags)
        assertEquals(walked, standing.distanceM ?: -1.0, 0.2)
        assertEquals(4, drop.viewCount)
    }

    @Test
    fun jamestownCardRespectsTheShortWalkAndTheRevealRadius() = runTest {
        val store = JamestownDemo.store()
        val bob = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        val downtown = bob.drops.hereCard(JamestownDemo.center, viewer = null)
        assertEquals(listOf("lunch", "truck", "concert"), downtown.map { it.shortId })
        assertTrue(downtown.all { !it.revealed && it.body == null })
        assertEquals(
            listOf("eatery_tip", "food_truck", "event"),
            Here.groups(downtown).map { it.categorySlug },
        )

        val library = bob.drops.hereCard(JamestownDemo.library, viewer = LatLng(0.0, 0.0))
        assertEquals(listOf("records", "fenton"), library.map { it.shortId })
        val records = library.first()
        assertEquals(true, records.revealed)
        assertTrue(records.isPublicRecord())
        assertEquals("Public library building. Open the source for the register entry.", records.body)
        assertEquals("National Register of Historic Places", records.sourceName)
        assertTrue(records.attribution!!.contains("National Park Service"))
        val fenton = library[1]
        assertEquals(false, fenton.revealed)
        assertNull(fenton.body)

        val standingThere = bob.drops.hereCard(JamestownDemo.library, viewer = JamestownDemo.library)
        assertNull(standingThere.first { it.shortId == "fenton" }.body)
        assertEquals(true, standingThere.first { it.shortId == "records" }.revealed)
    }

    @Test
    fun hiddenBlockedAndPendingDropsStayOffTheCard() = runTest {
        val store = JamestownDemo.store()
        val lunch = store.drops.values.first { it.shortId == "lunch" }
        val bob = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        bob.hidden.hideDrop(lunch.id)
        assertTrue(bob.drops.hereCard(JamestownDemo.center, null).none { it.shortId == "lunch" })
        bob.hidden.unhideDrop(lunch.id)
        assertTrue(bob.drops.hereCard(JamestownDemo.center, null).any { it.shortId == "lunch" })

        bob.blocks.blockUser(JamestownDemo.guide.id)
        val blocked = bob.drops.hereCard(JamestownDemo.center, null).map { it.shortId }
        assertTrue("lunch" !in blocked)
        assertTrue("truck" !in blocked)
        bob.blocks.unblockUser(JamestownDemo.guide.id)

        lunch.status = DropStatus.PENDING_REVIEW
        assertTrue(bob.drops.hereCard(JamestownDemo.center, JamestownDemo.center).none { it.shortId == "lunch" })
        val guide = InMemoryXHere(store, JamestownDemo.guide, clock = { Fixtures.now })
        val own = guide.drops.hereCard(JamestownDemo.center, null).first { it.shortId == "lunch" }
        assertEquals(true, own.isMine)
        assertEquals(true, own.revealed)
        assertTrue(own.body!!.contains("outdoor tables"))
    }

    @Test
    fun openDataStaysRevealedWhenTheViewerIsFarAway() = runTest {
        val store = JamestownDemo.store()
        val bob = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        val card = bob.drops.hereCard(JamestownDemo.library, viewer = LatLng(0.0, 0.0))
            .first { it.shortId == "records" }
        assertEquals(DropSource.OPEN_DATA, card.source)
        assertEquals(true, card.revealed)
        assertEquals(false, card.isMine)
        assertTrue(card.distanceM!! < 1.0)
    }

    @Test
    fun badRadiusAndLocationAreRejected() = runTest {
        val api = InMemoryXHere(JamestownDemo.store(), Fixtures.profile(2), clock = { Fixtures.now })
        val radius = assertFailsWith<XHereApiException> {
            api.drops.hereCard(JamestownDemo.center, null, radiusM = 10)
        }
        assertEquals("invalid_radius", radius.code)
        val spot = assertFailsWith<XHereApiException> {
            api.drops.hereCard(LatLng(95.0, 0.0), null)
        }
        assertEquals("invalid_location", spot.code)
        assertTrue(api.drops.hereCard(LatLng(-10.0, -10.0), null).isEmpty())
    }

    @Test
    fun cellsMatchPostgresRoundingAndTheCacheIsFirstWriteWins() = runTest {
        assertEquals(42.096, Here.cell(42.09636), 1e-9)
        assertEquals(-79.242, Here.cell(-79.24202), 1e-9)
        assertEquals(1.001, Here.cell(1.0005), 1e-9)
        assertEquals(-1.001, Here.cell(-1.0005), 1e-9)
        assertEquals(Here.cell(51.5), Here.cell(51.5004), 1e-9)
        assertEquals(Here.cell(-0.12), Here.cell(-0.1204), 1e-9)
        assertEquals(51.501, Here.cell(51.5005), 1e-9)
        assertEquals("42.09050, -79.24202", Here.formatSpot(JamestownDemo.library))
        assertEquals(
            "West 3rd Street · 42.09050, -79.24202",
            Here.pinLabel("West 3rd Street", JamestownDemo.library),
        )

        val api = InMemoryXHere(MemoryStore(Fixtures.taxonomy), Fixtures.profile(1), clock = { Fixtures.now })
        assertNull(api.places.cachedLabel(LatLng(51.5, -0.12)))
        api.places.remember(LatLng(51.5, -0.12), "Westminster Bridge", "London")
        val hit = api.places.cachedLabel(LatLng(51.5004, -0.1204))
        assertEquals("Westminster Bridge", hit?.name)
        assertEquals("London", hit?.address)
        assertEquals(Here.ATTRIBUTION, hit?.attribution)
        assertEquals(Here.LICENSE, hit?.license)
        assertEquals(Here.PROVIDER, hit?.provider)
        assertNull(api.places.cachedLabel(LatLng(51.5005, -0.12)))
        api.places.remember(LatLng(51.5, -0.12), "Not the real name", "nope")
        assertEquals("Westminster Bridge", api.places.cachedLabel(LatLng(51.5, -0.12))?.name)

        val key = Here.cellKey(LatLng(51.5, -0.12))
        val aged = api.store.placeLabels.getValue(key)
        api.store.placeLabels[key] = StoredPlaceLabel(aged.name, aged.address, Fixtures.now - 91.days)
        api.places.remember(LatLng(51.5, -0.12), "Westminster Bridge Road", "London")
        assertEquals("Westminster Bridge Road", api.places.cachedLabel(LatLng(51.5, -0.12))?.name)

        val blank = assertFailsWith<XHereApiException> {
            api.places.remember(LatLng(10.0, 20.0), "   ", null)
        }
        assertEquals("invalid_place", blank.code)
        val markup = assertFailsWith<XHereApiException> {
            api.places.remember(LatLng(10.0, 20.0), "<script>alert(1)</script>", null)
        }
        assertEquals("invalid_place", markup.code)
        val link = assertFailsWith<XHereApiException> {
            api.places.remember(LatLng(10.0, 20.0), "See https://evil.example", null)
        }
        assertEquals("invalid_place", link.code)
        api.places.remember(LatLng(10.0, 20.0), "O'Brien Street", "São Paulo")
        assertEquals("O'Brien Street", api.places.cachedLabel(LatLng(10.0, 20.0))?.name)
        val signedOut = InMemoryXHere(api.store, viewer = null, clock = { Fixtures.now })
        assertEquals(
            "not_authenticated",
            assertFailsWith<XHereApiException> { signedOut.places.remember(LatLng(10.0, 20.0), "Somewhere", null) }.code,
        )
        assertEquals(
            "not_authenticated",
            assertFailsWith<XHereApiException> { signedOut.places.claimNominatimSlot() }.code,
        )
    }

    @Test
    fun nominatimIsCachedRateLimitedAndAttributed() = runTest {
        val api = InMemoryXHere(MemoryStore(Fixtures.taxonomy), Fixtures.profile(1), clock = { Fixtures.now })
        var fetches = 0
        var now = 1_000L
        val places = HerePlaces(
            places = api.places,
            reverse = {
                fetches += 1
                PlaceLabel(name = "Bridge", address = "London", attribution = "client", license = "nope", provider = "nope")
            },
            now = { now },
        )
        val first = places.describe(LatLng(51.5, -0.12))
        assertEquals("Bridge", first?.name)
        assertEquals(Here.ATTRIBUTION, first?.attribution)
        assertEquals(Here.LICENSE, first?.license)
        assertEquals(Here.PROVIDER, first?.provider)
        assertEquals(1, fetches)
        assertEquals("Bridge", places.describe(LatLng(51.5004, -0.1204))?.name)
        assertEquals(1, fetches)

        assertNull(places.describe(LatLng(48.8, 2.3)))
        assertEquals(1, fetches)
        now = 2_200L
        assertEquals("Bridge", places.describe(LatLng(48.8, 2.3))?.name)
        assertEquals(2, fetches)

        api.store.nominatimOpen = false
        now = 10_000L
        assertNull(places.describe(LatLng(35.0, 139.0)))
        assertEquals(2, fetches)
        api.store.nominatimOpen = true

        var failed = 0
        val flaky = HerePlaces(
            places = api.places,
            reverse = {
                failed += 1
                error("down")
            },
            now = { 50_000L },
        )
        assertNull(flaky.describe(LatLng(1.0, 2.0)))
        assertEquals(1, failed)
        assertNull(api.places.cachedLabel(LatLng(1.0, 2.0)))
    }

    @Test
    fun nominatimParseAndClientFollowTheUsagePolicy() = runTest {
        val named = Nominatim.parse(
            """{"name":"Westminster Bridge","display_name":"Westminster Bridge, London","address":{"road":"Westminster Bridge"}}""",
        )
        assertEquals("Westminster Bridge", named?.name)
        assertEquals("Westminster Bridge, London", named?.address)
        assertEquals(Here.ATTRIBUTION, named?.attribution)
        assertNull(Nominatim.parse("""{"error":"Unable to geocode"}"""))
        assertEquals(
            "Some Road",
            Nominatim.parse("""{"display_name":"Some Road, City","address":{"road":"Some Road"}}""")?.name,
        )
        assertEquals("Only Place", Nominatim.parse("""{"display_name":"Only Place, City"}""")?.name)

        val engine = MockEngine { request ->
            assertEquals(Here.USER_AGENT, request.headers[HttpHeaders.UserAgent])
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            assertEquals(Here.REFERER, request.headers[HttpHeaders.Referrer])
            assertTrue(request.url.toString().startsWith(Here.ENDPOINT), request.url.toString())
            assertEquals("51.5", request.url.parameters["lat"])
            assertEquals("-0.12", request.url.parameters["lon"])
            assertEquals("jsonv2", request.url.parameters["format"])
            respond(
                content = """{"name":"Bridge","display_name":"Bridge, London"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val label = NominatimClient(engine).reverse(LatLng(51.5, -0.12))
        assertEquals("Bridge", label.name)
        assertEquals(Here.ATTRIBUTION, label.attribution)
    }

    private fun noteAt(latitude: Double, longitude: Double, body: String) = CategoryRules.check(
        Fixtures.draft(latitude = latitude, longitude = longitude, body = body, title = "Mill"),
        Fixtures.note,
        Fixtures.context(),
        emptyList(),
    ).toRequest(Fixtures.draft(latitude = latitude, longitude = longitude, body = body, title = "Mill"))
}
