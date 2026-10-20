package world.xhere.app.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Practical map layers. All of them start off. Accessibility is the same
 * switch as Profile settings, so "Always show accessibility" survives a restart.
 */
data class MapLayers(
    val accessibility: Boolean = false,
    val crime: Boolean = false,
    val safety: Boolean = false,
    val liveConditions: Boolean = false,
    val townHall: Boolean = false,
)

interface MapLayerMemory {
    val layers: StateFlow<MapLayers>

    fun save(layers: MapLayers)
}

/** In-memory layers for previews and tests that do not need a phone. */
class MemoryMapLayers(initial: MapLayers = MapLayers()) : MapLayerMemory {
    private val state = MutableStateFlow(initial)
    override val layers: StateFlow<MapLayers> = state.asStateFlow()

    override fun save(layers: MapLayers) {
        state.value = layers
    }
}

class MapLayerPrefs(context: Context) : MapLayerMemory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val layers: StateFlow<MapLayers> = state.asStateFlow()

    override fun save(layers: MapLayers) {
        prefs.edit()
            .putBoolean(ACCESS, layers.accessibility)
            .putBoolean(CRIME, layers.crime)
            .putBoolean(SAFETY, layers.safety)
            .putBoolean(LIVE, layers.liveConditions)
            .putBoolean(TOWN, layers.townHall)
            .apply()
        state.value = layers
    }

    private fun read(): MapLayers = MapLayers(
        accessibility = prefs.getBoolean(ACCESS, false),
        crime = prefs.getBoolean(CRIME, false),
        safety = prefs.getBoolean(SAFETY, false),
        liveConditions = prefs.getBoolean(LIVE, false),
        townHall = prefs.getBoolean(TOWN, false),
    )

    private companion object {
        const val FILE = "xhere_map_layers"
        const val ACCESS = "accessibility"
        const val CRIME = "crime"
        const val SAFETY = "safety"
        const val LIVE = "live_conditions"
        const val TOWN = "town_hall"
    }
}
