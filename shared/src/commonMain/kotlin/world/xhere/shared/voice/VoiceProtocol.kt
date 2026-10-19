package world.xhere.shared.voice

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Everything Has a Voice, on the air.
 *
 * A legacy BLE advertising packet is 31 bytes. The object is a 6-byte id
 * inside one of those packets, so a tiny advertiser does not need a
 * connection and a phone does not need a tap. NFC is a separate, optional
 * close-range record and is not how the object is heard.
 *
 * The 16-bit UUID 0xFCE1 is provisional. It is not assigned by the Bluetooth
 * SIG. A harvesting tag that cannot spare a flags AD may send the service-data
 * AD alone.
 */
object VoiceProtocol {
    const val SERVICE_UUID_16: Int = 0xFCE1
    const val SERVICE_UUID: String = "0000fce1-0000-1000-8000-00805f9b34fb"
    const val EDDYSTONE_UUID_16: Int = 0xFEAA
    const val EDDYSTONE_UUID: String = "0000feaa-0000-1000-8000-00805f9b34fb"
    /** ASCII "XHERE" plus version 1. Ten bytes, Eddystone-UID namespace. */
    const val NAMESPACE_HEX: String = "58484552450100000001"
    const val VERSION: Int = 1
    const val LEGACY_ADV_MAX: Int = 31
    /** Floor for a future energy-harvesting tag. Phones advertise faster. */
    const val MIN_ADVERTISEMENTS_PER_MINUTE: Int = 4
    const val APPLE_COMPANY_ID: Int = 0x004C
    const val RESOLVE_PER_MINUTE: Int = 15
    const val HEAR_PER_TEN_MINUTES: Int = 48
    const val MAX_KEYS: Int = 24
    const val REPORTS_TO_HIDE: Int = 5

    private val VOICE_ID = Regex("^[0-9a-f]{12}$")
    private val UID_KEY = Regex("^uid:[0-9a-f]{20}:[0-9a-f]{12}$")
    private val IBEACON_KEY = Regex(
        "^ibeacon:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9]{1,5}:[0-9]{1,5}$",
    )
    private val URL_BODY = Regex("^https?://[a-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{1,160}$")

    fun isVoiceId(value: String?): Boolean = value != null && VOICE_ID.matches(value)

    fun xhereKey(voiceId: String): String = "xhere:$voiceId"

    /** Bytes after the 16-bit UUID, for a phone that builds the AD header itself. */
    fun compactServiceData(voiceId: String, txAt1m: Int = RssiRange.REFERENCE_TX_AT_1M): ByteArray {
        require(isVoiceId(voiceId)) { "voice id" }
        return byteArrayOf(VERSION.toByte()) + hexToBytes(voiceId) + byteArrayOf(clampTx(txAt1m))
    }

    /**
     * One legacy advertising packet: flags, the 16-bit service UUID list
     * (so a background scan filter can match), and the service-data AD.
     * Nineteen bytes. The service-data AD alone is enough to parse.
     */
    fun legacyPacket(voiceId: String, txAt1m: Int = RssiRange.REFERENCE_TX_AT_1M): ByteArray {
        val payload = compactServiceData(voiceId, txAt1m)
        val service = byteArrayOf(
            (1 + 2 + payload.size).toByte(),
            0x16,
            0xE1.toByte(),
            0xFC.toByte(),
        ) + payload
        val packet = byteArrayOf(0x02, 0x01, 0x06, 0x03, 0x03, 0xE1.toByte(), 0xFC.toByte()) + service
        check(packet.size <= LEGACY_ADV_MAX) { "legacy packet is ${packet.size} bytes" }
        return packet
    }

    fun parseLegacy(packet: ByteArray): List<HeardId> {
        val found = mutableListOf<HeardId>()
        var index = 0
        while (index < packet.size) {
            val length = packet[index].u()
            if (length == 0) break
            if (index + 1 + length > packet.size) break
            val type = packet[index + 1].u()
            val data = packet.copyOfRange(index + 2, index + 1 + length)
            when (type) {
                0x16 -> {
                    if (data.size >= 3) {
                        val uuid = data[0].u() or (data[1].u() shl 8)
                        fromServiceData(uuid, data.copyOfRange(2, data.size))?.let(found::add)
                    }
                }
                0xFF -> {
                    if (data.size >= 3) {
                        val company = data[0].u() or (data[1].u() shl 8)
                        fromManufacturer(company, data.copyOfRange(2, data.size))?.let(found::add)
                    }
                }
            }
            index += 1 + length
        }
        return found
    }

