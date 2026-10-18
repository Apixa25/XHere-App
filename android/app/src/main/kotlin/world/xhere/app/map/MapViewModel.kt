package world.xhere.app.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.app.location.DeviceLocation
import world.xhere.shared.data.XHereServices
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.MapFeature

/**
 * Map discovery. Every viewport and "near me" read goes through [XHereServices.drops],
 * which is `drops_in_view` / `drops_nearby` / `drop_teaser` on Supabase and the same
 * rules on the in-memory fake. Category chips are arguments to those calls, not a
 * second filter over a downloaded city.
 */
class MapViewModel(
    private val services: XHereServices,
    private val location: DeviceLocation,
    private val presetStore: FilterPresetStore,
    private val debounceMs: Long = 320,
) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState(presets = presetStore.all()))
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private val locationKick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var cameraJob: Job? = null
    private var lastFrame: CameraFrame? = null

    init {
        viewModelScope.launch {
            val taxonomy = runCatching { services.taxonomy.load() }.getOrNull()
            _state.update {
                it.copy(
                    taxonomy = taxonomy,
                    groups = taxonomy?.groups?.filter { group -> group.active }?.sortedBy { group -> group.sort }.orEmpty(),
                )
            }
        }
        viewModelScope.launch {
            locationKick.onStart { emit(Unit) }.collectLatest {
                if (!location.hasPermission()) return@collectLatest
                location.fixes().collect { fix -> onFix(fix) }
            }
        }
    }

    fun onCameraIdle(frame: CameraFrame) {
        cameraJob?.cancel()
        cameraJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            fetch(frame)
        }
    }

    fun onUserPanned() {
        _state.update { it.copy(userPanned = true, followNextFix = false) }
    }

    fun selectAll() = applySelection(FilterSelection())

    fun selectGroup(slug: String) = applySelection(FilterSelection(slugs = listOf(slug), activeGroup = slug))

    fun selectPreset(preset: FilterPreset) = applySelection(
        FilterSelection(slugs = preset.slugs, activePreset = preset.name),
    )

    fun showSubcategories(groupSlug: String) {
        _state.update { it.copy(subcategoryGroup = groupSlug) }
    }

    fun dismissSubcategories() {
        _state.update { it.copy(subcategoryGroup = null) }
    }

    fun applySubcategories(groupSlug: String, selected: Set<String>) {
        val all = _state.value.taxonomy?.categoriesIn(groupSlug)?.map { it.slug }?.toSet().orEmpty()
        val slugs = if (selected.isEmpty() || (all.isNotEmpty() && selected == all)) {
            listOf(groupSlug)
        } else {
            selected.sorted()
        }
        _state.update { it.copy(subcategoryGroup = null) }
        applySelection(FilterSelection(slugs = slugs, activeGroup = groupSlug))
    }

    fun showSavePreset() {
        if (_state.value.selection.slugs.isNullOrEmpty()) return
        _state.update { it.copy(savingPreset = true) }
    }

    fun dismissSavePreset() {
        _state.update { it.copy(savingPreset = false) }
    }

    fun savePreset(name: String) {
        val slugs = _state.value.selection.slugs
        if (slugs.isNullOrEmpty()) {
            _state.update { it.copy(savingPreset = false) }
            return
        }
        if (!presetStore.save(name, slugs)) {
            _state.update { it.copy(savingPreset = false, notice = MapNotice.PRESET_NAME) }
            return
        }
        val clean = name.trim().replace("|", "").take(24)
        _state.update {
            it.copy(
                savingPreset = false,
                notice = null,
                presets = presetStore.all(),
                selection = it.selection.copy(activePreset = clean),
            )
        }
    }

    fun onFeature(feature: MapFeature) {
        val shortId = feature.shortId
        if (feature.isCluster || shortId == null) {
            val zoom = ((lastFrame?.zoom ?: JamestownDemo.CLUSTER_ZOOM) + 2)
                .coerceAtLeast(JamestownDemo.STREET_ZOOM)
                .coerceAtMost(18)
            moveCamera(feature.location, zoom.toDouble(), fromUser = true)
            return
        }
        openTeaser(shortId, distanceM = null)
    }

    fun openTeaser(shortId: String, distanceM: Double?) {
        viewModelScope.launch {
            _state.update { it.copy(teaserLoading = true, nearMe = null) }
            try {
                val card = services.drops.dropTeaser(shortId)
                val distance = distanceM ?: _state.value.myLocation?.let { here ->
                    Geo.distanceMeters(here, card.displayLocation)
                }
                _state.update {
                    it.copy(teaserLoading = false, teaser = card.toTeaser(it.taxonomy, distance), notice = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(teaserLoading = false, notice = MapNotice.TEASER_FAILED) }
            }
        }
    }

    fun dismissTeaser() {
        _state.update { it.copy(teaser = null) }
    }

    fun openNearMe() {
        viewModelScope.launch {
            val origin = _state.value.myLocation ?: JamestownDemo.center
            val slugs = _state.value.selection.slugs
            try {
                val cards = services.drops.dropsNearby(origin, radiusM = NEAR_RADIUS_M, categories = slugs)
                _state.update { it.copy(nearMe = cards, notice = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(notice = MapNotice.NEAR_FAILED) }
            }
        }
    }

    fun closeNearMe() {
        _state.update { it.copy(nearMe = null) }
    }

    fun goToMyLocation() {
        val fix = _state.value.myLocation
        if (fix != null) {
            moveCamera(fix, 16.0, fromUser = true)
            return
        }
        if (!location.hasPermission()) {
            _state.update { it.copy(notice = MapNotice.LOCATION_OFF) }
            return
        }
        _state.update { it.copy(followNextFix = true, notice = null) }
        locationKick.tryEmit(Unit)
    }

    fun refreshLocation() {
        locationKick.tryEmit(Unit)
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun applySelection(selection: FilterSelection) {
        _state.update { it.copy(selection = selection, teaser = null) }
        val frame = lastFrame ?: return
        cameraJob?.cancel()
        cameraJob = viewModelScope.launch { fetch(frame) }
    }

    private suspend fun fetch(frame: CameraFrame) {
        lastFrame = frame
        val slugs = _state.value.selection.slugs
        _state.update {
            it.copy(
                loading = true,
                lastQuery = ViewportQuery(frame.box, frame.zoom, slugs),
            )
        }
        try {
            val features = services.drops.dropsInView(frame.box, slugs, frame.zoom)
            if (lastFrame != frame) return
            _state.update { it.copy(loading = false, features = features, notice = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (lastFrame != frame) return
            _state.update { it.copy(loading = false, features = emptyList(), notice = MapNotice.LOAD_FAILED) }
        }
    }

    private fun onFix(fix: LatLng) {
        val snapshot = _state.value
        val shouldFollow = snapshot.followNextFix || (!snapshot.userPanned && snapshot.camera.token == 0)
        _state.update { it.copy(myLocation = fix, followNextFix = false) }
        if (shouldFollow) {
            val zoom = if (snapshot.followNextFix) 16.0 else snapshot.camera.zoom
            moveCamera(fix, zoom, fromUser = false)
        }
    }

    private fun moveCamera(point: LatLng, zoom: Double, fromUser: Boolean) {
        _state.update {
            it.copy(
                userPanned = it.userPanned || fromUser,
                camera = CameraTarget(point.latitude, point.longitude, zoom, it.camera.token + 1),
            )
        }
    }

    private companion object {
        const val NEAR_RADIUS_M = 5_000
    }
}
