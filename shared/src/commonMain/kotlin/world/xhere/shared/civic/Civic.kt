package world.xhere.shared.civic

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import kotlin.uuid.Uuid

/**
 * Town Hall is public record, not a person's drop.
 * PLAN §3 open-data layers and docs/OPEN-DATA-PLAYBOOK.md: a region lists
 * sources, and the same rules apply in every country. Jamestown is only the pilot.
 *
 * Summaries are shortened public text. Nothing here calls a language model.
 */
enum class CivicType {
    AGENDA_ITEM,
    HEARING,
    PERMIT,
    NOTICE,
    ;

    val slug: String
        get() = when (this) {
            AGENDA_ITEM -> "civic_agenda"
            HEARING -> "civic_hearing"
            PERMIT -> "civic_permit"
            NOTICE -> "civic_notice"
        }

    companion object {
        fun fromWire(value: String): CivicType? = when (value) {
            "agenda_item", "civic_agenda" -> AGENDA_ITEM
            "hearing", "civic_hearing" -> HEARING
            "permit", "civic_permit" -> PERMIT
            "notice", "civic_notice" -> NOTICE
            else -> null
        }
    }
}

data class CivicItem(
    val id: Uuid,
    val regionId: String,
    val itemType: CivicType,
    val title: String,
    val summary: String?,
    /** ISO date, `YYYY-MM-DD`, or null when the source has no day. */
    val occursOn: String?,
    val bodyName: String?,
    val sourceUrl: String,
    val addressText: String?,
    val latitude: Double?,
    val longitude: Double?,
    val attribution: String,
    val license: String,
    val expiresAt: Instant?,
    val sourceSlug: String,
    val distanceM: Int? = null,
) {
    val location: LatLng?
        get() {
            val lat = latitude
            val lng = longitude
            if (lat == null || lng == null) return null
            return LatLng(lat, lng)
        }
}

data class CivicNotice(
    val id: Uuid,
    val title: String,
    val addressText: String?,
    val sourceUrl: String,
    val readAt: Instant?,
    val createdAt: Instant,
) {
    val unread: Boolean get() = readAt == null
}

object Civic {
    const val NEAR_M = 300
    const val REGION_M = 25_000
    const val GROUP = "town_hall"

    /**
     * These four slugs are importer-only. `civic_issue` is the existing Open311
     * category and stays postable.
     */
    val RESERVED_SLUGS = setOf("civic_agenda", "civic_hearing", "civic_permit", "civic_notice")

    fun icon(type: CivicType): String = when (type) {
        CivicType.AGENDA_ITEM -> "📋"
        CivicType.HEARING -> "📣"
        CivicType.PERMIT -> "🏗️"
        CivicType.NOTICE -> "📌"
    }

    fun colorHex(type: CivicType): String = when (type) {
        CivicType.AGENDA_ITEM -> "#1565C0"
        CivicType.HEARING -> "#6A1B9A"
        CivicType.PERMIT -> "#E65100"
        CivicType.NOTICE -> "#2E7D32"
    }

    /** Collapse whitespace and drop markup. This is not a summary model. */
    fun clean(value: String?, limit: Int): String? {
        if (value.isNullOrBlank()) return null
        var text = value.replace(Regex("<[^>]+>"), " ")
        text = text.replace("&#8211;", "-").replace("&#038;", "&").replace("&amp;", "&").replace("&nbsp;", " ")
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        if (text.length <= limit) return text
        return text.take(limit - 1).trimEnd() + "…"
    }

