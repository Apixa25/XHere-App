package world.xhere.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import world.xhere.shared.Fixtures
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.model.CreateThingRequest
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ThingCard
import world.xhere.shared.voice.VoiceProtocol

class ThingRepositoryTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun hearingCountsTheObjectAndDoesNotNameTheListener() = runTest {
        val owner = Fixtures.profile(1)
        val services = services(owner)
        val card = services.things.create(
            CreateThingRequest(title = "Bench", story = "It remembers the mural.", categorySlug = "note"),
        )
        assertTrue(VoiceProtocol.isVoiceId(card.voiceId))
        assertEquals(1, services.things.hear(listOf(card.voiceId)))
        val resolved = services.things.resolve(listOf(card.voiceId)).single()
        assertEquals(1L, resolved.thing.seenCount)
        val encoded = json.encodeToString(ThingCard.serializer(), resolved.thing)
        assertTrue(owner.id.toString() !in encoded)
        assertTrue("owner" !in encoded)
        val again = services.things.resolve(listOf(VoiceProtocol.xhereKey(card.voiceId))).single()
        assertEquals(1L, again.thing.seenCount)
    }

    @Test
    fun unlistedAndRemovedThingsStayOffTheAir() = runTest {
        val owner = Fixtures.profile(1)
        val services = services(owner)
        val quiet = services.things.create(
            CreateThingRequest(title = "Chair", story = "Not on the air.", categorySlug = "note", visibility = "unlisted"),
        )
        assertTrue(services.things.resolve(listOf(quiet.voiceId)).isEmpty())
        val public = services.things.create(
            CreateThingRequest(title = "Mural", story = "The wall tells it.", categorySlug = "note"),
        )
        assertFailsWith<XHereApiException> {
            services.things.report(public.id, ReportReason.SPAM, null)
        }
        repeat(5) { index ->
            services.viewer = Fixtures.profile(index + 2)
            services.things.report(public.id, ReportReason.SPAM, null)
        }
        services.viewer = owner
        assertTrue(services.things.resolve(listOf(public.voiceId)).isEmpty())
    }

    @Test
    fun aSensitiveCategoryIsRejectedAndAForeignBeaconCanBeBound() = runTest {
        val services = services(Fixtures.profile(1))
        val denied = assertFailsWith<XHereApiException> {
            services.things.create(
                CreateThingRequest(title = "Siren", story = "Not an object voice.", categorySlug = "incident_report"),
            )
        }
        assertEquals("category_not_allowed", denied.code)
        val card = services.things.create(
            CreateThingRequest(title = "Door", story = "The shop keeps its hours here.", categorySlug = "note"),
        )
        val indoors = services.things.create(
            CreateThingRequest(title = "Gallery chair", story = "No pin. The broadcast is the place.", categorySlug = "note"),
        )
        assertEquals(null, indoors.lat)
        val key = "ibeacon:11111111-1111-4111-8111-111111111111:7:8"
        services.things.bindBeacon(card.id, key)
        assertEquals(card.voiceId, services.things.resolve(listOf(key)).single().thing.voiceId)
    }

    @Test
    fun resolveAllowsFifteenCallsAMinute() = runTest {
        val services = services(Fixtures.profile(9))
        repeat(VoiceProtocol.RESOLVE_PER_MINUTE) {
            services.things.resolve(listOf("aaaaaaaaaaaa"))
        }
        val limited = assertFailsWith<XHereApiException> {
            services.things.resolve(listOf("aaaaaaaaaaaa"))
        }
        assertEquals("rate_limited", limited.code)
    }

    private fun services(owner: world.xhere.shared.model.Profile): InMemoryXHere =
        InMemoryXHere(MemoryStore(Fixtures.taxonomy), owner)
}
