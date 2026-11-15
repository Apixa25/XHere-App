package world.xhere.app.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import world.xhere.shared.media.MediaCache

/**
 * One Coil request whose memory and disk keys are the storage object, not the
 * signed URL. A new 20-minute token must not download the file again.
 */
fun cachedImageRequest(context: Context, url: String, cacheKey: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(MemoryCache.Key(cacheKey))
        .diskCacheKey(cacheKey)
        .build()

@Composable
fun CachedRemoteImage(
    url: String,
    cacheKey: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(url, cacheKey) { cachedImageRequest(context, url, cacheKey) }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
