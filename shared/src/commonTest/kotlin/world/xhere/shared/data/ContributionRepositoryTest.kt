package world.xhere.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import world.xhere.shared.Fixtures
import world.xhere.shared.contribution.ContributionFile
import world.xhere.shared.contribution.ContributionSort
import world.xhere.shared.contribution.ContributionTarget
import world.xhere.shared.contribution.NewContribution
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.media.ExifStrip
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CreateDropRequest

class ContributionRepositoryTest {
    @Test
    fun anonymousTextHidesTheHandleAndAPhotoPathHasNoAuthor() = runTest {
        val alice = Fixtures.profile(1)
        val bob = Fixtures.profile(2)
        val client = InMemoryXHere(MemoryStore(Fixtures.taxonomy), alice, clock = { Fixtures.now })
        val drop = client.drops.createDrop(note("A bench worth knowing"))
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x0B,
        ) + "Exif\u0000\u0000GPS".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertTrue(ExifStrip.containsExifMarker(jpeg))
        val photo = client.contributions.add(
            NewContribution(
                targetKind = ContributionTarget.DROP,
                targetId = drop.id,
                kind = "photo",
                body = "Morning light",
                anonymous = true,
                file = ContributionFile(jpeg, "image/jpeg", width = 800, height = 600),
            ),
        )
        val path = photo.media.single().storagePath
        assertTrue(path.startsWith("c/"))
        assertTrue(alice.id.toString() !in path)
        val stored = client.store.contributionBytes.getValue(path)
        assertTrue(!ExifStrip.containsExifMarker(stored))
        client.viewer = bob
        val card = client.contributions.list(ContributionTarget.DROP, drop.id, ContributionSort.NEWEST).single()
        assertEquals(world.xhere.shared.contribution.ContributionRules.ANONYMOUS_NAME, card.authorHandle)
        assertTrue(card.anonymous)
        assertTrue(card.live)
        assertEquals(false, card.isMine)
    }

    @Test
    fun theAuthorCannotVoteAndANeighborCanChangeOrRemoveAVote() = runTest {
        val alice = Fixtures.profile(1)
        val bob = Fixtures.profile(2)
        val client = InMemoryXHere(MemoryStore(Fixtures.taxonomy), alice, clock = { Fixtures.now })
        val drop = client.drops.createDrop(note("A painted door"))
        val added = client.contributions.add(
            NewContribution(
                targetKind = ContributionTarget.DROP,
                targetId = drop.id,
                kind = "photo",
                body = null,
                anonymous = false,
                file = ContributionFile(byteArrayOf(1, 2, 3), "image/jpeg", width = 100, height = 80),
            ),
        )
        val self = assertFailsWith<XHereApiException> { client.contributions.vote(added.id, 1) }
        assertEquals("cannot_vote_self", self.code)
        client.viewer = bob
        client.contributions.vote(added.id, 1)
        client.contributions.vote(added.id, -1)
        val down = client.contributions.list(ContributionTarget.DROP, drop.id).single()
        assertEquals(-1, down.myVote)
        assertEquals(1, down.downCount)
        client.contributions.vote(added.id, 0)
        val cleared = client.contributions.list(ContributionTarget.DROP, drop.id).single()
        assertNull(cleared.myVote)
        assertEquals(0, cleared.downCount)
    }

    @Test
    fun aSaleCannotBePostedAnonymously() = runTest {
        val client = InMemoryXHere(MemoryStore(Fixtures.taxonomy), Fixtures.profile(tier = UserTier.REGULAR), clock = { Fixtures.now })
        val drop = client.drops.createDrop(note("Books on the step", category = "yard_sale"))
        val error = assertFailsWith<XHereApiException> {
            client.contributions.add(
                NewContribution(
                    targetKind = ContributionTarget.DROP,
                    targetId = drop.id,
                    kind = "text",
                    body = "Still going this afternoon",
                    anonymous = true,
                ),
            )
        }
        assertEquals("anonymous_not_allowed", error.code)
    }

    @Test
    fun textCannotBeVoted() = runTest {
        val alice = Fixtures.profile(1)
        val bob = Fixtures.profile(2)
        val client = InMemoryXHere(MemoryStore(Fixtures.taxonomy), alice, clock = { Fixtures.now })
        val drop = client.drops.createDrop(note("The bell rings at noon"))
        val added = client.contributions.add(
            NewContribution(
                targetKind = ContributionTarget.DROP,
                targetId = drop.id,
                kind = "text",
                body = "It rings twice",
                anonymous = false,
            ),
        )
        client.viewer = bob
        val error = assertFailsWith<XHereApiException> { client.contributions.vote(added.id, 1) }
        assertEquals("votes_are_for_media", error.code)
    }

    @Test
    fun theSameVoteAgainClearsItAndAReplyCanCarryTwoPhotos() = runTest {
        val alice = Fixtures.profile(1)
        val bob = Fixtures.profile(2)
        var now = Fixtures.now
        val client = InMemoryXHere(MemoryStore(Fixtures.taxonomy), alice, clock = { now })
        val drop = client.drops.createDrop(note("A maple by the path"))
        val photo = ContributionFile(byteArrayOf(1, 2, 3), "image/jpeg", width = 100, height = 80)
        val added = client.contributions.add(
            NewContribution(
                targetKind = ContributionTarget.DROP,
                targetId = drop.id,
                kind = "photo",
                body = "Two angles of the maple",
                anonymous = false,
                files = listOf(photo, photo.copy(bytes = byteArrayOf(4, 5, 6))),
            ),
        )
        assertEquals(2, added.media.size)
        assertTrue(added.media.all { alice.id.toString() !in it.storagePath })
        now += 1.hours
        val reply = client.contributions.add(
            NewContribution(
                targetKind = ContributionTarget.DROP,
                targetId = drop.id,
                kind = "text",
                body = "The bark is pale",
                anonymous = false,
                parentId = added.id,
            ),
        )
        client.contributions.edit(reply.id, "The bark is pale in spring")
        client.viewer = bob
        client.contributions.vote(added.id, 1)
        client.contributions.vote(added.id, 1)
        val cleared = client.contributions.list(ContributionTarget.DROP, drop.id).single { it.id == added.id }
        assertNull(cleared.myVote)
        assertEquals(0, cleared.upCount)
        client.contributions.vote(added.id, -1)
        client.contributions.vote(added.id, 1)
        val switched = client.contributions.list(ContributionTarget.DROP, drop.id).single { it.id == added.id }
        assertEquals(1, switched.myVote)
        assertEquals(0, client.store.contributionDownvotes.count { it.first == added.id })
        val oldest = client.contributions.list(ContributionTarget.DROP, drop.id, ContributionSort.OLDEST)
        assertEquals(added.id, oldest.first().id)
        assertEquals(reply.id, oldest.first { it.parentId == added.id }.id)
        assertNotNull(oldest.first { it.id == reply.id }.editedAt)
        val injected = client.contributions.list(
            ContributionTarget.DROP,
            drop.id,
            query = "'; drop table public.contributions; --",
        )
        assertTrue(injected.isEmpty())
        val found = client.contributions.list(ContributionTarget.DROP, drop.id, query = "maple")
        assertEquals(listOf(added.id), found.map { it.id })
        assertTrue(client.contributions.place(ContributionTarget.DROP, drop.id).shareUrl!!.startsWith("https://xhere.world/d/"))
    }

    @Test
    fun fiveDownvotesHideThePostAndASealedOrHiddenPlaceHidesItToo() = runTest {
        val alice = Fixtures.profile(1)
        val bob = Fixtures.profile(2)
        val client = InMemoryXHere(MemoryStore(Fixtures.taxonomy), alice, clock = { Fixtures.now })
        val drop = client.drops.createDrop(note("A door on the lane"))
        val added = client.contributions.add(
            NewContribution(
                targetKind = ContributionTarget.DROP,
                targetId = drop.id,
                kind = "photo",
                body = "The green door",
                anonymous = false,
                file = ContributionFile(byteArrayOf(9, 9), "image/jpeg", width = 40, height = 40),
            ),
        )
        repeat(5) { index ->
            client.viewer = Fixtures.profile(10 + index)
            client.contributions.vote(added.id, -1)
        }
        assertEquals(5, client.store.contributionDownvotes.count { it.first == added.id })
        client.viewer = bob
        assertTrue(client.contributions.list(ContributionTarget.DROP, drop.id).none { it.id == added.id })
        client.viewer = alice
        assertEquals(5, client.contributions.list(ContributionTarget.DROP, drop.id).single().downCount)

        client.store.drops.getValue(drop.id).capsuleMode = "visit"
        client.viewer = bob
        assertTrue(client.contributions.list(ContributionTarget.DROP, drop.id).isEmpty())
        client.viewer = alice
        assertTrue(client.contributions.list(ContributionTarget.DROP, drop.id).isNotEmpty())
        client.store.drops.getValue(drop.id).capsuleMode = null

        client.store.hidden[bob.id to drop.id] = Fixtures.now
        client.viewer = bob
        assertTrue(client.contributions.list(ContributionTarget.DROP, drop.id).isEmpty())
        client.viewer = alice
        assertTrue(client.contributions.list(ContributionTarget.DROP, drop.id).isNotEmpty())
        client.store.hidden.remove(bob.id to drop.id)

        client.store.drops.getValue(drop.id).deletedAt = Fixtures.now
        client.viewer = alice
        assertTrue(client.contributions.list(ContributionTarget.DROP, drop.id).isEmpty())
        client.viewer = bob
        assertTrue(client.contributions.list(ContributionTarget.DROP, drop.id).isEmpty())
    }

    private fun note(title: String, category: String = "note") = CreateDropRequest(
        categorySlug = category,
        latitude = 40.7128,
        longitude = -74.0060,
        title = title,
        body = "Something worth knowing.",
        attributes = JsonObject(emptyMap()),
        tags = emptyList(),
        revealRadiusM = null,
        expiresAt = null,
        displayAnonymous = false,
        media = emptyList(),
        acknowledgeGuidelines = false,
    )
}
