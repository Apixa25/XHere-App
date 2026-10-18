package world.xhere.app.detail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import world.xhere.shared.share.ShareLinks

/** The drop link the activity received, held until the map shell can open it. */
class IncomingLinks {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(url: String?) {
        val id = ShareLinks.shortId(url) ?: return
        _pending.value = id
    }

    fun consume() {
        _pending.value = null
    }
}
