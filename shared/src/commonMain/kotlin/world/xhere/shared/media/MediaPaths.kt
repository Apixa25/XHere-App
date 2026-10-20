package world.xhere.shared.media

/**
 * A drop photo keeps its storage path. The list thumbnail, when the phone
 * made one, sits beside it as `<name>.thumb.jpg`. Older photos have no
 * sibling. Callers fall back to the original path when the thumb is missing.
 */
object MediaPaths {
    fun thumbSibling(storagePath: String): String? {
        val slash = storagePath.lastIndexOf('/')
        val dot = storagePath.lastIndexOf('.')
        if (dot <= slash || dot >= storagePath.length - 1) return null
        val ext = storagePath.substring(dot + 1).lowercase()
        if (ext != "jpg" && ext != "jpeg" && ext != "png" && ext != "webp") return null
        return storagePath.substring(0, dot) + ".thumb.jpg"
    }
}
