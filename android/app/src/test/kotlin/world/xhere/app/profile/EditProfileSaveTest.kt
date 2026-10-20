package world.xhere.app.profile

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.shared.data.MediaRepository
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.profile.AvatarCrop

/**
 * A failed photo must not throw away the name, handle, and home town.
 * A successful replacement deletes the previous avatar object.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditProfileSaveTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun mainThread() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun aFailedPhotoStillSavesTheTextAndNamesTheReason() {
        val store = JamestownDemo.store()
        val old = "${JamestownDemo.viewer.id}/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp"
        val steven = InMemoryXHere(
            store,
            JamestownDemo.viewer.copy(displayName = "Preview", handle = "preview", avatarPath = old),
        )
        steven.store.mediaBytes[old] = byteArrayOf(1)
        val viewModel = EditProfileViewModel(AvatarUploadFails(steven))
        scheduler.advanceUntilIdle()

        viewModel.onDisplayName("Steven Sills")
        viewModel.onHandle("magnus")
        viewModel.onHome("Jamestown New York")
        scheduler.advanceUntilIdle()
        viewModel.startCrop(solid())
        viewModel.confirmCrop()
        viewModel.save()
        scheduler.advanceUntilIdle()

        val profile = kotlinx.coroutines.runBlocking { steven.profiles.currentProfile() }
        assertEquals("Steven Sills", profile.displayName)
        assertEquals("magnus", profile.handle)
        assertEquals("Jamestown New York", profile.homeArea)
        assertEquals(old, profile.avatarPath)
        assertTrue(steven.store.mediaBytes.containsKey(old))
        assertEquals(EditProfileError.PHOTO, viewModel.state.value.error)
        assertFalse(viewModel.state.value.saved)
        assertFalse(viewModel.state.value.saving)
    }

    @Test
    fun aBadWebsiteAndATakenHandleAreNamed() {
        val steven = InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer.copy(displayName = "Preview", handle = "preview"))
        val viewModel = EditProfileViewModel(steven)
        scheduler.advanceUntilIdle()

        viewModel.onDisplayName("Steven Sills")
        viewModel.onWebsite("javascript:alert(1)")
        viewModel.save()
        scheduler.advanceUntilIdle()
        assertEquals(EditProfileError.WEBSITE, viewModel.state.value.error)
        assertEquals("Preview", kotlinx.coroutines.runBlocking { steven.profiles.currentProfile() }.displayName)

        viewModel.onWebsite("")
        viewModel.onHandle("demo_guide")
        scheduler.advanceUntilIdle()
        viewModel.save()
        scheduler.advanceUntilIdle()
        assertEquals(EditProfileError.TAKEN, viewModel.state.value.error)
        assertEquals("preview", kotlinx.coroutines.runBlocking { steven.profiles.currentProfile() }.handle)
    }

    @Test
    fun aSuccessfulPhotoReplacesThePreviousObject() {
        val old = "${JamestownDemo.viewer.id}/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp"
        val steven = InMemoryXHere(
            JamestownDemo.store(),
            JamestownDemo.viewer.copy(displayName = "Preview", handle = "preview", avatarPath = old),
        )
        steven.store.mediaBytes[old] = byteArrayOf(1)
        val viewModel = EditProfileViewModel(steven)
        scheduler.advanceUntilIdle()
        viewModel.onDisplayName("Steven Sills")
        viewModel.onHandle("magnus")
        viewModel.onHome("Jamestown New York")
        scheduler.advanceUntilIdle()
        viewModel.startCrop(solid())
        viewModel.confirmCrop()
        viewModel.save()
        scheduler.advanceUntilIdle()

        val profile = kotlinx.coroutines.runBlocking { steven.profiles.currentProfile() }
        assertEquals("Steven Sills", profile.displayName)
        assertEquals("magnus", profile.handle)
        assertEquals("Jamestown New York", profile.homeArea)
        assertNotEquals(old, profile.avatarPath)
        assertTrue(profile.avatarPath!!.endsWith(".webp") || profile.avatarPath!!.endsWith(".jpg"))
        assertFalse(steven.store.mediaBytes.containsKey(old))
        assertTrue(steven.store.mediaBytes.containsKey(profile.avatarPath))
        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun draggingThePhotoMovesTheSavedSquareOntoThatSide() {
        val steven = InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer.copy(displayName = "Preview", handle = "preview"))
        val viewModel = EditProfileViewModel(steven)
        scheduler.advanceUntilIdle()
        viewModel.startCrop(split())
        assertEquals(AvatarCrop.MIN_ZOOM, viewModel.state.value.zoom, 0.001f)
        val centered = viewModel.framedBitmap()!!
        assertEquals(Color.RED, centered.getPixel(64, 256))
        assertEquals(Color.BLUE, centered.getPixel(448, 256))

        viewModel.onCropGesture(
            viewSize = 200f,
            panX = -10_000f,
            panY = 5_000f,
            zoomChange = 0.1f,
            focalX = 0.5f,
            focalY = 0.5f,
        )
        assertEquals(AvatarCrop.MIN_ZOOM, viewModel.state.value.zoom, 0.001f)
        assertEquals(Color.BLUE, viewModel.framedBitmap()!!.getPixel(256, 256))

        viewModel.onZoom(4f)
        assertEquals(AvatarCrop.MAX_ZOOM, viewModel.state.value.zoom, 0.001f)
        val tight = viewModel.state.value
        assertTrue(tight.cropLeft >= 0f)
        assertTrue(tight.cropTop >= 0f)

        viewModel.resetCrop()
        assertEquals(AvatarCrop.MIN_ZOOM, viewModel.state.value.zoom, 0.001f)
        val reset = viewModel.framedBitmap()!!
        assertEquals(Color.RED, reset.getPixel(64, 256))
        assertEquals(Color.BLUE, reset.getPixel(448, 256))
    }

    private fun split(): Bitmap {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(80 * 40)
        for (y in 0 until 40) {
            for (x in 0 until 80) pixels[y * 80 + x] = if (x < 40) Color.RED else Color.BLUE
        }
        bitmap.setPixels(pixels, 0, 80, 0, 0, 80, 40)
        return bitmap
    }

    private fun solid(): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        return bitmap
    }
}

private class AvatarUploadFails(private val inner: InMemoryXHere) : XHereServices by inner {
    override val media: MediaRepository = object : MediaRepository by inner.media {
        override suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String {
            throw XHereApiException("upload_failed", detail = "avatar upload failed")
        }
    }
}
