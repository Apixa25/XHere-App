package world.xhere.shared.profile

import kotlin.uuid.Uuid

/**
 * The same limits as `update_my_profile` and the profile check constraints.
 * The database is the authority. This copy is what the edit screen and the
 * offline client use before a save.
 */
object ProfileFields {
    const val NAME_MAX: Int = 50
    const val HANDLE_MIN: Int = 3
    const val HANDLE_MAX: Int = 24
    const val BIO_MAX: Int = 160
    const val HOME_MAX: Int = 80
    const val WEBSITE_MAX: Int = 200
    const val AVATAR_EDGE_PX: Int = 512
    const val AVATAR_MAX_BYTES: Int = 512 * 1024
    const val AVATAR_BUCKET: String = "avatars"

    private val handlePattern = Regex("^[a-z0-9_]{3,24}$")
    private val websitePattern = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val avatarFile = Regex("^[0-9a-fA-F-]{36}\\.(webp|jpe?g)$")

    fun cleanName(raw: String): String? {
        val name = raw.trim()
        if (name.length !in 1..NAME_MAX || name.any { it.isISOControl() }) return null
        return name
    }

    /** Empty stays empty so the field can show "still typing". Invalid spelling is null. */
    fun cleanHandle(raw: String): String? {
        val cleaned = raw.trim().lowercase()
        if (!handlePattern.matches(cleaned)) return null
        return cleaned
    }

    /** Blank clears the field. Too long, or a control character, is rejected. */
    fun cleanOptional(raw: String, max: Int): String? {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (text.length > max || text.any { it.isISOControl() }) return null
        return text
    }

    fun cleanWebsite(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (text.length > WEBSITE_MAX || !websitePattern.matches(text)) return null
        return text
    }

    fun avatarPathOk(userId: Uuid, path: String): Boolean {
        val folder = userId.toString().lowercase() + "/"
        if (!path.lowercase().startsWith(folder)) return false
        return avatarFile.matches(path.substring(folder.length))
    }

    /** Public object URL. A blank path has no picture. */
    fun avatarUrl(apiRoot: String?, path: String?): String? {
        val stored = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val root = apiRoot?.trimEnd('/')?.takeIf { it.startsWith("https://") && !it.contains("your-project-ref") }
            ?: "https://media.xhere.test"
        return "$root/storage/v1/object/public/$AVATAR_BUCKET/${stored.trimStart('/')}"
    }
}
