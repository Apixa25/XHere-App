package world.xhere.app.drop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DropPhotoCodecTest {
    @Test
    fun aLargePhotoShrinksUnderHalfAMegabyte() {
        val source = Bitmap.createBitmap(3200, 1800, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        var n = 1
        for (i in pixels.indices) {
            n = n * 1103515245 + 12345
            pixels[i] = Color.rgb((n shr 16) and 255, (n shr 8) and 255, n and 255)
        }
        source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val original = jpeg(source, quality = 95)
        source.recycle()
        assertTrue(original.size > DropPhotoCodec.TARGET_BYTES)

        val encoded = DropPhotoCodec.compress(original, "image/jpeg")
        assertTrue(encoded.bytes.size in 1..DropPhotoCodec.TARGET_BYTES)
        assertTrue(encoded.mimeType == "image/jpeg" || encoded.mimeType == "image/webp")
        val decoded = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
        assertNotNull(decoded)
        assertTrue(maxOf(decoded.width, decoded.height) <= DropPhotoCodec.MAX_EDGE)
        decoded.recycle()
    }

    @Test
    fun aSmallPhotoIsNotEnlarged() {
        val source = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val original = jpeg(source, quality = 90)
        source.recycle()
        val encoded = DropPhotoCodec.compress(original, "image/jpeg")
        val decoded = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
        assertNotNull(decoded)
        assertTrue(decoded.width <= 40)
        assertTrue(decoded.height <= 30)
        assertTrue(encoded.bytes.size <= DropPhotoCodec.TARGET_BYTES)
        decoded.recycle()
    }

    @Test
    fun bytesThatAreNotAPictureStayAsTheyAre() {
        val raw = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x11)
        val encoded = DropPhotoCodec.compress(raw, "image/jpeg")
        assertTrue(encoded.bytes.contentEquals(raw))
        assertTrue(encoded.mimeType == "image/jpeg")
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer))
        return buffer.toByteArray()
    }
}
