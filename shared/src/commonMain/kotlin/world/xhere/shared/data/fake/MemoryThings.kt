package world.xhere.shared.data.fake

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import world.xhere.shared.data.ThingRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.model.CreateThingRequest
import world.xhere.shared.model.Profile
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ResolvedThing
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.model.ThingCard
import world.xhere.shared.model.ThingMedia
import world.xhere.shared.voice.VoiceLinks
import world.xhere.shared.voice.VoiceProtocol

/**
 * Same doors as the SQL RPCs, without a database.
 * The quota maps store a caller and a hit count. They do not store tag ids.
 */
internal class MemoryThings(
    private val viewer: () -> Profile,
    private val taxonomy: () -> Taxonomy,
    private val clock: () -> Instant,
) : ThingRepository {
    private val rows = linkedMapOf<Uuid, Row>()
    private val tags = linkedMapOf<String, Uuid>()
    private val reports = mutableListOf<Flag>()
    private val resolveQuota = mutableMapOf<Uuid, Window>()
    private val hearQuota = mutableMapOf<Uuid, Window>()
    private var sequence = 0

    override suspend fun create(request: CreateThingRequest): ThingCard {
        val owner = viewer()
        val prepared = prepare(request)
        val voiceId = nextVoiceId()
        val row = Row(
            id = Uuid.random(),
            voiceId = voiceId,
            ownerId = owner.id,
            title = prepared.title,
            story = prepared.story,
            categorySlug = prepared.categorySlug,
            dropId = prepared.dropId,
            placeName = prepared.placeName,
            lat = prepared.latitude,
            lon = prepared.longitude,
            media = prepared.media,
            visibility = prepared.visibility,
            createdAt = clock(),
        )
        rows[row.id] = row
        tags[VoiceProtocol.xhereKey(voiceId)] = row.id
        return row.card(mine = true)
    }

    override suspend fun update(thingId: Uuid, request: CreateThingRequest): ThingCard {
        val owner = viewer()
        val row = rows[thingId] ?: throw missing()
        if (row.ownerId != owner.id) throw XHereApiException("not_owner", sqlState = XHereApiException.SQL_PERMISSION)
        val prepared = prepare(request)
        row.title = prepared.title
        row.story = prepared.story
        row.categorySlug = prepared.categorySlug
        row.dropId = prepared.dropId
        row.placeName = prepared.placeName
        row.lat = prepared.latitude
        row.lon = prepared.longitude
        row.media = prepared.media
        row.visibility = prepared.visibility
        return row.card(mine = true)
    }

    override suspend fun mine(): List<ThingCard> {
        val owner = viewer()
        return rows.values.filter { it.ownerId == owner.id }.sortedByDescending { it.createdAt }.map { it.card(true) }
    }

    override suspend fun resolve(keys: List<String>): List<ResolvedThing> {
        val owner = viewer()
        if (keys.isEmpty() || keys.size > VoiceProtocol.MAX_KEYS) {
            throw XHereApiException("invalid_keys", sqlState = XHereApiException.SQL_VALIDATION)
        }
        take(resolveQuota, owner.id, 1, VoiceProtocol.RESOLVE_PER_MINUTE, 60)
        return keys.mapNotNull { VoiceProtocol.normalizeKey(it) }.distinct().mapNotNull { key ->
            val row = publicRow(key) ?: return@mapNotNull null
            ResolvedThing(key, row.card(row.ownerId == owner.id))
        }
    }

    override suspend fun hear(keys: List<String>): Int {
        val owner = viewer()
        if (keys.isEmpty() || keys.size > VoiceProtocol.MAX_KEYS) {
            throw XHereApiException("invalid_keys", sqlState = XHereApiException.SQL_VALIDATION)
        }
        val matched = keys.mapNotNull { VoiceProtocol.normalizeKey(it) }.distinct().mapNotNull { publicRow(it) }.distinctBy { it.id }
        take(hearQuota, owner.id, matched.size.coerceAtLeast(1), VoiceProtocol.HEAR_PER_TEN_MINUTES, 600)
        matched.forEach { it.seenCount += 1 }
        return matched.size
    }

    override suspend fun bindBeacon(thingId: Uuid, key: String) {
        val owner = viewer()
        val row = rows[thingId] ?: throw missing()
        if (row.ownerId != owner.id) throw XHereApiException("not_owner", sqlState = XHereApiException.SQL_PERMISSION)
        val normalized = VoiceProtocol.normalizeKey(key) ?: throw XHereApiException("invalid_key", sqlState = XHereApiException.SQL_VALIDATION)
        val encoded = encodedVoice(normalized)
        if (encoded != null && encoded != row.voiceId) {
            throw XHereApiException("tag_in_use", sqlState = XHereApiException.SQL_VALIDATION)
        }
        val existing = tags[normalized]
        if (existing != null && existing != thingId) {
            throw XHereApiException("tag_in_use", sqlState = XHereApiException.SQL_VALIDATION)
        }
        tags[normalized] = thingId
    }

    override suspend fun bindNfc(thingId: Uuid): String {
        val owner = viewer()
        val row = rows[thingId] ?: throw missing()
        if (row.ownerId != owner.id) throw XHereApiException("not_owner", sqlState = XHereApiException.SQL_PERMISSION)
        return VoiceLinks.https(row.voiceId)
    }

    override suspend fun report(thingId: Uuid, reason: ReportReason, details: String?): Uuid {
        val reporter = viewer()
        val row = rows[thingId] ?: throw missing()
        if (row.status != "active" || (row.visibility != "public" && row.ownerId != reporter.id)) throw missing()
        if (row.ownerId == reporter.id) {
            throw XHereApiException("cannot_report_self", sqlState = XHereApiException.SQL_VALIDATION)
        }
        if ((details?.length ?: 0) > 1000) {
            throw XHereApiException("invalid_report", sqlState = XHereApiException.SQL_VALIDATION)
        }
        reports.firstOrNull { it.thingId == thingId && it.reporterId == reporter.id }?.let { return it.id }
        val flag = Flag(Uuid.random(), thingId, reporter.id)
        reports += flag
        val distinct = reports.filter { it.thingId == thingId }.map { it.reporterId }.distinct().size
        if (distinct >= VoiceProtocol.REPORTS_TO_HIDE) row.status = "removed"
        return flag.id
    }

    private fun publicRow(key: String): Row? {
        val voice = encodedVoice(key)
        val row = if (voice != null) {
            rows.values.firstOrNull { it.voiceId == voice }
        } else {
            tags[key]?.let { rows[it] }
        } ?: return null
        if (row.status != "active" || row.visibility != "public") return null
        return row
    }

    private fun encodedVoice(key: String): String? = when {
        key.startsWith("xhere:") -> key.removePrefix("xhere:")
        key.startsWith("uid:${VoiceProtocol.NAMESPACE_HEX}:") -> key.substringAfterLast(':')
        key.startsWith("url:${VoiceLinks.https("")}") -> key.takeLast(12)
        key.startsWith("url:${VoiceLinks.custom("")}") -> key.takeLast(12)
        else -> null
    }

    private fun prepare(request: CreateThingRequest): CreateThingRequest {
        val title = request.title.trim()
        if (title.length !in 1..80 || '\u0000' in request.title) {
            throw XHereApiException("invalid_title", sqlState = XHereApiException.SQL_VALIDATION)
        }
        if (request.story.length !in 1..2000 || '\u0000' in request.story) {
            throw XHereApiException("invalid_story", sqlState = XHereApiException.SQL_VALIDATION)
        }
        if (request.visibility !in setOf("public", "unlisted")) {
            throw XHereApiException("invalid_visibility", sqlState = XHereApiException.SQL_VALIDATION)
        }
        val slug = request.categorySlug?.trim()?.ifBlank { null }
        if (slug != null) {
            val category = taxonomy().category(slug) ?: throw XHereApiException("invalid_category", sqlState = XHereApiException.SQL_VALIDATION)
            if (category.sensitive) throw XHereApiException("category_not_allowed", sqlState = XHereApiException.SQL_VALIDATION)
        }
        val lat = request.latitude
        val lon = request.longitude
        if ((lat == null) != (lon == null)) {
            throw XHereApiException("invalid_location", sqlState = XHereApiException.SQL_VALIDATION)
        }
        request.media.forEach { media ->
            if (!media.url.startsWith("https://") || media.url.length > 288 || (media.alt?.length ?: 0) > 140) {
                throw XHereApiException("invalid_media", sqlState = XHereApiException.SQL_VALIDATION)
            }
        }
        return request.copy(
            title = title,
            categorySlug = slug,
            placeName = request.placeName?.trim()?.ifBlank { null },
            visibility = request.visibility,
        )
    }

    private fun nextVoiceId(): String {
        sequence += 1
        return sequence.toString(16).padStart(12, '0')
    }

    private fun take(bucket: MutableMap<Uuid, Window>, userId: Uuid, cost: Int, limit: Int, windowSeconds: Long) {
        val now = clock()
        val window = bucket.getOrPut(userId) { Window(now, 0) }
        if (now - window.start > windowSeconds.seconds) {
            window.start = now
            window.hits = 0
        }
        if (window.hits + cost > limit) {
            throw XHereApiException("rate_limited", sqlState = XHereApiException.SQL_RATE_LIMITED)
        }
        window.hits += cost
    }

    private fun missing(): XHereApiException = XHereApiException("thing_not_found", sqlState = XHereApiException.SQL_NOT_FOUND)

    private fun Row.card(mine: Boolean) = ThingCard(
        id = id,
        voiceId = voiceId,
        title = title,
        story = story,
        categorySlug = categorySlug,
        categoryLabel = if (categorySlug == null) null else taxonomy().category(categorySlug!!)?.label,
        placeName = placeName,
        dropId = dropId,
        lat = lat,
        lon = lon,
        media = media,
        seenCount = seenCount,
        isMine = mine,
        url = VoiceLinks.https(voiceId),
        status = status,
        visibility = visibility,
    )

    private class Row(
        val id: Uuid,
        val voiceId: String,
        val ownerId: Uuid,
        var title: String,
        var story: String,
        var categorySlug: String?,
        var dropId: Uuid?,
        var placeName: String?,
        var lat: Double?,
        var lon: Double?,
        var media: List<ThingMedia>,
        var seenCount: Long = 0,
        var visibility: String,
        var status: String = "active",
        val createdAt: Instant,
    )

    private data class Flag(val id: Uuid, val thingId: Uuid, val reporterId: Uuid)
    private data class Window(var start: Instant, var hits: Int)
}
