package world.xhere.shared.media

/**
 * Storage objects are immutable (a new photo is a new path), so a year-long
 * cache is safe. PLAN §1.4: free-plan egress is 5 GB. The signed URL changes
 * every time it is minted. Coil and the HTTP cache must key the object path,
 * not that token.
 */
object MediaCache {
    /** Sent on upload. One year, and the bytes will not change at this path. */
    const val CACHE_CONTROL: String = "public, max-age=31536000, immutable"

    /** How long a signed URL from Storage stays valid. */
    const val SIGNED_URL_MINUTES: Int = 20

    /**
     * Refresh a cached signed URL this long before it expires, so a view
     * near the end of the window does not hand Coil a URL that dies mid-load.
     */
    const val SIGNED_URL_REFRESH_SECONDS: Int = 120

    fun key(bucket: String, objectPath: String): String {
        val cleanBucket = bucket.trim().trim('/')
        val cleanPath = objectPath.trim().trimStart('/')
        return "$cleanBucket/$cleanPath"
    }

    /**
     * Stable Coil key for a Storage URL or any other https URL.
     * Query tokens on a signed URL are ignored. Two signatures of the same
     * object share one memory entry and one disk entry.
     */
    fun keyForUrl(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val markers = listOf(
            "/object/sign/",
            "/object/public/",
            "/object/authenticated/",
            "/render/image/public/",
            "/render/image/authenticated/",
        )
        for (marker in markers) {
            val at = path.indexOf(marker)
            if (at >= 0) return path.substring(at + marker.length)
        }
        return path
    }
}
