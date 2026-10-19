package world.xhere.shared.capsule

import kotlin.time.Instant

/**
 * Unlock rules for a time capsule. `both` means the date has passed and
 * someone else is standing within [VISIT_M]. The author is not that someone.
 * The database enforces the same split. This text is the public condition,
 * never the story.
 */
object Capsules {
    const val VISIT_M: Double = 30.0

    fun isLocked(mode: String?, openedAt: Instant?): Boolean = mode != null && openedAt == null

    fun condition(mode: String?, unlockAt: Instant?, openedAt: Instant? = null): String {
        if (!isLocked(mode, openedAt)) return "Opened"
        return when (mode) {
            "date" -> "Sealed until ${unlockAt?.toString().orEmpty()}".trim()
            "visit" -> "Sealed until someone stands here"
            "both" -> "Sealed until ${unlockAt?.toString().orEmpty()} and someone stands here".trim()
            else -> "Sealed"
        }
    }
}
