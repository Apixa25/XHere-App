package world.xhere.shared.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.xhere.shared.data.LiveRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.Fixtures
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.rules.CategoryRules
import world.xhere.shared.rules.CreateDropRequest

class LiveConditionsTest {
    @Test
    fun nwsParserReadsOfficialAlertsAndIgnoresJunk() {
        val alerts = NwsAlerts.parse(
            """
            {"features":[
              {"properties":{
                "id":"severe-1",
                "event":"Severe Thunderstorm Warning",
                "severity":"Severe",
                "expires":"2026-10-18T18:30:00Z",
                "headline":"Storm",
                "senderName":"NWS Buffalo",
                "@id":"https://api.weather.gov/alerts/severe-1"
              }},
              {"properties":{"id":"extreme-1","event":"Tornado Warning","severity":"Extreme","expires":"2026-10-18T17:00:00Z","senderName":"NWS Buffalo"}},
              {"properties":{"event":"Missing id"}},
              {"not":"a feature"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("extreme-1", "severe-1"), alerts.map { it.id })
        assertEquals(AlertSeverity.EXTREME, alerts[0].severity)
        assertEquals("National Weather Service", alerts[0].attribution)
        assertEquals("NWS Buffalo", alerts[1].senderName)
        assertEquals("2026-10-18 18:30 UTC", NwsAlerts.formatExpiry(alerts[1].expiresAt!!))
        assertEquals("https://api.weather.gov/alerts/severe-1", alerts[1].sourceUrl)
        assertTrue(NwsAlerts.parse("not json").isEmpty())
    }

    @Test
    fun coverageIsTheUnitedStatesAndNotEurope() {
        assertTrue(NwsCoverage.covers(LatLng(42.09, -79.24)))
        assertTrue(NwsCoverage.covers(LatLng(61.2, -149.9)))
        assertTrue(NwsCoverage.covers(LatLng(21.3, -157.8)))
        assertFalse(NwsCoverage.covers(LatLng(48.85, 2.35)))
        assertFalse(NwsCoverage.covers(LatLng(51.5, -0.12)))
        assertTrue(NwsCoverage.covers(LatLng(43.65, -79.38)))
    }

    @Test
    fun catalogMatchesThePilotBoxAndNoneTurnsAlertsOff() = runTest {
        val jamestown = LiveRegionCatalog.containing(LatLng(42.09, -79.24))
        assertEquals("us-ny-jamestown", jamestown?.id)
        assertEquals("nws", jamestown?.weatherProvider)
        assertNull(LiveRegionCatalog.containing(LatLng(48.85, 2.35)))

        val provider = CountingProvider()
        val none = CachingLiveConditions(
            live = FixedLive(LiveRegion(id = "quiet", country = "US", weatherProvider = "none")),
            providers = listOf(provider),
        )
        assertTrue(none.alertsAt(LatLng(42.09, -79.24)).isEmpty())
        assertEquals(0, provider.calls)

        val fallback = CachingLiveConditions(live = FixedLive(null), providers = listOf(provider))
        val alert = sampleAlert("one")
        provider.result = WeatherFetch(listOf(alert), 30)
        assertEquals(listOf(alert), fallback.alertsAt(LatLng(42.09, -79.24)))
        assertEquals(1, provider.calls)
        assertTrue(fallback.alertsAt(LatLng(48.85, 2.35)).isEmpty())
        assertEquals(1, provider.calls)
    }

    @Test
    fun cacheHonorsMaxAgeAndKeepsAnEmptyResultForAnHour() = runTest {
        var now = Instant.parse("2026-10-18T12:00:00Z")
        val provider = CountingProvider()
        provider.result = WeatherFetch(listOf(sampleAlert("storm")), 30)
        val live = CachingLiveConditions(live = FixedLive(null), providers = listOf(provider), now = { now })
        val point = LatLng(42.095, -79.242)
        assertEquals("storm", live.alertsAt(point).single().id)
        now += 20.seconds
        assertEquals("storm", live.alertsAt(point).single().id)
        assertEquals(1, provider.calls)
        now += 11.seconds
        provider.result = WeatherFetch(emptyList(), 5)
        assertTrue(live.alertsAt(point).isEmpty())
        assertEquals(2, provider.calls)
        now += 30.minutes
        assertTrue(live.alertsAt(point).isEmpty())
        assertEquals(2, provider.calls)
        now += 31.minutes
        provider.result = WeatherFetch(listOf(sampleAlert("later")), null)
        assertEquals("later", live.alertsAt(point).single().id)
        assertEquals(3, provider.calls)
    }

    @Test
    fun rightNowAcceptsOnlyTheThreeWindows() {
        val taxonomy = JamestownDemo.store().taxonomy
        val category = taxonomy.category("right_now")!!
        val filters = taxonomy.filters
        fun check(expires: Instant?) = CategoryRules.check(
            Fixtures.draft(
                category = "right_now",
                title = "Line",
                attributes = buildJsonObject { put("kind", "line") },
                expiresAt = expires,
            ),
            category,
            Fixtures.context(),
            filters,
        )
        assertTrue(check(Fixtures.now + 1.hours).isValid)
        assertTrue(check(Fixtures.now + 4.hours).isValid)
        assertTrue(check(Fixtures.now + 24.hours).isValid)
        assertEquals(Fixtures.now + 4.hours, check(null).expiresAt)
        assertTrue(check(null).isValid)
        assertEquals("invalid_right_now_ttl", check(Fixtures.now + 2.hours).violations.first().code)
        assertEquals("invalid_right_now_ttl", check(Fixtures.now + 30.minutes).violations.first().code)
        assertEquals("expires_too_far", check(Fixtures.now + 48.hours).violations.first().code)
    }

    @Test
    fun anExpiredRightNowDropLeavesTheMapAndTheHereCard() = runTest {
        val api = InMemoryXHere(JamestownDemo.store(), Fixtures.profile(1), clock = { Fixtures.now })
        val created = api.drops.createDrop(
            CreateDropRequest(
                categorySlug = "right_now",
                latitude = 40.0,
                longitude = -100.0,
                title = "Line",
                body = null,
                attributes = buildJsonObject { put("kind", "line") },
                tags = emptyList(),
                revealRadiusM = null,
                expiresAt = Fixtures.now + 1.hours,
                displayAnonymous = false,
                media = emptyList(),
                acknowledgeGuidelines = false,
            ),
        )
        val spot = LatLng(40.0, -100.0)
        assertTrue(api.drops.hereCard(spot, spot).any { it.id == created.id })
        api.store.drops[created.id]!!.expiresAt = Fixtures.now - 1.minutes
        assertFalse(api.drops.hereCard(spot, spot).any { it.id == created.id })
        val box = BoundingBox(-100.01, 39.99, -99.99, 40.01)
        assertFalse(api.drops.dropsInView(box, null, 16).any { it.dropId == created.id })
    }

    @Test
    fun theRepositoryRejectsARightNowLifetimeTheServerWouldReject() = runTest {
        val api = InMemoryXHere(JamestownDemo.store(), Fixtures.profile(1), clock = { Fixtures.now })
        val error = runCatching {
            api.drops.createDrop(
                CreateDropRequest(
                    categorySlug = "right_now",
                    latitude = 40.0,
                    longitude = -100.0,
                    title = "Two",
                    body = null,
                    attributes = buildJsonObject { put("kind", "line") },
                    tags = emptyList(),
                    revealRadiusM = null,
                    expiresAt = Fixtures.now + 2.hours,
                    displayAnonymous = false,
                    media = emptyList(),
                    acknowledgeGuidelines = false,
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is XHereApiException)
        assertEquals("invalid_right_now_ttl", error.code)
    }

    private fun sampleAlert(id: String) = WeatherAlert(
        id = id,
        event = "Severe Thunderstorm Warning",
        severity = AlertSeverity.SEVERE,
        expiresAt = Instant.parse("2026-10-18T18:30:00Z"),
        headline = null,
        attribution = "National Weather Service",
        senderName = "NWS Buffalo",
        sourceUrl = null,
    )

    private class FixedLive(private val region: LiveRegion?) : LiveRepository {
        override suspend fun regionAt(point: LatLng): LiveRegion? = region
    }

    private class CountingProvider : WeatherAlertProvider {
        override val id: String = "nws"
        var calls: Int = 0
        var result: WeatherFetch = WeatherFetch(emptyList(), 30)
        override fun covers(point: LatLng): Boolean = NwsCoverage.covers(point)
        override suspend fun fetchActive(point: LatLng): WeatherFetch {
            calls += 1
            return result
        }
    }
}
