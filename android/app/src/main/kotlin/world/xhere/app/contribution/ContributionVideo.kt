package world.xhere.app.contribution

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import world.xhere.app.drop.DropPhotoCodec
import world.xhere.shared.contribution.ContributionMediaPolicy

/**
 * Caps a clip before upload (PLAN §0).
 * Camera recordings are asked for 30 seconds at low quality. This trims a longer
 * file to 30 seconds, builds a JPEG thumbnail, and refuses anything that is still
 * over 720p or 8 MB so the free storage tier is not filled with a camera original.
 */
internal object ContributionVideo {
    data class Prepared(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val durationS: Double,
        val thumbnail: ByteArray,
    )

    fun prepare(context: Context, uri: Uri): Prepared {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) throw IllegalArgumentException("video_unreadable")
            if (maxOf(width, height) > ContributionMediaPolicy.VIDEO_MAX_EDGE) {
                throw IllegalArgumentException("video_too_big")
            }
            val frame = retriever.getFrameAtTime(0) ?: throw IllegalArgumentException("video_unreadable")
            val thumb = DropPhotoCodec.compress(bitmapJpeg(frame), "image/jpeg").bytes
            val durationS = ContributionMediaPolicy.trimSeconds(durationMs / 1000.0)
            val limitUs = (ContributionMediaPolicy.VIDEO_MAX_SECONDS * 1_000_000).toLong()
            val bytes = if (durationMs > limitUs / 1000) trim(context, uri, limitUs) else readAll(context, uri)
            if (bytes.isEmpty() || bytes.size > ContributionMediaPolicy.VIDEO_MAX_BYTES) {
                throw IllegalArgumentException("video_too_big")
            }
            val mime = context.contentResolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
            val stored = if (mime == "video/webm") "video/webm" else "video/mp4"
            return Prepared(
                bytes = bytes,
                mimeType = stored,
                width = width,
                height = height,
                durationS = durationS,
                thumbnail = thumb,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun bitmapJpeg(frame: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (!frame.isRecycled) frame.recycle()
        return out.toByteArray()
    }

    private fun readAll(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

    /** Copies samples until [maxUs]. A file that cannot be trimmed is left for the size check. */
    private fun trim(context: Context, uri: Uri, maxUs: Long): ByteArray {
        val extractor = MediaExtractor()
        val temp = java.io.File.createTempFile("xhere-clip", ".mp4", context.cacheDir)
        try {
            extractor.setDataSource(context, uri, emptyMap())
            val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val indexes = IntArray(extractor.trackCount)
            for (track in 0 until extractor.trackCount) {
                extractor.selectTrack(track)
                indexes[track] = muxer.addTrack(extractor.getTrackFormat(track))
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > maxUs) break
                info.set(0, size, time, extractor.sampleFlags)
                muxer.writeSampleData(indexes[track], buffer, info)
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            return temp.readBytes()
        } catch (_: Throwable) {
            return readAll(context, uri)
        } finally {
            runCatching { extractor.release() }
            temp.delete()
        }
    }
}