    fun fromServiceData(uuid16: Int, payload: ByteArray): HeardId? = when (uuid16) {
        SERVICE_UUID_16 -> parseCompact(payload)
        EDDYSTONE_UUID_16 -> parseEddystone(payload)
        else -> null
    }

    fun fromManufacturer(companyId: Int, payload: ByteArray): HeardId? {
        if (companyId != APPLE_COMPANY_ID || payload.size < 23) return null
        if (payload[0].u() != 0x02 || payload[1].u() != 0x15) return null
        val uuid = bytesToHex(payload.copyOfRange(2, 18))
        val formatted = uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" +
            uuid.substring(12, 16) + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20, 32)
        val major = (payload[18].u() shl 8) or payload[19].u()
        val minor = (payload[20].u() shl 8) or payload[21].u()
        val tx = payload[22].toInt()
        return HeardId(key = "ibeacon:$formatted:$major:$minor", txAt1m = tx)
    }

    /**
     * The lookup key stored on a tag and sent to `resolve_things`.
     * Null when the text is not a voice id, Eddystone-UID, Eddystone-URL, iBeacon, or XHere key.
     */
    fun normalizeKey(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim().lowercase()
        if (value.length > 180) return null
        if (VOICE_ID.matches(value)) return xhereKey(value)
        if (value.startsWith("xhere:")) {
            val id = value.removePrefix("xhere:")
            return if (VOICE_ID.matches(id)) value else null
        }
        if (UID_KEY.matches(value)) return value
        if (value.matches(Regex("^url:xhere://t/[0-9a-f]{12}$"))) return value
        if (value.matches(Regex("^url:https://xhere\\.world/t/[0-9a-f]{12}$"))) return value
        if (value.startsWith("ibeacon:")) return canonicalIBeacon(value)
        if (value.startsWith("url:")) {
            val url = value.removePrefix("url:")
            return if (URL_BODY.matches(url)) "url:$url" else null
        }
        return null
    }

    /** Background scans must be filtered. An empty list is not a valid scan. */
    fun backgroundFilters(): List<ScanFilterSpec> = listOf(
        ScanFilterSpec(kind = "service_data", uuid16 = SERVICE_UUID_16),
        ScanFilterSpec(kind = "service_data", uuid16 = EDDYSTONE_UUID_16),
        ScanFilterSpec(kind = "manufacturer", companyId = APPLE_COMPANY_ID),
    )

    private fun parseCompact(payload: ByteArray): HeardId? {
        if (payload.size < 8 || payload[0].u() != VERSION) return null
        val id = bytesToHex(payload.copyOfRange(1, 7))
        if (!VOICE_ID.matches(id)) return null
        return HeardId(xhereKey(id), payload[7].toInt())
    }

    private fun parseEddystone(payload: ByteArray): HeardId? {
        if (payload.isEmpty()) return null
        return when (payload[0].u()) {
            0x00 -> {
                if (payload.size < 18) return null
                val namespace = bytesToHex(payload.copyOfRange(2, 12))
                val instance = bytesToHex(payload.copyOfRange(12, 18))
                HeardId("uid:$namespace:$instance", payload[1].toInt())
            }
            0x10 -> {
                if (payload.size < 3) return null
                val url = expandEddystoneUrl(payload[2].u(), payload.copyOfRange(3, payload.size)) ?: return null
                val key = normalizeKey("url:$url") ?: return null
                HeardId(key, payload[1].toInt())
            }
            else -> null
        }
    }

    private fun canonicalIBeacon(value: String): String? {
        if (!IBEACON_KEY.matches(value)) return null
        val parts = value.split(":")
        val major = parts[parts.size - 2].toIntOrNull() ?: return null
        val minor = parts[parts.size - 1].toIntOrNull() ?: return null
        if (major !in 0..65535 || minor !in 0..65535) return null
        val uuid = parts.drop(1).dropLast(2).joinToString(":")
        return "ibeacon:$uuid:$major:$minor"
    }

    private fun expandEddystoneUrl(scheme: Int, body: ByteArray): String? {
        val prefix = when (scheme) {
            0x00 -> "http://www."
            0x01 -> "https://www."
            0x02 -> "http://"
            0x03 -> "https://"
            else -> return null
        }
        val expansions = arrayOf(
            ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/",
            ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov",
        )
        val text = StringBuilder(prefix)
        for (byte in body) {
            val code = byte.u()
            if (code < expansions.size) text.append(expansions[code]) else text.append(byte.toInt().toChar())
        }
        return text.toString().lowercase()
    }

    private fun clampTx(tx: Int): Byte = tx.coerceIn(-127, 20).toByte()
}

