package world.xhere.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.media.ExifStrip
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.suggest.SuggestionQuery

class QuickDropRepositoryTest {
    @Test
    fun descriptorsAreRejectedWithTheFilterSlug() = runTest {
        val client = InMemoryXHere(MemoryStore(Fixtures.taxonomy), Fixtures.profile(tier = UserTier.REGULAR), clock = { Fixtures.now })
        val error = assertFailsWith<XHereApiException> {
            client.drops.createDrop(
                request(
                    category = "incident_report",
                    title = "Something happened nearby",
                    body = "a man in a hoodie",
                    attributes = buildJsonObject { put("occurred_at", Fixtures.now.toString()) },
                    acknowledge = true,
                ),
            )
        }
        assertEquals("content_not_allowed", error.code)
        assertEquals("person_descriptors", error.detail)
    }

    @Test
    fun newAccountsStopAtThreeDropsAnHour() = runTest {
        val client = InMemoryXHere(MemoryStore(Fixtures.taxonomy), Fixtures.profile(tier = UserTier.NEW), clock = { Fixtures.now })
        repeat(3) { index ->
            client.drops.createDrop(request(title = "Hourly note $index"))
        }
        val error = assertFailsWith<XHereApiException> {
            client.drops.createDrop(request(title = "Hourly note 4"))
        }
        assertEquals("rate_limited", error.code)
        assertEquals(XHereApiException.SQL_RATE_LIMITED, error.sqlState)
    }

    @Test
    fun uploadedPhotosLoseExifAndSuggestionsAreLogged() = runTest {
        val client = InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer, clock = { Fixtures.now })
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x0B,
        ) + "Exif\u0000\u0000GPS".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertTrue(ExifStrip.containsExifMarker(jpeg))
        val uploaded = client.media.uploadPhoto(jpeg, "image/jpeg")
        val stored = client.store.mediaBytes.getValue(uploaded.storagePath)
        assertTrue(uploaded.storagePath.startsWith(JamestownDemo.viewer.id.toString() + "/"))
        assertTrue(!ExifStrip.containsExifMarker(stored))
        val query = SuggestionQuery(text = "food truck street food")
        val suggestions = client.suggestions.suggest(query)
        assertEquals("food_truck", suggestions.first().slug)
        client.suggestions.log(query, suggestions.map { it.slug }, "note")
        assertEquals(1, client.store.suggestionLogs.size)
    }

    private fun request(
        category: String = "note",
        title: String = "A note about here",
        body: String? = "Something worth knowing.",
        attributes: JsonObject = JsonObject(emptyMap()),
        acknowledge: Boolean = false,
    ) = CreateDropRequest(
        categorySlug = category,
        latitude = 40.7128,
        longitude = -74.0060,
        title = title,
        body = body,
        attributes = attributes,
        tags = emptyList(),
        revealRadiusM = null,
        expiresAt = null,
        displayAnonymous = false,
        media = emptyList(),
        acknowledgeGuidelines = acknowledge,
    )
}
