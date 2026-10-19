package world.xhere.app.profile

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.shared.profile.ProfileFields

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AvatarCodecTest {
    @Test
    fun aSquareBecomesACircleUnderTheBucketLimit() {
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        val circle = AvatarCodec.circle(source, zoom = 1f)
        assertEquals(ProfileFields.AVATAR_EDGE_PX, circle.width)
        assertEquals(ProfileFields.AVATAR_EDGE_PX, circle.height)
        assertEquals(0, Color.alpha(circle.getPixel(0, 0)))
        assertEquals(255, Color.alpha(circle.getPixel(256, 256)))
        val encoded = AvatarCodec.compress(circle)
        assertTrue(encoded.bytes.size in 1..ProfileFields.AVATAR_MAX_BYTES)
        assertTrue(encoded.mimeType == "image/webp" || encoded.mimeType == "image/jpeg")
    }

    @Test
    fun noisyPhotosStayUnderTheBucketLimit() {
        val source = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        var n = 1
        for (i in pixels.indices) {
            n = n * 1103515245 + 12345
            pixels[i] = Color.argb(255, (n shr 16) and 255, (n shr 8) and 255, n and 255)
        }
        source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val encoded = AvatarCodec.compress(AvatarCodec.circle(source, zoom = 1f))
        assertTrue(encoded.bytes.size in 1..ProfileFields.AVATAR_MAX_BYTES)
        assertTrue(encoded.mimeType == "image/webp" || encoded.mimeType == "image/jpeg")
    }
}
