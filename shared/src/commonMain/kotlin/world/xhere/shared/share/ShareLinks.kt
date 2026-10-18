package world.xhere.shared.share

/**
 * Public drop links. The website (M8) and Android App Links both use
 * `https://xhere.world/d/{short_id}`. The id is the drop's `short_id`.
 * Database ids are 12 url-safe characters; the demo fake also uses shorter ones.
 */
object ShareLinks {
    const val ORIGIN: String = "https://xhere.world"

    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun url(shortId: String): String = "$ORIGIN/d/$shortId"

    /** The short id from an https App Link, or null when the URL is not a drop link. */
    fun shortId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        val prefix = "$ORIGIN/d/"
        if (!trimmed.startsWith(prefix)) return null
        var rest = trimmed.removePrefix(prefix).substringBefore('?').substringBefore('#')
        if (rest.endsWith("/")) rest = rest.dropLast(1)
        if (rest.isEmpty() || '/' in rest || !ID.matches(rest)) return null
        return rest
    }
}
