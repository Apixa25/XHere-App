package world.xhere.app.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The viewport the map last asked for. Explore reads the same box. */
class MapAnchor {
    private val _frame = MutableStateFlow<CameraFrame?>(null)
    val frame: StateFlow<CameraFrame?> = _frame.asStateFlow()

    fun publish(frame: CameraFrame) {
        _frame.value = frame
    }
}