    /**
     * One comparable form of a street address.
     * "200 East Third Street, Jamestown, NY 14701" and
     * "200 East 3rd Street, Jamestown, New York" share a key.
     */
    fun addressKey(value: String?): String {
        if (value.isNullOrBlank()) return ""
        var text = value.lowercase()
        text = text.replace("&", " and ")
        text = text.replace(Regex("[^a-z0-9]+"), " ")
        text = " $text "
        val swaps = listOf(
            " street " to " st ",
            " avenue " to " ave ",
            " road " to " rd ",
            " boulevard " to " blvd ",
            " drive " to " dr ",
            " lane " to " ln ",
            " court " to " ct ",
            " place " to " pl ",
            " parkway " to " pkwy ",
            " highway " to " hwy ",
            " terrace " to " ter ",
            " circle " to " cir ",
            " trail " to " trl ",
            " first " to " 1st ",
            " second " to " 2nd ",
            " third " to " 3rd ",
            " new york " to " ",
            " ny " to " ",
            " usa " to " ",
        )
        swaps.forEach { (from, to) -> text = text.replace(from, to) }
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { token ->
            when (token) {
                "e" -> "east"
                "w" -> "west"
                "n" -> "north"
                "s" -> "south"
                else -> token
            }
        }.toMutableList()
        if (tokens.lastOrNull()?.matches(Regex("\\d{5}")) == true) tokens.removeAt(tokens.lastIndex)
        return tokens.joinToString(" ")
    }

    /** True when the followed key is the item address, or a whole phrase inside it. */
    fun addressMatches(followAddress: String?, itemAddress: String?): Boolean {
        val follow = addressKey(followAddress)
        val item = addressKey(itemAddress)
        if (follow.isEmpty() || item.isEmpty()) return false
        if (follow.split(" ").size < 2) return false
        return " $item ".contains(" $follow ")
    }

    fun visible(item: CivicItem, now: Instant): Boolean {
        val expires = item.expiresAt
        return expires == null || expires > now
    }

    /** Hearings, permits, notices, and meetings within [radiusM] of [spot]. */
    fun near(items: List<CivicItem>, spot: LatLng, now: Instant, radiusM: Int = NEAR_M): List<CivicItem> {
        if (!Geo.isValid(spot)) return emptyList()
        val today = now.toLocalDateTime(TimeZone.UTC).date
        return items.mapNotNull { item ->
            if (!visible(item, now)) return@mapNotNull null
            val point = item.location ?: return@mapNotNull null
            if (!Geo.isValid(point)) return@mapNotNull null
            if (!stillRelevant(item, today)) return@mapNotNull null
            val meters = Geo.distanceMeters(spot, point)
            if (meters > radiusM) return@mapNotNull null
            item.copy(distanceM = meters.toInt())
        }.sortedWith(compareBy({ it.distanceM ?: Int.MAX_VALUE }, { it.occursOn ?: "9999" }, { it.title }))
    }

    /**
     * Upcoming meetings and notices for the region that covers [spot].
     * A citywide row with no pin is included when some other row in that
     * region is within [radiusM] (the town, not the whole world).
     */
    fun upcoming(
        items: List<CivicItem>,
        spot: LatLng,
        now: Instant,
        radiusM: Int = REGION_M,
    ): List<CivicItem> {
        if (!Geo.isValid(spot)) return emptyList()
        val today = now.toLocalDateTime(TimeZone.UTC).date.toString()
        val regions = items.mapNotNull { item ->
            val point = item.location ?: return@mapNotNull null
            if (Geo.distanceMeters(spot, point) <= radiusM) item.regionId else null
        }.toSet()
        if (regions.isEmpty()) return emptyList()
        return items.filter { item ->
            item.regionId in regions && visible(item, now) && listedInTownHall(item, today)
        }.sortedWith(compareBy({ it.occursOn ?: "9999" }, { it.title }))
    }

    fun inBox(items: List<CivicItem>, box: BoundingBox, now: Instant): List<CivicItem> =
        items.filter { item ->
            val point = item.location ?: return@filter false
            visible(item, now) &&
                point.longitude in box.minLongitude..box.maxLongitude &&
                point.latitude in box.minLatitude..box.maxLatitude
        }

    /** Meetings from today forward, plus notices that have not expired. Permits stay on the map. */
    private fun listedInTownHall(item: CivicItem, today: String): Boolean = when (item.itemType) {
        CivicType.PERMIT -> false
        CivicType.NOTICE -> true
        CivicType.AGENDA_ITEM, CivicType.HEARING -> item.occursOn == null || item.occursOn >= today
    }

    private fun stillRelevant(item: CivicItem, today: LocalDate): Boolean {
        if (item.itemType == CivicType.PERMIT || item.itemType == CivicType.NOTICE) return true
        val day = item.occursOn ?: return true
        return day >= today.toString()
    }
}
