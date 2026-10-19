package world.xhere.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import world.xhere.app.ui.TestTags
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.LeaderboardEntry
import org.koin.compose.koinInject

@Composable
internal fun ProfileAvatar(
    url: String?,
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(if (tag == null) Modifier else Modifier.testTag(tag)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun rememberAvatarUrl(path: String?): String? {
    val services: XHereServices = koinInject()
    return services.media.avatarUrl(path)
}

/** Faces next to the names already printed on the board. Hidden when nobody has a picture. */
@Composable
internal fun LeaderboardFaces(entries: List<LeaderboardEntry>, modifier: Modifier = Modifier) {
    val pictured = entries.filter { !it.avatarPath.isNullOrBlank() }
    if (pictured.isEmpty()) return
    Row(
        modifier = modifier.testTag(TestTags.LEADERBOARD_AVATARS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pictured.take(5).forEach { entry ->
            ProfileAvatar(
                url = rememberAvatarUrl(entry.avatarPath),
                label = entry.displayName,
                size = 28.dp,
            )
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
