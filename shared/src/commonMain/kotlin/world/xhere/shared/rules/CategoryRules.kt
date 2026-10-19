package world.xhere.shared.rules

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import world.xhere.shared.geo.Geo
import world.xhere.shared.live.RightNowDuration
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.parseTimestamp
import world.xhere.shared.model.Category
import world.xhere.shared.model.ContentFilter
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload
import world.xhere.shared.model.ModerationMode
import world.xhere.shared.model.TierLimits
import world.xhere.shared.model.UserTier
import world.xhere.shared.text.charLength
import world.xhere.shared.validation.JsonSchemaValidator
import world.xhere.shared.validation.PosixPattern

/**
 * One problem with a draft, using the same slug `create_drop` / `confirm_drop` raise.
 * [field] is a hint for the composer (which control to mark). The server does not send it.
 */
data class RuleViolation(
    val code: String,
    val detail: String? = null,
    val hint: String? = null,
    val field: String? = null,
)

/**
 * Who is posting, for the checks that depend on the account rather than the draft.
 * Rate-limit fields are optional: when the client does not know recent counts, the
 * server still enforces `tier_limits`.
 */
data class PostingContext(
    val userId: Uuid?,
    val tier: UserTier,
    val now: Instant,
    val suspended: Boolean = false,
    val dropsLastHour: Int? = null,
    val dropsLastDay: Int? = null,
    val limits: TierLimits? = null,
)

/**
 * What the composer collected. Strings are trimmed and tags are normalized inside
 * [CategoryRules.check]; pass the raw field values.
 */
data class DropDraft(
    val categorySlug: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val body: String? = null,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val tags: List<String> = emptyList(),
    val revealRadiusM: Int? = null,
    val expiresAt: Instant? = null,
    val displayAnonymous: Boolean = false,
    val media: List<MediaUpload> = emptyList(),
    val acknowledgeGuidelines: Boolean = false,
)

/**
 * The draft after the same normalization `create_drop` applies, plus every rule that failed.
 * [status] is what a successful post would land in (`pending_review` or `active`).
 * [displayLocation] is the blurred pin. Send [toRequest] only when [isValid].
 */
data class CheckedDrop(
    val violations: List<RuleViolation>,
    val title: String,
    val body: String?,
    val tags: List<String>,
    val attributes: JsonObject,
    val revealRadiusM: Int,
    val expiresAt: Instant?,
    val status: DropStatus,
    val trueLocation: LatLng?,
    val displayLocation: LatLng?,
) {
    val isValid: Boolean get() = violations.isEmpty()

    fun toRequest(draft: DropDraft): CreateDropRequest {
        check(isValid) { "draft has violations: ${violations.joinToString { it.code }}" }
        val location = checkNotNull(trueLocation)
        return CreateDropRequest(
            categorySlug = draft.categorySlug,
            latitude = location.latitude,
            longitude = location.longitude,
            title = title,
            body = body,
            attributes = attributes,
            tags = tags,
            revealRadiusM = revealRadiusM,
            expiresAt = expiresAt,
            displayAnonymous = draft.displayAnonymous,
            media = draft.media,
            acknowledgeGuidelines = draft.acknowledgeGuidelines,
        )
    }
}

data class CreateDropRequest(
    val categorySlug: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val body: String?,
    val attributes: JsonObject,
    val tags: List<String>,
    val revealRadiusM: Int?,
    val expiresAt: Instant?,
    val displayAnonymous: Boolean,
    val media: List<MediaUpload>,
    val acknowledgeGuidelines: Boolean,
)

/**
 * Mirrors the category rules in `private.needs_premod`, `private.fuzz_point`,
 * `private.refresh_drop_confirmations`, and `public.create_drop`
 * (`supabase/migrations/20261018000200_rules.sql` and `000400_rpcs.sql`).
 *
 * The database is the source of truth. These checks exist so the UI can flag a field
 * before the RPC. They collect every problem instead of stopping at the first, which
 * is the one deliberate difference from `RAISE EXCEPTION`.
 */
object CategoryRules {
    const val MIN_TITLE: Int = 1
    const val MAX_TITLE: Int = 120
    const val MAX_BODY: Int = 2_000
    const val MAX_TAGS: Int = 10
    const val MAX_NOTE: Int = 280
    const val OCCURRED_AT_FUTURE_SLACK_MINUTES: Int = 5

