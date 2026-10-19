package world.xhere.shared.data.remote

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.json.InstantSerializer
import world.xhere.shared.json.UuidSerializer
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.CreateThingRequest
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportStatus
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.model.ThingMedia
import world.xhere.shared.rules.CreateDropRequest

/**
 * Argument names are the SQL parameter names (`p_lat`, …). PostgREST matches on those names.
 * Null lists are omitted (`explicitNulls = false`) so the SQL default, usually "no filter", applies.
 */

@Serializable
internal data class DropsInViewParams(
    @SerialName("p_min_lng") val minLng: Double,
    @SerialName("p_min_lat") val minLat: Double,
    @SerialName("p_max_lng") val maxLng: Double,
    @SerialName("p_max_lat") val maxLat: Double,
    @SerialName("p_categories") val categories: List<String>? = null,
    @SerialName("p_zoom") val zoom: Int,
)

@Serializable
internal data class DropsNearbyParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_radius_m") val radiusM: Int,
    @SerialName("p_categories") val categories: List<String>? = null,
    @SerialName("p_limit") val limit: Int,
)

@Serializable
internal data class HereCardParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_viewer_lat") val viewerLat: Double? = null,
    @SerialName("p_viewer_lng") val viewerLng: Double? = null,
    @SerialName("p_radius_m") val radiusM: Int,
    @SerialName("p_limit") val limit: Int,
)

@Serializable
internal data class RegionAtParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
)

@Serializable
internal data class RegionLiveRow(
    @SerialName("region_id") val regionId: String,
    val name: String = "",
    val country: String = "",
    @SerialName("weather_provider") val weatherProvider: String? = null,
)

@Serializable
internal data class PlaceLabelParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
)

@Serializable
internal data class RememberPlaceParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_name") val name: String,
    @SerialName("p_address") val address: String? = null,
)

@Serializable
internal data class DropDetailParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_drop_id") val dropId: Uuid,
    @SerialName("p_lat") val lat: Double? = null,
    @SerialName("p_lng") val lng: Double? = null,
    @SerialName("p_integrity") val integrity: LocationSignal? = null,
)

@Serializable
internal data class DropTeaserParams(
    @SerialName("p_short_id") val shortId: String,
)

@Serializable
internal data class CreateDropParams(
    @SerialName("p_category") val category: String,
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_title") val title: String,
    @SerialName("p_body") val body: String? = null,
    @SerialName("p_attributes") val attributes: JsonObject,
    @SerialName("p_tags") val tags: List<String>,
    @SerialName("p_reveal_radius_m") val revealRadiusM: Int? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("p_expires_at") val expiresAt: Instant? = null,
    @SerialName("p_display_anonymous") val displayAnonymous: Boolean = false,
    @SerialName("p_media") val media: List<MediaUpload> = emptyList(),
    @SerialName("p_acknowledge_guidelines") val acknowledgeGuidelines: Boolean = false,
    @SerialName("p_integrity") val integrity: LocationSignal? = null,
)

@Serializable
internal data class WalksForPointParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
)

@Serializable
internal data class StartWalkParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_walk_id") val walkId: Uuid,
)

@Serializable
internal data class CheckInParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_walk_id") val walkId: Uuid? = null,
    @SerialName("p_integrity") val integrity: LocationSignal? = null,
)

@Serializable
internal data class DismissQuestParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_quest_id") val questId: Uuid,
)

@Serializable
internal data class ImpactNoticesParams(
    @SerialName("p_limit") val limit: Int = 30,
)

@Serializable
internal data class MarkImpactReadParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_notice_id") val noticeId: Uuid? = null,
)

@Serializable
internal data class ConfirmDropParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_drop_id") val dropId: Uuid,
    @SerialName("p_verdict") val verdict: ConfirmationVerdict,
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_note") val note: String? = null,
    @SerialName("p_integrity") val integrity: LocationSignal? = null,
)

@Serializable
internal data class ReportParams(
    @SerialName("p_target_type") val targetType: ReportTarget,
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_target_id") val targetId: Uuid,
    @SerialName("p_reason") val reason: ReportReason,
    @SerialName("p_details") val details: String? = null,
)

@Serializable
internal data class UserIdParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_user_id") val userId: Uuid,
)

