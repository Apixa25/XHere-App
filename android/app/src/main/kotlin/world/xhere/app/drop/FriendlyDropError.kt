package world.xhere.app.drop

import world.xhere.shared.data.XHereApiException

/**
 * Turns a `create_drop` slug into a sentence. The slug stays in the exception
 * for logs; the screen shows this copy (PLAN §7 M5).
 */
fun friendlyDropError(error: XHereApiException, categorySlug: String?): String = when (error.code) {
    "rate_limited" -> "You've posted a few recently. Wait a bit and try again."
    "tier_too_low" -> if (categorySlug == "incident_report") {
        "Incident reports need a Regular account. This one stays off until then."
    } else {
        "This category needs a higher account level."
    }
    "content_not_allowed" -> when (error.detail) {
        "person_descriptors" -> "Describe what happened, not who."
        "contact_info" -> "Keep phone numbers and email addresses out of the drop."
        "street_address" -> "Leave out the street address. A street name is fine."
        else -> error.hint ?: "That text isn't allowed on this category."
    }
    "incident_reports_unavailable" ->
        "Incident reports are only available where a local police tip line is set up. In an emergency call 911."
    "guidelines_not_acknowledged" -> "Please acknowledge the notice before posting."
    "invalid_attributes" -> "Fill in the required details for this category."
    "photo_required" -> "This category needs a photo."
    "upload_failed", "invalid_image", "unsupported_type" -> "That photo couldn't be saved. Try another one."
    "not_authenticated" -> "Sign in again, then post."
    else -> error.hint ?: "That drop couldn't be posted."
}

fun fuzzNotice(precisionM: Int): String? =
    if (precisionM > 0) "The pin is moved about $precisionM m so the exact spot stays private." else null

fun duplicateNotice(shortIds: List<String>): String? =
    if (shortIds.isEmpty()) null else "A similar drop is already nearby."
