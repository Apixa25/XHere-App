package world.xhere.app.contribution

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import world.xhere.app.drop.DropPhotoCodec
import world.xhere.app.media.Mp4FastStart
import world.xhere.shared.contribution.ContributionMediaPolicy

/**
 * Turns a camera or gallery clip into a small MP4 before upload (PLAN §1.4, PLAN §8).
 * Media3 Transformer runs on the phone: H.264 and AAC, 720p, about 2.25 Mbps,
 * 128 kbps audio, 30 fps, and at most 60 seconds. The moov atom is moved to the
 * front. A poster frame becomes the list thumbnail.
 * [transcode] reports progress from 0 to 1. Cancelling the coroutine stops the export.
 */
@OptIn(UnstableApi::class)
internal object ContributionVideo {
    class TranscodeException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    data class Prepared(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val durationS: Double,
        val thumbnail: ByteArray,
    )

    suspend fun transcode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
    ): Prepared = withContext(Dispatchers.Main) {
        val source = readSource(context, uri)
        val output = File.createTempFile("xhere-clip", ".mp4", context.cacheDir)
        val progress = ProgressHolder()
        var transformer: Transformer? = null
        var watch: Job? = null
        try {
            val edited = editedItem(uri, source)
            val encoded = suspendCancellableCoroutine { cont ->
                val engine = transformer(context, source.hasAudio)
                transformer = engine
                watch = CoroutineScope(Dispatchers.Main).launch {
                    while (isActive && cont.isActive) {
                        val state = engine.getProgress(progress)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progress.progress.coerceIn(0, 100) / 100f)
                        }
                        delay(200)
                    }
                }
                engine.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        watch?.cancel()
                        if (cont.isActive) cont.resume(exportResult)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        watch?.cancel()
                        if (cont.isActive) {
                            cont.resumeWithException(TranscodeException("video_failed", exportException))
                        }
                    }
                })
                cont.invokeOnCancellation { engine.cancel() }
                engine.start(edited, output.absolutePath)
            }
            onProgress(1f)
            val bytes = withContext(Dispatchers.Default) {
                Mp4FastStart.relocate(output.readBytes())
            }
            if (bytes.isEmpty() || bytes.size > ContributionMediaPolicy.VIDEO_MAX_BYTES) {
                throw TranscodeException("video_too_big")
            }
            val (width, height) = frameSize(encoded, source)
            val durationS = ContributionMediaPolicy.trimSeconds(
                when {
                    encoded.durationMs > 0 -> encoded.durationMs / 1000.0
                    else -> source.durationS
                },
            )
            val poster = poster(context, Uri.fromFile(output)) ?: poster(context, uri)
                ?: throw TranscodeException("video_unreadable")
            val thumb = try {
                DropPhotoCodec.thumbnail(poster)
            } finally {
                if (!poster.isRecycled) poster.recycle()
            }
            Prepared(
                bytes = bytes,
                mimeType = "video/mp4",
                width = width,
                height = height,
                durationS = durationS,
                thumbnail = thumb,
            )
        } finally {
            withContext(NonCancellable) {
                watch?.cancel()
                transformer?.cancel()
                output.delete()
            }
        }
    }

    private data class Source(val width: Int, val height: Int, val durationS: Double, val hasAudio: Boolean)

    private fun readSource(context: Context, uri: Uri): Source {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) throw TranscodeException("video_unreadable")
            return Source(width, height, durationMs / 1000.0, hasAudio(context, uri))
        } catch (e: TranscodeException) {
            throw e
        } catch (e: Throwable) {
            throw TranscodeException("video_unreadable", e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun hasAudio(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, emptyMap())
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (_: Throwable) {
            true
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun editedItem(uri: Uri, source: Source): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setEndPositionMs((ContributionMediaPolicy.VIDEO_MAX_SECONDS * 1000).toLong())
            .build()
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()
        val video = ArrayList<Effect>()
        if (maxOf(source.width, source.height) > ContributionMediaPolicy.VIDEO_MAX_EDGE) {
            val (width, height) = ContributionMediaPolicy.videoFrame(source.width, source.height)
            video += Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        }
        video += FrameDropEffect.createDefaultFrameDropEffect(ContributionMediaPolicy.VIDEO_MAX_FPS.toFloat())
        return EditedMediaItem.Builder(item)
            .setEffects(Effects(emptyList(), video))
            .setFrameRate(ContributionMediaPolicy.VIDEO_MAX_FPS)
            .build()
    }

    private fun transformer(context: Context, hasAudio: Boolean): Transformer {
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(ContributionMediaPolicy.VIDEO_BITRATE)
                    .build(),
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(ContributionMediaPolicy.AUDIO_BITRATE)
                    .build(),
            )
            .build()
        val builder = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoders)
        if (hasAudio) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
        return builder.build()
    }

    private fun frameSize(encoded: ExportResult, source: Source): Pair<Int, Int> {
        val width = if (encoded.width > 0) encoded.width else source.width
        val height = if (encoded.height > 0) encoded.height else source.height
        return ContributionMediaPolicy.videoFrame(width, height)
    }

    private fun poster(context: Context, uri: Uri): android.graphics.Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
