package world.xhere.app.media

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.platform.LocalContext
import world.xhere.shared.data.XHereServices
import world.xhere.shared.media.MediaCache
import world.xhere.shared.media.MediaPaths
import world.xhere.shared.media.MediaSurfaces
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.MediaKind

/**
 * The first revealed image on a card. Map and list rows ask for the 400 px
 * sibling. A photo posted before thumbnails existed has no sibling, and a
 * failed sign falls back to the original path. PLAN §1.4.
 */
fun DropCard.listPhoto(): Pair<String, String>? {
    val image = media?.firstOrNull { it.mediaKind == MediaKind.IMAGE && it.storagePath.isNotBlank() }
    if (image != null) return image.storageBucket to image.storagePath
    val video = media?.firstOrNull { it.mediaKind == MediaKind.VIDEO && it.storagePath.isNotBlank() } ?: return null
    return video.storageBucket to video.storagePath
}

@Composable
fun SignedListImage(
    services: XHereServices,
    bucket: String,
    path: String,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    if (MediaSurfaces.isVideoPath(path)) {
        TapToPlayVideo(
            services = services,
            bucket = bucket,
            path = path,
            description = description,
            modifier = modifier,
            compact = true,
            size = size,
        )
        return
    }
    var url by remember(bucket, path) { mutableStateOf<String?>(null) }
    var shownPath by remember(bucket, path) { mutableStateOf(path) }
    LaunchedEffect(services, bucket, path) {
        val sibling = MediaPaths.thumbSibling(path)
        val thumb = sibling?.let { candidate ->
            runCatching { services.media.photoUrl(bucket, candidate) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (sibling != null && thumb != null) {
            shownPath = sibling
            url = thumb
        } else {
            shownPath = path
            url = runCatching { services.media.photoUrl(bucket, path) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }
    val shown = url ?: return
    val context = LocalContext.current
    val request = remember(shown, bucket, shownPath) {
        cachedImageRequest(context, shown, MediaCache.key(bucket, shownPath))
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp)),
    )
}
