package world.xhere.shared.media

import world.xhere.shared.contribution.ContributionMediaRef

/**
 * What a list, a map card, or a sheet may download before the person taps.
 *
 * A video file is not in that set. The poster (the `.thumb.jpg` sibling) is.
 * The video bytes load only after a tap. PLAN §1.4. The 60-second cap stays.
 */
object MediaSurfaces {
    fun isVideoMime(mime: String): Boolean =
        mime.substringBefore(';').trim().lowercase().startsWith("video/")

    fun isVideoPath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext == "mp4" || ext == "webm"
    }

    fun isVideo(mime: String, path: String): Boolean = isVideoMime(mime) || isVideoPath(path)

    /** Paths a list may sign up front. Never the video object itself. */
    fun listPaths(media: List<ContributionMediaRef>): List<String> = media.mapNotNull { item ->
        if (isVideo(item.mimeType, item.storagePath)) {
            item.thumbPath
        } else {
            item.thumbPath ?: item.storagePath.takeIf { it.isNotBlank() }
        }
    }.distinct()
}
