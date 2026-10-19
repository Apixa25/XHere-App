package world.xhere.shared.model

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.json.InstantSerializer
import world.xhere.shared.json.UuidSerializer

/** One row of official hours. [day] is 1..7, Monday through Sunday. */
@Serializable
data class PlaceHours(
    val day: Int,
    val open: String,
    val close: String,
)

/** The Official block on a Here Card or a drop. Null when no claim is active. */
@Serializable
data class OfficialPlace(
    @Serializable(with = UuidSerializer::class)
    @SerialName("place_id") val placeId: Uuid,
    val name: String,
    val badge: String = "Official",
    val summary: String? = null,
    val hours: List<PlaceHours> = emptyList(),
    @SerialName("website_domain") val websiteDomain: String? = null,
    @Serializable(with = UuidSerializer::class)
    @SerialName("anchor_drop_id") val anchorDropId: Uuid,
    @SerialName("plan_slug") val planSlug: String = "free",
    @SerialName("open_disputes") val openDisputes: Int = 0,
    @SerialName("can_reply") val canReply: Boolean = false,
)

@Serializable
data class PlaceCreated(
    @Serializable(with = UuidSerializer::class)
    @SerialName("place_id") val placeId: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("drop_id") val dropId: Uuid,
    @SerialName("short_id") val shortId: String,
)

@Serializable
data class ClaimTicket(
    @Serializable(with = UuidSerializer::class)
    @SerialName("claim_id") val claimId: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("place_id") val placeId: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("team_id") val teamId: Uuid? = null,
    val status: String,
    @SerialName("proof_method") val proofMethod: String? = null,
    @SerialName("plan_slug") val planSlug: String = "free",
)

@Serializable
data class PostcardStub(
    val requested: Boolean = true,
    val mailed: Boolean = false,
    val status: String = "pending_proof",
)

@Serializable
data class PlaceMembership(
    @Serializable(with = UuidSerializer::class)
    @SerialName("place_id") val placeId: Uuid,
    val name: String,
    val role: String,
    val status: String,
    @SerialName("plan_slug") val planSlug: String = "free",
    @SerialName("claim_status") val claimStatus: String? = null,
)

@Serializable
data class OwnerReply(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("place_id") val placeId: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("drop_id") val dropId: Uuid,
    @SerialName("author_handle") val authorHandle: String? = null,
    val body: String,
    @Serializable(with = InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
)

@Serializable
data class ClaimQueueItem(
    val kind: String,
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("place_id") val placeId: Uuid,
    @SerialName("place_name") val placeName: String,
    val status: String,
    @SerialName("claimant_handle") val claimantHandle: String? = null,
    @SerialName("proof_method") val proofMethod: String? = null,
    @SerialName("website_domain") val websiteDomain: String? = null,
    val note: String? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
)

@Serializable
data class AnnouncementPosted(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("short_id") val shortId: String,
    @Serializable(with = UuidSerializer::class)
    @SerialName("place_id") val placeId: Uuid,
)

@Serializable
data class CapsuleCreated(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("short_id") val shortId: String,
    @SerialName("capsule_mode") val capsuleMode: String,
    @SerialName("capsule_locked") val capsuleLocked: Boolean = true,
    @Serializable(with = InstantSerializer::class)
    @SerialName("capsule_unlock_at") val capsuleUnlockAt: Instant? = null,
    @SerialName("oral_history") val oralHistory: Boolean = false,
)

@Serializable
data class OpenedCount(
    val opened: Int = 0,
)

data class CapsuleRequest(
    val title: String,
    val body: String?,
    val latitude: Double,
    val longitude: Double,
    val mode: String,
    val unlockAt: Instant? = null,
    val oralHistory: Boolean = false,
    val media: List<MediaUpload> = emptyList(),
    val signal: LocationSignal? = null,
)
