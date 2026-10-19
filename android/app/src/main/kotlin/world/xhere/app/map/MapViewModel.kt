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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.uuid.Uuid
import world.xhere.app.ar.ArRuntime
import world.xhere.app.ar.ArSupport
import world.xhere.app.explore.ExperienceAction
import world.xhere.app.explore.ExperienceMemory
import world.xhere.app.explore.MemoryExperienceStore
import world.xhere.app.location.DeviceLocation
import world.xhere.app.location.LocationReading
import world.xhere.shared.access.AccessAnswer
import world.xhere.shared.access.AccessPlaces
import world.xhere.shared.access.AccessSummary
import world.xhere.shared.explore.Experience
import world.xhere.shared.explore.ExperienceKind
import world.xhere.shared.integrity.LocationReasons
import world.xhere.shared.data.XHereServices
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.here.Here
import world.xhere.shared.history.History
import world.xhere.shared.live.LiveConditions
import world.xhere.shared.live.QuietConditions
import world.xhere.shared.model.MapFeature
import world.xhere.shared.model.PlaceLabel
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.Walk

/** Reverse-geocodes one spot. Production uses the Nominatim cache. Tests return a fixed name. */
fun interface SpotNames {
    suspend fun describe(spot: LatLng): PlaceLabel?
}

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
    private val places: SpotNames,
    private val ar: ArRuntime,
    private val mapPins: MapPins = MapPins(),
    private val debounceMs: Long = 320,
    private val conditions: LiveConditions = QuietConditions,
    private val experiences: ExperienceMemory = MemoryExperienceStore(),
    private val anchor: MapAnchor = MapAnchor(),
    private val sheets: SheetSignals = SheetSignals(),
) : ViewModel() {
    private val _state = MutableStateFlow(
        MapUiState(
            presets = presetStore.all(),
            selection = presetStore.rememberedSelection(),
            dismissedAlertIds = presetStore.dismissedAlerts(),
        ),
    )
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private val locationKick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var cameraJob: Job? = null
    private var hereJob: Job? = null
    private var timeJob: Job? = null
    private var teaserJob: Job? = null
    private var nearJob: Job? = null
    private var arJob: Job? = null
    private var arPending: LatLng? = null
    private var checkJob: Job? = null
    private var lastFrame: CameraFrame? = null
    private var lastCheckIn: LatLng? = null
    private var lastReading: LocationReading? = null
    private var arCheckedAt: LatLng? = null
    /** True after the first quest list arrives, so a later completion can celebrate. */
    private var questsLoaded = false

    init {
        viewModelScope.launch {
            sheets.dismissals.collect { dismissOpenSheets() }
        }
        viewModelScope.launch {
            experiences.state.collect { local ->
                _state.update { it.copy(experienceLocal = local) }
            }
        }
        viewModelScope.launch {
            val taxonomy = runCatching { services.taxonomy.load() }.getOrNull()
            _state.update {
                it.copy(
                    taxonomy = taxonomy,
                    groups = taxonomy?.groups?.filter { group -> group.active }?.sortedBy { group -> group.sort }.orEmpty(),
                )
            }
        }
        viewModelScope.launch { reloadQuests(celebrate = false) }
        viewModelScope.launch {
            mapPins.hidden.collect { hidden ->
                _state.update { current ->
                    current.copy(
                        features = current.features.filter { feature -> feature.shortId !in hidden },
                        teaser = current.teaser?.takeIf { it.shortId !in hidden },
                    )
                }
            }
        }
        viewModelScope.launch {
            mapPins.restored.collect {
                val frame = lastFrame ?: return@collect
                fetch(frame)
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

    /** Tap on empty map. The same card as a long-press. */
    fun onMapSpot(point: LatLng) = openHere(point)

    /** Long-press anywhere, including on top of a pin. */
    fun onMapLongPress(point: LatLng) = openHere(point)

    fun dismissHere() {
        hereJob?.cancel()
        _state.update { it.copy(here = null, accessSurvey = false) }
    }

    /** Wheelchair layer. Category chips stay as they are. */
    fun toggleAccessibility() {
        val enable = !_state.value.accessibilityLayer
        _state.update { it.copy(accessibilityLayer = enable, accessPins = if (enable) it.accessPins else emptyList()) }
        val frame = lastFrame ?: return
        cameraJob?.cancel()
        cameraJob = viewModelScope.launch { fetch(frame) }
    }

    fun openAccessSurvey() {
        if (_state.value.here == null) return
        _state.update { it.copy(accessSurvey = true) }
    }

    fun dismissAccessSurvey() {
        _state.update { it.copy(accessSurvey = false) }
    }

    fun submitAccess(stepFree: AccessAnswer, ramp: AccessAnswer, restroom: AccessAnswer, notes: String?) {
        val sheet = _state.value.here ?: return
        val key = sheet.accessKey.ifBlank { AccessPlaces.cell(sheet.spot) }
        if (!AccessPlaces.valid(key)) return
        viewModelScope.launch {
            try {
                val summary = services.access.submit(
                    placeKey = key,
                    location = sheet.spot,
                    stepFree = stepFree,
                    ramp = ramp,
                    restroom = restroom,
                    notes = notes?.trim()?.ifEmpty { null },
                )
                _state.update { current ->
                    current.copy(
                        accessSurvey = false,
                        here = current.here?.copy(access = summary, accessKey = summary.placeKey),
                    )
                }
                val frame = lastFrame
                if (frame != null && _state.value.accessibilityLayer) fetch(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(notice = MapNotice.LOAD_FAILED) }
            }
        }
    }

    /** Map button. Uses the camera center when the person has not tapped a spot. */
    fun openTimeMachine(spot: LatLng? = null) {
        val point = spot ?: _state.value.camera.point
        if (!Geo.isValid(point)) return
        hereJob?.cancel()
        teaserJob?.cancel()
        nearJob?.cancel()
        timeJob?.cancel()
        timeJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    here = null,
                    teaser = null,
                    teaserLoading = false,
                    nearMe = null,
                    timeMachine = TimeMachineUi(spot = point, loading = true),
                )
            }
            val place = try {
                services.history.historyAt(point)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!isActive) return@launch
            _state.update { current ->
                val open = current.timeMachine
                if (open == null || open.spot != point) return@update current
                if (place == null) {
                    return@update current.copy(timeMachine = open.copy(loading = false, failed = true))
                }
                val year = place.let { History.eras(it.sheets, it.photos).maxOrNull() }
                current.copy(
                    timeMachine = open.copy(
                        loading = false,
                        failed = false,
                        place = place,
                        year = year,
                        sheetId = pickHistorySheet(place, year),
                    ),
                )
            }
        }
    }

    fun dismissTimeMachine() {
        timeJob?.cancel()
        _state.update { it.copy(timeMachine = null) }
    }

    fun selectHistoryYear(year: Int) {
        _state.update { current ->
            val open = current.timeMachine ?: return@update current
            current.copy(
                timeMachine = open.copy(
                    year = year,
                    sheetId = pickHistorySheet(open.place, year),
                    viewerService = null,
                ),
            )
        }
    }

    fun selectHistorySheet(sheetId: String) {
        _state.update { current ->
            val open = current.timeMachine ?: return@update current
            val sheet = open.place.sheets.firstOrNull { it.id == sheetId } ?: return@update current
            current.copy(
                timeMachine = open.copy(
                    sheetId = sheetId,
                    year = sheet.year ?: open.year,
                    viewerService = null,
                ),
            )
        }
    }

    fun setHistoryOpacity(opacity: Float) {
        _state.update { current ->
            val open = current.timeMachine ?: return@update current
            current.copy(timeMachine = open.copy(opacity = opacity.coerceIn(0.15f, 0.9f)))
        }
    }

    fun openHistoryViewer(sheetId: String) {
        _state.update { current ->
            val open = current.timeMachine ?: return@update current
            val sheet = open.place.sheets.firstOrNull { it.id == sheetId } ?: return@update current
            val service = sheet.iiifService
            if (service.isNullOrBlank()) return@update current
            current.copy(
                timeMachine = open.copy(
                    sheetId = sheetId,
                    viewerService = service,
                    viewerTitle = sheet.title,
                    viewerYear = sheet.dateLabel.ifBlank { sheet.year?.toString().orEmpty() },
                ),
            )
        }
    }

    fun closeHistoryViewer() {
        _state.update { current ->
            val open = current.timeMachine ?: return@update current
            current.copy(timeMachine = open.copy(viewerService = null))
        }
    }

    fun openTeaser(shortId: String, distanceM: Double?) {
        hereJob?.cancel()
        nearJob?.cancel()
        teaserJob?.cancel()
        timeJob?.cancel()
        teaserJob = viewModelScope.launch {
            _state.update { it.copy(teaserLoading = true, nearMe = null, here = null, timeMachine = null) }
            try {
                val card = services.drops.dropTeaser(shortId)
                if (!isActive) return@launch
                val distance = distanceM ?: _state.value.myLocation?.let { here ->
                    Geo.distanceMeters(here, card.displayLocation)
                }
                _state.update {
                    it.copy(teaserLoading = false, teaser = card.toTeaser(it.taxonomy, distance), notice = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!isActive) return@launch
                _state.update { it.copy(teaserLoading = false, notice = MapNotice.TEASER_FAILED) }
            }
        }
    }

    fun dismissTeaser() {
        _state.update { it.copy(teaser = null) }
    }

    fun openNearMe() {
        hereJob?.cancel()
        teaserJob?.cancel()
        nearJob?.cancel()
        timeJob?.cancel()
        _state.update { it.copy(here = null, teaser = null, teaserLoading = false, timeMachine = null) }
        nearJob = viewModelScope.launch {
            val origin = _state.value.myLocation ?: JamestownDemo.center
            val slugs = _state.value.selection.slugs
            try {
                val cards = services.drops.dropsNearby(origin, radiusM = NEAR_RADIUS_M, categories = slugs)
                if (!isActive) return@launch
                _state.update { it.copy(nearMe = cards, notice = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!isActive) return@launch
                _state.update { it.copy(notice = MapNotice.NEAR_FAILED) }
            }
        }
    }

    fun closeNearMe() {
        nearJob?.cancel()
        _state.update { it.copy(nearMe = null) }
    }

    /** The permission dialog came back denied. The notice explains how to turn it on. */
    fun locationDenied() {
        _state.update { it.copy(notice = MapNotice.LOCATION_OFF) }
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

    /** Reloads quest progress when the map comes back, so a confirm or a new drop shows up. */
    fun refreshGuide() {
        viewModelScope.launch { reloadQuests(celebrate = true) }
        val frame = lastFrame
        viewModelScope.launch {
            val ids = presetStore.dismissedAlerts()
            _state.update { current ->
                current.copy(
                    dismissedAlertIds = ids,
                    alerts = current.alerts.filter { alert -> alert.id !in ids },
                )
            }
            if (frame != null) refreshAlerts(frame)
        }
    }

    fun startWalk(id: Uuid? = null) {
        val target = id ?: _state.value.walk?.id ?: return
        viewModelScope.launch {
            try {
                val started = services.walks.start(target)
                _state.update { it.copy(walk = mergeWalk(it.walk, started) ?: started, notice = null) }
                val here = lastReading
                if (here != null) checkIn(here, force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(notice = MapNotice.WALK_FAILED) }
            }
        }
    }

    /** Opens one experience from Explore: move the camera, start a walk, or open Time Machine. */
    fun focus(action: ExperienceAction) {
        _state.update { it.copy(focusedExperienceId = action.id) }
        val point = action.anchor
        if (point != null && Geo.isValid(point)) moveCamera(point, 16.0, fromUser = true)
        if (action.id.startsWith("capsule:") && point != null && Geo.isValid(point)) openHere(point)
        if (action.openTimeMachine) openTimeMachine(point)
        if (action.startWalk) {
            val id = runCatching { Uuid.parse(action.id) }.getOrNull()
            startWalk(id)
        }
    }

    /** Hides a walk or campaign. A changed revision can show it again. Alerts use [dismissAlert]. */
    fun dismissExperience(item: Experience) {
        if (item.placeholder || item.kind == ExperienceKind.LIVE_ALERT) return
        _state.update { current ->
            if (current.focusedExperienceId == item.id) current.copy(focusedExperienceId = null) else current
        }
        viewModelScope.launch { experiences.dismiss(item.id, item.revision) }
    }

    fun undoDismiss() {
        val pending = _state.value.experienceLocal.pendingUndo ?: return
        viewModelScope.launch { experiences.undo(pending.id) }
    }

    fun clearUndo() {
        viewModelScope.launch { experiences.clearUndo() }
    }

    fun dismissQuest(id: Uuid) {
        if (_state.value.focusedExperienceId == id.toString()) {
            _state.update { it.copy(focusedExperienceId = null) }
        }
        viewModelScope.launch {
            try {
                services.walks.dismissQuest(id)
                _state.update { it.copy(quests = it.quests.filter { quest -> quest.id != id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The card stays so they can try again.
            }
        }
    }

    fun dismissCelebration() {
        _state.update { it.copy(celebrateWalk = false, celebrateQuest = false) }
    }

    /** Clears every sheet so the map underneath is usable again. */
    fun dismissOpenSheets() {
        dismissSubcategories()
        dismissSavePreset()
        closeNearMe()
        dismissHere()
        dismissTimeMachine()
        dismissTeaser()
        dismissCelebration()
    }

    fun dismissAlert(id: String) {
        val ids = _state.value.dismissedAlertIds + id
        presetStore.rememberDismissedAlerts(ids)
        _state.update {
            it.copy(
                dismissedAlertIds = ids,
                alerts = it.alerts.filter { alert -> alert.id != id },
            )
        }
    }

    fun refreshHere() {
        val spot = _state.value.here?.spot ?: return
        openHere(spot)
    }

    private fun openHere(spot: LatLng) {
        if (!Geo.isValid(spot)) return
        teaserJob?.cancel()
        nearJob?.cancel()
        hereJob?.cancel()
        timeJob?.cancel()
        hereJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    teaser = null,
                    teaserLoading = false,
                    nearMe = null,
                    timeMachine = null,
                    here = HereSheet(spot = spot, loading = true),
                )
            }
            val drops = try {
                services.drops.hereCard(spot, _state.value.myLocation, radiusM = Here.RADIUS_M, limit = Here.LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val access = if (drops != null) accessFor(spot) else null
            val official = if (drops != null) {
                try {
                    services.desk.officialAt(spot)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val place = try {
                places.describe(spot)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update { current ->
                val open = current.here
                if (open == null || open.spot != spot) return@update current
                current.copy(
                    here = open.copy(
                        loading = false,
                        place = place,
                        drops = drops.orEmpty(),
                        failed = drops == null,
                        access = open.access ?: access,
                        accessKey = open.access?.placeKey ?: access?.placeKey ?: AccessPlaces.cell(spot),
                        official = official,
                    ),
                )
            }
            val history = try {
                services.history.historyAt(spot)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val terms = History.placeTerms(
                place?.name,
                place?.address,
                history?.town.orEmpty(),
                history?.state.orEmpty(),
            )
            val clippings = if (history?.regionId == null || terms.isEmpty()) {
                emptyList()
            } else {
                try {
                    services.history.clippingsAt(spot, terms)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val today = LocalDate.now()
            val ranked = History.rankClippings(clippings, today.monthValue, today.dayOfMonth)
            if (!isActive) return@launch
            _state.update { current ->
                val open = current.here
                if (open == null || open.spot != spot) return@update current
                current.copy(here = open.copy(clippings = ranked))
            }
        }
    }

    private fun applySelection(selection: FilterSelection) {
        presetStore.rememberSelection(selection)
        _state.update { it.copy(selection = selection, teaser = null) }
        val frame = lastFrame ?: return
        cameraJob?.cancel()
        cameraJob = viewModelScope.launch { fetch(frame) }
    }

    private suspend fun fetch(frame: CameraFrame) {
        lastFrame = frame
        anchor.publish(frame)
        val slugs = _state.value.selection.slugs
        _state.update {
            it.copy(
                loading = true,
                lastQuery = ViewportQuery(frame.box, frame.zoom, slugs),
            )
        }
        try {
            val hidden = mapPins.hidden.value
            val features = services.drops.dropsInView(frame.box, slugs, frame.zoom)
                .filter { feature -> feature.shortId !in hidden }
            val walks = runCatching { services.walks.walksFor(centerOf(frame)) }.getOrDefault(emptyList())
            val campaigns = runCatching { services.campaigns.campaignsFor(frame.box) }.getOrDefault(emptyList())
            val pins = if (_state.value.accessibilityLayer) accessPins(frame) else emptyList()
            if (lastFrame != frame) return
            _state.update {
                it.copy(
                    loading = false,
                    features = features,
                    walk = mergeWalk(it.walk, walks.firstOrNull()),
                    campaigns = campaigns,
                    notice = null,
                    accessPins = if (it.accessibilityLayer) pins else emptyList(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (lastFrame != frame) return
            // Keep the pins already on screen. A failed refresh should not blank the map.
            _state.update { it.copy(loading = false, notice = MapNotice.LOAD_FAILED) }
        }
        if (lastFrame == frame) refreshAlerts(frame)
    }

    private suspend fun accessPins(frame: CameraFrame): List<AccessSummary> = try {
        services.access.inView(frame.box)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** The cell's own majority, or the nearest stored pin within 150 m. */
    private suspend fun accessFor(spot: LatLng): AccessSummary? {
        val own = try {
            services.access.summary(AccessPlaces.cell(spot))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (own != null) return own
        val near = try {
            services.access.inView(Geo.boundingBox(spot, 150.0))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return near
            .filter { Geo.distanceMeters(spot, it.location) <= 150.0 }
            .minByOrNull { Geo.distanceMeters(spot, it.location) }
    }

    private suspend fun refreshAlerts(frame: CameraFrame) {
        val center = LatLng(
            (frame.box.minLatitude + frame.box.maxLatitude) / 2.0,
            (frame.box.minLongitude + frame.box.maxLongitude) / 2.0,
        )
        try {
            val next = conditions.alertsAt(center)
            if (lastFrame != frame) return
            _state.update { current ->
                current.copy(alerts = next.filter { it.id !in current.dismissedAlertIds })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the alerts already on screen.
        }
    }

    private fun onFix(reading: LocationReading) {
        lastReading = reading
        val fix = reading.point
        val snapshot = _state.value
        val shouldFollow = snapshot.followNextFix || (!snapshot.userPanned && snapshot.camera.token == 0)
        _state.update { it.copy(myLocation = fix, followNextFix = false) }
        if (shouldFollow) {
            val zoom = if (snapshot.followNextFix) 16.0 else snapshot.camera.zoom
            moveCamera(fix, zoom, fromUser = false)
        }
        checkIn(reading, force = false)
        refreshAr(fix)
    }

    /**
     * Quiet probe for [MapUiState.arReady]. The Look around button stays
     * visible either way. A still-checking result (UNKNOWN_CHECKING or
     * UNKNOWN_TIMED_OUT) is not cached, so the next fix tries again.
     * A settled miss waits until the person moves [AR_RECHECK_M].
     * The check does not open the camera and does not call requestInstall.
     */
    private fun refreshAr(point: LatLng) {
        if (!Geo.isValid(point)) return
        val previous = arCheckedAt
        if (arJob?.isActive == true) {
            arPending = point
            return
        }
        if (previous != null && Geo.distanceMeters(previous, point) < AR_RECHECK_M) return
        arCheckedAt = point
        arJob = viewModelScope.launch { runArCheck(point) }
    }

    /**
     * A probe that returns false was skipped because another check holds the
     * session. Leaving [MapUiState.arReady] alone stops a later skip from
     * hiding a button a finished probe already earned.
     */
    private suspend fun runArCheck(point: LatLng) {
        val updated = try {
            ar.refreshSupport(point.latitude, point.longitude)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(arReady = false) }
            false
        }
        if (!updated || ar.support.value == ArSupport.UNKNOWN) {
            arCheckedAt = null
        } else {
            _state.update { it.copy(arReady = ar.support.value == ArSupport.READY) }
        }
        val next = arPending
        arPending = null
        val checked = arCheckedAt
        if (next != null && (checked == null || Geo.distanceMeters(checked, next) >= AR_RECHECK_M)) {
            arCheckedAt = next
            runArCheck(next)
        }
    }

    /**
     * A check-in uses the point the way a reveal does, then the server drops it.
     * Repeats closer than [CHECK_MOVE_M] are skipped unless the walk just started.
     */
    private fun checkIn(reading: LocationReading, force: Boolean) {
        val fix = reading.point
        if (!force) {
            val last = lastCheckIn
            if (last != null && Geo.distanceMeters(last, fix) < CHECK_MOVE_M) return
        }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            val walk = _state.value.walk
            val walkId = walk?.takeIf { it.started && !it.completed }?.id
            try {
                val result = services.walks.checkIn(fix, walkId, reading.signal)
                lastCheckIn = fix
                val opened = try {
                    services.desk.openCapsulesHere(fix, reading.signal)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    0
                }
                if (opened > 0) {
                    val frame = lastFrame
                    if (frame != null) fetch(frame)
                }
                val previous = _state.value.quests
                val flipped = questJustCompleted(previous, result.quests)
                questsLoaded = true
                val flagged = integrityNotice(result.reasons)
                _state.update {
                    val notice = when {
                        flagged != null -> flagged
                        it.notice in INTEGRITY_NOTICES -> null
                        else -> it.notice
                    }
                    it.copy(
                        quests = result.quests,
                        walk = result.walk?.let { fresh -> mergeWalk(it.walk, fresh) } ?: it.walk,
                        celebrateWalk = it.celebrateWalk || result.walkJustCompleted,
                        celebrateQuest = it.celebrateQuest || flipped,
                        notice = notice,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A missed check-in retries after the next 20 m.
            }
        }
    }

    private fun integrityNotice(reasons: List<String>): MapNotice? = when {
        LocationReasons.MOCK in reasons -> MapNotice.MOCK_LOCATION
        LocationReasons.TRAVEL in reasons -> MapNotice.IMPOSSIBLE_TRAVEL
        LocationReasons.UNVERIFIED in reasons -> MapNotice.UNVERIFIED_FIX
        else -> null
    }

    private suspend fun reloadQuests(celebrate: Boolean) {
        try {
            val next = services.walks.quests()
            val flipped = celebrate && questJustCompleted(_state.value.quests, next)
            questsLoaded = true
            _state.update { it.copy(quests = next, celebrateQuest = it.celebrateQuest || flipped) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The card keeps the last progress it showed.
        }
    }

    private fun questJustCompleted(previous: List<QuestProgress>, next: List<QuestProgress>): Boolean {
        if (!questsLoaded) return false
        return next.any { quest -> quest.completed && previous.none { it.id == quest.id && it.completed } }
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
        const val CHECK_MOVE_M = 20.0
        const val AR_RECHECK_M = 250.0
        val INTEGRITY_NOTICES = setOf(
            MapNotice.MOCK_LOCATION,
            MapNotice.IMPOSSIBLE_TRAVEL,
            MapNotice.UNVERIFIED_FIX,
        )

        fun centerOf(frame: CameraFrame): LatLng = LatLng(
            latitude = (frame.box.minLatitude + frame.box.maxLatitude) / 2.0,
            longitude = (frame.box.minLongitude + frame.box.maxLongitude) / 2.0,
        )

        /**
         * Keeps a check-off that already happened when a viewport refresh is still
         * in flight. A finished walk stays on the card while the camera is in its region.
         */
        fun mergeWalk(current: Walk?, incoming: Walk?): Walk? {
            if (incoming == null) {
                return if (current != null && current.started && !current.completed) current else null
            }
            if (current == null || current.id != incoming.id) return incoming
            val currentReached = current.stops.count { it.reached }
            val incomingReached = incoming.stops.count { it.reached }
            if (currentReached > incomingReached) return current
            if (currentReached == incomingReached && current.completed && !incoming.completed) return current
            return incoming
        }
    }
}
