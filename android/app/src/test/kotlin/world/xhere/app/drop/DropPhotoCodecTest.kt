package world.xhere.app.drop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.shared.contribution.ContributionMediaPolicy

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
        assertTrue(encoded.thumbnail.isNotEmpty())
        assertTrue(encoded.thumbnail.size <= ContributionMediaPolicy.THUMB_MAX_BYTES)
        val thumb = BitmapFactory.decodeByteArray(encoded.thumbnail, 0, encoded.thumbnail.size)
        assertNotNull(thumb)
        assertTrue(maxOf(thumb.width, thumb.height) <= DropPhotoCodec.THUMB_EDGE)
        thumb.recycle()
        decoded.recycle()
    }

    @Test
    fun orientationIsAppliedAndGpsIsLeftBehind() {
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        val file = File.createTempFile("xhere-exif", ".jpg")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        source.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "73/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }
        val encoded = DropPhotoCodec.compress(file.readBytes(), "image/jpeg")
        val decoded = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
        assertNotNull(decoded)
        assertTrue(decoded.height > decoded.width)
        val out = File.createTempFile("xhere-out", ".jpg")
        if (encoded.mimeType == "image/jpeg") {
            out.writeBytes(encoded.bytes)
            val gps = FloatArray(2)
            assertFalse(ExifInterface(out).getLatLong(gps))
        }
        decoded.recycle()
        file.delete()
        out.delete()
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
