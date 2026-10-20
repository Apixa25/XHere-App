package world.xhere.app.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import world.xhere.shared.profile.AvatarCrop
import world.xhere.shared.profile.ProfileFields

/**
 * Gallery and camera pictures become a 512 px square of whatever sat inside
 * the circular mask, then a WebP or JPEG around 200 KB. The avatars bucket
 * still accepts up to 512 KB.
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

    /** Center square at [zoom]. The editor passes [crop] once the user has panned. */
    fun circle(source: Bitmap, zoom: Float): Bitmap {
        return crop(source, AvatarCrop.centered(source.width, source.height, zoom))
    }

    /** The square inside the circle, scaled to [ProfileFields.AVATAR_EDGE_PX]. */
    fun crop(source: Bitmap, rect: AvatarCrop.SourceRect): Bitmap {
        val edge = ProfileFields.AVATAR_EDGE_PX
        if (source.width <= 0 || source.height <= 0 || rect.side <= 0f) {
            return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        }
        val left = rect.left.roundToInt().coerceIn(0, source.width - 1)
        val top = rect.top.roundToInt().coerceIn(0, source.height - 1)
        val side = rect.side.roundToInt()
            .coerceAtLeast(1)
            .coerceAtMost(source.width - left)
            .coerceAtMost(source.height - top)
        val square = Bitmap.createBitmap(source, left, top, side, side)
        if (square.width == edge && square.height == edge) return square
        val scaled = Bitmap.createScaledBitmap(square, edge, edge, true)
        if (scaled !== square && square !== source) square.recycle()
        return scaled
    }

    /**
     * WebP first, then JPEG, then a smaller square, until the file is about
     * 200 KB. The bucket still rejects anything over 512 KB.
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
            if (bytes.size in 1..ProfileFields.AVATAR_TARGET_BYTES) return Encoded(bytes, "image/webp")
        }
        for (quality in intArrayOf(70, 50, 35, 20, 10)) {
            val bytes = encode(bitmap, webp = false, quality = quality)
            if (bytes.size in 1..ProfileFields.AVATAR_TARGET_BYTES) return Encoded(bytes, "image/jpeg")
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
