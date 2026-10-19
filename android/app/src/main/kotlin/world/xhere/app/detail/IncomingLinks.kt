package world.xhere.app.detail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import world.xhere.shared.share.ShareLinks
import world.xhere.shared.voice.VoiceLinks

/**
 * A link the activity received, held until the shell can open it.
 * Drop links stay on [pending]. Object links stay on [pendingThing].
 */
class IncomingLinks {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()
    private val _pendingThing = MutableStateFlow<String?>(null)
    val pendingThing: StateFlow<String?> = _pendingThing.asStateFlow()

    fun offer(url: String?) {
        val drop = ShareLinks.shortId(url)
        if (drop != null) {
            _pending.value = drop
            return
        }
        val thing = VoiceLinks.voiceId(url) ?: return
        _pendingThing.value = thing
    }

    fun consume() {
        _pending.value = null
    }

    fun consumeThing() {
        _pendingThing.value = null
    }
}
