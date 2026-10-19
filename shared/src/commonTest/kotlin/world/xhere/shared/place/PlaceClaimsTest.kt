package world.xhere.shared.place

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import world.xhere.shared.model.PlaceHours

class PlaceClaimsTest {
    @Test
    fun emailDomainMatchesTheWebsiteAndFreeMailDoesNot() {
        assertEquals("mill.example.test", PlaceClaims.websiteHost("https://www.mill.example.test/about"))
        assertNull(PlaceClaims.websiteHost("http://mill.example.test"))
        assertTrue(PlaceClaims.domainsMatch("ada@mill.example.test", "https://www.mill.example.test"))
        assertFalse(PlaceClaims.domainsMatch("ada@gmail.com", "https://gmail.com"))
        assertFalse(PlaceClaims.domainsMatch("ada@other.example.test", "https://mill.example.test"))
    }

    @Test
    fun hoursStayInsideAWeek() {
        assertTrue(PlaceClaims.hoursOk(listOf(PlaceHours(1, "09:00", "17:00"))))
        assertFalse(PlaceClaims.hoursOk(listOf(PlaceHours(1, "17:00", "09:00"))))
        assertFalse(PlaceClaims.hoursOk(List(15) { PlaceHours(1, "09:00", "17:00") }))
    }
}
