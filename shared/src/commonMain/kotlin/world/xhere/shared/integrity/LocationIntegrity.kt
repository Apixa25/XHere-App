package world.xhere.shared.integrity

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.InstantSerializer
import world.xhere.shared.json.UuidSerializer

/**
 * Free location-integrity signals. The database copy in
 * `private.location_decision` (`supabase/migrations/20261020031500_location_integrity.sql`)
 * is the source of truth. These constants match that function so the in-memory
 * app and the tests reach the same decision.
 *
 * A failed check does not suspend anyone. The action is stored, `countsForCredit`
 * is false, and a drop's trust becomes `reduced`. Badges, quests, and campaigns
 * must ignore rows that do not count. See the migration header.
 */
object LocationIntegrity {
    const val MAX_SPEED_MPS: Double = 150.0
    const val MIN_SPEED_DISTANCE_M: Double = 2_000.0
    const val PROXIMITY_ACCURACY_M: Double = 250.0
    const val DROP_ACCURACY_M: Double = 1_000.0
    const val TAKEN_HERE_FLOOR_M: Double = 150.0
    const val TAKEN_HERE_CAP_M: Double = 500.0

    val minSpeedElapsed: Duration = 20.seconds
    val checkpointTtl: Duration = 2.hours
    val flagTtl: Duration = 7.days
    val flagDedupe: Duration = 1.hours
    val staleFix: Duration = 10.minutes
    val futureSkew: Duration = 5.minutes
    val clockSkew: Duration = 5.minutes

    const val TRUST_STANDARD: String = "standard"
    const val TRUST_REDUCED: String = "reduced"

    fun decide(
        action: LocationAction,
        here: LatLng,
        signal: LocationSignal?,
        previous: LocationCheckpoint?,
        now: Instant,
    ): LocationDecision {
        val reasons = mutableListOf<String>()
        val deviceFix = signal?.deviceFix != false
        if (!deviceFix) reasons += LocationReasons.UNVERIFIED
        if (signal?.mock == true) reasons += LocationReasons.MOCK
        if (deviceFix) {
            val accuracy = signal?.accuracyM
            val limit = if (action == LocationAction.CREATE_DROP) DROP_ACCURACY_M else PROXIMITY_ACCURACY_M
            if (accuracy != null && accuracy > limit) reasons += LocationReasons.ACCURACY
            val fixAt = signal?.fixAt
            if (fixAt != null) {
                val delta = fixAt - now
                if (delta > futureSkew) reasons += LocationReasons.CLOCK
                else if (now - fixAt > staleFix) reasons += LocationReasons.STALE
            }
            val deviceAt = signal?.deviceAt
            if (deviceAt != null && !reasons.contains(LocationReasons.CLOCK)) {
                val delta = if (deviceAt >= now) deviceAt - now else now - deviceAt
                if (delta > clockSkew) reasons += LocationReasons.CLOCK
            }
            val prior = previous?.takeIf { now - it.observedAt <= checkpointTtl && now >= it.observedAt }
            if (prior != null) {
                val elapsed = now - prior.observedAt
                val distance = Geo.distanceMeters(LatLng(prior.latitude, prior.longitude), here)
                if (elapsed >= minSpeedElapsed && distance >= MIN_SPEED_DISTANCE_M) {
                    val seconds = elapsed.inWholeMilliseconds.coerceAtLeast(1) / 1000.0
                    if (distance / seconds > MAX_SPEED_MPS) reasons += LocationReasons.TRAVEL
                }
            }
        }
        if (signal?.developerOptions == true && reasons.isNotEmpty()) reasons += LocationReasons.DEVELOPER
        val credit = reasons.isEmpty()
        return LocationDecision(
            countsForCredit = credit,
            locationTrust = if (credit) TRUST_STANDARD else TRUST_REDUCED,
            reasons = reasons,
            moveCheckpoint = deviceFix,
        )
    }

    /** Capture GPS agrees with the drop, and the drop itself counted. The coordinates are not stored. */
    fun photoTakenHere(
        drop: LatLng,
        capture: LatLng?,
        accuracyM: Double?,
        countsForCredit: Boolean,
    ): Boolean {
        if (!countsForCredit || capture == null || !Geo.isValid(capture)) return false
        val allowance = maxOf(TAKEN_HERE_FLOOR_M, accuracyM ?: TAKEN_HERE_FLOOR_M).coerceAtMost(TAKEN_HERE_CAP_M)
        return Geo.distanceMeters(drop, capture) <= allowance
    }
}

object LocationReasons {
    const val MOCK: String = "mock_location"
    const val TRAVEL: String = "impossible_travel"
    const val ACCURACY: String = "accuracy_too_large"
    const val STALE: String = "stale_fix"
    const val CLOCK: String = "clock_skew"
    const val UNVERIFIED: String = "unverified_fix"
    const val DEVELOPER: String = "developer_options"
}

enum class LocationAction(val wire: String) {
    CREATE_DROP("create_drop"),
    CONFIRM_DROP("confirm_drop"),
    REVEAL("reveal"),
    WALK_CHECK_IN("walk_check_in"),
    CLAIM_PLACE("claim_place"),
    OPEN_CAPSULE("open_capsule"),
}

/**
 * What the phone sends with a location-bound action. Coordinates of the action
 * stay on that action's existing arguments. This object has no latitude.
 *
 * [deviceFix] false means the point was chosen on the map, not reported by the
 * location provider. Omitting the object is the older client: no device signals,
 * and the server still checks travel speed.
 */
@Serializable
data class LocationSignal(
    val mock: Boolean = false,
    @SerialName("developer_options") val developerOptions: Boolean = false,
    @SerialName("device_fix") val deviceFix: Boolean = true,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("fix_at") val fixAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    @SerialName("device_at") val deviceAt: Instant? = null,
)

data class LocationCheckpoint(
    val latitude: Double,
    val longitude: Double,
    val observedAt: Instant,
)

data class LocationDecision(
    val countsForCredit: Boolean,
    val locationTrust: String,
    val reasons: List<String>,
    /** False when the point was not a device fix, so it must not become the travel baseline. */
    val moveCheckpoint: Boolean,
)

/**
 * One soft flag for a moderator. Reason codes only: no coordinates.
 * Badges and campaigns should read [counts for credit](LocationDecision.countsForCredit)
 * on the drop, confirmation, or visit instead of parsing these codes.
 */
@Serializable
data class LocationIntegrityFlag(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @Serializable(with = UuidSerializer::class)
    @SerialName("user_id") val userId: Uuid,
    val handle: String? = null,
    val action: String,
    @Serializable(with = UuidSerializer::class)
    @SerialName("target_id") val targetId: Uuid? = null,
    val reasons: List<String> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
)
