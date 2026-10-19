package world.xhere.app.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.here.Here
import world.xhere.shared.here.isPublicRecord
import world.xhere.shared.live.NwsAlerts
import world.xhere.shared.live.WeatherAlert
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.Taxonomy

/**
 * One sheet for a tapped spot (research §4.1 #3, PLAN §2 and §3).
 * The drop button stays put. The list underneath scrolls.
 * A locked row never prints the body the reveal rules hid.
 */
@Composable
internal fun HereCardSheet(
    sheet: HereSheet,
    taxonomy: Taxonomy?,
    alerts: List<WeatherAlert> = emptyList(),
    onDropHere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = Here.groups(sheet.drops)
    BottomSheet(
        onDismiss = onDismiss,
        tag = TestTags.HERE_SHEET,
        semantics = { set(HereSemantics, describeHere(sheet)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = placeTitle(sheet),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.HERE_PLACE),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.HERE_CLOSE)) {
                    Text(stringResource(R.string.here_close))
                }
            }
            val address = sheet.place?.address?.trim().orEmpty()
            if (address.isNotEmpty()) {
                Text(
                    text = address,
                    modifier = Modifier.testTag(TestTags.HERE_ADDRESS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val attribution = sheet.place?.attribution?.trim().orEmpty()
            if (attribution.isNotEmpty()) {
                Text(
                    text = attribution,
                    modifier = Modifier.testTag(TestTags.HERE_ATTRIBUTION),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onDropHere,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.HERE_DROP),
            ) {
                Text(stringResource(R.string.drop_fab))
            }
            if (alerts.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            testTag = TestTags.HERE_CONDITIONS
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.here_conditions),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    alerts.forEach { alert ->
                        val until = alert.expiresAt?.let(NwsAlerts::formatExpiry)
                            ?: stringResource(R.string.weather_no_expiry)
                        Text(
                            text = stringResource(
                                R.string.here_condition_line,
                                alert.event,
                                until,
                                alert.attribution,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            when {
                sheet.loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .testTag(TestTags.HERE_LOADING),
                        strokeWidth = 2.dp,
                    )
                }
                sheet.failed -> {
                    Text(
                        text = stringResource(R.string.here_failed),
                        modifier = Modifier.testTag(TestTags.HERE_FAILED),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                groups.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.here_empty),
                        modifier = Modifier.testTag(TestTags.HERE_EMPTY),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        groups.forEach { group ->
                            val label = taxonomy?.category(group.categorySlug)?.label ?: group.categorySlug
                            Text(
                                text = label,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .testTag(TestTags.hereGroup(group.categorySlug)),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            group.drops.forEach { card ->
                                HereRow(card)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HereRow(card: DropCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(TestTags.hereRow(card.shortId)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(card.title, style = MaterialTheme.typography.titleSmall)
        val distance = card.distanceM?.let(::formatMeters)
        if (distance != null) {
            Text(
                text = stringResource(R.string.detail_away, distance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.detail_impact, card.viewCount, card.confirmCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (card.isPublicRecord()) {
            Text(
                text = stringResource(R.string.here_public),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            val credit = card.attribution?.trim().orEmpty()
            if (credit.isNotEmpty()) {
                Text(
                    text = credit,
                    modifier = Modifier.testTag(TestTags.hereAttribution(card.shortId)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val sourceUrl = card.sourceUrl
            if (!sourceUrl.isNullOrBlank()) {
                val context = LocalContext.current
                TextButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl))) },
                    modifier = Modifier.testTag(TestTags.hereSource(card.shortId)),
                ) {
                    Text(stringResource(R.string.detail_view_source))
                }
            }
        }
        val body = card.body
        if (card.revealed && !body.isNullOrBlank()) {
            Text(
                text = body,
                modifier = Modifier.testTag(TestTags.hereBody(card.shortId)),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (!card.revealed) {
            Text(
                text = stringResource(R.string.here_locked),
                modifier = Modifier.testTag(TestTags.hereBody(card.shortId)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun placeTitle(sheet: HereSheet): String {
    val name = sheet.place?.name?.trim().orEmpty()
    return if (name.isEmpty()) Here.formatSpot(sheet.spot) else name
}
