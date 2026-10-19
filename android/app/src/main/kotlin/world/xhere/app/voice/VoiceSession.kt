package world.xhere.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import world.xhere.shared.data.XHereServices
import world.xhere.shared.voice.RssiRange
import world.xhere.shared.voice.VoiceProtocol

/**
 * Turns advertisements into public things.
 * The ids already counted this process stay in memory. They are not written
 * as "who heard this," and they are not kept after the process ends.
 */
class VoiceSession(
    private val radio: VoiceRadio,
    private val services: XHereServices,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val surfaces = mutableSetOf<VoiceSurface>()
    private val pending = linkedMapOf<String, HeardPacket>()
    private val noted = mutableSetOf<String>()
    private val gate = Mutex()
    private var nextResolve = 0L

    private val _nearby = MutableStateFlow<List<NearbyThing>>(emptyList())
    val nearby: StateFlow<List<NearbyThing>> = _nearby.asStateFlow()
    private val _selected = MutableStateFlow<NearbyThing?>(null)
    val selected: StateFlow<NearbyThing?> = _selected.asStateFlow()
    val speaking: StateFlow<String?> = radio.advertisingVoiceId
    private val _unresolved = MutableStateFlow<List<String>>(emptyList())
    val unresolved: StateFlow<List<String>> = _unresolved.asStateFlow()

    init {
        scope.launch {
            radio.packets.collect { packet -> hearPacket(packet) }
        }
    }

    fun watch(surface: VoiceSurface) {
        val changed = synchronized(surfaces) { surfaces.add(surface) }
        if (changed) applyScan()
    }

    fun unwatch(surface: VoiceSurface) {
        val changed = synchronized(surfaces) { surfaces.remove(surface) }
        if (changed) applyScan()
    }

    fun setBackgroundOptIn(enabled: Boolean) {
        if (synchronized(surfaces) { surfaces.isEmpty() }) radio.background(enabled)
    }

    fun speak(voiceId: String) {
        radio.advertise(voiceId)
    }

    fun stopSpeaking() {
        radio.stopAdvertising()
    }

    fun clearSelection() {
        _selected.value = null
    }

    suspend fun open(voiceId: String) {
        if (!VoiceProtocol.isVoiceId(voiceId)) return
        val hit = runCatching {
            services.things.resolve(listOf(VoiceProtocol.xhereKey(voiceId)))
        }.getOrNull()?.firstOrNull() ?: return
        _selected.value = NearbyThing(
            thing = hit.thing,
            key = hit.key,
            rssi = 0,
            txAt1m = RssiRange.REFERENCE_TX_AT_1M,
            band = null,
            meters = null,
        )
    }

    suspend fun hearPacket(packet: HeardPacket) {
        val key = VoiceProtocol.normalizeKey(packet.key) ?: return
        gate.withLock {
            pending[key] = packet.copy(key = key)
            val now = clock()
            if (now < nextResolve && _nearby.value.isNotEmpty()) {
                refreshLocal()
                return
            }
            nextResolve = now + RESOLVE_GAP_MS
            resolvePending()
        }
    }

    private suspend fun resolvePending() {
        val keys = pending.keys.take(VoiceProtocol.MAX_KEYS)
        if (keys.isEmpty()) return
        val resolved = runCatching { services.things.resolve(keys) }.getOrElse { emptyList() }
        val cards = resolved.map { hit ->
            val packet = pending[hit.key]
            val rssi = packet?.rssi ?: 0
            val tx = packet?.txAt1m ?: RssiRange.REFERENCE_TX_AT_1M
            NearbyThing(
                thing = hit.thing,
                key = hit.key,
                rssi = rssi,
                txAt1m = tx,
                band = if (packet == null) null else RssiRange.band(rssi, tx),
                meters = if (packet == null) null else RssiRange.meters(rssi, tx),
            )
        }.sortedBy { it.meters ?: Double.MAX_VALUE }
        _nearby.value = cards
        _unresolved.value = pending.keys.filter { key -> cards.none { it.key == key } }
        val fresh = cards.map { it.thing.voiceId }.filter { it !in noted }
        if (fresh.isEmpty()) return
        val hearKeys = cards.filter { it.thing.voiceId in fresh }.map { it.key }
        val counted = runCatching { services.things.hear(hearKeys) }.getOrNull() ?: return
        if (counted < 0) return
        noted += fresh
        _nearby.value = _nearby.value.map { item ->
            if (item.thing.voiceId in fresh) {
                item.copy(thing = item.thing.copy(seenCount = item.thing.seenCount + 1))
            } else {
                item
            }
        }
    }

    private fun refreshLocal() {
        _nearby.value = _nearby.value.map { item ->
            val packet = pending[item.key] ?: return@map item
            item.copy(
                rssi = packet.rssi,
                txAt1m = packet.txAt1m,
                band = RssiRange.band(packet.rssi, packet.txAt1m),
                meters = RssiRange.meters(packet.rssi, packet.txAt1m),
            )
        }
    }

    private fun applyScan() {
        val active = synchronized(surfaces) { surfaces.toSet() }
        if (active.isEmpty()) {
            radio.stopForeground()
            radio.background(true)
            return
        }
        val mode = if (VoiceSurface.SHEET in active || VoiceSurface.AR in active) {
            VoiceScanMode.LOW_LATENCY
        } else {
            VoiceScanMode.BALANCED
        }
        radio.foreground(mode)
    }

    private companion object {
        const val RESOLVE_GAP_MS = 4_000L
    }
}
