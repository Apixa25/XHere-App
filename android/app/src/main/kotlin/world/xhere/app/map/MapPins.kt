package world.xhere.app.map

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pins the author just deleted. The map hides them before the next viewport
 * fetch, and [restore] asks the map to load that pin again.
 */
class MapPins {
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _restored = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val restored: SharedFlow<String> = _restored.asSharedFlow()

    fun hide(shortId: String) {
        if (shortId.isBlank()) return
        _hidden.update { it + shortId }
    }

    fun restore(shortId: String) {
        if (shortId.isBlank()) return
        _hidden.update { it - shortId }
        _restored.tryEmit(shortId)
    }

    fun clear() {
        _hidden.value = emptySet()
    }
}
