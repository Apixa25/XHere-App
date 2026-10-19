package world.xhere.shared.capsule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CapsulesTest {
    private val later = Instant.parse("2027-06-01T00:00:00Z")

    @Test
    fun aLockedStoryStaysSealedUntilTheCondition() {
        assertTrue(Capsules.isLocked("visit", null))
        assertFalse(Capsules.isLocked("visit", later))
        assertFalse(Capsules.isLocked(null, null))
        assertEquals("Sealed until someone stands here", Capsules.condition("visit", null, null))
        assertEquals("Sealed until $later", Capsules.condition("date", later, null))
        assertEquals("Sealed until $later and someone stands here", Capsules.condition("both", later, null))
        assertEquals("Opened", Capsules.condition("visit", null, later))
    }

    @Test
    fun oralHistoryQuestionsAreNotTiedToOneTown() {
        assertTrue(OralHistory.questions.size >= 5)
        assertTrue(OralHistory.questions.none { it.contains("Jamestown", ignoreCase = true) })
    }
}
