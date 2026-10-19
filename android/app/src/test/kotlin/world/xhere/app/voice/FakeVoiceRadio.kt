package world.xhere.app.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Radio stand-in. Tests push packets. Nothing here touches Bluetooth. */
class FakeVoiceRadio : VoiceRadio {
    private val _packets = MutableSharedFlow<HeardPacket>(extraBufferCapacity = 32)
    override val packets: SharedFlow<HeardPacket> = _packets.asSharedFlow()
    private val _advertising = MutableStateFlow<String?>(null)
    override val advertisingVoiceId: StateFlow<String?> = _advertising.asStateFlow()
    var foregroundMode: VoiceScanMode? = null
        private set
    var backgroundEnabled: Boolean? = null
        private set

    override fun offer(packet: HeardPacket) {
        _packets.tryEmit(packet)
    }

    override fun foreground(mode: VoiceScanMode) {
        foregroundMode = mode
    }

    override fun stopForeground() {
        foregroundMode = null
    }

    override fun background(enabled: Boolean) {
        backgroundEnabled = enabled
    }

    override fun advertise(voiceId: String) {
        _advertising.value = voiceId
    }

    override fun stopAdvertising() {
        _advertising.value = null
    }
}
