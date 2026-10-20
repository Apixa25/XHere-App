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
import world.xhere.app.access.AccessStatusChip
import world.xhere.app.media.SignedListImage
import world.xhere.app.media.listPhoto
import world.xhere.app.contribution.CommunitySection
import world.xhere.app.reputation.TierChip
import world.xhere.shared.contribution.ContributionTarget
import world.xhere.shared.share.ShareLinks
import world.xhere.shared.data.XHereServices
import world.xhere.app.ui.TestTags
import world.xhere.shared.capsule.Capsules
import world.xhere.shared.civic.Civic
import world.xhere.shared.civic.CivicItem
import world.xhere.shared.model.OfficialPlace
import world.xhere.shared.place.PlaceClaims
import world.xhere.shared.reputation.Reputation
import world.xhere.shared.here.Here
import world.xhere.shared.here.isPublicRecord
import world.xhere.shared.live.NwsAlerts
import world.xhere.shared.live.WeatherAlert
import world.xhere.shared.history.Clipping
import world.xhere.shared.history.History
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
    onTimeMachine: () -> Unit,
    onAccess: () -> Unit = {},
    onClaim: () -> Unit = {},
    onMemory: () -> Unit = {},
    onOral: () -> Unit = {},
    onFollow: () -> Unit = {},
    onDismiss: () -> Unit,
    services: XHereServices? = null,
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
            sheet.official?.let { official -> OfficialBanner(official) }
            Button(
                onClick = onDropHere,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.HERE_DROP),
            ) {
                Text(stringResource(R.string.drop_fab))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onTimeMachine,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.HERE_TIME),
                ) {
                    Text(stringResource(R.string.time_machine))
                }
                TextButton(
                    onClick = onAccess,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.ACCESS_CHECK),
                ) {
                    Text(stringResource(R.string.access_check))
                }
            }
            if (!sheet.loading) {
                sheet.access?.let { AccessStatusChip(it) }
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
                    if (sheet.clippings.isNotEmpty()) ThisDayHere(sheet.clippings)
                    ComingUp(sheet.civic, onFollow, services)
                    PlaceActions(onClaim, onMemory, onOral)
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
                                HereRow(card, services)
                            }
                        }
                        if (sheet.clippings.isNotEmpty()) ThisDayHere(sheet.clippings)
                        ComingUp(sheet.civic, onFollow, services)
                        PlaceActions(onClaim, onMemory, onOral)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComingUp(items: List<CivicItem>, onFollow: () -> Unit, services: XHereServices? = null) {
    if (items.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(TestTags.HERE_CIVIC),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.here_civic_title),
            style = MaterialTheme.typography.titleMedium,
        )
        items.take(3).forEach { item ->
            val whenLabel = item.occursOn ?: item.bodyName.orEmpty()
            Text(
                text = "${Civic.icon(item.itemType)} ${item.title}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (whenLabel.isNotBlank()) {
                Text(
                    text = whenLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = item.attribution,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val context = LocalContext.current
            TextButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl))) },
            ) {
                Text(stringResource(R.string.detail_view_source))
            }
            if (services != null) {
                CommunitySection(
                    targetKind = ContributionTarget.CIVIC,
                    targetId = item.id,
                    services = services,
                )
            }
        }
        val address = items.firstOrNull { !it.addressText.isNullOrBlank() }?.addressText
        if (!address.isNullOrBlank()) {
            TextButton(
                onClick = onFollow,
                modifier = Modifier.testTag(TestTags.HERE_CIVIC_FOLLOW),
            ) {
                Text(stringResource(R.string.here_civic_follow))
            }
        }
    }
}

@Composable
private fun HereRow(card: DropCard, services: XHereServices? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(TestTags.hereRow(card.shortId)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val photo = card.listPhoto()
        if (services != null && photo != null) {
            SignedListImage(
                services = services,
                bucket = photo.first,
                path = photo.second,
                description = card.title,
                size = 96.dp,
            )
        }
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
        val shareContext = LocalContext.current
        TextButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, ShareLinks.url(card.shortId))
                }
                shareContext.startActivity(Intent.createChooser(send, null))
            },
            modifier = Modifier.testTag(TestTags.hereShare(card.shortId)),
        ) {
            Text(stringResource(R.string.detail_share))
        }
        if (!card.isPublicRecord()) {
            card.authorTier?.let { tier ->
                TierChip(label = Reputation.label(tier), tag = TestTags.TIER_CHIP + "_" + card.shortId)
            }
            card.authorBadges.firstOrNull()?.let { badge ->
                Text(
                    text = "${badge.icon} ${badge.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
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
                text = if (card.capsuleLocked) {
                    Capsules.condition(card.capsuleMode, card.capsuleUnlockAt, card.capsuleOpenedAt)
                } else {
                    stringResource(R.string.here_locked)
                },
                modifier = Modifier.testTag(TestTags.hereBody(card.shortId)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (card.official) {
            Text(
                text = PlaceClaims.BADGE,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (services != null) {
            CommunitySection(
                targetKind = ContributionTarget.DROP,
                targetId = card.id,
                services = services,
            )
        }
    }
}

@Composable
private fun ThisDayHere(clippings: List<Clipping>) {
    val today = java.time.LocalDate.now()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.HERE_THIS_DAY),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.this_day_here),
            style = MaterialTheme.typography.titleMedium,
        )
        clippings.forEach { clipping ->
            val context = LocalContext.current
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = clipping.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val whenLabel = if (History.onThisDate(clipping, today.monthValue, today.dayOfMonth)) {
                    stringResource(R.string.this_day_on_this_date, clipping.dateLabel, clipping.paper)
                } else {
                    stringResource(R.string.this_day_other_date, clipping.dateLabel, clipping.paper)
                }
                Text(
                    text = whenLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = clipping.attribution,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(clipping.pageUrl)))
                    },
                    modifier = Modifier.testTag(TestTags.hereClipping(clipping.id)),
                ) {
                    Text(stringResource(R.string.this_day_open))
                }
            }
        }
    }
}

/** Claim and memory actions sit under the drops so the first rows stay on screen. */
@Composable
private fun PlaceActions(
    onClaim: () -> Unit,
    onMemory: () -> Unit,
    onOral: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = onClaim,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.HERE_CLAIM),
        ) {
            Text(stringResource(R.string.here_claim))
        }
        TextButton(
            onClick = onMemory,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.HERE_MEMORY),
        ) {
            Text(stringResource(R.string.here_memory))
        }
    }
    TextButton(
        onClick = onOral,
        modifier = Modifier.testTag(TestTags.HERE_ORAL),
    ) {
        Text(stringResource(R.string.here_oral))
    }
}

@Composable
private fun OfficialBanner(official: OfficialPlace) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.HERE_OFFICIAL),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.official_badge, official.badge, official.name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        official.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(summary, style = MaterialTheme.typography.bodyLarge)
        }
        if (official.hours.isNotEmpty()) {
            val names = listOf(
                stringResource(R.string.hours_mon),
                stringResource(R.string.hours_tue),
                stringResource(R.string.hours_wed),
                stringResource(R.string.hours_thu),
                stringResource(R.string.hours_fri),
                stringResource(R.string.hours_sat),
                stringResource(R.string.hours_sun),
            )
            official.hours.forEach { row ->
                val day = names.getOrNull(row.day - 1) ?: row.day.toString()
                Text("$day ${row.open}–${row.close}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun placeTitle(sheet: HereSheet): String {
    val name = sheet.place?.name?.trim().orEmpty()
    return if (name.isEmpty()) Here.formatSpot(sheet.spot) else name
}
