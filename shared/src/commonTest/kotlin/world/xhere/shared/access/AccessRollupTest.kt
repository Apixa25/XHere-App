package world.xhere.shared.access

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.geo.LatLng

class AccessRollupTest {
    private val earlier = Instant.parse("2026-10-01T12:00:00Z")
    private val later = Instant.parse("2026-10-18T12:00:00Z")

    @Test
    fun majorityIgnoresUnknownAndATieIsNotAMajority() {
        val yes = AccessRollup.field(listOf(AccessAnswer.YES, AccessAnswer.YES, AccessAnswer.NO, AccessAnswer.UNKNOWN))
        assertEquals(AccessAnswer.YES, yes.answer)
        assertEquals(2.0 / 3.0, yes.confidence)

        val tie = AccessRollup.field(listOf(AccessAnswer.YES, AccessAnswer.NO))
        assertEquals(AccessAnswer.UNKNOWN, tie.answer)
        assertEquals(0.5, tie.confidence)

        val empty = AccessRollup.field(listOf(AccessAnswer.UNKNOWN, AccessAnswer.UNKNOWN))
        assertEquals(AccessAnswer.UNKNOWN, empty.answer)
        assertEquals(0.0, empty.confidence)
    }

    @Test
    fun threeChecksRollUpToAColoredStatus() {
        val summary = AccessRollup.summarize(
            placeKey = "drop:${Uuid.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")}",
            votes = listOf(
                AccessVote(AccessAnswer.YES, AccessAnswer.YES, AccessAnswer.NO, earlier),
                AccessVote(AccessAnswer.YES, AccessAnswer.YES, AccessAnswer.YES, later),
                AccessVote(AccessAnswer.YES, AccessAnswer.UNKNOWN, AccessAnswer.NO, earlier),
            ),
            latitude = 42.097,
            longitude = -79.244,
            title = "Library",
        )
        assertEquals(AccessAnswer.YES, summary.stepFree)
        assertEquals(AccessAnswer.YES, summary.ramp)
        assertEquals(AccessAnswer.NO, summary.restroom)
        assertEquals(AccessStatus.PARTIAL, summary.status)
        assertEquals("#C47B0A", AccessRollup.colorHex(summary.status))
        assertEquals(3, summary.sampleSize)
        assertEquals(later, summary.lastConfirmedAt)
        assertTrue(summary.confidence > 0.6)
    }

    @Test
    fun stepFreeYesWithNoNosIsGreenAndANoEntranceIsRed() {
        assertEquals(
            AccessStatus.ACCESSIBLE,
            AccessRollup.status(AccessAnswer.YES, AccessAnswer.UNKNOWN, AccessAnswer.UNKNOWN),
        )
        assertEquals("#1B7F4E", AccessRollup.colorHex(AccessStatus.ACCESSIBLE))
        assertEquals(
            AccessStatus.NOT_ACCESSIBLE,
            AccessRollup.status(AccessAnswer.NO, AccessAnswer.NO, AccessAnswer.UNKNOWN),
        )
        assertEquals(
            AccessStatus.PARTIAL,
            AccessRollup.status(AccessAnswer.NO, AccessAnswer.UNKNOWN, AccessAnswer.YES),
        )
        assertEquals("#B3261E", AccessRollup.colorHex(AccessStatus.NOT_ACCESSIBLE))
        assertEquals(AccessStatus.UNKNOWN, AccessRollup.status(AccessAnswer.UNKNOWN, AccessAnswer.UNKNOWN, AccessAnswer.UNKNOWN))
        assertEquals("#5F6B66", AccessRollup.colorHex(AccessStatus.UNKNOWN))
    }

    @Test
    fun limitedOsmTagDoesNotCountAsFullyAccessible() {
        val tags = mapOf("wheelchair" to "limited", "ramp" to "yes", "toilets:wheelchair" to "yes")
        val summary = OsmAccess.summary("osm:node/1", tags, 42.1, -79.2, "Cafe")
        assertEquals(AccessStatus.PARTIAL, summary?.status)
        assertEquals(AccessRollup.OSM, summary?.attribution)
        assertEquals(true, summary?.limited)
        assertNull(OsmAccess.vote(mapOf("name" to "Cafe")))
    }

    @Test
    fun placeKeysStayStable() {
        val id = Uuid.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        assertEquals("drop:$id", AccessPlaces.drop(id))
        assertEquals("cell:42.097:-79.244", AccessPlaces.cell(LatLng(42.0966, -79.2436)))
    }
}
