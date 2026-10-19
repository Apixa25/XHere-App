package world.xhere.app.reputation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.reputation.ReputationStanding

/** Small tier name. Tapping the profile chip opens [LevelUpSheet]. */
@Composable
internal fun TierChip(
    label: String,
    modifier: Modifier = Modifier,
    tag: String = TestTags.TIER_CHIP,
    onClick: (() -> Unit)? = null,
) {
    if (label.isBlank()) return
    val click = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .then(click)
                .testTag(tag)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LevelUpSheet(
    standing: ReputationStanding,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        BackHandler(onBack = onDismiss)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .testTag(TestTags.LEVEL_UP),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.reputation_how),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.LEVEL_UP_CLOSE)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.here_close))
                }
            }
            Text(
                text = stringResource(R.string.reputation_points, standing.points),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            val nextLabel = standing.nextLabel
            val nextAt = standing.nextAt
            if (nextLabel != null && nextAt != null) {
                Text(
                    text = stringResource(R.string.reputation_next, nextLabel, nextAt),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text(stringResource(R.string.reputation_top), style = MaterialTheme.typography.bodyLarge)
            }
            standing.ladder.forEach { rung ->
                Text(
                    text = stringResource(R.string.reputation_ladder_row, rung.label, rung.at),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = stringResource(
                    R.string.reputation_confirms,
                    standing.confirmFirst,
                    standing.confirmSecond,
                    standing.confirmThird,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    R.string.reputation_views,
                    standing.viewFirst,
                    standing.viewNext,
                    standing.viewCap,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.reputation_finish, standing.walkPoints, standing.questPoints),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.reputation_deny, standing.denyFirst, standing.denySecond),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.reputation_removed, standing.moderationPenalty),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.reputation_reports, standing.trustedExtraReports),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.reputation_own),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
