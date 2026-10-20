package world.xhere.shared.contribution

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.InstantSerializer
import world.xhere.shared.json.UuidSerializer

/**
 * Photos, short videos, and notes people add to a place (PLAN §0, PLAN §3).
 * The author id never appears on these cards. An anonymous post is labeled Anonymous.
 */
object ContributionTarget {
    const val DROP = "drop"
    const val MARKER = "marker"
    const val OPEN_DATA = "open_data"
    const val THING = "thing"
    const val CIVIC = "civic"

    val kinds: Set<String> = setOf(DROP, MARKER, OPEN_DATA, THING, CIVIC)
}

enum class ContributionSort {
    NEWEST,
    OLDEST,
    TOP,
    ;

    val wire: String
        get() = when (this) {
            NEWEST -> "newest"
            OLDEST -> "oldest"
            TOP -> "top"
        }

    companion object {
        fun fromWire(value: String?): ContributionSort = when (value) {
            "oldest" -> OLDEST
            "top" -> TOP
            else -> NEWEST
        }
    }
}

/**
 * Wilson lower bound, the live window, and when a low score folds away.
 * The database uses the same numbers.
 */
object ContributionRules {
    const val LIVE_HOURS = 24
    const val PAGE_SIZE = 20
    const val MAX_FILES = 6
    const val HIDE_DOWNVOTES = 5
    const val ANONYMOUS_NAME = "Anonymous"
    const val COLLAPSE_BELOW = 0.2
    const val COLLAPSE_MIN_VOTES = 3
    private const val Z = 1.96

    fun wilson(up: Int, down: Int): Double {
        val safeUp = up.coerceAtLeast(0)
        val safeDown = down.coerceAtLeast(0)
        val n = safeUp + safeDown
        if (n <= 0) return 0.0
        val z2 = Z * Z
        val p = safeUp.toDouble() / n.toDouble()
        val centre = p + z2 / (2.0 * n)
        val margin = Z * sqrt((p * (1.0 - p) + z2 / (4.0 * n)) / n.toDouble())
        val score = (centre - margin) / (1.0 + z2 / n)
        return score.coerceAtLeast(0.0)
    }

    fun collapsed(up: Int, down: Int): Boolean {
        val safeUp = up.coerceAtLeast(0)
        val safeDown = down.coerceAtLeast(0)
        return safeUp + safeDown >= COLLAPSE_MIN_VOTES &&
            safeDown > safeUp &&
            wilson(safeUp, safeDown) < COLLAPSE_BELOW
    }

    /** Five downvotes, or a net of -5, sends the post to the moderation queue. */
    fun autoHidden(up: Int, down: Int): Boolean {
        val safeUp = up.coerceAtLeast(0)
        val safeDown = down.coerceAtLeast(0)
        return safeDown >= HIDE_DOWNVOTES || safeUp - safeDown <= -HIDE_DOWNVOTES
    }

    /** Anything from the last 24 hours, including a line of text. */
    fun live(createdAt: Instant, now: Instant): Boolean =
        createdAt > now - LIVE_HOURS.hours

    /** The map halo is only for a photo or video in that window. */
    fun pulses(kind: String, createdAt: Instant, now: Instant): Boolean =
        (kind == "photo" || kind == "video" || kind == "media") && live(createdAt, now)

    fun <T> ordered(
        items: List<T>,
        sort: ContributionSort,
        createdAt: (T) -> Instant,
        score: (T) -> Double,
    ): List<T> = when (sort) {
        ContributionSort.NEWEST -> items.sortedByDescending(createdAt)
        ContributionSort.OLDEST -> items.sortedBy(createdAt)
        ContributionSort.TOP -> items.sortedWith(compareByDescending<T>(score).thenByDescending(createdAt))
    }

    /**
     * Sales and any other category with allow_anonymous_display false stay named.
     * The toggle is a request. The category rule wins.
     */
    fun anonymousAccepted(requested: Boolean, allowAnonymousDisplay: Boolean): Boolean =
        requested && allowAnonymousDisplay
}

/** Client-side caps before a file is uploaded. Photos are resized by the phone codec. */
object ContributionMediaPolicy {
    const val PHOTO_MAX_EDGE = 1600
    const val PHOTO_MAX_BYTES = 500 * 1024
    const val VIDEO_MAX_SECONDS = 30.0
    const val VIDEO_MAX_EDGE = 1280
    const val VIDEO_MAX_BYTES = 8 * 1024 * 1024

    fun videoFrame(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 2 to 2
        val longEdge = max(width, height)
        val scaled = if (longEdge <= VIDEO_MAX_EDGE) {
            width to height
        } else {
            val scale = VIDEO_MAX_EDGE.toDouble() / longEdge.toDouble()
            (width * scale).toInt() to (height * scale).toInt()
        }
        return even(scaled.first) to even(scaled.second)
    }

    fun trimSeconds(durationS: Double): Double = durationS.coerceIn(0.0, VIDEO_MAX_SECONDS)

    private fun even(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2
}

@Serializable
data class ContributionMediaRef(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid? = null,
    @SerialName("storage_bucket") val storageBucket: String = "contribution-media",
    @SerialName("storage_path") val storagePath: String,
    @SerialName("thumb_path") val thumbPath: String? = null,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_s") val durationS: Double? = null,
    @SerialName("alt_text") val altText: String? = null,
)

@Serializable
data class ContributionCard(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("target_kind") val targetKind: String,
    @Serializable(with = UuidSerializer::class)
    @SerialName("target_id") val targetId: Uuid,
    val kind: String,
    val body: String? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
    val live: Boolean = false,
    @SerialName("up_count") val upCount: Int = 0,
    @SerialName("down_count") val downCount: Int = 0,
    val score: Double = 0.0,
    @SerialName("my_vote") val myVote: Int? = null,
    @SerialName("author_handle") val authorHandle: String? = null,
    val anonymous: Boolean = false,
    @SerialName("is_mine") val isMine: Boolean = false,
    val collapsed: Boolean = false,
    val media: List<ContributionMediaRef> = emptyList(),
    @Serializable(with = UuidSerializer::class)
    @SerialName("parent_id") val parentId: Uuid? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("edited_at") val editedAt: Instant? = null,
)

@Serializable
data class ContributionPlace(
    @SerialName("anonymous_allowed") val anonymousAllowed: Boolean = true,
    val visible: Boolean = true,
    @SerialName("share_url") val shareUrl: String? = null,
)

@Serializable
data class ContributionUploadSlot(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("storage_bucket") val storageBucket: String = "contribution-media",
    @SerialName("storage_path") val storagePath: String,
    @SerialName("thumb_path") val thumbPath: String? = null,
    @SerialName("mime_type") val mimeType: String,
)

@Serializable
data class CreatedContribution(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    val status: String,
    val anonymous: Boolean = false,
    val media: List<ContributionUploadSlot> = emptyList(),
)

@Serializable
data class LivePlace(
    @SerialName("target_kind") val targetKind: String,
    @Serializable(with = UuidSerializer::class)
    @SerialName("target_id") val targetId: Uuid,
    val lat: Double,
    val lng: Double,
) {
    val location: LatLng get() = LatLng(lat, lng)
}

data class ContributionFile(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationS: Double? = null,
    val altText: String? = null,
    val thumbnail: ByteArray? = null,
)

data class NewContribution(
    val targetKind: String,
    val targetId: Uuid,
    val kind: String,
    val body: String?,
    val anonymous: Boolean,
    val file: ContributionFile? = null,
    val thumbnail: ByteArray? = null,
    val files: List<ContributionFile> = emptyList(),
    val parentId: Uuid? = null,
)
