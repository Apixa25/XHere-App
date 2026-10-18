package world.xhere.app.play

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import world.xhere.app.ui.theme.XHereTheme

/**
 * Phone shots are 1080×1920 (9:16). The feature graphic is 1024×500.
 * The icon is 512×512. Set XHERE_CAPTURE_PLAY=1 to rewrite the PNGs
 * under android/play/graphics from these Compose previews.
 */
internal fun playGraphicsDir(): File {
    val module = File("proguard-rules.pro").absoluteFile.parentFile
    return File(module, "../play/graphics").canonicalFile
}

internal fun ImageBitmap.toSoftwareBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    readPixels(pixels)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

internal fun savePng(bitmap: Bitmap, file: File) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
    }
    // Play screenshots and the feature graphic have to be 24-bit (no alpha).
    // ffmpeg is on the capture machine; CI only checks the committed files.
    val flat = File(file.parentFile, file.name + ".rgb.png")
    val ffmpeg = ProcessBuilder(
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", file.absolutePath,
        "-pix_fmt", "rgb24",
        flat.absolutePath,
    ).redirectErrorStream(true).start()
    if (ffmpeg.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && ffmpeg.exitValue() == 0 && flat.isFile) {
        flat.copyTo(file, overwrite = true)
        flat.delete()
    } else {
        flat.delete()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class PlayPhoneCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    @Test fun capture_map() = capturePhone("phone-map.png") { PlayMapPreview() }
    @Test fun capture_near() = capturePhone("phone-near.png") { PlayNearPreview() }
    @Test fun capture_drop() = capturePhone("phone-drop.png") { PlayDropPreview() }
    @Test fun capture_detail() = capturePhone("phone-detail.png") { PlayDetailPreview() }

    private fun capturePhone(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        assumeTrue(System.getenv("XHERE_CAPTURE_PLAY") == "1")
        capture(name, playGraphicsDir(), content)
    }

    private fun capture(name: String, dir: File, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            XHereTheme {
                Box(
                    Modifier
                        .size(360.dp, 640.dp)
                        .testTag("shot"),
                ) { content() }
            }
        }
        rule.waitForIdle()
        val image = rule.onNodeWithTag("shot").captureToImage()
        savePng(image.toSoftwareBitmap(), File(dir, name))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h500dp-mdpi")
class PlayFeatureCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun capture_feature_graphic() {
        assumeTrue(System.getenv("XHERE_CAPTURE_PLAY") == "1")
        rule.setContent {
            Box(
                Modifier
                    .size(1024.dp, 500.dp)
                    .testTag("shot"),
            ) { PlayFeatureGraphic() }
        }
        rule.waitForIdle()
        savePng(
            rule.onNodeWithTag("shot").captureToImage().toSoftwareBitmap(),
            File(playGraphicsDir(), "feature-graphic.png"),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w512dp-h512dp-mdpi")
class PlayIconCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun capture_icon() {
        assumeTrue(System.getenv("XHERE_CAPTURE_PLAY") == "1")
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("shot"),
            ) { PlayIcon() }
        }
        rule.waitForIdle()
        savePng(
            rule.onNodeWithTag("shot").captureToImage().toSoftwareBitmap(),
            File(playGraphicsDir(), "icon-512.png"),
        )
    }
}

class PlayListingAssetsTest {
    @Test
    fun listing_text_fits_the_play_limits() {
        val play = playGraphicsDir().parentFile
        val title = File(play, "title.txt").readText().trim()
        val short = File(play, "short-description.txt").readText().trim()
        val full = File(play, "full-description.txt").readText().trim()
        assertTrue(title.isNotEmpty())
        assertTrue("title is ${title.length}", title.length <= 30)
        assertTrue("short is ${short.length}", short.length in 1..80)
        assertTrue("full is ${full.length}", full.length in 1..4000)
        assertTrue(full.contains("https://xhere.world/privacy"))
    }

    @Test
    fun graphics_match_play_pixel_sizes() {
        val dir = playGraphicsDir()
        assertSize(File(dir, "phone-map.png"), 1080, 1920)
        assertSize(File(dir, "phone-near.png"), 1080, 1920)
        assertSize(File(dir, "phone-drop.png"), 1080, 1920)
        assertSize(File(dir, "phone-detail.png"), 1080, 1920)
        assertSize(File(dir, "feature-graphic.png"), 1024, 500)
        assertSize(File(dir, "icon-512.png"), 512, 512)
        val icon = File(dir, "icon-512.png").length()
        assertTrue("icon is $icon bytes", icon in 1..1024 * 1024)
        listOf(
            "phone-map.png",
            "phone-near.png",
            "phone-drop.png",
            "phone-detail.png",
            "feature-graphic.png",
            "icon-512.png",
        ).forEach { name ->
            assertHasColor(File(dir, name))
        }
    }

    private fun assertHasColor(file: File) {
        assertTrue("${file.name} looks blank", pngHasInk(file))
    }

    private fun assertSize(file: File, width: Int, height: Int) {
        assertTrue("${file.name} missing at ${file.path}", file.isFile)
        val size = pngSize(file)
        assertEquals("${file.name} width", width, size.first)
        assertEquals("${file.name} height", height, size.second)
    }
}

/** IHDR width and height. BitmapFactory is shadowed under Robolectric and returns 0 for these files. */
internal fun pngSize(file: File): Pair<Int, Int> {
    file.inputStream().use { input ->
        val sig = ByteArray(8)
        check(input.read(sig) == 8)
        val len = ByteArray(4)
        check(input.read(len) == 4)
        val type = ByteArray(4)
        check(input.read(type) == 4 && type.decodeToString() == "IHDR")
        val ihdr = ByteArray(8)
        check(input.read(ihdr) == 8)
        val buf = ByteBuffer.wrap(ihdr).order(ByteOrder.BIG_ENDIAN)
        return buf.int to buf.int
    }
}

internal fun pngHasInk(file: File): Boolean {
    val bytes = file.readBytes()
    var pos = 8
    val idat = ByteArrayOutputStream()
    while (pos + 8 <= bytes.size) {
        val length = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
        val type = bytes.copyOfRange(pos + 4, pos + 8).decodeToString()
        val start = pos + 8
        if (type == "IDAT") idat.write(bytes, start, length)
        if (type == "IEND") break
        pos = start + length + 4
    }
    val inflater = Inflater()
    inflater.setInput(idat.toByteArray())
    val out = ByteArray(64 * 1024)
    while (!inflater.finished()) {
        val n = inflater.inflate(out)
        if (n == 0) break
        for (i in 0 until n) {
            if (out[i] != 0.toByte()) return true
        }
    }
    return false
}
