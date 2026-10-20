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
import world.xhere.app.ui.TestTags

enum class DropPhotoPhase {
    LOADING,
    READY,
    FAILED,
}

/** One revealed photo. [alt] is the content description, not a caption. */
data class DropPhotoUi(
    val url: String,
    val alt: String,
    val bucket: String = "",
    val path: String = "",
    val phase: DropPhotoPhase = DropPhotoPhase.READY,
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
            SubcomposeAsyncImage(
                model = photo.url,
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
