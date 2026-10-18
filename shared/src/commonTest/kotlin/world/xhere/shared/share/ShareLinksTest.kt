package world.xhere.shared.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareLinksTest {
    @Test
    fun shareUrlMatchesTheWebsiteAndAppLinks() {
        assertEquals("https://xhere.world/d/JmtwnHist01a", ShareLinks.url("JmtwnHist01a"))
        assertEquals("https://xhere.world/d/fenton", ShareLinks.url("fenton"))
    }

    @Test
    fun onlyHttpsDropLinksYieldAShortId() {
        assertEquals("JmtwnHist01a", ShareLinks.shortId("https://xhere.world/d/JmtwnHist01a"))
        assertEquals("fenton", ShareLinks.shortId("https://xhere.world/d/fenton/"))
        assertEquals("JmtwnNote02b", ShareLinks.shortId("https://xhere.world/d/JmtwnNote02b?utm=share"))
        assertNull(ShareLinks.shortId("http://xhere.world/d/JmtwnHist01a"))
        assertNull(ShareLinks.shortId("https://example.com/d/JmtwnHist01a"))
        assertNull(ShareLinks.shortId("https://xhere.world/privacy"))
        assertNull(ShareLinks.shortId("https://xhere.world/d/"))
        assertNull(ShareLinks.shortId("https://xhere.world/d/has space"))
        assertNull(ShareLinks.shortId(null))
    }
}
