package world.xhere.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationReasons
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.rules.CategoryRules
import world.xhere.shared.walks.StarterQuest

class LocationIntegrityRepositoryTest {
    @Test
    fun suspiciousActionsStayVisibleAndDoNotEarnCredit() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        var now = Fixtures.now
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { now })
        val other = InMemoryXHere(store, Fixtures.profile(2), clock = { now })

        val mocked = author.drops.createDrop(noteAt(40.0, -100.0, "Mocked bench").copy(signal = LocationSignal(mock = true)))
        assertEquals(DropStatus.ACTIVE, mocked.status)
        assertFalse(mocked.countsForCredit)
        assertEquals("reduced", mocked.locationTrust)
        assertTrue(LocationReasons.MOCK in mocked.reasons)
        assertEquals(0, progress(author, "create_drop"))
        assertTrue(store.integrityFlags.any { LocationReasons.MOCK in it.reasons })
        assertTrue(store.suspendedUntil.isEmpty())

        val options = author.drops.createDrop(noteAt(40.0, -100.0, "Dev bench").copy(signal = LocationSignal(developerOptions = true)))
        assertTrue(options.countsForCredit)
        assertEquals(1, progress(author, "create_drop"))

        val pinned = author.drops.createDrop(noteAt(40.2, -100.0, "Pinned bench").copy(signal = LocationSignal(deviceFix = false)))
        assertFalse(pinned.countsForCredit)
        assertEquals(listOf(LocationReasons.UNVERIFIED), pinned.reasons)
        assertEquals(1, store.checkpoints.size)

        now += 5.seconds
        val quick = author.drops.createDrop(noteAt(45.0, -100.0, "Too soon"))
        assertTrue(quick.countsForCredit)

        now += 60.seconds
        val flown = author.drops.createDrop(noteAt(50.0, -100.0, "Too fast"))
        assertFalse(flown.countsForCredit)
        assertTrue(LocationReasons.TRAVEL in flown.reasons)

        val trusted = author.drops.createDrop(noteAt(40.0, -100.0, "Real bench"))
        val revealed = other.drops.dropDetail(trusted.id, trusted.displayLocation, LocationSignal(mock = true))
        assertFalse(revealed.revealed)
        assertNull(revealed.body)
        assertTrue(revealed.locationWithheld)
        val honest = other.drops.dropDetail(trusted.id, trusted.displayLocation)
        assertTrue(honest.revealed)
        assertEquals("Something worth knowing.", honest.body)

        val before = store.drops.getValue(trusted.id).confirmCount
        val flagged = other.drops.confirmDrop(
            trusted.id,
            ConfirmationVerdict.STILL_TRUE,
            trusted.displayLocation,
            signal = LocationSignal(accuracyM = 5_000.0),
        )
        assertFalse(flagged.countsForCredit)
        assertEquals(before, store.drops.getValue(trusted.id).confirmCount)
        assertEquals(0, progress(other, "confirm_drop"))

        val counted = other.drops.confirmDrop(trusted.id, ConfirmationVerdict.STILL_TRUE, trusted.displayLocation)
        assertTrue(counted.countsForCredit)
        assertEquals(1, store.drops.getValue(trusted.id).confirmCount)
        val spoofedAgain = other.drops.confirmDrop(
            trusted.id,
            ConfirmationVerdict.NOT_TRUE,
            trusted.displayLocation,
            signal = LocationSignal(mock = true),
        )
        assertFalse(spoofedAgain.countsForCredit)
        assertEquals(ConfirmationVerdict.STILL_TRUE, spoofedAgain.verdict)
        assertEquals(1, store.drops.getValue(trusted.id).confirmCount)
        assertEquals(1, progress(other, "confirm_drop"))

        val skewed = other.drops.confirmDrop(
            pinned.id,
            ConfirmationVerdict.STILL_TRUE,
            pinned.displayLocation,
            signal = LocationSignal(deviceAt = now - 1.hours),
        )
        assertEquals(listOf(LocationReasons.CLOCK), skewed.reasons)

        val visitsBefore = store.visits.count { it.first == other.viewer?.id }
        val missed = other.walks.checkIn(trusted.displayLocation, signal = LocationSignal(mock = true))
        assertEquals(0, missed.visitsAdded)
        assertFalse(missed.countsForCredit)
        assertEquals(visitsBefore, store.visits.count { it.first == other.viewer?.id })
        val visited = other.walks.checkIn(trusted.displayLocation)
        assertTrue(visited.visitsAdded >= 1)
        assertTrue(store.visits.any { it.first == other.viewer?.id && it.second == trusted.id })

        val photo = MediaUpload(
            storagePath = "${Fixtures.id(1)}/photo.jpg",
            mediaKind = MediaKind.IMAGE,
            mimeType = "image/jpeg",
            captureLat = 40.0,
            captureLng = -100.0,
        )
        val withPhoto = author.drops.createDrop(noteAt(40.0, -100.0, "Photo bench").copy(media = listOf(photo)))
        val mine = author.drops.dropDetail(withPhoto.id, null)
        assertTrue(mine.media.orEmpty().single().takenHere)
        val spoofPhoto = author.drops.createDrop(
            noteAt(40.0, -100.0, "Spoof photo").copy(media = listOf(photo), signal = LocationSignal(mock = true)),
        )
        assertFalse(author.drops.dropDetail(spoofPhoto.id, null).media.orEmpty().single().takenHere)

        val moderator = InMemoryXHere(store, Fixtures.profile(9, role = AppRole.MODERATOR), clock = { now })
        val queue = moderator.moderation.integrityQueue()
        assertTrue(queue.any { it.userId == Fixtures.profile(2).id && LocationReasons.MOCK in it.reasons })
        assertTrue(queue.none { it.userId == moderator.viewer?.id })
        val denied = assertFailsWith<XHereApiException> { author.moderation.integrityQueue() }
        assertEquals("not_moderator", denied.code)
        assertTrue(store.suspendedUntil.isEmpty())
    }

    @Test
    fun anOpenRecordStaysReadableWhenTheFixIsMocked() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        val created = author.drops.createDrop(noteAt(40.0, -100.0, "Library"))
        val drop = store.drops.getValue(created.id)
        drop.source = DropSource.OPEN_DATA
        val reader = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        val card = reader.drops.dropDetail(created.id, created.displayLocation, LocationSignal(mock = true))
        assertTrue(card.revealed)
        assertFalse(card.locationWithheld)
        assertEquals("Something worth knowing.", card.body)
    }

    private fun noteAt(latitude: Double, longitude: Double, title: String) =
        CategoryRules.check(
            Fixtures.draft(title = title, latitude = latitude, longitude = longitude),
            Fixtures.note,
            Fixtures.context(),
            emptyList(),
        ).toRequest(Fixtures.draft(title = title, latitude = latitude, longitude = longitude))

    private suspend fun progress(app: InMemoryXHere, kind: String): Int =
        app.walks.quests().first { it.id == StarterQuest.ID }.steps.first { it.kind == kind }.progress
}
