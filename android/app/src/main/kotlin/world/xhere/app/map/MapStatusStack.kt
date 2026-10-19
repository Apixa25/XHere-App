package world.xhere.app.map

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import world.xhere.app.explore.ExperienceCloseButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.live.WeatherAlert
import world.xhere.shared.model.CampaignProgress

/**
 * Chips stay on their own row. A campaign and a weather alert share one card
 * so a Pixel-sized map does not stack three floating panels under the status bar.
 * Either one alone keeps its own card. Together they start as two lines.
 */
@Composable
internal fun MapStatusStack(
    campaign: CampaignProgress?,
    alerts: List<WeatherAlert>,
    onDismissAlert: (String) -> Unit,
    onDismissCampaign: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val active = campaign
    if (active == null && alerts.isEmpty()) return
    if (active == null || alerts.isEmpty()) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active != null) {
                CampaignCard(
                    campaign = active,
                    now = Clock.System.now(),
                    compact = true,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (alerts.isNotEmpty()) {
                WeatherAlertBanner(alerts = alerts, onDismiss = onDismissAlert)
            }
        }
        return
    }
    val alert = alerts.first()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val progress = stringResource(R.string.campaign_progress, active.dropCount, active.goalDrops)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .animateContentSize()
            .testTag(TestTags.MAP_STATUS),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = active.title,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.CAMPAIGN_TITLE),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!expanded) {
                    Text(
                        text = progress,
                        modifier = Modifier.testTag(TestTags.CAMPAIGN_PROGRESS),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag(TestTags.MAP_STATUS_TOGGLE),
                ) {
                    Text(
                        stringResource(
                            if (expanded) R.string.map_status_hide else R.string.map_status_details,
                        ),
                    )
                }
                if (onDismissCampaign != null) {
                    ExperienceCloseButton(onClick = onDismissCampaign, tag = TestTags.CAMPAIGN_DISMISS)
                }
            }
            if (!expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = severityLabel(alert.severity),
                        modifier = Modifier.testTag(TestTags.WEATHER_SEVERITY),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = alert.event,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                            .testTag(TestTags.WEATHER_EVENT),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                val mine = if (active.myDrops == 0) {
                    stringResource(R.string.campaign_mine_zero)
                } else {
                    stringResource(R.string.campaign_mine, active.myDrops)
                }
                val board = active.leaderboard.joinToString(" · ") { "${it.displayName} ${it.drops}" }
                    .ifBlank { stringResource(R.string.campaign_board_empty) }
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
                    text = board,
                    modifier = Modifier.testTag(TestTags.CAMPAIGN_BOARD),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                WeatherAlertBanner(
                    alerts = alerts,
                    onDismiss = onDismissAlert,
                    inset = false,
                )
            }
        }
    }
}
