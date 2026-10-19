package world.xhere.shared.data

import world.xhere.shared.rules.RuleViolation

/**
 * A failed RPC, or the same slug raised locally by [world.xhere.shared.rules.CategoryRules].
 *
 * [code] is the stable message slug (`invalid_attributes`, `too_far`, …).
 * [sqlState] is the SQLSTATE PostgREST maps to HTTP: `42501` permission, `22023` validation,
 * `P0002` not found, `PT429` rate limited. See `supabase/README.md`.
 */
class XHereApiException(
    val code: String,
    val sqlState: String? = null,
    val detail: String? = null,
    val hint: String? = null,
    val violations: List<RuleViolation> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(if (detail.isNullOrBlank()) code else "$code: $detail", cause) {
    val isValidation: Boolean get() = sqlState == SQL_VALIDATION || code in VALIDATION_CODES
    val isNotFound: Boolean get() = sqlState == SQL_NOT_FOUND
    val isRateLimited: Boolean get() = sqlState == SQL_RATE_LIMITED || code == "rate_limited"
    val isPermission: Boolean get() = sqlState == SQL_PERMISSION

    companion object {
        const val SQL_PERMISSION: String = "42501"
        const val SQL_VALIDATION: String = "22023"
        const val SQL_NOT_FOUND: String = "P0002"
        const val SQL_RATE_LIMITED: String = "PT429"

        fun fromViolation(violation: RuleViolation, violations: List<RuleViolation> = listOf(violation)): XHereApiException =
            XHereApiException(
                code = violation.code,
                sqlState = sqlStateFor(violation.code),
                detail = violation.detail,
                hint = violation.hint,
                violations = violations,
            )

        fun sqlStateFor(code: String): String = when (code) {
            "not_authenticated",
            "account_unavailable",
            "account_suspended",
            "tier_too_low",
            "not_moderator",
            "not_admin",
            "not_owner",
            "not_allowed",
            "cannot_confirm_own_drop",
            "cannot_moderate_own_drop",
            "cannot_moderate_self",
            -> SQL_PERMISSION
            "drop_not_found", "user_not_found", "report_not_found" -> SQL_NOT_FOUND
            "rate_limited" -> SQL_RATE_LIMITED
            else -> SQL_VALIDATION
        }

        private val VALIDATION_CODES = setOf(
            "invalid_location",
            "invalid_title",
            "invalid_body",
            "invalid_tags",
            "invalid_attributes",
            "content_not_allowed",
            "anonymous_not_allowed",
            "invalid_reveal_radius",
            "expires_in_past",
            "expires_too_far",
            "invalid_media",
            "voice_too_long",
            "voice_too_large",
            "photo_required",
            "guidelines_not_acknowledged",
            "category_not_found",
            "invalid_radius",
            "invalid_bbox",
            "invalid_zoom",
            "too_far",
            "location_not_trusted",
            "email_domain_mismatch",
            "place_already_claimed",
            "not_a_place",
            "invalid_claim",
            "invalid_website",
            "invalid_capsule",
            "invalid_hours",
            "invalid_invite",
            "plan_not_available",
            "invalid_note",
            "invalid_report",
            "invalid_verdict",
        )
    }
}
