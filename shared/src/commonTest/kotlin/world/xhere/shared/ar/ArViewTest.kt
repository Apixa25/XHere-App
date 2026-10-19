package world.xhere.shared.ar

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus

/**
 * Ranking, privacy, and projection for the AR camera. No ARCore.
 */
class ArViewTest {
    private val now: Instant = Instant.parse("2026-10-18T04:00:00Z")
    private val viewer = LatLng(0.0, 0.0)
    private val fine = LocationSignal(accuracyM = 10.0, fixAt = now, deviceAt = now)

    @Test
    fun unusablePoseDoesNotBecomeADeviceFix() {
        val pose = ArPose(42.0, -79.0, 180.0, 15.0, horizontalAccuracyM = 8.0)
        val signal = ArView.composerSignal(pose, now, developerOptions = true, mock = false)
        assertNotNull(signal)
        assertTrue(signal.deviceFix)
        assertEquals(8.0, signal.accuracyM)
        assertEquals(now, signal.fixAt)
        assertEquals(now, signal.deviceAt)
        assertTrue(signal.developerOptions)
        assertEquals(false, signal.mock)
        assertEquals("AR · ±8 m", ArView.accuracyPhrase(8.4))
        assertNull(ArView.composerSignal(pose.copy(latitude = 120.0), now))
        assertNull(ArView.composerSignal(pose.copy(horizontalAccuracyM = -1.0), now))
        assertTrue(!ArView.poseUsable(pose.copy(headingDegrees = Double.NaN)))
    }

    @Test
    fun nearestTwentyFiveInsideRangeAndLockedBodiesStayHidden() {
        val cards = buildList {
            add(card("removed", 0.00001, status = DropStatus.REMOVED, body = "gone"))
            add(card("expired", 0.00002, status = DropStatus.EXPIRED, body = "gone"))
            add(card("invalid", 0.00001, lng = 200.0, body = "off the map"))
            repeat(30) { index ->
                add(card("n%02d".format(index), latitude = index * 0.00004, body = "note $index"))
            }
        }
        val labels = ArView.select(cards, viewer, fine, now, mapOf("note" to "#112233"))
        assertEquals(25, labels.size)
        assertEquals("n00", labels.first().shortId)
        assertEquals("n24", labels.last().shortId)
        assertTrue(labels.none { it.shortId == "removed" || it.shortId == "expired" || it.shortId == "invalid" || it.shortId == "n25" })
        assertTrue(labels.first().scale >= labels.last().scale)
        assertTrue(labels.first().alpha >= labels.last().alpha)
        assertTrue(labels.all { it.colorHex == "#112233" })
        assertTrue(labels.all { it.body != null })
    }

    @Test
    fun revealAndIntegrityDecideWhetherABodyFloats() {
        val locked = card("locked", 0.0001, revealed = false, body = "secret tables")
        val withheld = card("coarse", 0.0001, revealed = true, body = "the lunch special", locationWithheld = true)
        val user = card("user", 0.0001, revealed = true, body = "the lunch special")
        val record = card("record", 0.0002, revealed = true, body = "Public library building.", source = DropSource.OPEN_DATA)
        val mine = card("mine", 0.0003, revealed = true, body = "my own note", isMine = true)
        val coarse = LocationSignal(accuracyM = 800.0, fixAt = now, deviceAt = now)
        val mocked = LocationSignal(mock = true, accuracyM = 10.0, fixAt = now, deviceAt = now)

        assertNull(ArView.visibleBody(locked, fine, viewer, now))
        assertNull(ArView.visibleBody(withheld, fine, viewer, now))
        assertNull(ArView.visibleBody(user, coarse, viewer, now))
        assertNull(ArView.visibleBody(user, mocked, viewer, now))
        assertNull(ArView.visibleBody(user, signal = null, viewer, now))
        assertEquals("Public library building.", ArView.visibleBody(record, coarse, viewer, now))
        assertEquals("my own note", ArView.visibleBody(mine, mocked, viewer, now))

        val hidden = ArView.select(listOf(user), viewer, coarse, now).single()
        assertNull(hidden.body)
        assertTrue(hidden.withheld)
        val shown = ArView.select(listOf(record), viewer, coarse, now).single()
        assertEquals("Public library building.", shown.body)
        assertTrue(shown.publicRecord)
        assertTrue(!shown.withheld)
    }

    @Test
    fun eastNorthUpWrapsLongitudeAndPitchMovesTheHorizonDown() {
        val north = ArView.enu(42.0, -79.0, 0.0, 42.001, -79.0, 0.0)
        assertTrue(north.north > 100.0)
        assertTrue(abs(north.east) < 1.0)
        val east = ArView.enu(42.0, -79.0, 10.0, 42.0, -78.999, 12.0)
        assertTrue(east.east > 0.0)
        assertEquals(2.0, east.up, 0.001)
        val wrap = ArView.enu(0.0, 179.0, 0.0, 0.0, -179.0, 0.0)
        assertTrue(wrap.east > 0.0)

        val facingEast = ArPose(0.0, 0.0, 0.0, headingDegrees = 90.0, horizontalAccuracyM = 5.0)
        val left = ArView.cameraSpace(facingEast, 0.001, 0.0, 0.0)
        assertTrue(left.right < 0.0)

        val facingNorth = ArPose(0.0, 0.0, 0.0, headingDegrees = 0.0, horizontalAccuracyM = 5.0)
        assertNull(ArView.project(facingNorth, -0.001, 0.0, 0.0, 200.0, 200.0, 60.0))
        val level = ArView.project(facingNorth, 0.001, 0.0, 0.0, 200.0, 200.0, 60.0)
        val raised = ArView.project(facingNorth.copy(pitchDegrees = 20.0), 0.001, 0.0, 0.0, 200.0, 200.0, 60.0)
        assertNotNull(level)
        assertNotNull(raised)
        assertEquals(100.0, level.x, 1.0)
        assertTrue(raised.y > level.y)
    }

    @Test
    fun viewProjectionDropsPointsBehindTheCamera() {
        val view = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val projection = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[11] = -1f }
        val center = ArView.projectView(view, projection, 0f, 0f, -2f, 200f, 100f)
        val right = ArView.projectView(view, projection, 2f, 0f, -2f, 200f, 100f)
        assertNotNull(center)
        assertEquals(100.0, center.x, 0.01)
        assertEquals(50.0, center.y, 0.01)
        assertNotNull(right)
        assertEquals(200.0, right.x, 0.01)
        assertNull(ArView.projectView(view, projection, 0f, 0f, 2f, 200f, 100f))
    }

    private fun card(
        shortId: String,
        latitude: Double,
        lng: Double = 0.0,
        revealed: Boolean = true,
        body: String? = "body",
        source: DropSource = DropSource.USER,
        isMine: Boolean = false,
        status: DropStatus = DropStatus.ACTIVE,
        locationWithheld: Boolean = false,
    ) = DropCard(
        id = Uuid.random(),
        shortId = shortId,
        categorySlug = "note",
        groupSlug = "notes",
        title = shortId,
        latitude = latitude,
        longitude = lng,
        locationPrecisionM = 0,
        revealRadiusM = 150,
        revealed = revealed,
        body = body,
        status = status,
        isConfirmed = false,
        confirmCount = 0,
        denyCount = 0,
        createdAt = now,
        isMine = isMine,
        source = source,
        locationWithheld = locationWithheld,
    )
}
