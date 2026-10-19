package world.xhere.shared.voice

import world.xhere.shared.model.MediaKind
import world.xhere.shared.model.MediaUpload

/**
 * A short voice clip on a drop. 30 seconds, compressed, in the private
 * `voice-clips` bucket. The database enforces the same limits.
 */
object VoiceClips {
    const val BUCKET: String = "voice-clips"
    const val MAX_SECONDS: Double = 30.0
    const val MAX_BYTES: Long = 262_144

    val MIME_TYPES: Set<String> = setOf(
        "audio/mp4",
        "audio/aac",
        "audio/3gpp",
        "audio/amr",
    )

    /** Null when the clip is allowed. Otherwise a stable error code. */
    fun problem(mimeType: String, byteSize: Long, durationS: Double): String? {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (mime !in MIME_TYPES) return "invalid_media"
        if (byteSize <= 0 || byteSize > MAX_BYTES) return "voice_too_large"
        if (durationS <= 0.0 || durationS > MAX_SECONDS) return "voice_too_long"
        return null
    }

    fun problem(upload: MediaUpload): String? {
        if (upload.mediaKind != MediaKind.AUDIO) return null
        if (upload.storageBucket != BUCKET) return "invalid_media"
        val size = upload.byteSize ?: return "voice_too_large"
        val duration = upload.durationS ?: return "voice_too_long"
        return problem(upload.mimeType, size, duration)
    }
}
