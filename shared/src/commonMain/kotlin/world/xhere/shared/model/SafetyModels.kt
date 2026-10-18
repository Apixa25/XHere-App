package world.xhere.shared.model

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.json.InstantSerializer
import world.xhere.shared.json.UuidSerializer

/**
 * Wire shapes for the M7 safety RPCs in
 * `supabase/migrations/20261018001000_safety_compliance.sql`.
 */

/** `public_profile(handle)`: what anyone signed in may know about a handle shown on a drop. */
@Serializable
data class PublicProfile(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    val handle: String,
    @SerialName("display_name") val displayName: String? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
)

/** One row of `my_blocks()`. */
@Serializable
data class BlockedUser(
    @Serializable(with = UuidSerializer::class)
    @SerialName("user_id") val userId: Uuid,
    val handle: String,
    @SerialName("display_name") val displayName: String? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("blocked_at") val blockedAt: Instant,
)

/** One row of `my_hidden_drops()`. */
@Serializable
data class HiddenDrop(
    @Serializable(with = UuidSerializer::class)
    @SerialName("drop_id") val dropId: Uuid,
    @SerialName("short_id") val shortId: String,
    val title: String,
    @SerialName("category_slug") val categorySlug: String,
    @Serializable(with = InstantSerializer::class)
    @SerialName("hidden_at") val hiddenAt: Instant,
)

@Serializable
enum class QueueItemKind {
    @SerialName("drop") DROP,
    @SerialName("profile") PROFILE,
}

/**
 * One row of `moderation_queue()`. Drop rows carry the drop fields; profile rows
 * only the subject. [reportIds] are the open reports to resolve together.
 */
@Serializable
data class ModerationQueueItem(
    val kind: QueueItemKind,
    @Serializable(with = UuidSerializer::class)
    @SerialName("target_id") val targetId: Uuid,
    @SerialName("short_id") val shortId: String? = null,
    val title: String? = null,
    val body: String? = null,
    @SerialName("category_slug") val categorySlug: String? = null,
    val status: DropStatus? = null,
    val sensitive: Boolean = false,
    @Serializable(with = UuidSerializer::class)
    @SerialName("subject_id") val subjectId: Uuid? = null,
    @SerialName("subject_handle") val subjectHandle: String? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("subject_suspended_until") val subjectSuspendedUntil: Instant? = null,
    @SerialName("open_reports") val openReports: Int = 0,
    val reasons: List<ReportReason> = emptyList(),
    @SerialName("latest_details") val latestDetails: String? = null,
    @SerialName("report_ids") val reportIds: List<@Serializable(with = UuidSerializer::class) Uuid> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    @SerialName("queued_at") val queuedAt: Instant,
) {
    val isPending: Boolean get() = status == DropStatus.PENDING_REVIEW
    fun isSubjectSuspended(now: Instant): Boolean = subjectSuspendedUntil?.let { it > now } == true
}
