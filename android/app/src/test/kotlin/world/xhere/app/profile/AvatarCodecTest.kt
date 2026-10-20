package world.xhere.app.profile

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.shared.profile.AvatarCrop
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
        assertEquals(Color.BLUE, circle.getPixel(0, 0))
        assertEquals(Color.BLUE, circle.getPixel(256, 256))
        val encoded = AvatarCodec.compress(circle)
        assertTrue(encoded.bytes.size in 1..ProfileFields.AVATAR_TARGET_BYTES)
        assertTrue(encoded.mimeType == "image/webp" || encoded.mimeType == "image/jpeg")
    }

    @Test
    fun panAndZoomMapOntoTheSourceSquare() {
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(80 * 40)
        for (y in 0 until 40) {
            for (x in 0 until 80) {
                pixels[y * 80 + x] = if (x < 40) Color.RED else Color.BLUE
            }
        }
        source.setPixels(pixels, 0, 80, 0, 0, 80, 40)
        val centered = AvatarCrop.centered(80, 40)
        val middle = AvatarCodec.crop(source, centered)
        assertEquals(Color.RED, middle.getPixel(64, 256))
        assertEquals(Color.BLUE, middle.getPixel(448, 256))

        val (_, right) = AvatarCrop.gesture(
            srcW = 80,
            srcH = 40,
            viewSize = 200f,
            zoom = 1f,
            left = centered.left,
            top = centered.top,
            panX = -10_000f,
            panY = 0f,
            zoomChange = 1f,
            focalX = 0.5f,
            focalY = 0.5f,
        )
        assertEquals(40f, right.left, 0.01f)
        assertEquals(Color.BLUE, AvatarCodec.crop(source, right).getPixel(256, 256))

        val (_, left) = AvatarCrop.gesture(
            srcW = 80,
            srcH = 40,
            viewSize = 200f,
            zoom = 1f,
            left = right.left,
            top = right.top,
            panX = 10_000f,
            panY = 0f,
            zoomChange = 1f,
            focalX = 0.5f,
            focalY = 0.5f,
        )
        assertEquals(0f, left.left, 0.01f)
        assertEquals(Color.RED, AvatarCodec.crop(source, left).getPixel(256, 256))

        val (zoom, tighter) = AvatarCrop.zoomAboutCenter(80, 40, 1f, 2f, centered.left, centered.top)
        assertEquals(2f, zoom, 0.01f)
        assertEquals(20f, tighter.side, 0.01f)
        assertTrue(tighter.left >= 0f && tighter.right <= 80f)
        assertTrue(tighter.top >= 0f && tighter.bottom <= 40f)
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
        assertTrue(encoded.bytes.size in 1..ProfileFields.AVATAR_TARGET_BYTES)
        assertTrue(encoded.mimeType == "image/webp" || encoded.mimeType == "image/jpeg")
    }
}
