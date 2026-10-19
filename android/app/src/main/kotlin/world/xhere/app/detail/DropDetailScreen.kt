package world.xhere.app.detail

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import kotlin.time.Instant
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import world.xhere.app.R
import world.xhere.app.location.ForegroundLocation
import world.xhere.app.map.formatMeters
import world.xhere.app.safety.BlockDialog
import world.xhere.app.safety.ReportForm
import world.xhere.app.safety.SafetyFooter
import world.xhere.app.ui.TestTags
import world.xhere.shared.geo.Geo
import world.xhere.shared.model.ConfirmationVerdict
import world.xhere.shared.model.ReportReason

/** `yes` while this screen is collecting foreground location fixes. */
internal val DetailListeningSemantics = SemanticsPropertyKey<String>("DetailListening")

@Composable
fun DropDetailScreen(
    shortId: String,
    onBack: () -> Unit,
    viewModel: DropDetailViewModel = koinViewModel { parametersOf(shortId) },
    sharer: DropSharer = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        viewModel.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
        }
    }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) viewModel.refreshLocation()
    }
    if (state.dialog == SafetyDialog.BLOCK_AUTHOR) {
        BlockDialog(
            handle = state.authorHandle.orEmpty(),
            onConfirm = viewModel::blockAuthor,
            onDismiss = viewModel::dismissDialog,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag(TestTags.SCREEN_DETAIL)
            .semantics {
                set(DetailListeningSemantics, if (state.listening) "yes" else "no")
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.DETAIL_BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        when {
            state.loading -> {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge)
            }
            state.gone -> {
                SafetyMessageText(state)
                if (state.canUndoHide) {
                    OutlinedButton(onClick = viewModel::undoHide, modifier = Modifier.testTag(TestTags.DETAIL_UNDO_HIDE)) {
                        Text(stringResource(R.string.safety_undo))
                    }
                }
            }
            state.missing -> {
                Text(
                    text = stringResource(R.string.detail_missing),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag(TestTags.DETAIL_TITLE),
                )
            }
            else -> DetailBody(
                state = state,
                onStillTrue = { viewModel.confirm(ConfirmationVerdict.STILL_TRUE) },
                onNotTrue = { viewModel.confirm(ConfirmationVerdict.NOT_TRUE) },
                onShare = { sharer.share(state.shareUrl) },
                onUseLocation = { permission.launch(ForegroundLocation.permissions) },
            )
        }
        if (!state.loading && !state.missing && !state.gone) {
            SafetyActions(
                state = state,
                onDialog = viewModel::openDialog,
                onHide = viewModel::hideDrop,
                onReportDrop = viewModel::reportDrop,
                onReportAuthor = viewModel::reportAuthor,
                onCancel = viewModel::dismissDialog,
            )
        }
    }
}

