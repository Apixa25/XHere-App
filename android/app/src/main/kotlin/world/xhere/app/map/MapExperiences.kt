package world.xhere.app.map

import world.xhere.shared.explore.Experiences
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.Walk

/**
 * The big suggestion cards stay off the map. A walk that is already underway,
 * or one the person just opened from Explore, still gets a card. Closing it
 * keeps the route and leaves the item in Explore.
 */
internal fun exploreHintCount(state: MapUiState): Int {
    val walk = state.walk?.takeIf { card -> card.stops.any { it.available } }
    val here = state.myLocation ?: state.camera.point
    return Experiences.hintCount(
        walks = listOfNotNull(walk),
        quests = state.quests,
        campaigns = state.campaigns,
        alerts = state.alerts,
        here = here,
        dismissals = state.experienceLocal.dismissals,
    )
}

internal fun walkOnMap(state: MapUiState): Walk? {
    val walk = state.walk?.takeIf { card -> card.stops.any { it.available } } ?: return null
    val item = Experiences.walk(walk, state.myLocation)
    val dismissed = Experiences.isDismissed(item, state.experienceLocal.dismissals)
    val focused = state.focusedExperienceId == item.id
    val guiding = walk.started && !walk.completed
    if (!focused && !guiding) return null
    if (dismissed && !focused) return null
    return walk
}

internal fun campaignOnMap(state: MapUiState): CampaignProgress? {
    val id = state.focusedExperienceId ?: return null
    return state.campaigns.firstOrNull { Experiences.campaign(it).id == id }
}

internal fun questOnMap(state: MapUiState): QuestProgress? {
    val id = state.focusedExperienceId ?: return null
    return state.quests.firstOrNull { Experiences.quest(it).id == id }
}
