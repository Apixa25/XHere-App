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

    /**
     * The poster beside a photo or a video. A video file is `name.mp4`. Its
     * poster is `name.thumb.jpg`. [thumbSibling] stays photo-only so a drop
     * photo upload does not invent a thumbnail for a clip.
     */
    fun posterSibling(storagePath: String): String? {
        val slash = storagePath.lastIndexOf('/')
        val dot = storagePath.lastIndexOf('.')
        if (dot <= slash || dot >= storagePath.length - 1) return null
        val ext = storagePath.substring(dot + 1).lowercase()
        if (ext != "jpg" && ext != "jpeg" && ext != "png" && ext != "webp" && ext != "mp4" && ext != "webm") {
            return null
        }
        return storagePath.substring(0, dot) + ".thumb.jpg"
    }
}
