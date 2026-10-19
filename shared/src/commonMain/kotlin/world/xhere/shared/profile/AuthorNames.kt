package world.xhere.shared.profile

/**
 * What to show instead of a generated handle such as `user_abc123`.
 * A display name wins. A handle the person chose is next. An automatic handle
 * is not a name, so the UI uses a friendly stand-in.
 */
object AuthorNames {
    private val automaticHandle = Regex("^user_[0-9a-f]{6,}$")

    /** True when this account still has the generated handle and no display name. */
    fun needsChoice(displayName: String?, handle: String?): Boolean {
        if (!displayName.isNullOrBlank()) return false
        val cleaned = handle?.trim().orEmpty()
        return cleaned.isEmpty() || automaticHandle.matches(cleaned)
    }

    /**
     * The name to show on a drop. Null means there is nothing friendly yet
     * and the screen should say "A neighbor" rather than the raw id.
     */
    fun label(displayName: String?, handle: String?): String? {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null) return name
        val cleaned = handle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (automaticHandle.matches(cleaned)) return null
        return cleaned
    }
}
