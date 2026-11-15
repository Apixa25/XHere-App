package world.xhere.shared.here

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng

class GeoProxyTest {
    @Test
    fun thePhoneCallsTheProxyAndNotNominatim() = runTest {
        val endpoint = GeoProxy.endpoint("https://example.supabase.co")
        var seen = ""
        val engine = MockEngine { request ->
            seen = request.url.toString()
            assertEquals("Bearer user-token", request.headers[HttpHeaders.Authorization])
            assertEquals("anon-key", request.headers["apikey"])
            assertFalse(seen.contains("nominatim"))
            assertFalse(seen.contains("overpass"))
            val posted = (request.body as TextContent).text
            assertTrue(posted.contains("\"op\": \"reverse\"") || posted.contains("\"op\":\"reverse\""))
            assertFalse(posted.contains("openstreetmap"))
            respond(
                content = """{"ok":true,"cached":true,"limited":false,"body":{"name":"Bridge","display_name":"Bridge, London"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = GeoProxyClient(endpoint, "anon-key", bearer = { "user-token" }, engine = engine)
        val label = client.reverse(LatLng(51.5, -0.12))
        assertEquals(endpoint, seen)
        assertEquals("Bridge", label?.name)
        assertEquals(Here.ATTRIBUTION, label?.attribution)
    }

    @Test
    fun aLimitedProxyReturnsNothingAndASignedOutPhoneDoesNotCall() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls += 1
            respond(
                """{"ok":true,"cached":false,"limited":true,"body":null}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val endpoint = GeoProxy.endpoint("https://example.supabase.co")
        val client = GeoProxyClient(endpoint, "anon-key", bearer = { "user-token" }, engine = engine)
        assertNull(client.reverse(LatLng(1.0, 2.0)))
        assertEquals(1, calls)
        val signedOut = GeoProxyClient(endpoint, "anon-key", bearer = { null }, engine = engine)
        assertNull(signedOut.overpass(BoundingBox(-0.2, 51.4, -0.1, 51.6)))
        assertEquals(1, calls)
        assertNull(GeoProxy.payload("""{"ok":true,"body":null}"""))
        assertTrue(GeoProxy.payload("""{"body":{"elements":[]}}""")!!.contains("elements"))
    }
}