    private val TAG = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
    private val MIME = Regex("^[a-z]+/[a-z0-9.+-]+$")
    private val STORAGE_PATH = Regex("^[A-Za-z0-9/_.-]+$")

    /** `private.needs_premod`. */
    fun needsPremoderation(category: Category, tier: UserTier): Boolean = when (category.moderationMode) {
        ModerationMode.POST -> false
        ModerationMode.PRE_FOR_NEW -> tier == UserTier.NEW
        ModerationMode.PRE -> category.premodExemptTier == null || !tier.isAtLeast(category.premodExemptTier)
    }

    fun initialStatus(category: Category, tier: UserTier): DropStatus =
        if (needsPremoderation(category, tier)) DropStatus.PENDING_REVIEW else DropStatus.ACTIVE

    /**
     * The status `private.refresh_drop_confirmations` would write for a live drop.
     * Removed, expired, and pending rows are left alone, matching the SQL `case`.
     */
    fun statusAfterConfirmations(
        current: DropStatus,
        confirmCount: Int,
        denyCount: Int,
        category: Category,
    ): DropStatus {
        if (current !in LIVE_STATUSES) return current
        if (denyCount >= category.denialsToHide && denyCount > confirmCount) return DropStatus.DISPUTED
        if (confirmCount >= category.confirmationsToConfirm) return DropStatus.CONFIRMED
        return DropStatus.ACTIVE
    }

    /**
     * When a category becomes permanent at the confirmation bar, expiry is cleared.
     * Returns the current expiry otherwise.
     */
    fun expiryAfterConfirmations(
        current: DropStatus,
        expiresAt: Instant?,
        confirmCount: Int,
        denyCount: Int,
        category: Category,
    ): Instant? {
        val hidden = denyCount >= category.denialsToHide && denyCount > confirmCount
        val confirmed = confirmCount >= category.confirmationsToConfirm
        if (category.permanentWhenConfirmed && current in LIVE_STATUSES && confirmed && !hidden) return null
        return expiresAt
    }

    fun checkConfirmation(
        distanceM: Double,
        revealRadiusM: Int,
        note: String?,
        isOwnDrop: Boolean,
    ): List<RuleViolation> {
        val violations = mutableListOf<RuleViolation>()
        if (isOwnDrop) {
            violations += RuleViolation("cannot_confirm_own_drop", field = "verdict")
        }
        if (!Geo.isWithinConfirmationRange(distanceM, revealRadiusM)) {
            val range = Geo.confirmationRangeM(revealRadiusM)
            violations += RuleViolation(
                code = "too_far",
                detail = "${distanceM.toInt()} m away; must be within $range m",
                field = "location",
            )
        }
        if (note != null && note.charLength() > MAX_NOTE) {
            violations += RuleViolation("invalid_note", detail = "note must be at most $MAX_NOTE characters", field = "note")
        }
        return violations
    }

