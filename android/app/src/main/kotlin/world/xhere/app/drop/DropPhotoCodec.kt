package world.xhere.app.drop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Drop photos are resized before they leave the phone.
 * The long edge stays at about 1600 px, and the file is JPEG or WebP
 * around 80% until it fits under 500 KB. A camera original of a few
 * megabytes is too large for the free storage tier.
 * Bytes that are not a picture are returned unchanged, so a caller that
 * already holds a small file can still upload it.
 */
internal object DropPhotoCodec {
    const val MAX_EDGE = 1600
    const val TARGET_BYTES = 500 * 1024
    private const val START_QUALITY = 80

    data class Encoded(val bytes: ByteArray, val mimeType: String)

    fun compress(bytes: ByteArray, mimeType: String): Encoded {
        val mime = mimeType.substringBefore(';').trim().lowercase().ifBlank { "image/jpeg" }
        val decoded = decode(bytes) ?: return Encoded(bytes, mime)
        return try {
            encode(decoded)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private fun encode(source: Bitmap): Encoded {
        var working = fit(source, MAX_EDGE)
        var owned: Bitmap? = if (working !== source) working else null
        var best: Encoded? = null
        while (true) {
            for (quality in intArrayOf(START_QUALITY, 65, 50, 35)) {
                val webp = pack(working, webp = true, quality = quality)
                val jpeg = pack(working, webp = false, quality = quality)
                val chosen = smaller(webp, jpeg)
                if (best == null || chosen.bytes.size < best.bytes.size) best = chosen
                if (chosen.bytes.size in 1..TARGET_BYTES) {
                    recycleOwned(owned, source)
                    return chosen
                }
            }
            if (maxOf(working.width, working.height) <= 480) {
                recycleOwned(owned, source)
                return best ?: pack(working, webp = false, quality = 35)
            }
            val nextEdge = (maxOf(working.width, working.height) * 3 / 4).coerceAtLeast(480)
            val next = fit(working, nextEdge)
            if (next.width >= working.width && next.height >= working.height) {
                recycleOwned(owned, source)
                return best ?: pack(working, webp = false, quality = 35)
            }
            recycleOwned(owned, source)
            owned = next
            working = next
        }
    }

    private fun fit(source: Bitmap, edge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= edge || longEdge <= 0) return source
        val scale = edge.toFloat() / longEdge.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun pack(bitmap: Bitmap, webp: Boolean, quality: Int): Encoded {
        val format = when {
            !webp -> Bitmap.CompressFormat.JPEG
            Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
        val buffer = ByteArrayOutputStream()
        bitmap.compress(format, quality, buffer)
        val mime = if (webp) "image/webp" else "image/jpeg"
        return Encoded(buffer.toByteArray(), mime)
    }

    private fun smaller(left: Encoded, right: Encoded): Encoded =
        if (left.bytes.isNotEmpty() && left.bytes.size <= right.bytes.size) left else right

    private fun recycleOwned(owned: Bitmap?, original: Bitmap) {
        if (owned != null && owned !== original && !owned.isRecycled) owned.recycle()
    }
}
