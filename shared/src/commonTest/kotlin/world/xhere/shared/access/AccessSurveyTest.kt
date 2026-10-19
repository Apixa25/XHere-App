package world.xhere.shared.access

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessSurveyTest {
    @Test
    fun threeTapsAdvanceAndNotesStayOptional() {
        var draft = SurveyDraft()
        assertFalse(draft.ready)
        draft = AccessSurvey.answer(draft, AccessAnswer.YES)
        assertEquals(SurveyQuestion.RAMP, draft.question)
        draft = AccessSurvey.answer(draft, AccessAnswer.NO)
        draft = AccessSurvey.answer(draft, AccessAnswer.UNKNOWN)
        assertTrue(draft.ready)
        assertEquals(AccessAnswer.YES, draft.stepFree)
        assertEquals(AccessAnswer.NO, draft.ramp)
        assertEquals(AccessAnswer.UNKNOWN, draft.restroom)
        draft = AccessSurvey.notes(draft, "a".repeat(400))
        assertEquals(AccessSurvey.NOTE_MAX, draft.notes.length)
        val stuck = AccessSurvey.answer(draft, AccessAnswer.YES)
        assertEquals(draft.restroom, stuck.restroom)
    }

    @Test
    fun osmUrlBecomesThePlaceKey() {
        assertEquals(
            "osm:way/1334837223",
            AccessPlaces.fromOsmUrl("https://www.openstreetmap.org/way/1334837223"),
        )
        assertNull(AccessPlaces.fromOsmUrl("https://example.com/node/1"))
    }
}