@Composable
private fun SafetyActions(
    state: DetailUiState,
    onDialog: (SafetyDialog) -> Unit,
    onHide: () -> Unit,
    onReportDrop: (ReportReason, String?, Boolean) -> Unit,
    onReportAuthor: (ReportReason, String?) -> Unit,
    onCancel: () -> Unit,
) {
    SafetyMessageText(state)
    state.safetyResources?.let { SafetyFooter(it) }
    if (state.isMine) return
    when (state.dialog) {
        SafetyDialog.REPORT_DROP -> {
            ReportForm(
                title = stringResource(R.string.safety_report_title),
                showAlsoHide = true,
                sending = state.sending,
                onSend = onReportDrop,
                onDismiss = onCancel,
            )
            return
        }
        SafetyDialog.REPORT_AUTHOR -> {
            ReportForm(
                title = stringResource(R.string.safety_report_user_title, state.authorHandle.orEmpty()),
                showAlsoHide = false,
                sending = state.sending,
                onSend = { reason, details, _ -> onReportAuthor(reason, details) },
                onDismiss = onCancel,
            )
            return
        }
        else -> Unit
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { onDialog(SafetyDialog.REPORT_DROP) },
            enabled = !state.sending,
            modifier = Modifier.testTag(TestTags.DETAIL_REPORT),
        ) {
            Text(stringResource(R.string.safety_report_drop))
        }
        TextButton(
            onClick = onHide,
            enabled = !state.sending,
            modifier = Modifier.testTag(TestTags.DETAIL_HIDE),
        ) {
            Text(stringResource(R.string.safety_hide_drop))
        }
    }
    val handle = state.authorHandle ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { onDialog(SafetyDialog.REPORT_AUTHOR) },
            enabled = !state.sending,
            modifier = Modifier.testTag(TestTags.DETAIL_REPORT_AUTHOR),
        ) {
            Text(stringResource(R.string.safety_report_user, handle))
        }
        TextButton(
            onClick = { onDialog(SafetyDialog.BLOCK_AUTHOR) },
            enabled = !state.sending,
            modifier = Modifier.testTag(TestTags.DETAIL_BLOCK_AUTHOR),
        ) {
            Text(stringResource(R.string.safety_block_user, handle), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SafetyMessageText(state: DetailUiState) {
    val message = state.safetyMessage ?: return
    Text(
        text = when (message) {
            SafetyMessage.REPORTED -> stringResource(R.string.safety_report_sent)
            SafetyMessage.REPORTED_HIDDEN -> stringResource(R.string.safety_report_sent_hidden)
            SafetyMessage.HIDDEN -> stringResource(R.string.safety_hidden)
            SafetyMessage.BLOCKED -> stringResource(R.string.safety_blocked, state.authorHandle.orEmpty())
            SafetyMessage.FAILED -> stringResource(R.string.safety_failed)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = if (message == SafetyMessage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(TestTags.DETAIL_SAFETY_MESSAGE),
    )
}

@Composable
private fun DetailBody(
    state: DetailUiState,
    onStillTrue: () -> Unit,
    onNotTrue: () -> Unit,
    onShare: () -> Unit,
    onUseLocation: () -> Unit,
) {
    Text(state.icon, style = MaterialTheme.typography.headlineLarge)
    Text(
        text = state.categoryLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(TestTags.DETAIL_CATEGORY),
    )
    if (state.publicRecord) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Text(
                text = stringResource(R.string.detail_public_records),
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(TestTags.DETAIL_PUBLIC),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
    Text(
        text = state.title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.testTag(TestTags.DETAIL_TITLE),
    )
    if (!state.publicRecord) {
        Text(
            text = stringResource(R.string.detail_impact, state.viewCount, state.confirmCount),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag(TestTags.DETAIL_IMPACT),
        )
    }
    state.authorHandle?.let { handle ->
        Text(
            text = stringResource(R.string.detail_author, handle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.authorBadges.forEach { badge ->
        Text(
            text = "${badge.icon} ${badge.name}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.testTag(TestTags.DETAIL_FOUNDING),
        )
    }
    if (!state.publicRecord) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.isConfirmed) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.detail_confirmed),
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag(TestTags.DETAIL_BADGE),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = stringResource(R.string.detail_progress, state.confirmCount, state.confirmBar),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(TestTags.DETAIL_PROGRESS),
            )
        }
        if (state.denyCount > 0) {
            Text(
                text = stringResource(R.string.detail_denies, state.denyCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = freshnessText(state.lastConfirmedAt, Clock.System.now()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.DETAIL_FRESHNESS),
        )
    }
    if (state.approximate) {
        Text(
            text = stringResource(R.string.map_teaser_approximate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.distanceM?.let { meters ->
        Text(
            text = stringResource(R.string.detail_away, formatMeters(meters)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(TestTags.DETAIL_DISTANCE),
        )
    }
    val body = state.body
    if (state.showBody && !body.isNullOrBlank()) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag(TestTags.DETAIL_BODY),
        )
    } else {
        LockedCard(state = state, onUseLocation = onUseLocation)
    }
    if (state.publicRecord && !state.attribution.isNullOrBlank()) {
        Text(
            text = state.attribution,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.DETAIL_ATTRIBUTION),
        )
    }
    if (state.publicRecord && !state.license.isNullOrBlank() && state.license != state.attribution) {
        Text(
            text = state.license,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val sourceUrl = state.sourceUrl
    if (state.publicRecord && !sourceUrl.isNullOrBlank()) {
        val context = LocalContext.current
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
            },
            modifier = Modifier.testTag(TestTags.DETAIL_SOURCE),
        ) {
            Text(stringResource(R.string.detail_view_source))
        }
    }
    if (state.isMine) {
        Text(stringResource(R.string.detail_yours), style = MaterialTheme.typography.bodyLarge)
    } else {
        if (state.youConfirmed) {
            Text(
                text = stringResource(R.string.detail_you_confirmed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(TestTags.DETAIL_YOU_CONFIRMED),
            )
        } else if (state.canAnswer) {
            Text(
                text = stringResource(R.string.detail_still_prompt),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                onClick = onStillTrue,
                enabled = !state.answering,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.DETAIL_STILL_TRUE),
            ) {
                Text(stringResource(R.string.detail_still_true))
            }
        }
        if (state.canAnswer) {
            TextButton(
                onClick = onNotTrue,
                enabled = !state.answering,
                modifier = Modifier.testTag(TestTags.DETAIL_NOT_TRUE),
            ) {
                Text(stringResource(R.string.detail_not_true))
            }
        }
    }
    state.notice?.let { notice ->
        Text(
            text = noticeText(notice),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(TestTags.DETAIL_NOTICE),
        )
    }
    OutlinedButton(
        onClick = onShare,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.DETAIL_SHARE),
    ) {
        Icon(Icons.Filled.Share, contentDescription = null)
        Text(stringResource(R.string.detail_share), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LockedCard(state: DetailUiState, onUseLocation: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val gap = state.distanceM?.let { Geo.revealGapMeters(it, state.revealRadiusM) }
            Text(
                text = when {
                    state.locationWithheld -> stringResource(R.string.detail_location_withheld)
                    gap != null && gap > 0 -> stringResource(R.string.detail_walk_closer, gap)
                    else -> stringResource(R.string.detail_locked)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag(TestTags.DETAIL_LOCKED),
            )
            Text(
                text = stringResource(R.string.detail_within, state.revealRadiusM),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (state.locationOff) {
                Text(
                    text = stringResource(R.string.detail_location_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Button(onClick = onUseLocation, modifier = Modifier.testTag(TestTags.DETAIL_LOCATION)) {
                    Text(stringResource(R.string.detail_use_location))
                }
            }
        }
    }
}

@Composable
private fun freshnessText(at: Instant?, now: Instant): String {
    if (at == null) return stringResource(R.string.detail_fresh_never)
    val elapsed = now - at
    val seconds = elapsed.inWholeSeconds
    if (seconds < 45) return stringResource(R.string.detail_fresh_now)
    val minutes = elapsed.inWholeMinutes
    if (minutes < 60) return stringResource(R.string.detail_fresh_min, minutes)
    val hours = elapsed.inWholeHours
    if (hours < 24) {
        return if (hours == 1L) {
            stringResource(R.string.detail_fresh_hour_one)
        } else {
            stringResource(R.string.detail_fresh_hour, hours)
        }
    }
    val days = elapsed.inWholeDays
    return if (days == 1L) {
        stringResource(R.string.detail_fresh_day_one)
    } else {
        stringResource(R.string.detail_fresh_day, days)
    }
}

@Composable
private fun noticeText(notice: DetailNotice): String = when (notice) {
    DetailNotice.TOO_FAR -> stringResource(R.string.detail_too_far)
    DetailNotice.RATE_LIMITED -> stringResource(R.string.detail_rate_limited)
    DetailNotice.UNAVAILABLE -> stringResource(R.string.detail_unavailable)
    DetailNotice.FAILED -> stringResource(R.string.detail_confirm_failed)
    DetailNotice.NOT_COUNTED -> stringResource(R.string.detail_not_counted)
}
