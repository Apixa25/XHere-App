package world.xhere.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.rules.CategoryRules

/**
 * Seen-by is one signed-in person per drop. Confirm is one answer per person.
 * The author gets at most one "your drop helped" note per drop per UTC day.
 */
class ImpactFeedbackTest {
    @Test
    fun viewsAreUniqueAndTheInboxBatchesADay() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        val created = author.drops.createDrop(note())

        val own = author.drops.dropDetail(created.id, created.displayLocation)
        assertEquals(0, own.viewCount)
        assertTrue(author.impact.myNotices().isEmpty())

        val bob = InMemoryXHere(store, Fixtures.profile(2), clock = { Fixtures.now })
        assertEquals(0, bob.drops.dropTeaser(created.shortId).viewCount)
        assertEquals(1, bob.drops.dropDetail(created.id, null).viewCount)
        assertEquals(1, bob.drops.dropDetail(created.id, created.displayLocation).viewCount)

        val carol = InMemoryXHere(store, Fixtures.profile(3), clock = { Fixtures.now })
        val seen = carol.drops.dropDetail(created.id, created.displayLocation)
        assertEquals(2, seen.viewCount)
        assertEquals(0, seen.confirmCount)

        val notes = author.impact.myNotices()
        assertEquals(1, notes.size)
        assertEquals(created.shortId, notes.first().shortId)
        assertEquals(2, notes.first().viewEvents)
        assertEquals(0, notes.first().confirmEvents)
        assertTrue(notes.first().unread)
        assertEquals("2026-10-18", notes.first().noticeDay)

        bob.drops.confirmDrop(created.id, ConfirmationVerdict.STILL_TRUE, created.displayLocation)
        bob.drops.confirmDrop(created.id, ConfirmationVerdict.STILL_TRUE, created.displayLocation)
        val afterRepeat = author.impact.myNotices().single()
        assertEquals(1, afterRepeat.confirmEvents)
        assertEquals(ConfirmationVerdict.STILL_TRUE, bob.drops.dropDetail(created.id, created.displayLocation).myVerdict)

        bob.drops.confirmDrop(created.id, ConfirmationVerdict.NOT_TRUE, created.displayLocation)
        assertEquals(1, author.impact.myNotices().single().confirmEvents)
        assertEquals(ConfirmationVerdict.NOT_TRUE, bob.drops.dropDetail(created.id, created.displayLocation).myVerdict)

        carol.drops.confirmDrop(created.id, ConfirmationVerdict.STILL_TRUE, created.displayLocation)
        val batched = author.impact.myNotices().single()
        assertEquals(2, batched.viewEvents)
        assertEquals(2, batched.confirmEvents)

        author.impact.markRead(batched.id)
        assertEquals(0, author.impact.myNotices().count { it.unread })
        assertTrue(author.impact.myNotices().single().readAt != null)
        assertEquals(0, bob.impact.myNotices().size)
    }

    @Test
    fun youCannotConfirmYourOwnDrop() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val author = InMemoryXHere(store, Fixtures.profile(1), clock = { Fixtures.now })
        val created = author.drops.createDrop(note())
        val denied = assertFailsWith<XHereApiException> {
            author.drops.confirmDrop(created.id, ConfirmationVerdict.STILL_TRUE, created.displayLocation)
        }
        assertEquals("cannot_confirm_own_drop", denied.code)
        assertTrue(author.impact.myNotices().isEmpty())
    }

    private fun note() = CategoryRules.check(
        Fixtures.draft(),
        Fixtures.note,
        Fixtures.context(),
        emptyList(),
    ).toRequest(Fixtures.draft())
}
