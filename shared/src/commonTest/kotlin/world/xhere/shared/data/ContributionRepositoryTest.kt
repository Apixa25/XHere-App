package world.xhere.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        assertNull(card.authorHandle)
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
