package world.xhere.app.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import java.io.ByteArrayOutputStream
import world.xhere.shared.profile.ProfileFields

/**
 * Gallery and camera pictures become a 512 px circle, then a WebP or JPEG
 * small enough for the avatars bucket (512 KB).
 */
internal object AvatarCodec {
    data class Encoded(val bytes: ByteArray, val mimeType: String)

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    /** Center square, zoomed in, drawn inside a circle. Corners are transparent. */
    fun circle(source: Bitmap, zoom: Float): Bitmap {
        val zoomed = zoom.coerceIn(1f, 4f)
        val side = (minOf(source.width, source.height) / zoomed).toInt().coerceAtLeast(1)
        val left = ((source.width - side) / 2).coerceAtLeast(0)
        val top = ((source.height - side) / 2).coerceAtLeast(0)
        val square = Bitmap.createBitmap(source, left, top, side.coerceAtMost(source.width - left), side.coerceAtMost(source.height - top))
        val scaled = if (square.width == ProfileFields.AVATAR_EDGE_PX && square.height == ProfileFields.AVATAR_EDGE_PX) {
            square
        } else {
            Bitmap.createScaledBitmap(square, ProfileFields.AVATAR_EDGE_PX, ProfileFields.AVATAR_EDGE_PX, true)
        }
        val edge = ProfileFields.AVATAR_EDGE_PX
        val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(edge * edge)
        scaled.getPixels(pixels, 0, edge, 0, 0, edge, edge)
        val radius = edge / 2f
        val radiusSq = radius * radius
        for (y in 0 until edge) {
            val dy = y + 0.5f - radius
            for (x in 0 until edge) {
                val dx = x + 0.5f - radius
                if (dx * dx + dy * dy > radiusSq) pixels[y * edge + x] = Color.TRANSPARENT
            }
        }
        out.setPixels(pixels, 0, edge, 0, 0, edge, edge)
        if (scaled !== square) scaled.recycle()
        if (square !== source) square.recycle()
        return out
    }

    /**
     * WebP first, then JPEG, then a smaller square, until the bytes fit the
     * avatars bucket. The bucket rejects anything over 512 KB.
     */
    fun compress(bitmap: Bitmap): Encoded {
        var working = bitmap
        var owned: Bitmap? = null
        while (true) {
            encodeUnderLimit(working)?.let { encoded ->
                recycleOwned(owned, bitmap)
                return encoded
            }
            if (working.width <= 48) {
                val last = encode(working, webp = false, quality = 5)
                recycleOwned(owned, bitmap)
                return Encoded(last, "image/jpeg")
            }
            val edge = (working.width * 3 / 4).coerceAtLeast(48)
            val next = Bitmap.createScaledBitmap(working, edge, edge, true)
            if (next.width >= working.width && next.height >= working.height) {
                val last = encode(working, webp = false, quality = 5)
                recycleOwned(owned, bitmap)
                if (next !== working && next !== bitmap) next.recycle()
                return Encoded(last, "image/jpeg")
            }
            recycleOwned(owned, bitmap)
            owned = if (next !== bitmap) next else null
            working = next
        }
    }

    private fun recycleOwned(owned: Bitmap?, original: Bitmap) {
        if (owned != null && owned !== original && !owned.isRecycled) owned.recycle()
    }

    private fun encodeUnderLimit(bitmap: Bitmap): Encoded? {
        for (quality in intArrayOf(80, 60, 45, 30, 20, 10)) {
            val bytes = encode(bitmap, webp = true, quality = quality)
            if (bytes.size in 1..ProfileFields.AVATAR_MAX_BYTES) return Encoded(bytes, "image/webp")
        }
        for (quality in intArrayOf(70, 50, 35, 20, 10)) {
            val bytes = encode(bitmap, webp = false, quality = quality)
            if (bytes.size in 1..ProfileFields.AVATAR_MAX_BYTES) return Encoded(bytes, "image/jpeg")
        }
        return null
    }

    private fun encode(bitmap: Bitmap, webp: Boolean, quality: Int): ByteArray {
        val format = when {
            !webp -> Bitmap.CompressFormat.JPEG
            Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
        val buffer = ByteArrayOutputStream()
        bitmap.compress(format, quality, buffer)
        return buffer.toByteArray()
    }
}
