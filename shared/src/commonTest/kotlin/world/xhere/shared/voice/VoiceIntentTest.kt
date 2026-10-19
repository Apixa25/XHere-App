package world.xhere.shared.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.access.AccessAnswer
import world.xhere.shared.access.AccessRollup
import world.xhere.shared.access.AccessSummary
import world.xhere.shared.access.AccessStatus
import world.xhere.shared.model.Category
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ModerationMode
import world.xhere.shared.model.UserTier

class VoiceIntentTest {
    private val now = Instant.parse("2026-10-19T17:00:00Z")

    @Test
    fun spokenReportsBecomeDrops() {
        val pothole = VoiceIntents.parse("Pothole here!")
        assertEquals("hazard", pothole?.categorySlug)
        assertEquals("Pothole", pothole?.title)
        assertEquals("road", pothole?.hazardType)
        assertEquals("med", pothole?.severity)
        assertEquals(true, pothole?.ongoing)

        val coffee = VoiceIntents.parse("great coffee at this place")
        assertEquals("eatery_tip", coffee?.categorySlug)
        assertEquals("Great coffee", coffee?.title)

        val closed = VoiceIntents.parse("road closed")
        assertEquals("hazard", closed?.categorySlug)
        assertEquals("Road closed", closed?.title)
        assertEquals("high", closed?.severity)
        assertEquals("road", closed?.hazardType)
    }

    @Test
    fun questionsDoNotCreateADrop() {
        val around = VoiceIntents.parse("what's around me?")
        assertEquals(VoiceQuestion.AROUND, around?.question)
        assertFalse(around?.isDrop == true)

        val history = VoiceIntents.parse("any history here?")
        assertEquals(VoiceQuestion.HISTORY, history?.question)
        assertNull(history?.categorySlug)

        assertEquals(VoiceQuestion.HISTORY, VoiceIntents.parse("what happened here")?.question)
        assertEquals(VoiceQuestion.AROUND, VoiceIntents.parse("What is nearby?")?.question)
    }

    @Test
    fun historyWinsWhenBothWordsAppear() {
        assertEquals(VoiceQuestion.HISTORY, VoiceIntents.parse("any history around me")?.question)
    }

    @Test
    fun blankAndSingleWordsStayQuiet() {
        assertNull(VoiceIntents.parse("   "))
        assertNull(VoiceIntents.parse("hello"))
    }

    @Test
    fun aShortPhraseBecomesANote() {
        val note = VoiceIntents.parse("nice bench here")
        assertEquals("note", note?.categorySlug)
        assertEquals("Nice bench here", note?.title)
    }

    @Test
    fun hazardAttributesIncludeTheMoment() {
        val parse = checkNotNull(VoiceIntents.parse("pothole here"))
        val attributes = VoiceDrops.attributes(parse, now)
        assertEquals("2026-10-19T17:00:00Z", attributes["occurred_at"]?.toString()?.trim('"'))
        assertEquals("road", attributes["hazard_type"]?.toString()?.trim('"'))
        assertEquals(true, attributes["ongoing"]?.toString()?.toBoolean())
    }

    @Test
    fun geminiPromptKeepsAKnownCategoryAndDropsAnUnknownOne() {
        val categories = listOf(category("hazard"), category("eatery_tip"), category("note"))
        val prompted = VoiceDrafts.fromPrompt(
            """
            INTENT: drop
            TITLE: Hole in the lane
            CATEGORY: hazard
            DESCRIPTION: A pothole in the road
            """.trimIndent(),
            categories,
        )
        assertEquals(VoiceSource.GEMINI_NANO, prompted?.source)
        assertEquals("hazard", prompted?.categorySlug)
        assertEquals("Hole in the lane", prompted?.title)

        val unknown = VoiceDrafts.fromPrompt(
            """
            INTENT: drop
            TITLE: Pothole
            CATEGORY: not_a_category
            DESCRIPTION: pothole here
            """.trimIndent(),
            categories,
        )
        assertEquals("hazard", unknown?.categorySlug)
        assertEquals(VoiceSource.RULES, unknown?.source)

        val question = VoiceDrafts.fromPrompt(
            """
            INTENT: history
            TITLE: none
            CATEGORY: none
            DESCRIPTION: none
            TRANSCRIPT: any history here
            """.trimIndent(),
            categories,
        )
        assertEquals(VoiceQuestion.HISTORY, question?.question)
        assertEquals(VoiceSource.GEMINI_NANO, question?.source)
    }

