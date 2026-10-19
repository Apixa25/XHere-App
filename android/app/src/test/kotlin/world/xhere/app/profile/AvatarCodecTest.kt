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
}
