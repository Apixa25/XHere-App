package world.xhere.shared.profile

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import world.xhere.shared.SupabaseConfig
import world.xhere.shared.data.ProfileDraft
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.supabase.SupabaseXHereClient
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus

class ProfileEditTest {
    @Test
    fun fieldsMatchTheDatabaseLimits() {
        assertEquals("ada", ProfileFields.cleanHandle(" Ada "))
        assertNull(ProfileFields.cleanHandle("no"))
        assertNull(ProfileFields.cleanHandle("has space"))
        assertEquals("https://xhere.world", ProfileFields.cleanWebsite("https://xhere.world"))
        assertEquals("", ProfileFields.cleanWebsite("  "))
        assertNull(ProfileFields.cleanWebsite("javascript:alert(1)"))
        assertNull(ProfileFields.cleanOptional("x".repeat(161), ProfileFields.BIO_MAX))
        assertEquals("", ProfileFields.cleanOptional("  ", ProfileFields.BIO_MAX))
        val id = Uuid.parse("11111111-1111-4111-8111-111111111111")
        assertTrue(ProfileFields.avatarPathOk(id, "$id/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp"))
        assertFalse(ProfileFields.avatarPathOk(id, "22222222-2222-4222-8222-222222222222/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp"))
        assertEquals(
            "https://media.xhere.test/storage/v1/object/public/avatars/a/b.webp",
            ProfileFields.avatarUrl(null, "a/b.webp"),
        )
    }

    @Test
    fun theOwnerCanEditAndSomeoneElseCanOpenTheCard() = runTest {
        val store = JamestownDemo.store()
        val steven = InMemoryXHere(store, JamestownDemo.viewer.copy(handle = "steven", displayName = "Steven"))
        assertTrue(steven.profiles.handleAvailable("STEVEN"))
        assertFalse(steven.profiles.handleAvailable("demo_guide"))
        assertFalse(steven.profiles.handleAvailable("no"))

        val saved = steven.profiles.updateProfile(
            ProfileDraft(
                displayName = "Steven S",
                handle = "Steven_S",
                bio = "Walks the lake.",
                homeArea = "Jamestown",
                website = "https://xhere.world",
                avatarPath = "${JamestownDemo.viewer.id}/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.webp",
            ),
        )
        assertEquals("steven_s", saved.handle)
        assertEquals("Walks the lake.", saved.bio)

        val guide = steven.profiles.publicProfile("DEMO_GUIDE")
        assertEquals("Demo guide", guide?.displayName)
        assertTrue((guide?.dropCount ?: 0) > 0)
        assertTrue(guide?.recentDrops?.isNotEmpty() == true)
        assertTrue(guide?.recentDrops?.all { it.title.isNotBlank() } == true)

        val card = steven.profiles.publicProfile("steven_s")
        assertEquals("Walks the lake.", card?.bio)
        assertEquals("Jamestown", card?.homeArea)
        assertEquals("https://xhere.world", card?.website)
        assertEquals(0, card?.dropCount)

        val taken = assertFailsWith<XHereApiException> {
            steven.profiles.updateProfile(ProfileDraft(displayName = "Steven", handle = "demo_guide"))
        }
        assertEquals("handle_taken", taken.code)

        val picture = steven.media.uploadAvatar(byteArrayOf(1, 2, 3), "image/webp")
        assertTrue(picture.startsWith("${JamestownDemo.viewer.id}/"))
        assertTrue(steven.media.avatarUrl(picture)!!.contains("/avatars/"))

        val before = steven.profiles.publicProfile("demo_guide")!!.dropCount
        val hidden = store.drops.values.first { it.authorId == JamestownDemo.guide.id && it.source == DropSource.USER }
        hidden.status = DropStatus.REMOVED
        val afterRemoved = steven.profiles.publicProfile("demo_guide")!!.dropCount
        assertTrue(afterRemoved < before)
    }
}

private data class AvatarRequest(
    val method: HttpMethod,
    val path: String,
    val upsert: String?,
    val contentType: String?,
)

class AvatarUploadContractTest {
    @Test
    fun avatarUploadPostsWithUpsertAndAnImageContentType() = runTest {
        val seen = mutableListOf<AvatarRequest>()
        val engine = MockEngine { request ->
            val headerType = request.headers[HttpHeaders.ContentType]
            val bodyType = request.body.contentType?.toString()
            seen += AvatarRequest(
                method = request.method,
                path = request.url.encodedPath,
                upsert = request.headers["x-upsert"],
                contentType = headerType ?: bodyType,
            )
            val uploading = request.method == HttpMethod.Post && request.url.encodedPath.contains("/object/avatars/")
            val body = if (uploading) """{"Id":"obj","Key":"avatars/file.webp"}""" else "{}"
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val userId = "11111111-1111-4111-8111-111111111111"
        val client = SupabaseXHereClient(
            SupabaseConfig(url = "https://example.supabase.co", anonKey = "test-anon-key"),
            engine = engine,
        )
        client.supabase.auth.importSession(
            UserSession(
                accessToken = "test-access-token",
                refreshToken = "",
                expiresIn = 3_600,
                tokenType = "bearer",
                user = UserInfo(id = userId, aud = "authenticated"),
            ),
            autoRefresh = false,
        )

        val webp = client.media.uploadAvatar(byteArrayOf(1, 2, 3, 4), "image/webp")
        assertTrue(webp.startsWith("$userId/"), webp)
        assertTrue(webp.endsWith(".webp"), webp)
        val webpUpload = seen.last { it.method == HttpMethod.Post && it.path.contains("/object/avatars/") }
        assertEquals(HttpMethod.Post, webpUpload.method)
        assertEquals("true", webpUpload.upsert)
        assertEquals("image/webp", webpUpload.contentType?.substringBefore(';')?.trim())
        assertTrue(webpUpload.path.contains("/storage/v1/object/avatars/$userId/"), webpUpload.path)
        assertTrue(seen.none { it.method == HttpMethod.Put && it.path.contains("avatars") })

        val jpeg = client.media.uploadAvatar(byteArrayOf(5, 6, 7), "image/jpeg")
        assertTrue(jpeg.endsWith(".jpg"), jpeg)
        val jpegUpload = seen.last { it.path.endsWith(".jpg") }
        assertEquals(HttpMethod.Post, jpegUpload.method)
        assertEquals("true", jpegUpload.upsert)
        assertEquals("image/jpeg", jpegUpload.contentType?.substringBefore(';')?.trim())

        client.media.deleteAvatar(webp)
        val deletion = seen.last { it.method == HttpMethod.Delete }
        assertTrue(deletion.path.contains("/storage/v1/object/avatars"), deletion.path)

        val tooBig = assertFailsWith<XHereApiException> {
            client.media.uploadAvatar(ByteArray(ProfileFields.AVATAR_MAX_BYTES + 1), "image/webp")
        }
        assertEquals("invalid_avatar", tooBig.code)
    }
}
