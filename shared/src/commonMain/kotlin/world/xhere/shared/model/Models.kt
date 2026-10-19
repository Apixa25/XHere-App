package world.xhere.shared.model

import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.InstantSerializer
import world.xhere.shared.json.UuidSerializer

/**
 * Domain types for the M1 tables in `supabase/migrations/20261018000100_core_schema.sql`
 * and the taxonomy rules in `20261018000600_taxonomy.sql`.
 *
 * Geography is a [LatLng], not WKB. The true point is never returned to other users;
 * read models carry the display (possibly blurred) position only. See PLAN §5 Privacy.
 */

@Serializable
data class Profile(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    val handle: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null,
    val tier: UserTier = UserTier.NEW,
    val role: AppRole = AppRole.USER,
    val points: Int = 0,
    @SerialName("home_area") val homeArea: String? = null,
    /** Public bio, at most 160 characters. */
    val bio: String? = null,
    /** Optional http or https link. */
    val website: String? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    @SerialName("deleted_at") val deletedAt: Instant? = null,
) {
    val isModerator: Boolean get() = role == AppRole.MODERATOR || role == AppRole.ADMIN
    val isDeleted: Boolean get() = deletedAt != null
}

@Serializable
data class TierLimits(
    val tier: UserTier,
    @SerialName("drops_per_hour") val dropsPerHour: Int,
    @SerialName("drops_per_day") val dropsPerDay: Int,
    @SerialName("confirmations_per_hour") val confirmationsPerHour: Int,
    @SerialName("reports_per_day") val reportsPerDay: Int,
    @SerialName("max_media_per_drop") val maxMediaPerDrop: Int,
)

@Serializable
data class ContentFilter(
    val slug: String,
    val description: String,
    /** Case-insensitive POSIX regex, matched against title and body. The client translates the subset the taxonomy uses. */
    val pattern: String,
    val message: String,
)

@Serializable
data class CategoryGroup(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    val slug: String,
    val label: String,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val sort: Int = 0,
    val active: Boolean = true,
)

/**
 * One category row: JSON Schema for `drops.attributes`, plus the rules the database enforces
 * and [world.xhere.shared.rules.CategoryRules] mirrors for instant UI feedback.
 *
 * Intervals are [Duration]s. Calendar years and months in a Postgres interval are approximated
 * (365 and 30 days) for the preview; `create_drop` on the server is the authority.
 */
data class Category(
    val id: Uuid,
    val groupId: Uuid,
    val slug: String,
    val label: String,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val attributesSchema: JsonObject,
    val minTierToPost: UserTier = UserTier.NEW,
    val moderationMode: ModerationMode = ModerationMode.POST,
    val premodExemptTier: UserTier? = null,
    val sensitive: Boolean = false,
    val postingNotice: String? = null,
    val contentFilters: List<String> = emptyList(),
    val defaultRevealM: Int,
    val maxRevealM: Int,
    val locationPrecisionM: Int = 0,
    val defaultTtl: Duration? = null,
    val maxTtl: Duration? = null,
    val maxTtlNew: Duration? = null,
    val expiresAtAttribute: String? = null,
    val confirmExtendsTtl: Duration? = null,
    val permanentWhenConfirmed: Boolean = false,
    val confirmationsToConfirm: Int = 3,
    val denialsToHide: Int = 3,
    val reportsToHide: Int = 3,
    val requiresPhoto: Boolean = false,
    val allowAnonymousDisplay: Boolean = true,
    val active: Boolean = true,
    val sort: Int = 0,
    val version: Int = 1,
) {
    val minimumRevealM: Int get() = maxOf(10, locationPrecisionM)
}

data class Taxonomy(
    val groups: List<CategoryGroup>,
    val categories: List<Category>,
    val filters: List<ContentFilter>,
    val tierLimits: List<TierLimits>,
) {
    fun category(slug: String): Category? = categories.firstOrNull { it.slug == slug && it.active }

    fun group(slug: String): CategoryGroup? = groups.firstOrNull { it.slug == slug }

    fun categoriesIn(groupSlug: String): List<Category> {
        val groupId = group(groupSlug)?.id ?: return emptyList()
        return categories.filter { it.groupId == groupId && it.active }.sortedBy { it.sort }
    }

    fun limits(tier: UserTier): TierLimits? = tierLimits.firstOrNull { it.tier == tier }

    fun filtersFor(category: Category): List<ContentFilter> =
        filters.filter { it.slug in category.contentFilters }
}

/**
 * A drop as the app reasons about it. [displayLocation] is what other people see.
 * [trueLocation] is set only when this client just created the drop and still knows
 * where the author stood. It is never filled from a read RPC.
 */
data class Drop(
    val id: Uuid,
    val shortId: String,
    val authorId: Uuid?,
    val categoryId: Uuid,
    val categorySlug: String,
    val groupSlug: String,
    val categoryVersion: Int,
    val source: DropSource,
    val title: String,
    val body: String?,
    val attributes: JsonObject,
    val tags: List<String>,
    val displayLocation: LatLng,
    val trueLocation: LatLng?,
    val locationPrecisionM: Int,
    val revealRadiusM: Int,
    val status: DropStatus,
    val displayAnonymous: Boolean,
    val expiresAt: Instant?,
    val confirmCount: Int,
    val denyCount: Int,
    val lastConfirmedAt: Instant?,
    val createdAt: Instant,
    val removedReason: String?,
    val media: List<DropMedia>,
    val revealed: Boolean,
    val distanceM: Double?,
    val authorHandle: String?,
    val isMine: Boolean,
) {
    val isConfirmed: Boolean get() = status == DropStatus.CONFIRMED
}

@Serializable
data class DropMedia(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("storage_bucket") val storageBucket: String = "drop-media",
    @SerialName("storage_path") val storagePath: String,
    @SerialName("media_kind") val mediaKind: MediaKind,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_s") val durationS: Double? = null,
    @SerialName("taken_here") val takenHere: Boolean = false,
    /** Spoken name of the image. Null when the photo was posted without one. */
    @SerialName("alt_text") val altText: String? = null,
)

/** One item in `create_drop`'s `p_media` array. The server assigns the row id. */
@Serializable
data class MediaUpload(
    @SerialName("storage_bucket") val storageBucket: String = "drop-media",
    @SerialName("storage_path") val storagePath: String,
    @SerialName("media_kind") val mediaKind: MediaKind,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_s") val durationS: Double? = null,
    /** Sent with create_drop and stored on drop_media. At most 300 characters. */
    @SerialName("alt_text") val altText: String? = null,
    /** Read from the photo before Exif is stripped. The server does not store these. */
    @SerialName("capture_lat") val captureLat: Double? = null,
    @SerialName("capture_lng") val captureLng: Double? = null,
)

data class Confirmation(
    val dropId: Uuid,
    val userId: Uuid,
    val verdict: ConfirmationVerdict,
    val note: String?,
    val distanceM: Int,
    val createdAt: Instant,
    val countsForCredit: Boolean = true,
)

data class Report(
    val id: Uuid,
    val targetType: ReportTarget,
    val targetId: Uuid,
    val reporterId: Uuid,
    val reason: ReportReason,
    val details: String?,
    val status: ReportStatus,
    val createdAt: Instant,
)

data class UserBlock(
    val blockerId: Uuid,
    val blockedId: Uuid,
    val createdAt: Instant,
)

data class ModerationAction(
    val id: Long,
    val actorId: Uuid?,
    val targetType: String,
    val targetId: Uuid,
    val action: ModerationActionType,
    val reason: String?,
    val createdAt: Instant,
)
