package world.xhere.shared.integrity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import world.xhere.shared.geo.LatLng

class LocationIntegrityTest {
    private val now = Instant.parse("2026-10-18T04:00:00Z")
    private val here = LatLng(40.0, -100.0)

    @Test
    fun aMissingSignalStillCounts() {
        val decision = LocationIntegrity.decide(LocationAction.CREATE_DROP, here, null, null, now)
        assertTrue(decision.countsForCredit)
        assertEquals(LocationIntegrity.TRUST_STANDARD, decision.locationTrust)
        assertTrue(decision.reasons.isEmpty())
        assertTrue(decision.moveCheckpoint)
    }

    @Test
    fun developerOptionsAloneDoNotRemoveCredit() {
        val signal = LocationSignal(developerOptions = true)
        val decision = LocationIntegrity.decide(LocationAction.CONFIRM_DROP, here, signal, null, now)
        assertTrue(decision.countsForCredit)
        assertTrue(decision.reasons.isEmpty())
    }

    @Test
    fun mockLocationRemovesCreditAndKeepsTheDeveloperBit() {
        val signal = LocationSignal(mock = true, developerOptions = true)
        val decision = LocationIntegrity.decide(LocationAction.CONFIRM_DROP, here, signal, null, now)
        assertFalse(decision.countsForCredit)
        assertEquals(LocationIntegrity.TRUST_REDUCED, decision.locationTrust)
        assertEquals(listOf(LocationReasons.MOCK, LocationReasons.DEVELOPER), decision.reasons)
    }

    @Test
    fun aMapPinIsUnverifiedAndDoesNotMoveTheCheckpoint() {
        val signal = LocationSignal(deviceFix = false)
        val decision = LocationIntegrity.decide(LocationAction.CREATE_DROP, here, signal, null, now)
        assertEquals(listOf(LocationReasons.UNVERIFIED), decision.reasons)
        assertFalse(decision.moveCheckpoint)
    }

    @Test
    fun impossibleTravelNeedsDistanceAndTime() {
        val prior = LocationCheckpoint(40.0, -100.0, now - 60.seconds)
        val far = LatLng(45.0, -100.0)
        val flagged = LocationIntegrity.decide(LocationAction.CREATE_DROP, far, null, prior, now)
        assertTrue(LocationReasons.TRAVEL in flagged.reasons)

        val brief = LocationCheckpoint(40.0, -100.0, now - 5.seconds)
        val quick = LocationIntegrity.decide(LocationAction.CREATE_DROP, far, null, brief, now)
        assertTrue(quick.countsForCredit)
    }

    @Test
    fun accuracyLimitsDifferByAction() {
        val loose = LocationSignal(accuracyM = 800.0)
        assertTrue(LocationIntegrity.decide(LocationAction.CREATE_DROP, here, loose, null, now).countsForCredit)
        assertTrue(LocationReasons.ACCURACY in LocationIntegrity.decide(LocationAction.REVEAL, here, loose, null, now).reasons)
        val huge = LocationSignal(accuracyM = 5_000.0)
        assertTrue(LocationReasons.ACCURACY in LocationIntegrity.decide(LocationAction.CREATE_DROP, here, huge, null, now).reasons)
    }

    @Test
    fun clockSkewAndStaleFixesAreSeparate() {
        val future = LocationSignal(fixAt = now + 6.minutes)
        assertEquals(listOf(LocationReasons.CLOCK), LocationIntegrity.decide(LocationAction.REVEAL, here, future, null, now).reasons)
        val stale = LocationSignal(fixAt = now - 11.minutes)
        assertEquals(listOf(LocationReasons.STALE), LocationIntegrity.decide(LocationAction.REVEAL, here, stale, null, now).reasons)
        val drifted = LocationSignal(deviceAt = now - 6.minutes)
        assertEquals(listOf(LocationReasons.CLOCK), LocationIntegrity.decide(LocationAction.REVEAL, here, drifted, null, now).reasons)
    }

    @Test
    fun aPhotoCountsAsTakenHereOnlyWhenTheDropAlsoCounted() {
        val near = LatLng(40.0001, -100.0)
        assertTrue(LocationIntegrity.photoTakenHere(here, near, accuracyM = 20.0, countsForCredit = true))
        assertFalse(LocationIntegrity.photoTakenHere(here, near, accuracyM = 20.0, countsForCredit = false))
        assertFalse(LocationIntegrity.photoTakenHere(here, LatLng(41.0, -100.0), accuracyM = 20.0, countsForCredit = true))
        assertFalse(LocationIntegrity.photoTakenHere(here, null, accuracyM = 20.0, countsForCredit = true))
    }
}
