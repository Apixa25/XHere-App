package world.xhere.app.profile

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import world.xhere.app.media.ImageOrientation
import world.xhere.shared.contribution.ContributionMediaPolicy
import world.xhere.shared.profile.AvatarCrop
import world.xhere.shared.profile.ProfileFields

/**
 * Gallery and camera pictures become a 512 px square of whatever sat inside
 * the circular mask, then a WebP or JPEG at about quality 82. The avatars
 * bucket still accepts up to 512 KB. Orientation is applied and GPS is not
 * copied, the same rule as a drop photo.
 */
internal object AvatarCodec {
    data class Encoded(val bytes: ByteArray, val mimeType: String)

    fun decode(bytes: ByteArray): Bitmap? = ImageOrientation.decode(bytes, ContributionMediaPolicy.PHOTO_MAX_EDGE)

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
     * WebP at quality 80–85, then JPEG in that same band. A noisy square
     * that is still over 512 KB is reduced, but the quality stays in the band
     * until the edge itself has to come down.
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
                val last = encode(working, webp = false, quality = 80)
                recycleOwned(owned, bitmap)
                return Encoded(last, "image/jpeg")
            }
            val edge = (working.width * 3 / 4).coerceAtLeast(48)
            val next = Bitmap.createScaledBitmap(working, edge, edge, true)
            if (next.width >= working.width && next.height >= working.height) {
                val last = encode(working, webp = false, quality = 80)
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
        for (quality in intArrayOf(85, ContributionMediaPolicy.PHOTO_QUALITY, 80)) {
            val webp = encode(bitmap, webp = true, quality = quality)
            if (webp.size in 1..ProfileFields.AVATAR_MAX_BYTES) {
                if (webp.size <= ProfileFields.AVATAR_TARGET_BYTES || quality == 80) {
                    return Encoded(webp, "image/webp")
                }
            }
        }
        for (quality in intArrayOf(85, ContributionMediaPolicy.PHOTO_QUALITY, 80)) {
            val jpeg = encode(bitmap, webp = false, quality = quality)
            if (jpeg.size in 1..ProfileFields.AVATAR_MAX_BYTES) return Encoded(jpeg, "image/jpeg")
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