data class HeardId(val key: String, val txAt1m: Int)

data class ScanFilterSpec(val kind: String, val uuid16: Int? = null, val companyId: Int? = null)

/**
 * Approximate range from the calibrated RSSI at 1 meter.
 * Immediate is under 1.5 m, near is under 5 m, and far is everything else
 * the radio still delivered. Ten meters (about 30 feet) is far, and it is
 * still shown. The path-loss exponent 2.2 is a middle indoor value.
 */
object RssiRange {
    const val REFERENCE_TX_AT_1M: Int = -59
    const val PATH_LOSS: Double = 2.2
    private const val IMMEDIATE_M: Double = 1.5
    private const val NEAR_M: Double = 5.0

    fun meters(rssi: Int, txAt1m: Int, n: Double = PATH_LOSS): Double {
        if (rssi == 0) return NEAR_M
        val exponent = (txAt1m - rssi) / (10.0 * n)
        return 10.0.pow(exponent).coerceIn(0.05, 50.0)
    }

    fun rssiForMeters(meters: Double, txAt1m: Int = REFERENCE_TX_AT_1M, n: Double = PATH_LOSS): Int {
        val loss = 10.0 * n * log10(meters.coerceAtLeast(0.05))
        return (txAt1m - loss).roundToInt()
    }

    fun band(rssi: Int, txAt1m: Int, n: Double = PATH_LOSS): DistanceBand {
        val distance = meters(rssi, txAt1m, n)
        return when {
            distance < IMMEDIATE_M -> DistanceBand.IMMEDIATE
            distance < NEAR_M -> DistanceBand.NEAR
            else -> DistanceBand.FAR
        }
    }
}

enum class DistanceBand {
    IMMEDIATE,
    NEAR,
    FAR,
}

/** https://xhere.world/t/{voiceId} and xhere://t/{voiceId}. The id is the 6-byte voice id. */
object VoiceLinks {
    const val ORIGIN: String = "https://xhere.world"

    fun https(voiceId: String): String = "$ORIGIN/t/$voiceId"

    fun custom(voiceId: String): String = "xhere://t/$voiceId"

    fun voiceId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        val rest = when {
            trimmed.startsWith("$ORIGIN/t/") -> trimmed.removePrefix("$ORIGIN/t/")
            trimmed.startsWith("xhere://t/") -> trimmed.removePrefix("xhere://t/")
            else -> return null
        }
        val id = rest.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
        if (id.isEmpty() || '/' in id || !VoiceProtocol.isVoiceId(id)) return null
        return id
    }
}

/**
 * Optional NFC extra. Two URI records in one message: the https URL first,
 * then the xhere:// URL. Hearing the object does not use this.
 */
object NdefUrls {
    fun message(voiceId: String): ByteArray {
        require(VoiceProtocol.isVoiceId(voiceId)) { "voice id" }
        return uriRecord(0x04, "xhere.world/t/$voiceId", first = true, last = false) +
            uriRecord(0x00, "xhere://t/$voiceId", first = false, last = true)
    }

    private fun uriRecord(prefix: Int, rest: String, first: Boolean, last: Boolean): ByteArray {
        val payload = byteArrayOf(prefix.toByte()) + rest.encodeToByteArray()
        require(payload.size < 256)
        var flags = 0x11
        if (first) flags = flags or 0x80
        if (last) flags = flags or 0x40
        return byteArrayOf(flags.toByte(), 0x01, payload.size.toByte(), 'U'.code.toByte()) + payload
    }
}

private fun Byte.u(): Int = toInt() and 0xFF

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