    fun check(draft: DropDraft, category: Category, context: PostingContext, filters: List<ContentFilter>): CheckedDrop {
        val violations = mutableListOf<RuleViolation>()

        if (!category.active || category.slug != draft.categorySlug) {
            violations += RuleViolation("category_not_found", detail = draft.categorySlug, field = "category")
        }
        if (context.userId == null) {
            violations += RuleViolation("not_authenticated", field = "account")
        }
        if (context.suspended) {
            violations += RuleViolation("account_suspended", field = "account")
        }
        if (context.userId != null && !context.tier.isAtLeast(category.minTierToPost)) {
            violations += RuleViolation(
                code = "tier_too_low",
                detail = "${category.slug} requires tier ${category.minTierToPost.name.lowercase()}",
                field = "category",
            )
        }
        if (category.sensitive && !draft.acknowledgeGuidelines) {
            violations += RuleViolation(
                code = "guidelines_not_acknowledged",
                hint = category.postingNotice,
                field = "guidelines",
            )
        }

        val trueLocation = LatLng(draft.latitude, draft.longitude)
        val locationOk = Geo.isValid(trueLocation)
        if (!locationOk) {
            violations += RuleViolation(
                code = "invalid_location",
                detail = "lat=${draft.latitude} lng=${draft.longitude}",
                field = "location",
            )
        }

        val title = draft.title.trim()
        if (title.charLength() !in MIN_TITLE..MAX_TITLE) {
            violations += RuleViolation("invalid_title", detail = "title must be 1..120 characters", field = "title")
        }
        val body = draft.body?.trim()?.ifEmpty { null }
        if (body != null && body.charLength() > MAX_BODY) {
            violations += RuleViolation("invalid_body", detail = "body must be at most 2000 characters", field = "body")
        }

        val tags = normalizeTags(draft.tags)
        if (tags.size > MAX_TAGS || tags.any { !TAG.matches(it) }) {
            violations += RuleViolation(
                code = "invalid_tags",
                detail = "at most 10 tags of 1..32 characters: a-z, 0-9, _ and -",
                field = "tags",
            )
        }

        if (draft.attributes.isNotEmpty() || category.attributesSchema.isNotEmpty()) {
            val schemaErrors = JsonSchemaValidator.validate(category.attributesSchema, draft.attributes)
            if (schemaErrors.isNotEmpty()) {
                violations += RuleViolation(
                    code = "invalid_attributes",
                    detail = schemaErrors.joinToString("; "),
                    field = "attributes",
                )
            }
        }
        violations += crossFieldAttributes(category, draft.attributes, context.now)

        val applicable = filters.filter { it.slug in category.contentFilters }
        val texts = listOfNotNull(title, body)
        for (filter in applicable) {
            val regex = PosixPattern.compile(filter.pattern) ?: continue
            if (texts.any { regex.containsMatchIn(it) }) {
                violations += RuleViolation(
                    code = "content_not_allowed",
                    detail = filter.slug,
                    hint = filter.message,
                    field = "body",
                )
                break
            }
        }

        if (draft.displayAnonymous && !category.allowAnonymousDisplay) {
            violations += RuleViolation("anonymous_not_allowed", detail = category.slug, field = "anonymous")
        }

        val reveal = draft.revealRadiusM ?: category.defaultRevealM
        val minReveal = category.minimumRevealM
        if (reveal < minReveal || reveal > category.maxRevealM) {
            violations += RuleViolation(
                code = "invalid_reveal_radius",
                detail = "allowed $minReveal..${category.maxRevealM} m",
                field = "reveal",
            )
        }

        val expiry = resolveExpiry(category, context, draft.attributes, draft.expiresAt)
        violations += expiry.violations

        violations += checkMedia(draft.media, category, context)

        val limits = context.limits
        if (limits != null && context.dropsLastHour != null && context.dropsLastDay != null) {
            if (context.dropsLastHour >= limits.dropsPerHour || context.dropsLastDay >= limits.dropsPerDay) {
                violations += RuleViolation(
                    code = "rate_limited",
                    detail = "tier ${context.tier.name.lowercase()} allows ${limits.dropsPerHour} drops per hour and ${limits.dropsPerDay} per day",
                    field = "account",
                )
            }
        }

        val display = if (locationOk) Geo.fuzz(trueLocation, category.locationPrecisionM) else null
        return CheckedDrop(
            violations = violations,
            title = title,
            body = body,
            tags = tags,
            attributes = draft.attributes,
            revealRadiusM = reveal,
            expiresAt = expiry.expiresAt,
            status = initialStatus(category, context.tier),
            trueLocation = if (locationOk) trueLocation else null,
            displayLocation = display,
        )
    }

    fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    private fun crossFieldAttributes(category: Category, attributes: JsonObject, now: Instant): List<RuleViolation> {
        val violations = mutableListOf<RuleViolation>()
        val starts = attributes.timestamp("starts_at")
        val ends = attributes.timestamp("ends_at")
        if (starts != null && ends != null && ends < starts) {
            violations += RuleViolation("invalid_attributes", detail = "ends_at is before starts_at", field = "attributes")
        }
        val occurred = attributes.timestamp("occurred_at")
        if (occurred != null) {
            if (occurred > now + OCCURRED_AT_FUTURE_SLACK_MINUTES.minutes) {
                violations += RuleViolation("invalid_attributes", detail = "occurred_at is in the future", field = "attributes")
            }
            val maxTtl = category.maxTtl
            if (maxTtl != null && occurred < now - maxTtl) {
                violations += RuleViolation(
                    code = "invalid_attributes",
                    detail = "occurred_at is older than ${PgInterval.format(maxTtl)}",
                    field = "attributes",
                )
            }
        }
        return violations
    }

