package world.xhere.app.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import world.xhere.app.explore.ExperienceCloseButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.time.Instant
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.model.CampaignProgress
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
    onDismiss: (() -> Unit)? = null,
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
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag(TestTags.WALK_START),
                    ) {
                        Text(
                            text = stringResource(R.string.walk_start),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (onDismiss != null) {
                    ExperienceCloseButton(onClick = onDismiss, tag = TestTags.WALK_DISMISS)
                }
            }
            when {
                !walk.started && !walk.completed && stops.isNotEmpty() -> Text(
                    text = stringResource(R.string.walk_progress, done, stops.size),
                    modifier = Modifier.testTag(TestTags.WALK_PROGRESS),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/**
 * Community progress for one live campaign. The map uses the compact card.
 * The profile uses the same card with the description and the full short board.
 * Names are display names. A person who opted out still sees their own count.
 */
@Composable
internal fun CampaignCard(
    campaign: CampaignProgress,
    now: Instant,
    compact: Boolean,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val days = (campaign.endsAt - now).inWholeDays
    val progress = stringResource(R.string.campaign_progress, campaign.dropCount, campaign.goalDrops)
    val mine = if (campaign.myDrops == 0) {
        stringResource(R.string.campaign_mine_zero)
    } else {
        stringResource(R.string.campaign_mine, campaign.myDrops)
    }
    val daysLabel = if (days <= 0L) {
        stringResource(R.string.campaign_ends_today)
    } else {
        stringResource(R.string.campaign_days, days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    val board = campaign.leaderboard.joinToString(" · ") { "${it.displayName} ${it.drops}" }
        .ifBlank { stringResource(R.string.campaign_board_empty) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.CAMPAIGN_CARD),
    ) {
        if (compact) {
            // Two lines so the banner sits under the chips and leaves the
            // map's pan control, tap spot, and markers free to receive clicks.
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = campaign.title,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.CAMPAIGN_TITLE),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = progress,
                        modifier = Modifier.testTag(TestTags.CAMPAIGN_PROGRESS),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mine,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.CAMPAIGN_MINE),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = board,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.CAMPAIGN_BOARD),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onDismiss != null) {
                        ExperienceCloseButton(onClick = onDismiss, tag = TestTags.CAMPAIGN_DISMISS)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = campaign.title,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.CAMPAIGN_TITLE),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onDismiss != null) {
                        ExperienceCloseButton(onClick = onDismiss, tag = TestTags.CAMPAIGN_DISMISS)
                    }
                }
                Text(
                    text = campaign.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { campaign.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = progress,
                    modifier = Modifier.testTag(TestTags.CAMPAIGN_PROGRESS),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = mine,
                    modifier = Modifier.testTag(TestTags.CAMPAIGN_MINE),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = daysLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = board,
                    modifier = Modifier.testTag(TestTags.CAMPAIGN_BOARD),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!campaign.showOnLeaderboard) {
                    Text(
                        text = stringResource(R.string.campaign_opted_out),
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