    @Test
    fun autoPostIsOffUntilTheCountdownEnds() {
        assertFalse(VoiceAutoPost.DEFAULT_ENABLED)
        assertEquals(5, VoiceAutoPost.secondsLeft(0))
        assertEquals(4, VoiceAutoPost.secondsLeft(1_000))
        assertEquals(1, VoiceAutoPost.secondsLeft(4_200))
        assertTrue(VoiceAutoPost.finished(5_000))
        assertEquals(0, VoiceAutoPost.secondsLeft(9_000))
    }

    @Test
    fun clipsStayShortAndCompressed() {
        assertNull(VoiceClips.problem("audio/mp4", 90_000, 12.0))
        assertEquals("voice_too_long", VoiceClips.problem("audio/mp4", 90_000, 31.0))
        assertEquals("voice_too_large", VoiceClips.problem("audio/mp4", VoiceClips.MAX_BYTES + 1, 10.0))
        assertEquals("invalid_media", VoiceClips.problem("audio/wav", 1_000, 5.0))
        assertEquals(30.0, VoiceClips.MAX_SECONDS)
    }

    @Test
    fun hereSpeechReadsTheCardAndTheAccessLine() {
        val cards = listOf(
            card("story", "Fenton Mansion", "A public museum.", revealed = true),
            card("eatery_tip", "Great coffee", null, revealed = false),
        )
        val around = HereSpeech.script(VoiceQuestion.AROUND, "West 3rd Street", cards, access())
        assertTrue(around.startsWith("2 things around West 3rd Street."))
        assertTrue(around.contains("Fenton Mansion. A public museum."))
        assertTrue(around.contains("Great coffee"))
        assertTrue(around.contains("Step-free entrance yes"))
        assertTrue(around.contains("Confidence 100 percent"))

        val history = HereSpeech.script(VoiceQuestion.HISTORY, "West 3rd Street", cards)
        assertTrue(history.startsWith("History at West 3rd Street."))
        assertFalse(history.contains("Great coffee"))

        val empty = HereSpeech.script(VoiceQuestion.HISTORY, null, emptyList())
        assertEquals("No history is saved at this spot yet.", empty)
    }

    private fun access() = AccessSummary(
        placeKey = "cell:42.096:-79.242",
        stepFree = AccessAnswer.YES,
        ramp = AccessAnswer.YES,
        restroom = AccessAnswer.YES,
        status = AccessStatus.ACCESSIBLE,
        confidence = 1.0,
        sampleSize = 3,
        latitude = 42.096,
        longitude = -79.242,
        title = "Here",
    )

    private fun card(slug: String, title: String, body: String?, revealed: Boolean) = DropCard(
        id = Uuid.parse("11111111-1111-4111-8111-111111111111"),
        shortId = "abcdefghijkl",
        categorySlug = slug,
        groupSlug = "history_stories",
        title = title,
        latitude = 42.0,
        longitude = -79.0,
        locationPrecisionM = 0,
        revealRadiusM = 100,
        revealed = revealed,
        body = body,
        status = DropStatus.ACTIVE,
        isConfirmed = false,
        confirmCount = 0,
        denyCount = 0,
        createdAt = now,
        isMine = false,
        source = DropSource.USER,
        distanceM = 12.0,
    )

    private fun category(slug: String) = Category(
        id = Uuid.parse("22222222-2222-4222-8222-222222222222"),
        groupId = Uuid.parse("33333333-3333-4333-8333-333333333333"),
        slug = slug,
        label = slug,
        attributesSchema = kotlinx.serialization.json.JsonObject(emptyMap()),
        minTierToPost = UserTier.NEW,
        moderationMode = ModerationMode.POST,
        defaultRevealM = 100,
        maxRevealM = 100,
    )
}
