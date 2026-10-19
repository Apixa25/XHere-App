package world.xhere.app.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import world.xhere.app.R
import world.xhere.app.ui.TestTags

/** One revealed photo. [alt] is the content description, not a caption. */
data class DropPhotoUi(
    val url: String,
    val alt: String,
)

@Composable
fun DropPhotos(photos: List<DropPhotoUi>) {
    var full: DropPhotoUi? by remember { mutableStateOf(null) }
    photos.forEachIndexed { index, photo ->
        if (photo.url.isBlank()) return@forEachIndexed
        val description = photo.alt.ifBlank { stringResource(R.string.detail_photo) }
        AsyncImage(
            model = photo.url,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 360.dp)
                .testTag(if (index == 0) TestTags.DETAIL_PHOTO else "${TestTags.DETAIL_PHOTO}_$index")
                .clickable { full = photo },
        )
    }
    val open = full
    if (open != null && open.url.isNotBlank()) {
        Dialog(
            onDismissRequest = { full = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = open.url,
                        contentDescription = open.alt.ifBlank { stringResource(R.string.detail_photo) },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TestTags.DETAIL_PHOTO_FULL)
                            .clickable { full = null },
                    )
                }
            }
        }
    }
}
