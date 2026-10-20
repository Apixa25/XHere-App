package world.xhere.app.drop

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import world.xhere.app.media.ImageOrientation
import world.xhere.shared.contribution.ContributionMediaPolicy

/**
 * Drop photos and contribution photos are resized on the phone.
 * The long edge stays at about 2048 px. The file is WebP or JPEG at
 * quality 80–85, and it stays under 500 KB. A 400 px JPEG thumbnail is
 * produced for map and list rows. Re-encoding drops EXIF, including GPS.
 * The orientation tag is applied to the pixels first.
 * Bytes that are not a picture are returned unchanged.
 */
internal object DropPhotoCodec {
    const val MAX_EDGE = ContributionMediaPolicy.PHOTO_MAX_EDGE
    const val THUMB_EDGE = ContributionMediaPolicy.THUMB_EDGE
    const val TARGET_BYTES = ContributionMediaPolicy.PHOTO_MAX_BYTES
    const val QUALITY = ContributionMediaPolicy.PHOTO_QUALITY
    private val BAND = intArrayOf(85, 82, 80)

    data class Encoded(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int = 0,
        val height: Int = 0,
        val thumbnail: ByteArray = ByteArray(0),
    )

    fun compress(bytes: ByteArray, mimeType: String): Encoded {
        val mime = mimeType.substringBefore(';').trim().lowercase().ifBlank { "image/jpeg" }
        val decoded = ImageOrientation.decode(bytes, MAX_EDGE) ?: return Encoded(bytes, mime)
        return try {
            val fitted = fit(decoded, MAX_EDGE)
            try {
                val packed = underLimit(fitted, floor = 960, limit = TARGET_BYTES)
                Encoded(
                    bytes = packed.bytes,
                    mimeType = packed.mimeType,
                    width = packed.width,
                    height = packed.height,
                    thumbnail = thumbnail(fitted),
                )
            } finally {
                if (fitted !== decoded && !fitted.isRecycled) fitted.recycle()
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    /** A poster frame or an already-decoded bitmap, as a list thumbnail. */
    fun thumbnail(source: Bitmap): ByteArray {
        val fitted = fit(source, THUMB_EDGE)
        return try {
            underLimit(fitted, floor = 240, limit = ContributionMediaPolicy.THUMB_MAX_BYTES).bytes
        } finally {
            if (fitted !== source && !fitted.isRecycled) fitted.recycle()
        }
    }

    private data class Packed(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
    )

    /**
     * Stay at quality 80–85 while the long edge comes down.
     * A noisy picture that is still over the cap at the floor may drop to
     * quality 60 so the upload is not rejected. Ordinary photos do not reach that step.
     */
    private fun underLimit(source: Bitmap, floor: Int, limit: Int): Packed {
        var working = source
        var owned: Bitmap? = null
        try {
            while (true) {
                val band = bestInBand(working)
                if (band.bytes.size in 1..limit) {
                    return band.copy(width = working.width, height = working.height)
                }
                if (maxOf(working.width, working.height) <= floor) {
                    val low = smaller(pack(working, webp = true, quality = 60), pack(working, webp = false, quality = 60))
                    return Packed(low.bytes, low.mimeType, working.width, working.height)
                }
                val nextEdge = (maxOf(working.width, working.height) * 3 / 4).coerceAtLeast(floor)
                val next = fit(working, nextEdge)
                if (next === working || (next.width >= working.width && next.height >= working.height)) {
                    return band.copy(width = working.width, height = working.height)
                }
                if (owned != null && owned !== source && !owned.isRecycled) owned.recycle()
                owned = if (next !== source) next else null
                working = next
            }
        } finally {
            if (owned != null && owned !== source && !owned.isRecycled) owned.recycle()
        }
    }

    private fun bestInBand(bitmap: Bitmap): Packed {
        var best: Encoded? = null
        for (quality in BAND) {
            val chosen = smaller(pack(bitmap, webp = true, quality), pack(bitmap, webp = false, quality))
            if (best == null || chosen.bytes.size < best.bytes.size) best = chosen
            if (chosen.bytes.size in 1..TARGET_BYTES && quality == QUALITY) {
                return Packed(chosen.bytes, chosen.mimeType, bitmap.width, bitmap.height)
            }
        }
        val chosen = best ?: pack(bitmap, webp = false, quality = QUALITY)
        return Packed(chosen.bytes, chosen.mimeType, bitmap.width, bitmap.height)
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
        if (left.bytes.isNotEmpty() && (right.bytes.isEmpty() || left.bytes.size <= right.bytes.size)) left else right
}
