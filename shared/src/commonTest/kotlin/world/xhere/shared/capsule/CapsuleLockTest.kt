package world.xhere.shared.capsule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.AppRole
import world.xhere.shared.model.CapsuleRequest

/**
 * The in-memory stand-in for the capsule and claim RPCs.
 * A locked story stays off the drop until someone else is on the spot.
 */
class CapsuleLockTest {
    @Test
    fun theNextPersonOpensAVisitCapsuleAndTheAuthorIsTold() = runTest {
        val author = InMemoryXHere(JamestownDemo.store(), Fixtures.profile(1), clock = { NOW })
        val created = author.desk.createCapsule(
            CapsuleRequest(
                title = "Neighborhood memory",
                body = "the sealed neighborhood secret",
                latitude = JamestownDemo.center.latitude,
                longitude = JamestownDemo.center.longitude,
                mode = "visit",
            ),
        )
        val stored = author.store.drops.values.first { it.shortId == created.shortId }
        assertNull(stored.body)
        assertEquals("the sealed neighborhood secret", stored.capsuleSecret)

        val openerProfile = Fixtures.profile(2)
        author.viewer = openerProfile
        val opened = author.desk.openCapsulesHere(JamestownDemo.center, null)
        author.viewer = Fixtures.profile(1)
        assertEquals(1, opened)
        assertEquals("the sealed neighborhood secret", stored.body)
        val notice = author.impact.myNotices().single { it.shortId == created.shortId }
        assertEquals(1, notice.capsuleOpens)
    }

    @Test
    fun anEmailDomainMatchMakesThePlaceOfficial() = runTest {
        val owner = InMemoryXHere(JamestownDemo.store(), Fixtures.profile(1), clock = { NOW })
        owner.claimEmail = "ada@mill.example.test"
        val created = owner.desk.createUserPlace("Mill", JamestownDemo.center, "https://www.mill.example.test", null)
        val ticket = owner.desk.startClaim(created.dropId, "https://mill.example.test", JamestownDemo.center, null)
        val active = owner.desk.proveEmail(ticket.claimId)
        assertEquals("active", active.status)
        val official = owner.desk.officialAt(JamestownDemo.center)
        assertEquals("Mill", official?.name)
        assertEquals("Official", official?.badge)
        assertEquals("free", official?.planSlug)

        val pendingPlace = owner.desk.createUserPlace("City hall", LatLng(10.0, 10.0), "https://hall.example.test", null)
        val pending = owner.desk.startClaim(pendingPlace.dropId, "https://hall.example.test", LatLng(10.0, 10.0), null)
        owner.desk.submitForReview(pending.claimId, "Please check")
        owner.viewer = Fixtures.profile(9, role = AppRole.ADMIN)
        val queue = owner.desk.reviewQueue()
        assertTrue(queue.any { it.id == pending.claimId && it.kind == "claim" })
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-19T15:00:00Z")
    }
}
