package world.xhere.app.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import world.xhere.shared.voice.VoiceClips

/** A compressed clip ready for the private `voice-clips` bucket. */
data class RecordedClip(
    val bytes: ByteArray,
    val mimeType: String,
    val durationS: Double,
)

/**
 * Records at most 30 seconds. [stop] ends the take early.
 * Null when the microphone cannot start.
 */
interface VoiceClipRecorder {
    suspend fun record(): RecordedClip?

    fun stop()
}

/**
 * AAC in an mp4 container, capped at 30 seconds and 256 KB.
 * The file stays in the cache until the bytes are read, then it is deleted.
 */
class AndroidVoiceClipRecorder(
    context: Context,
) : VoiceClipRecorder {
    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt: Long = 0L
    private var waiting: CancellableContinuation<RecordedClip?>? = null

    override suspend fun record(): RecordedClip? = suspendCancellableCoroutine { continuation ->
        val target = File(appContext.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val media = create()
        try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(24_000)
            media.setAudioSamplingRate(16_000)
            media.setMaxDuration((VoiceClips.MAX_SECONDS * 1_000).toInt())
            media.setMaxFileSize(VoiceClips.MAX_BYTES)
            media.setOutputFile(target.absolutePath)
            media.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    finish()
                }
            }
            media.prepare()
            media.start()
        } catch (_: Exception) {
            runCatching { media.release() }
            target.delete()
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        recorder = media
        file = target
        startedAt = System.currentTimeMillis()
        waiting = continuation
        continuation.invokeOnCancellation { releaseQuietly() }
    }

    override fun stop() {
        finish()
    }

    private fun create(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun finish() {
        val continuation = waiting ?: return
        waiting = null
        if (!continuation.isActive) {
            releaseQuietly()
            return
        }
        val media = recorder
        recorder = null
        runCatching { media?.stop() }
        runCatching { media?.release() }
        val target = file
        file = null
        val bytes = target?.let { runCatching { it.readBytes() }.getOrNull() }
        target?.delete()
        val elapsed = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1L)) / 1_000.0
        continuation.resume(
            bytes?.takeIf { it.isNotEmpty() }?.let {
                RecordedClip(it, "audio/mp4", elapsed.coerceAtMost(VoiceClips.MAX_SECONDS))
            },
        )
    }

    private fun releaseQuietly() {
        waiting = null
        val media = recorder
        recorder = null
        runCatching { media?.stop() }
        runCatching { media?.release() }
        file?.delete()
        file = null
    }
}
