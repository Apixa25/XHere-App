package world.xhere.app.media

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.data.XHereServices
import world.xhere.shared.media.MediaCache
import world.xhere.shared.media.MediaPaths

/**
 * Poster first. The video file is signed and streamed only after a tap.
 * Nothing on a feed, a drop sheet, or a map card starts playback by itself.
 * The clip is still capped at 60 seconds when it is prepared for upload.
 */
@Composable
fun TapToPlayVideo(
    services: XHereServices,
    bucket: String,
    path: String,
    description: String,
    modifier: Modifier = Modifier,
    posterUrl: String? = null,
    compact: Boolean = false,
    size: Dp = 64.dp,
) {
    val playLabel = stringResource(R.string.video_play)
    var poster by remember(bucket, path, posterUrl) { mutableStateOf(posterUrl?.takeIf { it.isNotBlank() }) }
    var playing by remember(bucket, path) { mutableStateOf(false) }
    LaunchedEffect(services, bucket, path, posterUrl) {
        if (!posterUrl.isNullOrBlank()) {
            poster = posterUrl
            return@LaunchedEffect
        }
        val sibling = MediaPaths.posterSibling(path) ?: return@LaunchedEffect
        poster = runCatching { services.media.photoUrl(bucket, sibling) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    if (playing && !compact) {
        StreamAfterTap(
            services = services,
            bucket = bucket,
            path = path,
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp),
        )
        return
    }
    val frame = if (compact) {
        modifier.size(size)
    } else {
        modifier
            .fillMaxWidth()
            .height(200.dp)
    }
    val posterKey = MediaCache.key(bucket, MediaPaths.posterSibling(path) ?: path)
    Box(
        modifier = frame
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { playing = true }
            .semantics { contentDescription = playLabel }
            .testTag(TestTags.VIDEO_POSTER),
        contentAlignment = Alignment.Center,
    ) {
        val shown = poster
        if (!shown.isNullOrBlank()) {
            CachedRemoteImage(
                url = shown,
                cacheKey = posterKey,
                contentDescription = description.ifBlank { playLabel },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        PlayMark()
    }
    if (playing && compact) {
        Dialog(
            onDismissRequest = { playing = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                StreamAfterTap(
                    services = services,
                    bucket = bucket,
                    path = path,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun StreamAfterTap(
    services: XHereServices,
    bucket: String,
    path: String,
    modifier: Modifier,
) {
    var attempt by remember(bucket, path) { mutableIntStateOf(0) }
    var url by remember(bucket, path) { mutableStateOf<String?>(null) }
    var failed by remember(bucket, path) { mutableStateOf(false) }
    LaunchedEffect(services, bucket, path, attempt) {
        failed = false
        url = null
        val signed = runCatching { services.media.photoUrl(bucket, path) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (signed == null) failed = true else url = signed
    }
    val stream = url
    when {
        failed -> {
            Box(
                modifier = modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { attempt += 1 }
                    .testTag(TestTags.VIDEO_PLAYER),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.video_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        stream == null -> {
            Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> StreamedVideo(url = stream, modifier = modifier.testTag(TestTags.VIDEO_PLAYER))
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun StreamedVideo(
    url: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier.background(Color.Black),
    )
}

@Composable
private fun PlayMark() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.32f, size.height * 0.18f)
                lineTo(size.width * 0.82f, size.height * 0.5f)
                lineTo(size.width * 0.32f, size.height * 0.82f)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}
