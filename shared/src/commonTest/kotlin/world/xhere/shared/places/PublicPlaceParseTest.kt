package world.xhere.shared.places

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng

class PublicPlaceParseTest {
    @Test
    fun wikipediaKeepsAShortAttributedArticleAndSkipsAPageWithNoPoint() {
        val places = WikipediaGeo.parse(
            """
            {"query":{"pages":[
              {"pageid":42,"title":"Riverside Hall","extract":"A hall by the river.","thumbnail":{"source":"https://upload.wikimedia.org/a.jpg"},"coordinates":[{"lat":51.5,"lon":-0.12}]},
              {"pageid":7,"title":"No point"}
            ]}}
            """.trimIndent(),
        )
        val hall = places.single()
        assertEquals("enwiki:42", hall.id)
        assertEquals("Riverside Hall", hall.title)
        assertEquals("A hall by the river.", hall.summary)
        assertEquals("https://upload.wikimedia.org/a.jpg", hall.imageUrl)
        assertEquals("https://en.wikipedia.org/wiki/Riverside_Hall", hall.pageUrl)
        assertEquals(PublicSources.WIKI_CREDIT, hall.attribution)
        assertEquals(PublicSources.WIKI_LICENSE, hall.license)
        assertEquals(PublicPlaceKind.ARTICLE, hall.kind)
        assertEquals(LatLng(51.5, -0.12), hall.location)
    }

    @Test
    fun commonsKeepsAFreePhotoAndDropsAReservedOne() {
        val places = CommonsGeo.parse(
            """
            {"query":{"pages":[
              {"pageid":9,"title":"File:River_hall.jpg","coordinates":[{"lat":48.8,"lon":2.3}],
               "imageinfo":[{"thumburl":"https://upload.wikimedia.org/thumb.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:River_hall.jpg",
                 "extmetadata":{"LicenseShortName":{"value":"CC BY-SA 4.0"},"Artist":{"value":"<a href=\"https://example.test\">Ada</a>"}}}]},
              {"pageid":10,"title":"File:Locked.jpg","coordinates":[{"lat":48.8,"lon":2.3}],
               "imageinfo":[{"descriptionurl":"https://commons.wikimedia.org/wiki/File:Locked.jpg",
                 "extmetadata":{"LicenseShortName":{"value":"All rights reserved"}}}]}
            ]}}
            """.trimIndent(),
        )
        val photo = places.single()
        assertEquals("commons:9", photo.id)
        assertEquals("River hall.jpg", photo.title)
        assertEquals("Photo by Ada, Wikimedia Commons", photo.attribution)
        assertEquals("CC BY-SA 4.0", photo.license)
        assertEquals(PublicPlaceKind.PHOTO, photo.kind)
        assertTrue(photo.pageUrl.startsWith("https://commons.wikimedia.org/"))
    }

    @Test
    fun osmKeepsANamedMemorialAndSkipsAPlainBuilding() {
        val places = OsmHistoric.parse(
            """
            {"elements":[
              {"type":"node","id":15,"lat":35.6,"lon":139.7,"tags":{"historic":"memorial","name":"Peace stone"}},
              {"type":"way","id":16,"center":{"lat":35.7,"lon":139.8},"tags":{"historic":"building","name":"Warehouse"}},
              {"type":"node","id":17,"lat":35.6,"lon":139.7,"tags":{"historic":"yes"}}
            ]}
            """.trimIndent(),
        )
        val stone = places.single()
        assertEquals("osm:node/15", stone.id)
        assertEquals("Peace stone", stone.title)
        assertEquals("https://www.openstreetmap.org/node/15", stone.pageUrl)
        assertEquals(PublicSources.OSM_CREDIT, stone.attribution)
        assertEquals(PublicSources.OSM_LICENSE, stone.license)
        assertEquals(PublicPlaceKind.HISTORIC, stone.kind)
        assertTrue(stone.summary!!.contains("memorial"))
    }

    @Test
    fun wikidataReadsAPointAndPrefersTheArticleLink() {
        val places = WikidataHistoric.parse(
            """
            {"results":{"bindings":[
              {"item":{"value":"http://www.wikidata.org/entity/Q99"},
               "itemLabel":{"value":"Old gate"},
               "coord":{"value":"Point(12.49 41.89)"},
               "article":{"value":"https://en.wikipedia.org/wiki/Old_gate"},
               "desc":{"value":"A city gate."}},
              {"item":{"value":"http://www.wikidata.org/entity/Q100"},
               "itemLabel":{"value":"Q100"},
               "coord":{"value":"Point(12.5 41.9)"}}
            ]}}
            """.trimIndent(),
        )
        val gate = places.single()
        assertEquals("wikidata:Q99", gate.id)
        assertEquals("Old gate", gate.title)
        assertEquals("A city gate.", gate.summary)
        assertEquals(41.89, gate.latitude)
        assertEquals(12.49, gate.longitude)
        assertEquals("https://en.wikipedia.org/wiki/Old_gate", gate.pageUrl)
        assertEquals(PublicSources.WIKIDATA_LICENSE, gate.license)
    }

    @Test
    fun queriesStayGlobal() {
        val box = BoundingBox(minLongitude = -0.2, minLatitude = 51.4, maxLongitude = -0.1, maxLatitude = 51.6)
        val overpass = PublicSources.overpassQuery(box)
        val wiki = PublicSources.wikidataQuery(box)
        assertFalse(overpass.contains("Jamestown", ignoreCase = true))
        assertFalse(wiki.contains("Jamestown", ignoreCase = true))
        assertTrue(overpass.contains("historic"))
        assertTrue(wiki.contains("wdt:P625"))
        assertTrue(wiki.contains("-0.2"))
        assertTrue(wiki.contains("51.4"))
        assertFalse(PublicSources.licenseAllowed("All rights reserved"))
        assertTrue(PublicSources.licenseAllowed("CC BY 4.0"))
    }

    @Test
    fun theClientSendsAUserAgentCachesAndSkipsWikidataWhenOsmHits() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls += 1
            assertEquals(PublicSources.USER_AGENT, request.headers[HttpHeaders.UserAgent])
            val url = request.url.toString()
            val body = when {
                url.startsWith(PublicSources.WIKIPEDIA) ->
                    """{"query":{"pages":[{"pageid":1,"title":"Bridge","extract":"A bridge.","coordinates":[{"lat":51.5,"lon":-0.12}]}]}}"""
                url.startsWith(PublicSources.COMMONS) -> """{"query":{"pages":[]}}"""
                url.startsWith(PublicSources.OVERPASS) ->
                    """{"elements":[{"type":"node","id":3,"lat":51.51,"lon":-0.11,"tags":{"historic":"memorial","name":"Stone"}}]}"""
                else -> error("unexpected $url")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = PublicPlaceClient(engine, minIntervalMs = 0, clock = frozen())
        val box = BoundingBox(-0.2, 51.4, -0.1, 51.6)
        val first = client.around(box)
        assertEquals(setOf("enwiki:1", "osm:node/3"), first.map { it.id }.toSet())
        assertEquals(3, calls)
        val second = client.around(box)
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
        assertEquals(3, calls)
        assertNull(first.first { it.id == "enwiki:1" }.imageUrl)
    }

    private fun frozen(): Clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-20T12:00:00Z")
    }
}
