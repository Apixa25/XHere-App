package world.xhere.shared.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceProtocolTest {
    private val voiceId = "0123456789ab"

    @Test
    fun legacyPacketFitsOneAdvertisingSlotAndRoundTrips() {
        val packet = VoiceProtocol.legacyPacket(voiceId)
        assertTrue(packet.size <= VoiceProtocol.LEGACY_ADV_MAX)
        assertTrue(packet.size < 31)
        val heard = VoiceProtocol.parseLegacy(packet).single()
        assertEquals(VoiceProtocol.xhereKey(voiceId), heard.key)
        assertEquals(RssiRange.REFERENCE_TX_AT_1M, heard.txAt1m)
        val again = VoiceProtocol.fromServiceData(VoiceProtocol.SERVICE_UUID_16, VoiceProtocol.compactServiceData(voiceId))
        assertEquals(heard, again)
    }

    @Test
    fun eddystoneUidAndAShortUrlBecomeLookupKeys() {
        val namespace = hexToBytes(VoiceProtocol.NAMESPACE_HEX)
        val instance = hexToBytes(voiceId)
        val uid = byteArrayOf(0x00, RssiRange.REFERENCE_TX_AT_1M.toByte()) + namespace + instance
        val heard = VoiceProtocol.fromServiceData(VoiceProtocol.EDDYSTONE_UUID_16, uid)
        assertEquals("uid:${VoiceProtocol.NAMESPACE_HEX}:$voiceId", heard?.key)

        val urlFrame = byteArrayOf(0x10, RssiRange.REFERENCE_TX_AT_1M.toByte(), 0x03) + "x.co".encodeToByteArray()
        val url = VoiceProtocol.fromServiceData(VoiceProtocol.EDDYSTONE_UUID_16, urlFrame)
        assertEquals("url:https://x.co", url?.key)
    }

    @Test
    fun ibeaconManufacturerDataBecomesAKey() {
        val uuid = hexToBytes("11111111111141118111111111111111")
        val payload = byteArrayOf(0x02, 0x15) + uuid + byteArrayOf(0x00, 0x07, 0x00, 0x08, RssiRange.REFERENCE_TX_AT_1M.toByte())
        val heard = VoiceProtocol.fromManufacturer(VoiceProtocol.APPLE_COMPANY_ID, payload)
        assertEquals("ibeacon:11111111-1111-4111-8111-111111111111:7:8", heard?.key)
        assertNull(VoiceProtocol.fromManufacturer(0x0001, payload))
    }

    @Test
    fun normalizeAcceptsTheDocumentedKeyForms() {
        assertEquals("xhere:$voiceId", VoiceProtocol.normalizeKey(voiceId))
        assertEquals("xhere:$voiceId", VoiceProtocol.normalizeKey("XHERE:$voiceId"))
        assertEquals("uid:${VoiceProtocol.NAMESPACE_HEX}:$voiceId", VoiceProtocol.normalizeKey("uid:${VoiceProtocol.NAMESPACE_HEX}:$voiceId"))
        assertEquals("url:xhere://t/$voiceId", VoiceProtocol.normalizeKey("url:xhere://t/$voiceId"))
        assertEquals("url:https://xhere.world/t/$voiceId", VoiceProtocol.normalizeKey("url:https://xhere.world/t/$voiceId"))
        assertEquals("ibeacon:11111111-1111-4111-8111-111111111111:7:8", VoiceProtocol.normalizeKey("ibeacon:11111111-1111-4111-8111-111111111111:7:8"))
        assertNull(VoiceProtocol.normalizeKey("ibeacon:11111111-1111-4111-8111-111111111111:7"))
        assertNull(VoiceProtocol.normalizeKey("not a tag"))
    }

    @Test
    fun tenMetersIsFarAndStillAHearing() {
        val rssi = RssiRange.rssiForMeters(10.0)
        val meters = RssiRange.meters(rssi, RssiRange.REFERENCE_TX_AT_1M)
        assertTrue(meters in 8.0..12.0, "expected about 10 m, was $meters at rssi $rssi")
        assertEquals(DistanceBand.FAR, RssiRange.band(rssi, RssiRange.REFERENCE_TX_AT_1M))
        assertEquals(DistanceBand.IMMEDIATE, RssiRange.band(RssiRange.rssiForMeters(0.5), RssiRange.REFERENCE_TX_AT_1M))
        assertEquals(DistanceBand.NEAR, RssiRange.band(RssiRange.rssiForMeters(3.0), RssiRange.REFERENCE_TX_AT_1M))
    }

    @Test
    fun ndefCarriesBothUrlsAndBackgroundFiltersAreNotEmpty() {
        val message = NdefUrls.message(voiceId)
        val text = message.decodeToString()
        assertTrue(text.contains("xhere.world/t/$voiceId"))
        assertTrue(text.contains("xhere://t/$voiceId"))
        assertEquals(3, VoiceProtocol.backgroundFilters().size)
        assertNotNull(VoiceLinks.voiceId("https://xhere.world/t/$voiceId"))
        assertEquals(voiceId, VoiceLinks.voiceId("xhere://t/$voiceId"))
        assertNull(VoiceLinks.voiceId("https://xhere.world/d/lunch"))
    }
}
