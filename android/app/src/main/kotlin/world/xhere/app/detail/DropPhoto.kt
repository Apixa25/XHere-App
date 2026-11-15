package world.xhere.app.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import world.xhere.app.R
import world.xhere.app.media.cachedImageRequest
import world.xhere.app.ui.TestTags
import world.xhere.shared.media.MediaCache
import world.xhere.shared.media.MediaPaths

enum class DropPhotoPhase {
    LOADING,
    READY,
    FAILED,
}

/** A revealed video. The poster loads with the sheet. The file loads on tap. */
data class DropVideoUi(
    val bucket: String,
    val path: String,
    val alt: String = "",
)

/** One revealed photo. [alt] is the content description, not a caption. */
data class DropPhotoUi(
    val url: String,
    val alt: String,
    val bucket: String = "",
    val path: String = "",
    val phase: DropPhotoPhase = DropPhotoPhase.READY,
    /** About 400 px. Blank means the slot uses [url], which is the full photo. */
    val previewUrl: String = "",
)

@Composable
fun DropPhotos(photos: List<DropPhotoUi>, onRetry: (Int) -> Unit = {}) {
    var full: DropPhotoUi? by remember { mutableStateOf(null) }
    photos.forEachIndexed { index, photo ->
        val description = photo.alt.ifBlank { stringResource(R.string.detail_photo) }
        val frameTag = if (index == 0) TestTags.DETAIL_PHOTO else "${TestTags.DETAIL_PHOTO}_$index"
        PhotoSlot(
            photo = photo,
            description = description,
            frameTag = frameTag,
            onOpen = { full = photo },
            onRetry = { onRetry(index) },
        )
    }
    val open = full
    if (open != null) {
        val description = open.alt.ifBlank { stringResource(R.string.detail_photo) }
        Dialog(
            onDismissRequest = { full = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PhotoSlot(
                        photo = open,
                        description = description,
                        frameTag = TestTags.DETAIL_PHOTO_FULL,
                        onOpen = { full = null },
                        onRetry = {
                            val index = photos.indexOfFirst { it.path == open.path && it.bucket == open.bucket }
                            if (index >= 0) onRetry(index)
                        },
                        fill = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoSlot(
    photo: DropPhotoUi,
    description: String,
    frameTag: String,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    fill: Boolean = false,
) {
    val frame = if (fill) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 360.dp)
    }
    when (photo.phase) {
        DropPhotoPhase.LOADING -> {
            Box(
                modifier = frame
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics { contentDescription = description }
                    .testTag(if (frameTag == TestTags.DETAIL_PHOTO_FULL) frameTag else TestTags.DETAIL_PHOTO_LOADING),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        DropPhotoPhase.FAILED -> {
            Box(
                modifier = frame
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onRetry)
                    .semantics { contentDescription = description }
                    .testTag(TestTags.DETAIL_PHOTO_RETRY)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.detail_photo_retry),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            val full = fill || photo.previewUrl.isBlank()
            val shown = if (full) photo.url else photo.previewUrl
            val keyPath = if (full) photo.path else (MediaPaths.thumbSibling(photo.path) ?: photo.path)
            val cacheKey = if (photo.bucket.isNotBlank() && keyPath.isNotBlank()) {
                MediaCache.key(photo.bucket, keyPath)
            } else {
                MediaCache.keyForUrl(shown)
            }
            val context = LocalContext.current
            val request = remember(shown, cacheKey) { cachedImageRequest(context, shown, cacheKey) }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = frame
                    .testTag(frameTag)
                    .clickable(onClick = onOpen),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .semantics { contentDescription = description }
                            .testTag(TestTags.DETAIL_PHOTO_LOADING),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClick = onRetry)
                            .semantics { contentDescription = description }
                            .testTag(TestTags.DETAIL_PHOTO_RETRY)
                            .padding(PaddingValues(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.detail_photo_retry),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                success = { SubcomposeAsyncImageContent() },
            )
        }
    }
}
