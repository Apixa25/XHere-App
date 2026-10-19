package world.xhere.app.map

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The bottom bar asks every open sheet to go away.
 * One shared signal, so the map, the things list, and the profile sheet
 * all hear the same tap even when they live in different view models.
 */
class SheetSignals {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dismissals: SharedFlow<Unit> = requests.asSharedFlow()

    fun requestDismiss() {
        requests.tryEmit(Unit)
    }
}
