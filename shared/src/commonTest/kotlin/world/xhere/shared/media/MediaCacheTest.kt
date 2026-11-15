package world.xhere.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import world.xhere.shared.contribution.ContributionMediaPolicy
import world.xhere.shared.contribution.ContributionMediaRef

class MediaCacheTest {
    @Test
    fun coilKeyIgnoresTheSignedToken() {
        val first = "https://example.supabase.co/storage/v1/object/sign/drop-media/user/a.jpg?token=aaa"
        val second = "https://example.supabase.co/storage/v1/object/sign/drop-media/user/a.jpg?token=bbb"
        assertEquals(MediaCache.keyForUrl(first), MediaCache.keyForUrl(second))
        assertEquals("drop-media/user/a.jpg", MediaCache.keyForUrl(first))
        assertEquals(MediaCache.key("drop-media", "user/a.jpg"), MediaCache.keyForUrl(first))
        assertEquals(
            "avatars/user/face.webp",
            MediaCache.keyForUrl("https://example.supabase.co/storage/v1/object/public/avatars/user/face.webp"),
        )
        assertTrue(MediaCache.CACHE_CONTROL.contains("max-age=31536000"))
        assertEquals(60.0, ContributionMediaPolicy.VIDEO_MAX_SECONDS)
    }

    @Test
    fun aListSignsThePosterAndNotTheVideo() {
        val video = ContributionMediaRef(
            storagePath = "c/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.mp4",
            thumbPath = "c/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.thumb.jpg",
            mimeType = "video/mp4",
        )
        val photo = ContributionMediaRef(
            storagePath = "c/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/cccccccc-cccc-4ccc-8ccc-cccccccccccc.webp",
            thumbPath = "c/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/cccccccc-cccc-4ccc-8ccc-cccccccccccc.thumb.jpg",
            mimeType = "image/webp",
        )
        val paths = MediaSurfaces.listPaths(listOf(video, photo))
        assertEquals(listOfNotNull(video.thumbPath, photo.thumbPath), paths)
        assertFalse(paths.any { it.endsWith(".mp4") })
        assertTrue(MediaSurfaces.isVideo("video/mp4", video.storagePath))
        assertFalse(MediaSurfaces.isVideo("image/jpeg", photo.storagePath))
    }

    @Test
    fun aSignedUrlIsReusedUntilShortlyBeforeItExpires() = runTest {
        var now = Instant.parse("2026-11-15T12:00:00Z")
        val cache = SignedUrlCache(
            lifetime = 20.minutes,
            refreshBefore = 2.minutes,
            now = { now },
        )
        assertNull(cache.get("drop-media", "user/a.jpg"))
        cache.put("drop-media", "user/a.jpg", "https://cdn.example/a?token=1")
        now += 17.minutes
        assertEquals("https://cdn.example/a?token=1", cache.get("drop-media", "user/a.jpg"))
        now += 2.minutes
        assertNull(cache.get("drop-media", "user/a.jpg"))
        cache.put("drop-media", "user/b.jpg", "https://cdn.example/b?token=2")
        now += 20.minutes - 30.seconds
        assertNull(cache.get("drop-media", "user/b.jpg"))
    }
}
