package world.xhere.shared.live

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The three lifetimes a "right now" drop may use (PLAN §5 expiry).
 * A usual drop leaves [id] null and keeps the category default.
 */
enum class RightNowDuration(val id: String, val duration: Duration) {
    ONE_HOUR("1h", 1.hours),
    FOUR_HOURS("4h", 4.hours),
    ONE_DAY("24h", 24.hours),
    ;

    companion object {
        fun fromId(id: String): RightNowDuration? = values().firstOrNull { it.id == id }

        /** Slack matches `private.enforce_right_now_window`: a slow request still lands. */
        fun matches(span: Duration): Boolean {
            val minutes = span.inWholeMinutes
            return minutes in 50..70 ||
                minutes in (3 * 60 + 40)..(4 * 60 + 20) ||
                minutes in (23 * 60 + 40)..(24 * 60 + 20)
        }
    }
}
