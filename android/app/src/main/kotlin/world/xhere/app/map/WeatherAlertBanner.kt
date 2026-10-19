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
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.live.AlertSeverity
import world.xhere.shared.live.NwsAlerts
import world.xhere.shared.live.WeatherAlert

/** Official alerts on the map. Separate from the Here Card text so place tests stay stable. */
internal val WeatherSemantics = SemanticsPropertyKey<String>("WeatherAlerts")

@Composable
internal fun WeatherAlertBanner(
    alerts: List<WeatherAlert>,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
    inset: Boolean = true,
) {
    val alert = alerts.firstOrNull() ?: return
    val tone = when (alert.severity) {
        AlertSeverity.EXTREME, AlertSeverity.SEVERE -> MaterialTheme.colorScheme.errorContainer
        AlertSeverity.MODERATE -> MaterialTheme.colorScheme.tertiaryContainer
        AlertSeverity.MINOR, AlertSeverity.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onTone = when (alert.severity) {
        AlertSeverity.EXTREME, AlertSeverity.SEVERE -> MaterialTheme.colorScheme.onErrorContainer
        AlertSeverity.MODERATE -> MaterialTheme.colorScheme.onTertiaryContainer
        AlertSeverity.MINOR, AlertSeverity.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) 12.dp else 0.dp)
            .testTag(TestTags.WEATHER_BANNER)
            .semantics { set(WeatherSemantics, describeWeather(alerts)) },
        shape = MaterialTheme.shapes.large,
        color = tone,
        shadowElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = severityLabel(alert.severity),
                    style = MaterialTheme.typography.labelLarge,
                    color = onTone,
                    modifier = Modifier.testTag(TestTags.WEATHER_SEVERITY),
                )
                TextButton(
                    onClick = { onDismiss(alert.id) },
                    modifier = Modifier.testTag(TestTags.WEATHER_DISMISS),
                ) {
                    Text(stringResource(R.string.weather_dismiss))
                }
            }
            Text(
                text = alert.event,
                style = MaterialTheme.typography.titleMedium,
                color = onTone,
                modifier = Modifier.testTag(TestTags.WEATHER_EVENT),
            )
            val until = alert.expiresAt?.let(NwsAlerts::formatExpiry)
                ?: stringResource(R.string.weather_no_expiry)
            Text(
                text = stringResource(R.string.weather_until, until),
                style = MaterialTheme.typography.bodyMedium,
                color = onTone,
            )
            Text(
                text = stringResource(R.string.weather_source, alert.attribution, alert.senderName),
                style = MaterialTheme.typography.bodySmall,
                color = onTone,
                modifier = Modifier.testTag(TestTags.WEATHER_SOURCE),
            )
            if (alerts.size > 1) {
                Text(
                    text = stringResource(R.string.weather_more, alerts.size - 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = onTone,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag(TestTags.WEATHER_MORE),
                )
            }
        }
    }
}

internal fun describeWeather(alerts: List<WeatherAlert>): String {
    val first = alerts.firstOrNull() ?: return "none"
    val expiry = first.expiresAt?.let(NwsAlerts::formatExpiry) ?: "none"
    return "${first.event}|${first.severity.name}|$expiry|${first.attribution}|${first.senderName}|+${alerts.size - 1}"
}

@Composable
internal fun severityLabel(severity: AlertSeverity): String = when (severity) {
    AlertSeverity.EXTREME -> stringResource(R.string.weather_severity_extreme)
    AlertSeverity.SEVERE -> stringResource(R.string.weather_severity_severe)
    AlertSeverity.MODERATE -> stringResource(R.string.weather_severity_moderate)
    AlertSeverity.MINOR -> stringResource(R.string.weather_severity_minor)
    AlertSeverity.UNKNOWN -> stringResource(R.string.weather_severity_unknown)
}
