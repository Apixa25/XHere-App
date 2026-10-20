package world.xhere.app.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.places.PublicPlace
import world.xhere.shared.places.PublicPlaceKind

/**
 * One public place. Close returns to the map. The source link and the
 * contribution button are the only actions.
 */
@Composable
internal fun PublicPlaceSheet(
    place: PublicPlace,
    onContribute: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BottomSheet(onDismiss = onDismiss, tag = TestTags.PUBLIC_PLACE_SHEET) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.public_place_category),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(place.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(TestTags.PUBLIC_PLACE_TITLE))
            place.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = place.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .testTag(TestTags.PUBLIC_PLACE_IMAGE),
                    contentScale = ContentScale.Crop,
                )
            }
            place.summary?.let { summary ->
                Text(summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TestTags.PUBLIC_PLACE_SUMMARY))
            }
            Text(
                stringResource(R.string.public_place_credit, place.attribution, place.license),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.PUBLIC_PLACE_CREDIT),
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(place.pageUrl))) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.PUBLIC_PLACE_LINK),
            ) {
                Text(stringResource(kindLabel(place.kind)))
            }
            Button(
                onClick = onContribute,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.PUBLIC_PLACE_ADD),
            ) {
                Text(stringResource(R.string.public_place_add))
            }
        }
    }
}

private fun kindLabel(kind: PublicPlaceKind): Int = when (kind) {
    PublicPlaceKind.ARTICLE -> R.string.public_place_article
    PublicPlaceKind.PHOTO -> R.string.public_place_photo
    PublicPlaceKind.HISTORIC -> R.string.public_place_historic
}
