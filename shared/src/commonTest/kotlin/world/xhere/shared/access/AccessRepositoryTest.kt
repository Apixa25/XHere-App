package world.xhere.shared.access

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.rules.CreateDropRequest
import world.xhere.shared.voice.VoiceClips

class AccessRepositoryTest {
    @Test
    fun aSecondPersonChangesTheMajorityAndALongClipIsRefused() = runTest {
        val store = JamestownDemo.store()
        val client = InMemoryXHere(store, JamestownDemo.guide)
        val spot = LatLng(42.09636, -79.24202)
        val key = AccessPlaces.cell(spot)
        val first = client.access.submit(key, spot, AccessAnswer.YES, AccessAnswer.YES, AccessAnswer.NO, "ramp by the side door")
        assertEquals(AccessStatus.PARTIAL, first.status)
        assertEquals("ramp by the side door", first.myNotes)
        assertEquals(1, first.sampleSize)

        client.viewer = JamestownDemo.viewer
        val second = client.access.submit(key, spot, AccessAnswer.YES, AccessAnswer.YES, AccessAnswer.YES, null)
        assertEquals(AccessAnswer.YES, second.stepFree)
        assertEquals(AccessAnswer.UNKNOWN, second.restroom)
        assertEquals(AccessStatus.ACCESSIBLE, second.status)
        assertEquals(2, second.sampleSize)
        assertNull(second.myNotes)

        client.viewer = JamestownDemo.guide
        assertEquals("ramp by the side door", client.access.summary(key)?.myNotes)

        val clip = MediaUpload(
            storageBucket = VoiceClips.BUCKET,
            storagePath = "${JamestownDemo.guide.id}/clip.m4a",
            mediaKind = MediaKind.AUDIO,
            mimeType = "audio/mp4",
            byteSize = 400_000,
            durationS = 10.0,
        )
        val error = kotlin.test.assertFailsWith<world.xhere.shared.data.XHereApiException> {
            client.drops.createDrop(
                CreateDropRequest(
                    categorySlug = "note",
                    latitude = spot.latitude,
                    longitude = spot.longitude,
                    title = "Voice note",
                    body = "hello",
                    attributes = kotlinx.serialization.json.JsonObject(emptyMap()),
                    tags = emptyList(),
                    revealRadiusM = null,
                    expiresAt = null,
                    displayAnonymous = false,
                    media = listOf(clip),
                    acknowledgeGuidelines = false,
                ),
            )
        }
        assertEquals("voice_too_large", error.code)
    }
}
