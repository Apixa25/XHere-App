package world.xhere.app.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.model.QuestProgress
import world.xhere.shared.model.Walk
import world.xhere.shared.walks.WalkGuide

/**
 * The walk for this part of the map. The title, description, length, and time
 * all come from the region config. Starting it turns on next-stop guidance.
 */
@Composable
internal fun WalkCard(
    walk: Walk,
    distanceToNextM: Double?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stops = WalkGuide.ordered(walk)
    val done = stops.count { it.reached }
    val next = WalkGuide.nextStop(walk)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.WALK_CARD),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = walk.title,
                        modifier = Modifier.testTag(TestTags.WALK_TITLE),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.walk_meta, formatMeters(walk.distanceM.toDouble()), walk.durationMin),
                        modifier = Modifier.testTag(TestTags.WALK_META),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = walk.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!walk.started && !walk.completed) {
                    Surface(
                        onClick = onStart,
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(TestTags.WALK_START),
                    ) {
                        Text(
                            text = stringResource(R.string.walk_start),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            when {
                walk.completed -> Text(
                    text = stringResource(R.string.walk_done),
                    modifier = Modifier.testTag(TestTags.WALK_DONE),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                walk.started && next != null -> {
                    val label = if (distanceToNextM != null) {
                        stringResource(R.string.walk_next_away, next.title, formatMeters(distanceToNextM))
                    } else {
                        stringResource(R.string.walk_next, next.title)
                    }
                    Text(
                        text = label,
                        modifier = Modifier.testTag(TestTags.WALK_NEXT),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { if (stops.isEmpty()) 0f else done.toFloat() / stops.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.walk_progress, done, stops.size),
                        modifier = Modifier.testTag(TestTags.WALK_PROGRESS),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Starter-quest progress. Dismiss hides it. The counts stay on the server. */
@Composable
internal fun QuestCard(
    quest: QuestProgress,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.QUEST_CARD),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = quest.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(TestTags.QUEST_DISMISS),
                ) {
                    Text(stringResource(R.string.quest_dismiss))
                }
            }
            Column(
                modifier = Modifier
                    .semantics(mergeDescendants = true) {}
                    .testTag(TestTags.QUEST_PROGRESS),
            ) {
                quest.steps.sortedBy { it.position }.forEach { step ->
                    val progress = min(step.progress, step.targetCount)
                    Text(
                        text = stringResource(R.string.quest_step, step.title, progress, step.targetCount),
                        modifier = Modifier.padding(bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
