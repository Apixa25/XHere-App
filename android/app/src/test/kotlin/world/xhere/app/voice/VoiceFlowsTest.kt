package world.xhere.app.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.app.TestXHereApplication
import world.xhere.app.detail.IncomingLinks
import world.xhere.app.ui.theme.XHereTheme
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.model.CreateThingRequest
import world.xhere.shared.model.Profile
import world.xhere.shared.model.UserTier
import world.xhere.shared.voice.DistanceBand
import world.xhere.shared.voice.RssiRange
import world.xhere.shared.voice.VoiceProtocol
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestXHereApplication::class)
class VoiceFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tenMetersShowsFarAndTheStory() {
        val rssi = RssiRange.rssiForMeters(10.0)
        val meters = RssiRange.meters(rssi, RssiRange.REFERENCE_TX_AT_1M)
        assertTrue(meters in 8.0..12.0)
        val nearby = NearbyThing(
            thing = sample().copy(title = "Bench", story = "It remembers the mural."),
            key = "xhere:0123456789ab",
            rssi = rssi,
            txAt1m = RssiRange.REFERENCE_TX_AT_1M,
            band = RssiRange.band(rssi, RssiRange.REFERENCE_TX_AT_1M),
            meters = meters,
        )
        assertEquals(DistanceBand.FAR, nearby.band)
        compose.setContent {
            XHereTheme {
                ThingStoryCard(
                    thing = nearby,
                    reporting = false,
                    busy = false,
                    onReport = {},
                    onSendReport = { _, _ -> },
                    onDismissReport = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Far").assertIsDisplayed()
        compose.onNodeWithText("It remembers the mural.").assertIsDisplayed()
    }

    @Test
    fun aHearingIsCountedOnceAndDoesNotNameTheListener() = runBlocking {
        val owner = Profile(
            id = Uuid.parse("00000000-0000-4000-8000-0000000000aa"),
            handle = "steven",
            displayName = "Steven",
            tier = UserTier.REGULAR,
            createdAt = Instant.parse("2026-10-18T05:00:00Z"),
        )
        val services = InMemoryXHere(JamestownDemo.store(), owner)
        val card = services.things.create(
            CreateThingRequest(title = "Bench", story = "It remembers the mural.", categorySlug = "note"),
        )
        var now = 1_000L
        val session = VoiceSession(FakeVoiceRadio(), services, clock = { now })
        val rssi = RssiRange.rssiForMeters(10.0)
        val packet = HeardPacket(VoiceProtocol.xhereKey(card.voiceId), rssi, RssiRange.REFERENCE_TX_AT_1M)
        session.hearPacket(packet)
        val shown = session.nearby.value.single()
        assertEquals(DistanceBand.FAR, shown.band)
        assertEquals("It remembers the mural.", shown.thing.story)
        assertTrue(shown.meters!! in 8.0..12.0)
        now = 20_000L
        session.hearPacket(packet)
        val stored = services.things.resolve(listOf(card.voiceId)).single()
        assertEquals(1L, stored.thing.seenCount)
        assertTrue(owner.id.toString() !in stored.thing.story)
    }

    @Test
    fun anObjectLinkDoesNotOpenAsADrop() {
        val links = IncomingLinks()
        links.offer("https://xhere.world/t/0123456789ab")
        assertNull(links.pending.value)
        assertEquals("0123456789ab", links.pendingThing.value)
        links.offer("xhere://t/0123456789ab")
        assertEquals("0123456789ab", links.pendingThing.value)
        links.offer("https://xhere.world/d/lunch")
        assertEquals("lunch", links.pending.value)
        assertEquals("0123456789ab", links.pendingThing.value)
    }

    private fun sample() = runBlocking {
        val owner = Profile(
            id = Uuid.parse("00000000-0000-4000-8000-0000000000bb"),
            handle = "ada",
            displayName = "Ada",
            tier = UserTier.REGULAR,
            createdAt = Instant.parse("2026-10-18T05:00:00Z"),
        )
        InMemoryXHere(JamestownDemo.store(), owner).things.create(
            CreateThingRequest(title = "Bench", story = "Story", categorySlug = "note"),
        )
    }
}
