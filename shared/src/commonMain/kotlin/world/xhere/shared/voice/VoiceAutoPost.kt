package world.xhere.shared.voice

/**
 * Driving mode. Off unless the person turns it on in Settings.
 * The confirm sheet then counts down and posts unless they cancel.
 */
object VoiceAutoPost {
    const val SECONDS: Int = 5
    const val DEFAULT_ENABLED: Boolean = false

    /** Whole seconds still showing. Zero means the countdown finished. */
    fun secondsLeft(elapsedMs: Long, totalSeconds: Int = SECONDS): Int {
        if (elapsedMs < 0) return totalSeconds
        val used = (elapsedMs / 1_000L).toInt()
        return (totalSeconds - used).coerceAtLeast(0)
    }

    fun finished(elapsedMs: Long, totalSeconds: Int = SECONDS): Boolean = secondsLeft(elapsedMs, totalSeconds) == 0
}
