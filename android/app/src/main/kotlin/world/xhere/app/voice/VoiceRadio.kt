package world.xhere.app.voice

import android.bluetooth.le.ScanResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import world.xhere.shared.model.ThingCard
import world.xhere.shared.voice.DistanceBand
import world.xhere.shared.voice.VoiceProtocol

/** One advertisement the radio delivered. The key is not yet a person. */
data class HeardPacket(
    val key: String,
    val rssi: Int,
    val txAt1m: Int,
)

data class NearbyThing(
    val thing: ThingCard,
    val key: String,
    val rssi: Int,
    val txAt1m: Int,
    val band: DistanceBand?,
    val meters: Double?,
)

enum class VoiceScanMode {
    BALANCED,
    LOW_LATENCY,
}

enum class VoiceSurface {
    MAP,
    SHEET,
    AR,
}

/**
 * BLE advertise and scan. Implementations no-op when the radio is missing,
 * off, or the permission is denied, so a test process never needs hardware.
 */
interface VoiceRadio {
    val packets: SharedFlow<HeardPacket>
    val advertisingVoiceId: StateFlow<String?>

    fun offer(packet: HeardPacket)

    fun foreground(mode: VoiceScanMode)

    fun stopForeground()

    /** Starts the filtered background scan only when [enabled] is true. */
    fun background(enabled: Boolean)

    fun advertise(voiceId: String)

    fun stopAdvertising()
}

fun packetsOf(result: ScanResult): List<HeardPacket> {
    val bytes = result.scanRecord?.bytes ?: return emptyList()
    return VoiceProtocol.parseLegacy(bytes).map { heard ->
        HeardPacket(key = heard.key, rssi = result.rssi, txAt1m = heard.txAt1m)
    }
}
