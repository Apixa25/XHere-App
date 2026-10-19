package world.xhere.shared.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorNamesTest {
    @Test
    fun aGeneratedHandleIsNotShown() {
        assertTrue(AuthorNames.needsChoice(null, "user_abc123"))
        assertTrue(AuthorNames.needsChoice("  ", "user_abc123"))
        assertNull(AuthorNames.label(null, "user_abc123"))
    }

    @Test
    fun aChosenNameWinsAndAChosenHandleIsEnough() {
        assertFalse(AuthorNames.needsChoice("Ada", "user_abc123"))
        assertEquals("Ada", AuthorNames.label("Ada", "user_abc123"))
        assertFalse(AuthorNames.needsChoice(null, "ada"))
        assertEquals("ada", AuthorNames.label(null, "ada"))
    }
}
