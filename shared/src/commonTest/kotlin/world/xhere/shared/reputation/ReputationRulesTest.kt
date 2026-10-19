package world.xhere.shared.reputation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CategoryRules

/**
 * The numbers in [Reputation] match `public.reputation_rules`.
 * pgTAP checks the database copy.
 */
class ReputationRulesTest {
    @Test
    fun schedulesAndTiersMatchTheRulesRow() {
        assertEquals(8, Reputation.confirmPoints(0))
        assertEquals(3, Reputation.confirmPoints(1))
        assertEquals(1, Reputation.confirmPoints(2))
        assertEquals(0, Reputation.confirmPoints(3))
        assertEquals(2, Reputation.viewPoints(0))
        assertEquals(1, Reputation.viewPoints(1))
        assertEquals(1, Reputation.viewPoints(2))
        assertEquals(0, Reputation.viewPoints(3))
        assertEquals(6, Reputation.denyPoints(0))
        assertEquals(2, Reputation.denyPoints(1))
        assertEquals(0, Reputation.denyPoints(2))

        assertEquals(UserTier.NEW, Reputation.tierFor(23))
        assertEquals(UserTier.REGULAR, Reputation.tierFor(24))
        assertEquals(UserTier.TRUSTED, Reputation.tierFor(80))
        assertEquals(UserTier.GUIDE, Reputation.tierFor(200))
        assertEquals("Local", Reputation.label(UserTier.REGULAR))
        assertEquals(UserTier.TRUSTED, Reputation.nextTier(UserTier.REGULAR))
        assertNull(Reputation.nextTier(UserTier.GUIDE))

        assertEquals(5, Reputation.reportsToHide(3, UserTier.TRUSTED))
        assertEquals(3, Reputation.reportsToHide(3, UserTier.REGULAR))
        assertEquals(2, Reputation.reportsToHide(2, UserTier.NEW))
        assertEquals(4, Reputation.reportsToHide(2, UserTier.GUIDE))

        assertEquals(-10.0, Reputation.adjustedMeters(30.0, UserTier.TRUSTED))
        assertEquals(30.0, Reputation.adjustedMeters(30.0, null))

        val standing = Reputation.standing(24)
        assertEquals("Local", standing.tierLabel)
        assertEquals("Trusted", standing.nextLabel)
        assertEquals(80, standing.nextAt)
        val encoded = XHereJson.encodeToString(ReputationStanding.serializer(), standing)
        val decoded = XHereJson.decodeFromString(ReputationStanding.serializer(), encoded)
        assertEquals(standing, decoded)
    }

    @Test
    fun aTrustedDropAShortWalkFartherOutranksANewOne() = runTest {
        val store = MemoryStore(Fixtures.taxonomy)
        val newbie = Fixtures.profile(1, UserTier.NEW)
        val trusted = Fixtures.profile(2, UserTier.TRUSTED)
        val reader = Fixtures.profile(3, UserTier.NEW)
        val newApi = InMemoryXHere(store, newbie, clock = { Fixtures.now })
        val trustedApi = InMemoryXHere(store, trusted, clock = { Fixtures.now })
        val here = LatLng(40.0, -100.0)
        val closer = newApi.drops.createDrop(noteAt(40.0, -100.0, "Brand new bench"))
        store.drops.getValue(closer.id).status = DropStatus.ACTIVE
        val farther = trustedApi.drops.createDrop(noteAt(40.0 + (30.0 / 111_320.0), -100.0, "Trusted bench"))
        val cards = InMemoryXHere(store, reader, clock = { Fixtures.now })
            .drops.dropsNearby(here, radiusM = 1_000)
        assertEquals(farther.shortId, cards.first().shortId)
        assertEquals(UserTier.TRUSTED, cards.first().authorTier)
        assertTrue((cards.first().distanceM ?: 0.0) > (cards[1].distanceM ?: 0.0))
        assertEquals(closer.shortId, cards[1].shortId)
        assertEquals(UserTier.NEW, cards[1].authorTier)
    }

    private fun noteAt(latitude: Double, longitude: Double, title: String) = CategoryRules.check(
        Fixtures.draft(latitude = latitude, longitude = longitude, body = "A bench.", title = title),
        Fixtures.note,
        Fixtures.context(tier = UserTier.TRUSTED),
        emptyList(),
    ).toRequest(Fixtures.draft(latitude = latitude, longitude = longitude, body = "A bench.", title = title))
}
