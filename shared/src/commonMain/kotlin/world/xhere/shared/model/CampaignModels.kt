package world.xhere.shared.model

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.json.InstantSerializer
import world.xhere.shared.json.UuidSerializer

/**
 * One award from `my_badges` or `drop_card.author_badges`.
 * [regionId] is null for a global badge (M11). Founding Local sets it.
 * The wire shape is the M11 shelf: slug, name, icon, and optional evidence.
 */
@Serializable
data class BadgeAward(
    val slug: String,
    val name: String,
    val icon: String,
    val description: String? = null,
    @SerialName("region_id") val regionId: String? = null,
    @SerialName("region_name") val regionName: String? = null,
    val rank: Int? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("awarded_at") val awardedAt: Instant? = null,
)

/** `my_badges()`: the caller's awards and the leaderboard switch. */
@Serializable
data class BadgeShelf(
    val badges: List<BadgeAward> = emptyList(),
    @SerialName("show_on_leaderboard") val showOnLeaderboard: Boolean = true,
)

/** One public row. A display name only. No handle, no user id. */
@Serializable
data class LeaderboardEntry(
    @SerialName("display_name") val displayName: String,
    val drops: Int,
)

/**
 * One live campaign (`campaigns_for_map` / `my_campaigns`).
 * [dropCount] is every qualifying user drop. [myDrops] is the caller's own, even when they opt out.
 */
@Serializable
data class CampaignProgress(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("region_id") val regionId: String,
    @SerialName("region_name") val regionName: String,
    val slug: String,
    val title: String,
    val description: String,
    @SerialName("goal_drops") val goalDrops: Int,
    @SerialName("drop_count") val dropCount: Int,
    @SerialName("my_drops") val myDrops: Int = 0,
    @Serializable(with = InstantSerializer::class)
    @SerialName("starts_at") val startsAt: Instant,
    @Serializable(with = InstantSerializer::class)
    @SerialName("ends_at") val endsAt: Instant,
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    @SerialName("show_on_leaderboard") val showOnLeaderboard: Boolean = true,
    @SerialName("my_listed") val myListed: Boolean = false,
) {
    val fraction: Float
        get() = if (goalDrops <= 0) 0f else (dropCount.toFloat() / goalDrops).coerceIn(0f, 1f)
}
