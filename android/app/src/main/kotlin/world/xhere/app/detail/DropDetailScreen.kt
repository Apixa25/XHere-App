package world.xhere.app.detail

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
import world.xhere.app.ui.TestTags
import world.xhere.shared.geo.Geo
import world.xhere.shared.model.ConfirmationVerdict

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
    }
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
    Text(
        text = state.title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.testTag(TestTags.DETAIL_TITLE),
    )
    state.authorHandle?.let { handle ->
        Text(
            text = stringResource(R.string.detail_author, handle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    if (state.isMine) {
        Text(stringResource(R.string.detail_yours), style = MaterialTheme.typography.bodyLarge)
    } else if (state.canAnswer) {
        Text(
            text = stringResource(R.string.detail_still_prompt),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStillTrue,
                enabled = !state.answering,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.DETAIL_STILL_TRUE),
            ) {
                Text(stringResource(R.string.detail_still_true))
            }
            OutlinedButton(
                onClick = onNotTrue,
                enabled = !state.answering,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.DETAIL_NOT_TRUE),
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
                text = if (gap != null && gap > 0) {
                    stringResource(R.string.detail_walk_closer, gap)
                } else {
                    stringResource(R.string.detail_locked)
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
}
