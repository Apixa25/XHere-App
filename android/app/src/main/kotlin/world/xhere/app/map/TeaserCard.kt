package world.xhere.app.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.reputation.TierChip
import world.xhere.app.ui.TestTags

@Composable
internal fun TeaserCard(
    teaser: TeaserUi,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.TEASER_CARD),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(teaser.icon, style = MaterialTheme.typography.headlineSmall)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = teaser.categoryLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(TestTags.TEASER_CATEGORY),
                    )
                    Text(
                        text = teaser.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(TestTags.TEASER_TITLE),
                    )
                    teaser.tierLabel?.let { tier ->
                        TierChip(label = tier, tag = TestTags.TEASER_TIER)
                    }
                    teaser.foundingLabel?.let { founding ->
                        Text(
                            text = founding,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.testTag(TestTags.TEASER_FOUNDING),
                        )
                    }
                    if (teaser.publicRecord) {
                        Text(
                            text = stringResource(R.string.detail_public_records),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.testTag(TestTags.TEASER_PUBLIC),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.detail_impact, teaser.viewCount, teaser.confirmCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(TestTags.DETAIL_IMPACT),
                        )
                    }
                }
                TextButton(onClick = onOpen, modifier = Modifier.testTag(TestTags.TEASER_OPEN)) {
                    Text(stringResource(R.string.map_teaser_open))
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag(TestTags.TEASER_CLOSE)) {
                    Text(stringResource(R.string.map_teaser_close))
                }
            }
            teaser.distanceLabel?.let { distance ->
                Text(
                    text = distance,
                    modifier = Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (teaser.approximate) {
                Text(
                    text = stringResource(R.string.map_teaser_approximate),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (teaser.publicRecord && !teaser.attribution.isNullOrBlank()) {
                Text(
                    text = teaser.attribution,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (teaser.revealed && !teaser.body.isNullOrBlank()) {
                Text(
                    text = teaser.body,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 6.dp)
                        .testTag(TestTags.TEASER_BODY),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text(
                    text = stringResource(R.string.map_teaser_locked),
                    modifier = Modifier
                        .padding(start = 4.dp, top = 6.dp)
                        .testTag(TestTags.TEASER_LOCKED),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
