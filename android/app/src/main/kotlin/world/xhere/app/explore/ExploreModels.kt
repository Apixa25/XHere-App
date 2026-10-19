package world.xhere.app.explore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import world.xhere.shared.explore.Experience
import world.xhere.shared.explore.ExperienceCta
import world.xhere.shared.explore.ExperienceKind
import world.xhere.shared.explore.ExperienceSort
import world.xhere.shared.explore.Experiences
import world.xhere.shared.geo.LatLng
import world.xhere.shared.live.WeatherAlert
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.Walk

enum class ExperienceFilter {
    ALL,
    WALKS,
    QUESTS,
    CAMPAIGNS,
    LIVE,
    TIME,
    THINGS,
    CAPSULES,
    TOWN_HALL,
}

/** A tap from Explore. The map focuses [anchor], starts a walk, or opens Time Machine. */
data class ExperienceAction(
    val id: String,
    val anchor: LatLng?,
    val startWalk: Boolean,
    val openTimeMachine: Boolean,
)

fun Experience.toAction(): ExperienceAction = ExperienceAction(
    id = id,
    anchor = anchor,
    startWalk = cta == ExperienceCta.START,
    openTimeMachine = cta == ExperienceCta.TIME_MACHINE,
)

data class ExploreUiState(
    val experiences: List<Experience> = emptyList(),
    val walks: List<Walk> = emptyList(),
    val quests: List<QuestProgress> = emptyList(),
    val campaigns: List<CampaignProgress> = emptyList(),
    val alerts: List<WeatherAlert> = emptyList(),
    val local: ExperienceLocal = ExperienceLocal(),
    val dismissedAlertIds: Set<String> = emptySet(),
    val sort: ExperienceSort = ExperienceSort.RELEVANCE,
    val filter: ExperienceFilter = ExperienceFilter.ALL,
    val loading: Boolean = true,
)

/** The map asks this to open one experience after Explore navigates back. */
class ExperienceFocus {
    private val pending = MutableStateFlow<ExperienceAction?>(null)
    val requests: StateFlow<ExperienceAction?> = pending

    fun request(action: ExperienceAction) {
        pending.value = action
    }

    fun consume() {
        pending.value = null
    }
}

fun Experience.hiddenIn(state: ExploreUiState): Boolean = when (kind) {
    ExperienceKind.LIVE_ALERT -> id.removePrefix("alert:") in state.dismissedAlertIds
    else -> Experiences.isDismissed(this, state.local.dismissals)
}

fun Experience.matches(filter: ExperienceFilter): Boolean = when (filter) {
    ExperienceFilter.ALL -> true
    ExperienceFilter.WALKS -> kind == ExperienceKind.WALK
    ExperienceFilter.QUESTS -> kind == ExperienceKind.QUEST
    ExperienceFilter.CAMPAIGNS -> kind == ExperienceKind.CAMPAIGN
    ExperienceFilter.LIVE -> kind == ExperienceKind.LIVE_ALERT
    ExperienceFilter.TIME -> kind == ExperienceKind.TIME_MACHINE
    ExperienceFilter.THINGS -> kind == ExperienceKind.NEARBY_THING
    ExperienceFilter.CAPSULES -> kind == ExperienceKind.TIME_CAPSULE
    ExperienceFilter.TOWN_HALL -> kind == ExperienceKind.TOWN_HALL
}
