package world.xhere.shared.walks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.demo.JamestownHistoryWalk
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.rules.CategoryRules

/**
 * A walk is an ordered route of places that already exist. The starter quest
 * is the same in every region. Check-off uses the reveal radius, the same
 * "there" rule as opening a drop.
 */
class WalksAndQuestsTest {
    @Test
    fun jamestownWalkIsTheEightPublicRecords() {
        val meters = JamestownHistoryWalk.distanceM
        assertTrue(meters in 1000..1030, "was $meters")
        assertEquals(WalkGuide.durationMinutes(meters), JamestownHistoryWalk.durationMin)
        assertEquals(14, JamestownHistoryWalk.durationMin)
        assertEquals(
            listOf(
                "Erie Railroad Station",
                "National Comedy Center",
                "The Wellman Building",
                "Lucille Ball Desi Arnaz Museum & Center for Comedy",
                "Reg Lenna Center for the Arts",
                "Jamestown Downtown Historic District",
                "Robert H. Jackson Center",
                "First Presbyterian Church, Jamestown, New York",
            ),
            JamestownHistoryWalk.stops.map { it.title },
        )
        assertTrue(WalkGuide.isThere(40.0, 150))
        assertTrue(!WalkGuide.isThere(60.0, 40))
    }

    @Test
    fun standingOnAStopChecksOffWhateverIsInsideTheRevealRadius() = runTest {
        val store = JamestownDemo.store()
        val me = InMemoryXHere(store, Fixtures.profile(4))
        val station = LatLng(42.094509, -79.244603)
        assertTrue(me.walks.walksFor(LatLng(42.88640, -78.87840)).isEmpty())
        val listed = me.walks.walksFor(JamestownDemo.center).single()
        assertEquals(JamestownHistoryWalk.SLUG, listed.slug)
        assertTrue(!listed.started)
        assertEquals(8, listed.stops.size)

        val early = me.walks.checkIn(station, listed.id)
        assertTrue(!early.walkJustCompleted)
        assertTrue(me.walks.walksFor(station).single().stops.none { it.reached })

        val started = me.walks.start(listed.id)
        assertTrue(started.started)
        val first = me.walks.checkIn(station, started.id)
        assertEquals(listOf(1, 2), first.walk!!.stops.filter { it.reached }.map { it.position })
        assertTrue(!first.walkJustCompleted)
        assertEquals(0, me.walks.checkIn(station, started.id).visitsAdded)
        assertEquals(listed.id, me.walks.walksFor(LatLng(42.88640, -78.87840)).single().id)

        val other = InMemoryXHere(store, Fixtures.profile(5))
        assertTrue(other.walks.walksFor(station).single().stops.none { it.reached })
        assertTrue(other.walks.walksFor(LatLng(42.88640, -78.87840)).isEmpty())

        var just = false
        for (stop in JamestownHistoryWalk.stops) {
            val result = me.walks.checkIn(LatLng(stop.latitude, stop.longitude), listed.id)
            if (result.walkJustCompleted) just = true
        }
        assertTrue(just)
        val done = me.walks.walksFor(JamestownDemo.center).single()
        assertTrue(done.completed)
        assertTrue(done.stops.all { it.reached })
        assertTrue(!me.walks.checkIn(station, listed.id).walkJustCompleted)
        assertTrue(me.walks.walksFor(LatLng(42.88640, -78.87840)).isEmpty())
        val gap = Geo.distanceMeters(station, LatLng(42.095922, -79.242619))
        assertTrue(gap > 150.0, "was $gap")
    }

    @Test
    fun starterQuestCountsVisitsConfirmsAndAFirstDrop() = runTest {
        val store = world.xhere.shared.data.fake.MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1))
        val me = InMemoryXHere(store, Fixtures.profile(2))
        val quest = me.walks.quests().single()
        assertEquals(StarterQuest.ID, quest.id)
        assertEquals(listOf(0, 0, 0), quest.steps.map { it.progress })

        val mine = me.drops.createDrop(note(41.10, -100.0, "Mine"))
        assertEquals(0, me.walks.checkIn(LatLng(mine.latitude, mine.longitude)).visitsAdded)

        val hidden = author.drops.createDrop(note(41.20, -100.0, "Hidden mill"))
        me.hidden.hideDrop(hidden.id)
        assertEquals(0, me.walks.checkIn(LatLng(hidden.latitude, hidden.longitude)).visitsAdded)

        val stone = author.drops.createDrop(note(41.30, -100.0, "Corner stone"))
        me.drops.confirmDrop(stone.id, ConfirmationVerdict.NOT_TRUE, LatLng(stone.latitude, stone.longitude))
        assertEquals(0, progress(me, "confirm_drop"))
        me.drops.confirmDrop(stone.id, ConfirmationVerdict.STILL_TRUE, LatLng(stone.latitude, stone.longitude))
        assertEquals(1, progress(me, "confirm_drop"))

        listOf(41.00 to "Bench one", 41.01 to "Bench two", 41.02 to "Bench three").forEach { (lat, title) ->
            val drop = author.drops.createDrop(note(lat, -100.0, title))
            me.walks.checkIn(LatLng(drop.latitude, drop.longitude))
        }
        val done = me.walks.quests().single()
        assertTrue(done.completed)
        assertEquals(listOf(3, 1, 1), done.steps.map { it.progress })

        me.walks.dismissQuest(done.id)
        assertTrue(me.walks.quests().isEmpty())
        assertEquals(3, store.visits.count { it.first == Fixtures.profile(2).id })
    }

    private suspend fun progress(me: InMemoryXHere, kind: String): Int =
        me.walks.quests().single().steps.first { it.kind == kind }.progress

    private fun note(latitude: Double, longitude: Double, title: String) = CategoryRules.check(
        Fixtures.draft(title = title, latitude = latitude, longitude = longitude),
        Fixtures.note,
        Fixtures.context(),
        emptyList(),
    ).toRequest(Fixtures.draft(title = title, latitude = latitude, longitude = longitude))
}
