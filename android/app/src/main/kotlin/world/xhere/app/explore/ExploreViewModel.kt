package world.xhere.app.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.app.map.CameraFrame
import world.xhere.app.map.FilterPresetStore
import world.xhere.app.map.MapAnchor
import world.xhere.app.voice.NearbyThing
import world.xhere.app.voice.VoiceSession
import world.xhere.shared.data.XHereServices
import world.xhere.shared.explore.Experience
import world.xhere.shared.explore.ExperienceDismissal
import world.xhere.shared.explore.ExperienceKind
import world.xhere.shared.explore.ExperienceSort
import world.xhere.shared.explore.Experiences
import world.xhere.shared.geo.LatLng
import world.xhere.shared.live.LiveConditions
import world.xhere.shared.live.QuietConditions
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.ThingCard
import world.xhere.shared.model.Walk
import world.xhere.shared.live.WeatherAlert

/**
 * One list for everything the map can discover. The reads are the same RPCs
 * the map already uses, plus objects this phone owns or has heard.
 */
class ExploreViewModel(
    private val services: XHereServices,
    private val memory: ExperienceMemory,
    private val anchor: MapAnchor,
    private val presets: FilterPresetStore,
    private val voice: VoiceSession,
    private val conditions: LiveConditions = QuietConditions,
) : ViewModel() {
    private val _state = MutableStateFlow(ExploreUiState(dismissedAlertIds = presets.dismissedAlerts()))
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    private var here: LatLng? = null
    private var walks: List<Walk> = emptyList()
    private var quests: List<QuestProgress> = emptyList()
    private var campaigns: List<CampaignProgress> = emptyList()
    private var alerts: List<WeatherAlert> = emptyList()
    private var owned: List<ThingCard> = emptyList()
    private var heard: List<NearbyThing> = emptyList()
    private var capsules: List<DropCard> = emptyList()

    init {
        viewModelScope.launch {
            memory.state.collect { local -> _state.update { it.copy(local = local) } }
        }
        viewModelScope.launch {
            voice.nearby.collect { nearby ->
                heard = nearby
                publish()
            }
        }
        viewModelScope.launch {
            anchor.frame.filterNotNull().collectLatest { frame -> load(frame) }
        }
    }

    fun setSort(sort: ExperienceSort) {
        _state.update { it.copy(sort = sort) }
    }

    fun setFilter(filter: ExperienceFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun dismiss(item: Experience) {
        if (item.placeholder) return
        if (item.kind == ExperienceKind.LIVE_ALERT) {
            val raw = item.id.removePrefix("alert:")
            val next = presets.dismissedAlerts() + raw
            presets.rememberDismissedAlerts(next)
            _state.update { it.copy(dismissedAlertIds = next) }
            viewModelScope.launch { memory.rememberUndo(ExperienceDismissal(item.id, item.revision)) }
            return
        }
        viewModelScope.launch { memory.dismiss(item.id, item.revision) }
    }

    fun dismissQuest(id: Uuid) {
        viewModelScope.launch {
            try {
                services.walks.dismissQuest(id)
                quests = quests.filter { it.id != id }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The card stays so they can try again.
            }
        }
    }

    fun undoDismiss() {
        val pending = _state.value.local.pendingUndo ?: return
        viewModelScope.launch {
            if (pending.id.startsWith("alert:")) {
                val raw = pending.id.removePrefix("alert:")
                val next = presets.dismissedAlerts() - raw
                presets.rememberDismissedAlerts(next)
                _state.update { it.copy(dismissedAlertIds = next) }
                memory.clearUndo()
            } else {
                memory.undo(pending.id)
            }
        }
    }

    fun clearUndo() {
        viewModelScope.launch { memory.clearUndo() }
    }

    private suspend fun load(frame: CameraFrame) {
        val center = LatLng(
            (frame.box.minLatitude + frame.box.maxLatitude) / 2.0,
            (frame.box.minLongitude + frame.box.maxLongitude) / 2.0,
        )
        here = center
        walks = runCatching { services.walks.walksFor(center) }.getOrDefault(walks)
        quests = runCatching { services.walks.quests() }.getOrDefault(quests)
        campaigns = runCatching { services.campaigns.campaignsFor(frame.box) }.getOrDefault(campaigns)
        alerts = runCatching { conditions.alertsAt(center) }.getOrDefault(alerts)
        owned = runCatching { services.things.mine() }.getOrDefault(owned)
        capsules = runCatching {
            services.drops.dropsNearby(center, radiusM = 2_000, categories = listOf("time_capsule"), limit = 20)
        }.getOrDefault(capsules)
        _state.update { it.copy(dismissedAlertIds = presets.dismissedAlerts()) }
        publish()
    }

    private fun publish() {
        if (here == null && walks.isEmpty() && quests.isEmpty()) return
        val point = here
        val cards = linkedMapOf<String, ThingCard>()
        val meters = linkedMapOf<String, Double>()
        owned.forEach { card -> cards[card.id.toString()] = card }
        heard.forEach { nearby ->
            cards[nearby.thing.id.toString()] = nearby.thing
            nearby.meters?.let { meters[nearby.thing.id.toString()] = it }
        }
        val items = Experiences.assemble(
            walks = walks,
            quests = quests,
            campaigns = campaigns,
            alerts = alerts,
            things = cards.values.toList(),
            here = point,
            heardMeters = meters,
            capsules = capsules,
        )
        _state.update {
            it.copy(
                experiences = items,
                walks = walks,
                quests = quests,
                campaigns = campaigns,
                alerts = alerts,
                loading = false,
            )
        }
    }
}
