package world.xhere.shared.model

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.InstantSerializer
import world.xhere.shared.json.UuidSerializer

/**
 * Wire shape of `public.drop_card` (`supabase/migrations/20261018000400_rpcs.sql`).
 * [body], [attributes], [media], and [tags] are null unless the drop is revealed to the caller.
 * [latitude] and [longitude] are always the display position, never the true one.
 */
@Serializable
data class DropCard(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("short_id") val shortId: String,
    @SerialName("category_slug") val categorySlug: String,
    @SerialName("group_slug") val groupSlug: String,
    val title: String,
    @SerialName("lat") val latitude: Double,
    @SerialName("lng") val longitude: Double,
    @SerialName("location_precision_m") val locationPrecisionM: Int,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("reveal_radius_m") val revealRadiusM: Int,
    val revealed: Boolean,
    val body: String? = null,
    val attributes: JsonObject? = null,
    val media: List<DropMedia>? = null,
    val tags: List<String>? = null,
    val status: DropStatus,
    @SerialName("is_confirmed") val isConfirmed: Boolean,
    @SerialName("confirm_count") val confirmCount: Int,
    @SerialName("deny_count") val denyCount: Int,
    @Serializable(with = InstantSerializer::class)
    @SerialName("expires_at") val expiresAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("last_confirmed_at") val lastConfirmedAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("author_handle") val authorHandle: String? = null,
    @SerialName("is_mine") val isMine: Boolean,
    /** `user` when an older payload omits it. Public records are `open_data`. */
    val source: DropSource = DropSource.USER,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val license: String? = null,
    val attribution: String? = null,
    /** Unique signed-in viewers, not counting the author. Anonymous opens are not included. */
    @SerialName("view_count") val viewCount: Long = 0,
    /** This caller's answer, when they have confirmed or denied and that answer counted. */
    @SerialName("my_verdict") val myVerdict: ConfirmationVerdict? = null,
    /**
     * Badges to show next to the author. Empty when the drop is anonymous or a public record.
     * A regional badge is included only when this drop is inside that region.
     */
    @SerialName("author_badges") val authorBadges: List<BadgeAward> = emptyList(),
    /** `standard` or `reduced`. Reduced posts stay on the map and do not earn credit. */
    @SerialName("location_trust") val locationTrust: String = "standard",
    /** True when this caller was close enough, but the location check withheld the body. */
    @SerialName("location_withheld") val locationWithheld: Boolean = false,
) {
    val displayLocation: LatLng get() = LatLng(latitude, longitude)

    fun toDrop(categoryId: Uuid, authorId: Uuid?, categoryVersion: Int = 0): Drop = Drop(
        id = id,
        shortId = shortId,
        authorId = authorId,
        categoryId = categoryId,
        categorySlug = categorySlug,
        groupSlug = groupSlug,
        categoryVersion = categoryVersion,
        source = source,
        title = title,
        body = body,
        attributes = attributes ?: JsonObject(emptyMap()),
        tags = tags.orEmpty(),
        displayLocation = displayLocation,
        trueLocation = null,
        locationPrecisionM = locationPrecisionM,
        revealRadiusM = revealRadiusM,
        status = status,
        displayAnonymous = authorHandle == null && authorId != null,
        expiresAt = expiresAt,
        confirmCount = confirmCount,
        denyCount = denyCount,
        lastConfirmedAt = lastConfirmedAt,
        createdAt = createdAt,
        removedReason = null,
        media = media.orEmpty(),
        revealed = revealed,
        distanceM = distanceM,
        authorHandle = authorHandle,
        isMine = isMine,
    )
}

/** One row from `drops_in_view`. [kind] is `drop` or `cluster`. Never includes a body. */
@Serializable
data class MapFeature(
    val kind: String,
    @Serializable(with = UuidSerializer::class)
    @SerialName("drop_id") val dropId: Uuid? = null,
    @SerialName("short_id") val shortId: String? = null,
    @SerialName("category_slug") val categorySlug: String? = null,
    @SerialName("group_slug") val groupSlug: String? = null,
    val title: String? = null,
    @SerialName("lat") val latitude: Double,
    @SerialName("lng") val longitude: Double,
    @SerialName("point_count") val pointCount: Int,
    @SerialName("category_counts") val categoryCounts: Map<String, Int>? = null,
    /** Set on a single pin. Clusters mix sources, so this stays null. */
    val source: DropSource? = null,
) {
    val isCluster: Boolean get() = kind == "cluster"
    val location: LatLng get() = LatLng(latitude, longitude)
}

@Serializable
data class CreateDropResult(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("short_id") val shortId: String,
    val status: DropStatus,
    val category: String,
    @SerialName("category_version") val categoryVersion: Int,
    @SerialName("reveal_radius_m") val revealRadiusM: Int,
    @Serializable(with = InstantSerializer::class)
    @SerialName("expires_at") val expiresAt: Instant? = null,
    @SerialName("lat") val latitude: Double,
    @SerialName("lng") val longitude: Double,
    @SerialName("location_precision_m") val locationPrecisionM: Int,
    @SerialName("posting_notice") val postingNotice: String? = null,
    @SerialName("possible_duplicates") val possibleDuplicates: List<String> = emptyList(),
    /** False when this post failed the location check. Quests and badges must skip it. */
    @SerialName("counts_for_credit") val countsForCredit: Boolean = true,
    @SerialName("location_trust") val locationTrust: String = "standard",
    val reasons: List<String> = emptyList(),
) {
    val displayLocation: LatLng get() = LatLng(latitude, longitude)
}

@Serializable
data class ConfirmDropResult(
    @Serializable(with = UuidSerializer::class)
    @SerialName("drop_id") val dropId: Uuid,
    val verdict: ConfirmationVerdict,
    val status: DropStatus,
    @SerialName("is_confirmed") val isConfirmed: Boolean,
    @SerialName("confirm_count") val confirmCount: Int,
    @SerialName("deny_count") val denyCount: Int,
    @Serializable(with = InstantSerializer::class)
    @SerialName("expires_at") val expiresAt: Instant? = null,
    @SerialName("distance_m") val distanceM: Int,
    @SerialName("counts_for_credit") val countsForCredit: Boolean = true,
    val reasons: List<String> = emptyList(),
)

/**
 * One "your drop helped" inbox row (`my_impact_notices`).
 * At most one per drop per UTC day. [viewEvents] and [confirmEvents] are that day's totals.
 */
@Serializable
data class ImpactNotice(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("drop_id") val dropId: Uuid,
    @SerialName("short_id") val shortId: String,
    val title: String,
    @SerialName("notice_day") val noticeDay: String,
    @SerialName("view_events") val viewEvents: Int,
    @SerialName("confirm_events") val confirmEvents: Int,
    @Serializable(with = InstantSerializer::class)
    @SerialName("read_at") val readAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
    /** Not on the wire. The in-memory inbox uses it to keep each person's notes apart. */
    @Serializable(with = UuidSerializer::class)
    @SerialName("recipient_id") val recipientId: Uuid? = null,
    /** Not on the wire. The server orders the list; the in-memory inbox sorts on this. */
    @Serializable(with = InstantSerializer::class)
    @SerialName("updated_at") val updatedAt: Instant? = null,
) {
    val unread: Boolean get() = readAt == null
}