@Serializable
internal data class ModerateDropParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_drop_id") val dropId: Uuid,
    @SerialName("p_action") val action: String,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
internal data class ResolveReportParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_report_id") val reportId: Uuid,
    @SerialName("p_status") val status: ReportStatus,
    @SerialName("p_note") val note: String? = null,
)

@Serializable
internal data class SuspendUserParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_user_id") val userId: Uuid,
    @Serializable(with = InstantSerializer::class)
    @SerialName("p_until") val until: Instant? = null,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
internal data class DropIdParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_drop_id") val dropId: Uuid,
)

@Serializable
internal data class CampaignsForMapParams(
    @SerialName("p_min_lng") val minLng: Double,
    @SerialName("p_min_lat") val minLat: Double,
    @SerialName("p_max_lng") val maxLng: Double,
    @SerialName("p_max_lat") val maxLat: Double,
)

@Serializable
internal data class LeaderboardVisibilityParams(
    @SerialName("p_visible") val visible: Boolean,
)

@Serializable
internal data class HandleParams(
    @SerialName("p_handle") val handle: String,
)

@Serializable
internal data class QueueParams(
    @SerialName("p_limit") val limit: Int,
)

@Serializable
internal data class HistoryAtParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
)

@Serializable
internal data class ClippingsAtParams(
    @SerialName("p_lat") val lat: Double,
    @SerialName("p_lng") val lng: Double,
    @SerialName("p_terms") val terms: List<String>,
)

@Serializable
internal data class CreateThingParams(
    @SerialName("p_title") val title: String,
    @SerialName("p_story") val story: String,
    @SerialName("p_category_slug") val categorySlug: String? = null,
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_drop_id") val dropId: Uuid? = null,
    @SerialName("p_place_name") val placeName: String? = null,
    @SerialName("p_lat") val lat: Double? = null,
    @SerialName("p_lng") val lng: Double? = null,
    @SerialName("p_media") val media: List<ThingMedia>? = null,
    @SerialName("p_visibility") val visibility: String? = null,
)

@Serializable
internal data class UpdateThingParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_thing_id") val thingId: Uuid,
    @SerialName("p_title") val title: String,
    @SerialName("p_story") val story: String,
    @SerialName("p_category_slug") val categorySlug: String? = null,
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_drop_id") val dropId: Uuid? = null,
    @SerialName("p_place_name") val placeName: String? = null,
    @SerialName("p_lat") val lat: Double? = null,
    @SerialName("p_lng") val lng: Double? = null,
    @SerialName("p_media") val media: List<ThingMedia>? = null,
    @SerialName("p_visibility") val visibility: String? = null,
)

@Serializable
internal data class ThingKeysParams(
    @SerialName("p_keys") val keys: List<String>,
)

@Serializable
internal data class BindBeaconParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_thing_id") val thingId: Uuid,
    @SerialName("p_key") val key: String,
)

@Serializable
internal data class ThingIdParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_thing_id") val thingId: Uuid,
)

@Serializable
internal data class ReportThingParams(
    @Serializable(with = UuidSerializer::class)
    @SerialName("p_thing_id") val thingId: Uuid,
    @SerialName("p_reason") val reason: ReportReason,
    @SerialName("p_details") val details: String? = null,
)

internal fun CreateThingRequest.toThingParams(): CreateThingParams = CreateThingParams(
    title = title,
    story = story,
    categorySlug = categorySlug,
    dropId = dropId,
    placeName = placeName,
    lat = latitude,
    lng = longitude,
    media = media,
    visibility = visibility,
)

internal fun CreateThingRequest.toUpdateParams(thingId: Uuid): UpdateThingParams = UpdateThingParams(
    thingId = thingId,
    title = title,
    story = story,
    categorySlug = categorySlug,
    dropId = dropId,
    placeName = placeName,
    lat = latitude,
    lng = longitude,
    media = media,
    visibility = visibility,
)

internal fun CreateDropRequest.toParams(): CreateDropParams = CreateDropParams(
    category = categorySlug,
    lat = latitude,
    lng = longitude,
    title = title,
    body = body,
    attributes = attributes,
    tags = tags,
    revealRadiusM = revealRadiusM,
    expiresAt = expiresAt,
    displayAnonymous = displayAnonymous,
    media = media,
    acknowledgeGuidelines = acknowledgeGuidelines,
    integrity = signal,
)
