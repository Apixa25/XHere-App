package world.xhere.shared.data.remote

import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import world.xhere.shared.json.UuidSerializer
import world.xhere.shared.model.Category
import world.xhere.shared.model.CategoryGroup
import world.xhere.shared.model.ContentFilter
import world.xhere.shared.model.ModerationMode
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.TierLimits
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.PgInterval

/** Columns of `public.categories` the client reads. `embedding` is intentionally absent. */
@Serializable
internal data class CategoryRow(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("group_id") val groupId: Uuid,
    val slug: String,
    val label: String,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    @SerialName("attributes_schema") val attributesSchema: JsonObject,
    @SerialName("min_tier_to_post") val minTierToPost: UserTier = UserTier.NEW,
    @SerialName("moderation_mode") val moderationMode: ModerationMode = ModerationMode.POST,
    @SerialName("premod_exempt_tier") val premodExemptTier: UserTier? = null,
    val sensitive: Boolean = false,
    @SerialName("posting_notice") val postingNotice: String? = null,
    @SerialName("content_filters") val contentFilters: List<String> = emptyList(),
    @SerialName("default_reveal_m") val defaultRevealM: Int,
    @SerialName("max_reveal_m") val maxRevealM: Int,
    @SerialName("location_precision_m") val locationPrecisionM: Int = 0,
    @SerialName("default_ttl") val defaultTtl: String? = null,
    @SerialName("max_ttl") val maxTtl: String? = null,
    @SerialName("max_ttl_new") val maxTtlNew: String? = null,
    @SerialName("expires_at_attribute") val expiresAtAttribute: String? = null,
    @SerialName("confirm_extends_ttl") val confirmExtendsTtl: String? = null,
    @SerialName("permanent_when_confirmed") val permanentWhenConfirmed: Boolean = false,
    @SerialName("confirmations_to_confirm") val confirmationsToConfirm: Int = 3,
    @SerialName("denials_to_hide") val denialsToHide: Int = 3,
    @SerialName("reports_to_hide") val reportsToHide: Int = 3,
    @SerialName("requires_photo") val requiresPhoto: Boolean = false,
    @SerialName("allow_anonymous_display") val allowAnonymousDisplay: Boolean = true,
    val active: Boolean = true,
    val sort: Int = 0,
    val version: Int = 1,
) {
    fun toCategory(): Category = Category(
        id = id,
        groupId = groupId,
        slug = slug,
        label = label,
        description = description,
        icon = icon,
        color = color,
        attributesSchema = attributesSchema,
        minTierToPost = minTierToPost,
        moderationMode = moderationMode,
        premodExemptTier = premodExemptTier,
        sensitive = sensitive,
        postingNotice = postingNotice,
        contentFilters = contentFilters,
        defaultRevealM = defaultRevealM,
        maxRevealM = maxRevealM,
        locationPrecisionM = locationPrecisionM,
        defaultTtl = defaultTtl?.let(PgInterval::parse),
        maxTtl = maxTtl?.let(PgInterval::parse),
        maxTtlNew = maxTtlNew?.let(PgInterval::parse),
        expiresAtAttribute = expiresAtAttribute,
        confirmExtendsTtl = confirmExtendsTtl?.let(PgInterval::parse),
        permanentWhenConfirmed = permanentWhenConfirmed,
        confirmationsToConfirm = confirmationsToConfirm,
        denialsToHide = denialsToHide,
        reportsToHide = reportsToHide,
        requiresPhoto = requiresPhoto,
        allowAnonymousDisplay = allowAnonymousDisplay,
        active = active,
        sort = sort,
        version = version,
    )
}

internal fun taxonomyOf(
    groups: List<CategoryGroup>,
    categories: List<CategoryRow>,
    filters: List<ContentFilter>,
    limits: List<TierLimits>,
): Taxonomy = Taxonomy(
    groups = groups.sortedBy { it.sort },
    categories = categories.map { it.toCategory() }.sortedWith(compareBy({ it.groupId.toString() }, { it.sort })),
    filters = filters.sortedBy { it.slug },
    tierLimits = limits,
)
