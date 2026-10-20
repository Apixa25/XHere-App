package world.xhere.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaPathsTest {
    @Test
    fun aPhotoGainsAJpegSiblingAndAVideoDoesNot() {
        assertEquals(
            "11111111-1111-4111-8111-111111111111/abc.thumb.jpg",
            MediaPaths.thumbSibling("11111111-1111-4111-8111-111111111111/abc.jpg"),
        )
        assertEquals(
            "user/pic.thumb.jpg",
            MediaPaths.thumbSibling("user/pic.webp"),
        )
        assertEquals(
            "user/pic.thumb.jpg",
            MediaPaths.thumbSibling("user/pic.jpeg"),
        )
        assertNull(MediaPaths.thumbSibling("user/clip.mp4"))
        assertNull(MediaPaths.thumbSibling("user/clip.m4a"))
        assertNull(MediaPaths.thumbSibling("no-extension"))
    }
}
