package world.xhere.shared.contribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ContributionRulesTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")

    @Test
    fun wilsonIsZeroWithNoVotesAndRisesWithAgreement() {
        assertEquals(0.0, ContributionRules.wilson(0, 0))
        assertTrue(ContributionRules.wilson(8, 1) > ContributionRules.wilson(2, 2))
    }

    @Test
    fun aLowScoreWithEnoughDownvotesFoldsAway() {
        assertFalse(ContributionRules.collapsed(0, 2))
        assertTrue(ContributionRules.collapsed(0, 3))
        assertFalse(ContributionRules.collapsed(3, 1))
    }

    @Test
    fun newestAndTopUseTheSameOrderingAsTheSheet() {
        val older = now
        val newer = Instant.parse("2026-10-20T18:00:00Z")
        val rows = listOf("old" to older, "new" to newer)
        assertEquals(
            listOf("new", "old"),
            ContributionRules.ordered(rows, ContributionSort.NEWEST, { it.second }, { 0.0 }).map { it.first },
        )
        val scored = listOf("low" to 0.1, "high" to 0.9)
        assertEquals(
            listOf("high", "low"),
            ContributionRules.ordered(scored, ContributionSort.TOP, { now }, { it.second }).map { it.first },
        )
        assertEquals(
            listOf("old", "new"),
            ContributionRules.ordered(rows, ContributionSort.OLDEST, { it.second }, { 0.0 }).map { it.first },
        )
    }

    @Test
    fun fiveDownvotesOrANetOfMinusFiveHidesThePost() {
        assertFalse(ContributionRules.autoHidden(0, 4))
        assertFalse(ContributionRules.autoHidden(6, 4))
        assertTrue(ContributionRules.autoHidden(0, 5))
        assertTrue(ContributionRules.autoHidden(1, 6))
    }

    @Test
    fun liveCoversADayAndTheMapPulseIsOnlyMedia() {
        val recent = Instant.parse("2026-10-20T00:00:00Z")
        val stale = Instant.parse("2026-10-18T00:00:00Z")
        assertTrue(ContributionRules.live(recent, now))
        assertFalse(ContributionRules.live(stale, now))
        assertTrue(ContributionRules.pulses("photo", recent, now))
        assertTrue(ContributionRules.pulses("video", recent, now))
        assertTrue(ContributionRules.pulses("media", recent, now))
        assertFalse(ContributionRules.pulses("text", recent, now))
        assertFalse(ContributionRules.pulses("photo", stale, now))
    }

    @Test
    fun salesStayNamedEvenWhenAnonymousWasAskedFor() {
        assertFalse(ContributionRules.anonymousAccepted(requested = true, allowAnonymousDisplay = false))
        assertTrue(ContributionRules.anonymousAccepted(requested = true, allowAnonymousDisplay = true))
        assertFalse(ContributionRules.anonymousAccepted(requested = false, allowAnonymousDisplay = true))
    }

    @Test
    fun videoFramesStayEvenAndInside720p() {
        assertEquals(1280 to 720, ContributionMediaPolicy.videoFrame(1920, 1080))
        assertEquals(720 to 1280, ContributionMediaPolicy.videoFrame(1080, 1920))
        assertEquals(640 to 360, ContributionMediaPolicy.videoFrame(640, 360))
        assertEquals(60.0, ContributionMediaPolicy.trimSeconds(90.0))
        assertEquals(12.0, ContributionMediaPolicy.trimSeconds(12.0))
        assertTrue(ContributionMediaPolicy.PHOTO_MAX_EDGE == 2048)
        assertTrue(ContributionMediaPolicy.THUMB_EDGE == 400)
        assertTrue(ContributionMediaPolicy.PHOTO_QUALITY in 80..85)
        assertTrue(ContributionMediaPolicy.PHOTO_MAX_BYTES == 500 * 1024)
        assertTrue(ContributionMediaPolicy.VIDEO_MAX_EDGE == 1280)
        assertTrue(ContributionMediaPolicy.VIDEO_MAX_FPS == 30)
        assertTrue(ContributionMediaPolicy.VIDEO_BITRATE in 2_000_000..2_500_000)
        assertTrue(ContributionMediaPolicy.AUDIO_BITRATE == 128_000)
        assertTrue(ContributionMediaPolicy.VIDEO_MAX_SECONDS == 60.0)
        assertTrue(ContributionMediaPolicy.VIDEO_MAX_BYTES == 22 * 1024 * 1024)
    }
}