    private fun resolveExpiry(
        category: Category,
        context: PostingContext,
        attributes: JsonObject,
        requested: Instant?,
    ): ExpiryDecision {
        val violations = mutableListOf<RuleViolation>()
        val cap = if (context.tier == UserTier.NEW && category.maxTtlNew != null) category.maxTtlNew else category.maxTtl
        var expires: Instant? = null
        var explicit = false
        val attributeName = category.expiresAtAttribute
        if (attributeName != null && attributes.containsKey(attributeName)) {
            val parsed = attributes.timestamp(attributeName) ?: attributes.date(attributeName)
            if (parsed == null) {
                violations += RuleViolation(
                    code = "invalid_attributes",
                    detail = "$attributeName is not a date",
                    field = "attributes",
                )
            } else {
                expires = parsed
                explicit = true
            }
        }
        if (requested != null) {
            expires = if (expires == null) requested else minOf(expires, requested)
            explicit = true
        }
        if (expires == null && category.defaultTtl != null) {
            expires = context.now + category.defaultTtl
        }
        if (explicit && expires != null && expires <= context.now) {
            violations += RuleViolation("expires_in_past", field = "expires")
        }
        if (cap != null) {
            val capAt = context.now + cap
            when {
                expires == null -> expires = capAt
                expires > capAt && explicit -> {
                    violations += RuleViolation(
                        code = "expires_too_far",
                        detail = "${category.slug} allows at most ${PgInterval.format(cap)} ahead",
                        field = "expires",
                    )
                }
                expires > capAt -> expires = capAt
            }
        }
        val tooFar = violations.any { it.code == "expires_too_far" }
        if (category.slug == "right_now" && !tooFar) {
            val span = expires?.let { it - context.now }
            if (span == null || !RightNowDuration.matches(span)) {
                violations += RuleViolation(
                    code = "invalid_right_now_ttl",
                    detail = "right now drops last 1 hour, 4 hours, or 24 hours",
                    field = "expires",
                )
            }
        }
        return ExpiryDecision(expires, violations)
    }

    private fun checkMedia(media: List<MediaUpload>, category: Category, context: PostingContext): List<RuleViolation> {
        val violations = mutableListOf<RuleViolation>()
        val maxMedia = context.limits?.maxMediaPerDrop
        if (maxMedia != null && media.size > maxMedia) {
            violations += RuleViolation("too_many_media", detail = "max $maxMedia", field = "media")
        }
        for (item in media) {
            val kind = item.mediaKind
            val mimeOk = MIME.matches(item.mimeType)
            val kindOk = kind == MediaKind.FILE || item.mimeType.substringBefore('/') == kind.name.lowercase()
            val path = item.storagePath
            val owner = context.userId?.toString()
            val pathOk = path.isNotEmpty() &&
                STORAGE_PATH.matches(path) &&
                ".." !in path &&
                (owner == null || path.substringBefore('/') == owner)
            val sizeOk = item.byteSize == null || item.byteSize > 0
            val widthOk = item.width == null || item.width > 0
            val heightOk = item.height == null || item.height > 0
            if (!mimeOk || !kindOk || !pathOk || !sizeOk || !widthOk || !heightOk || item.storageBucket.isBlank()) {
                violations += RuleViolation(
                    code = "invalid_media",
                    detail = "each item needs media_kind, mime_type, and a storage_path under \"<your user id>/\"",
                    field = "media",
                )
                break
            }
        }
        if (category.requiresPhoto && media.none { it.mediaKind == MediaKind.IMAGE }) {
            violations += RuleViolation("photo_required", detail = category.slug, field = "media")
        }
        return violations
    }

    private fun JsonObject.timestamp(key: String): Instant? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return try {
            parseTimestamp(primitive.content)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** A `date` attribute (construction `est_completion`) becomes midnight UTC, matching a `timestamptz` cast of a date. */
    private fun JsonObject.date(key: String): Instant? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString || primitive.content.length != 10) return null
        return try {
            parseTimestamp("${primitive.content}T00:00:00Z")
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private data class ExpiryDecision(val expiresAt: Instant?, val violations: List<RuleViolation>)

    private val LIVE_STATUSES = setOf(DropStatus.ACTIVE, DropStatus.CONFIRMED, DropStatus.DISPUTED)
}
