package world.xhere.shared.safety

import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.Category
import world.xhere.shared.model.ContentFilter
import world.xhere.shared.model.UserTier
import world.xhere.shared.rules.CategoryRules
import world.xhere.shared.validation.PosixPattern

/** A number people can call. [url] is the agency page, when there is one. */
data class SafetyContact(
    val name: String,
    val phone: String,
    val url: String? = null,
) {
    val dialUri: String get() = "tel:" + phone.filter { it.isDigit() || it == '+' }
}

/**
 * Who to call from a Safety screen (CRIME-OPEN-DATA §1, OPEN-DATA-PLAYBOOK §3 `tip_lines`).
 * A null [tipLine] means no law-enforcement tip line is configured where the person is.
 */
data class SafetyResources(
    val regionName: String?,
    val emergency: SafetyContact,
    val tipLine: SafetyContact? = null,
    val nonEmergency: SafetyContact? = null,
) {
    /** The footer every Safety screen shows. */
    val footer: String
        get() = tipLine?.let { "Have information about a crime? ${it.name} ${it.phone} · Emergency ${emergency.phone}." }
            ?: "In an emergency call ${emergency.phone}."
}

/**
 * Tip lines by region. The pilot region is hard-coded here until M16b moves
 * `tip_lines` into the per-region config on the server.
 */
object SafetyDirectory {
    val EMERGENCY_US: SafetyContact = SafetyContact("Emergency", "911")

    val chautauqua: SafetyResources = SafetyResources(
        regionName = "Chautauqua County, NY",
        emergency = EMERGENCY_US,
        tipLine = SafetyContact(
            name = "Jamestown Police anonymous tip line",
            phone = "(716) 483-8477",
            url = "https://www.jamestownny.gov/police-department/anonymous-tip-line/",
        ),
        nonEmergency = SafetyContact("Jamestown Police non-emergency", "(716) 483-7536"),
    )

    /** `region.bbox` for `us-ny-chautauqua` in OPEN-DATA-PLAYBOOK §3. */
    private val CHAUTAUQUA_BOX = BoundingBox(
        minLongitude = -79.77,
        minLatitude = 41.99,
        maxLongitude = -79.05,
        maxLatitude = 42.58,
    )

    private val elsewhere = SafetyResources(regionName = null, emergency = EMERGENCY_US)

    fun forLocation(location: LatLng?): SafetyResources {
        if (location == null) return elsewhere
        val inside = location.latitude in CHAUTAUQUA_BOX.minLatitude..CHAUTAUQUA_BOX.maxLatitude &&
            location.longitude in CHAUTAUQUA_BOX.minLongitude..CHAUTAUQUA_BOX.maxLongitude
        return if (inside) chautauqua else elsewhere
    }
}

/**
 * The extra steps for Safety categories (PLAN §4.3, §7 M7): reminder, people and
 * address filters, disclaimer, location blur, pre-moderation, and the tip line.
 * `create_drop` enforces the same rules; this is for instant feedback in the composer.
 */
object SafetyFlow {
    /** The crime category. Apple 1.7: only offered where a police tip line is configured. */
    const val CRIME_CATEGORY: String = "incident_report"

    const val DISCLAIMER: String =
        "Safety posts are unverified community reports, not official records. " +
            "XHere is not a way to contact the police."

    fun appliesTo(category: Category): Boolean = category.sensitive

    fun availableAt(category: Category, resources: SafetyResources): Boolean =
        category.slug != CRIME_CATEGORY || resources.tipLine != null

    fun willBeHeld(category: Category, tier: UserTier): Boolean = CategoryRules.needsPremoderation(category, tier)

    /** The first of the category's filters that matches [texts], the same order the server checks. */
    fun filterHit(category: Category, filters: List<ContentFilter>, texts: List<String?>): ContentFilter? {
        val values = texts.mapNotNull { it?.trim()?.ifEmpty { null } }
        if (values.isEmpty()) return null
        return filters
            .filter { it.slug in category.contentFilters }
            .sortedBy { it.slug }
            .firstOrNull { filter ->
                val regex = PosixPattern.compile(filter.pattern) ?: return@firstOrNull false
                values.any { regex.containsMatchIn(it) }
            }
    }
}
