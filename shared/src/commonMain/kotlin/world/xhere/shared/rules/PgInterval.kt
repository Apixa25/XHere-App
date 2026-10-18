package world.xhere.shared.rules

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Parses the Postgres interval text that PostgREST returns for `categories.default_ttl`
 * and its siblings (`7 days`, `6 hours`, `3 years`, `06:00:00`).
 *
 * A calendar year is 365 days and a month is 30 days. That is only for the preview of
 * "how long can this live". `create_drop` applies the real interval.
 */
object PgInterval {
    fun parse(text: String): Duration {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("empty interval")

        if (trimmed.startsWith("P")) {
            return parseIso(trimmed)
        }

        HMS.matchEntire(trimmed)?.let { match ->
            val hoursPart = match.groupValues[1].toLong()
            val minutesPart = match.groupValues[2].toLong()
            val secondsPart = match.groupValues[3].toDouble()
            return hoursPart.hours + minutesPart.minutes + secondsPart.seconds
        }

        val tokens = UNIT.findAll(trimmed).toList()
        if (tokens.isEmpty()) throw IllegalArgumentException("unrecognized interval: $text")
        var total = Duration.ZERO
        for (token in tokens) {
            val amount = token.groupValues[1].toDouble()
            val unit = token.groupValues[2].lowercase()
            total += when {
                unit.startsWith("year") -> scaledDays(amount, 365)
                unit.startsWith("mon") -> scaledDays(amount, 30)
                unit.startsWith("day") -> scaledDays(amount, 1)
                unit.startsWith("hour") -> amount.hours
                unit.startsWith("min") -> amount.minutes
                unit.startsWith("sec") -> amount.seconds
                else -> throw IllegalArgumentException("unrecognized interval unit: $unit")
            }
        }
        return total
    }

    fun format(duration: Duration): String {
        val wholeSeconds = duration.inWholeSeconds
        if (wholeSeconds == 0L) return "0 seconds"
        if (wholeSeconds % 86_400L == 0L) {
            val days = wholeSeconds / 86_400L
            return "$days ${if (days == 1L) "day" else "days"}"
        }
        if (wholeSeconds % 3_600L == 0L) {
            val hours = wholeSeconds / 3_600L
            return "$hours ${if (hours == 1L) "hour" else "hours"}"
        }
        if (wholeSeconds % 60L == 0L) {
            val minutes = wholeSeconds / 60L
            return "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        }
        return "$wholeSeconds ${if (wholeSeconds == 1L) "second" else "seconds"}"
    }

    private fun parseIso(text: String): Duration {
        val match = ISO.matchEntire(text) ?: throw IllegalArgumentException("unrecognized interval: $text")
        val years = match.groupValues[1].toAmount()
        val months = match.groupValues[2].toAmount()
        val days = match.groupValues[3].toAmount()
        val hours = match.groupValues[4].toAmount()
        val minutes = match.groupValues[5].toAmount()
        val seconds = match.groupValues[6].toAmount()
        return scaledDays(years, 365) + scaledDays(months, 30) + scaledDays(days, 1) +
            hours.hours + minutes.minutes + seconds.seconds
    }

    /** Whole amounts stay on the long path so `3 years` is exactly 1095 days. */
    private fun scaledDays(amount: Double, dayCount: Int): Duration {
        if (amount % 1.0 == 0.0 && amount.isFinite()) return (amount.toLong() * dayCount).days
        return (amount * dayCount).days
    }

    private fun String.toAmount(): Double = if (isEmpty()) 0.0 else toDouble()

    private val UNIT = Regex("""(-?\d+(?:\.\d+)?)\s+(years?|mons?|months?|days?|hours?|mins?|minutes?|secs?|seconds?)""")
    private val HMS = Regex("""^(-?\d+):(\d{2}):(\d{2}(?:\.\d+)?)$""")
    private val ISO = Regex("""^P(?:(\d+(?:\.\d+)?)Y)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""")
}
