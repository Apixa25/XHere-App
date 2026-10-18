package world.xhere.shared.model

import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.UuidSerializer

/**
 * One walk from `walks_for_point` / `start_walk` / `check_in`.
 * Stops are places that already exist. [distanceM] is the straight line
 * between them, and [durationMin] is that length at a walking pace.
 */
@Serializable
data class Walk(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("region_id") val regionId: String,
    val slug: String,
    val title: String,
    val description: String,
    @SerialName("distance_m") val distanceM: Int,
    @SerialName("duration_min") val durationMin: Int,
    val started: Boolean = false,
    val completed: Boolean = false,
    val stops: List<WalkStop> = emptyList(),
)

@Serializable
data class WalkStop(
    val position: Int,
    val title: String,
    val summary: String? = null,
    val lat: Double,
    val lng: Double,
    @SerialName("reveal_radius_m") val revealRadiusM: Int,
    @SerialName("short_id") val shortId: String? = null,
    @Serializable(with = UuidSerializer::class)
    @SerialName("drop_id") val dropId: Uuid? = null,
    val reached: Boolean = false,
    @SerialName("distance_m") val distanceM: Double? = null,
    val available: Boolean = true,
) {
    val location: LatLng get() = LatLng(lat, lng)
}

/**
 * One quest from `my_quests` / `check_in`. A null region on the server means
 * every region. Progress is derived. Dismissal removes the quest from this list.
 */
@Serializable
data class QuestProgress(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    val slug: String,
    val title: String,
    val description: String,
    val completed: Boolean = false,
    val steps: List<QuestStepProgress> = emptyList(),
)

@Serializable
data class QuestStepProgress(
    val position: Int,
    val kind: String,
    val title: String,
    @SerialName("target_count") val targetCount: Int,
    val progress: Int = 0,
)

/** `check_in`: visits and, when a walk was passed, that walk after check-off. */
@Serializable
data class CheckInResult(
    @SerialName("visit_count") val visitCount: Int,
    @SerialName("visits_added") val visitsAdded: Int,
    val quests: List<QuestProgress> = emptyList(),
    val walk: Walk? = null,
    @SerialName("walk_just_completed") val walkJustCompleted: Boolean = false,
)
