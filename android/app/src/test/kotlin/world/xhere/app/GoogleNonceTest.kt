package world.xhere.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import world.xhere.app.auth.GoogleNonce
import world.xhere.app.auth.looksLikeEmail

class GoogleNonceTest {
    @Test
    fun hashed_nonce_is_stable_url_safe_and_not_the_raw_value() {
        val raw = "example-raw-nonce"
        val hashed = GoogleNonce.hash(raw)
        assertEquals(hashed, GoogleNonce.hash(raw))
        assertNotEquals(raw, hashed)
        assertFalse(hashed.contains("="))
        assertFalse(hashed.contains("+"))
        assertFalse(hashed.contains("/"))
    }

    @Test
    fun email_check_matches_what_the_form_sends() {
        assertFalse(looksLikeEmail("not-an-email"))
        assertFalse(looksLikeEmail("a@b"))
        assertTrueEmail("ada@xhere.world")
    }

    private fun assertTrueEmail(value: String) {
        org.junit.Assert.assertTrue(looksLikeEmail(value))
    }
}
