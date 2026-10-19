package world.xhere.shared.civic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.geo.LatLng

class CivicTest {
    private val now = Instant.parse("2026-10-19T18:00:00Z")
    private val hall = LatLng(42.091578, -79.255392)
    private val cityHall = LatLng(42.096704, -79.238842)
    private val downtown = LatLng(42.09636, -79.24202)

    @Test
    fun addressKeysTreatThirdAnd3rdAsTheSameStreet() {
        assertEquals(
            "200 east 3rd st jamestown",
            Civic.addressKey("200 East Third Street, Jamestown, NY 14701"),
        )
        assertEquals(
            Civic.addressKey("200 East 3rd Street, Jamestown, New York"),
            Civic.addressKey("200 E Third Street, Jamestown, NY 14701"),
        )
        assertEquals("hall ave jamestown", Civic.addressKey("Hall Avenue, Jamestown, NY"))
    }

    @Test
    fun aFollowedStreetMatchesALongerOfficialAddress() {
        assertTrue(
            Civic.addressMatches(
                "West Third Street, Jamestown",
                "10 West Third Street, Jamestown, NY 14701",
            ),
        )
        assertTrue(
            Civic.addressMatches(
                "200 East Third Street, Jamestown, NY 14701",
                "200 East 3rd Street, Jamestown, New York",
            ),
        )
        assertFalse(Civic.addressMatches("st", "200 East Third Street, Jamestown, NY"))
        assertFalse(Civic.addressMatches("Hall Avenue, Jamestown", "Palmer Street, Jamestown, NY"))
    }

    @Test
    fun cleanupDropsTagsAndDoesNotInventAShorterMeaning() {
        assertEquals(
            "Voting session at 7:30 p.m.",
            Civic.clean("  Voting   session\nat <b>7:30</b> p.m.  ", 80),
        )
        assertEquals("abcdefg…", Civic.clean("abcdefghij", 8))
        assertNull(Civic.clean("   ", 40))
    }

    @Test
    fun nearKeepsAHearingInside300MetersAndDropsTheNextStreetOver() {
        val hearing = item(
            type = CivicType.HEARING,
            title = "Zoning hearing",
            at = cityHall,
            occursOn = "2026-10-26",
        )
        val permit = item(
            type = CivicType.PERMIT,
            title = "Hall Avenue reconstruction",
            at = hall,
            occursOn = "2026-09-21",
        )
        val past = item(
            type = CivicType.AGENDA_ITEM,
            title = "Last month",
            at = cityHall,
            occursOn = "2026-09-01",
        )
        val near = Civic.near(listOf(hearing, permit, past), downtown, now)
        assertEquals(listOf("Zoning hearing"), near.map { it.title })
        assertTrue((near.single().distanceM ?: 999) <= Civic.NEAR_M)
    }

    @Test
    fun upcomingListsMeetingsInTheRegionAndSkipsAFinishedOne() {
        val voting = item(CivicType.AGENDA_ITEM, "Voting session", cityHall, "2026-10-26")
        val done = item(CivicType.AGENDA_ITEM, "September session", cityHall, "2026-09-28")
        val viaduct = item(CivicType.NOTICE, "Main Street Viaduct", null, "2026-10-05", region = "us-ny-jamestown")
        val otherTown = item(
            CivicType.AGENDA_ITEM,
            "Buffalo council",
            LatLng(42.886, -78.878),
            "2026-11-01",
            region = "us-ny-buffalo",
        )
        val rows = Civic.upcoming(listOf(voting, done, viaduct, otherTown), downtown, now)
        assertEquals(listOf("Main Street Viaduct", "Voting session"), rows.map { it.title })
    }

    @Test
    fun eachTypeHasItsOwnIcon() {
        assertEquals("📋", Civic.icon(CivicType.AGENDA_ITEM))
        assertEquals("📣", Civic.icon(CivicType.HEARING))
        assertEquals("🏗️", Civic.icon(CivicType.PERMIT))
        assertEquals("📌", Civic.icon(CivicType.NOTICE))
    }

    private fun item(
        type: CivicType,
        title: String,
        at: LatLng?,
        occursOn: String?,
        region: String = "us-ny-jamestown",
    ) = CivicItem(
        id = Uuid.parse("00000000-0000-4000-8000-0000000009${title.length.toString().padStart(2, '0')}"),
        regionId = region,
        itemType = type,
        title = title,
        summary = title,
        occursOn = occursOn,
        bodyName = "City Council",
        sourceUrl = "https://www.jamestownny.gov/city-council/meeting-schedule/",
        addressText = if (at == cityHall) "200 East Third Street, Jamestown, NY 14701" else "Hall Avenue, Jamestown, NY",
        latitude = at?.latitude,
        longitude = at?.longitude,
        attribution = "City of Jamestown",
        license = "Public record",
        expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
        sourceSlug = "jamestown-city-record",
    )
}
