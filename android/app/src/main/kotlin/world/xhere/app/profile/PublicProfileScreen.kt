package world.xhere.app.profile

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import world.xhere.app.R
import world.xhere.app.reputation.TierChip
import world.xhere.app.safety.ReportForm
import world.xhere.app.ui.TestTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicProfileScreen(
    handle: String,
    onBack: () -> Unit,
    onOpenDrop: (String) -> Unit,
    viewModel: PublicProfileViewModel = koinViewModel { parametersOf(handle) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_public_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp)
                .testTag(TestTags.SCREEN_PUBLIC_PROFILE),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) {
                CircularProgressIndicator()
                return@Column
            }
            if (state.missing) {
                Text(
                    text = stringResource(R.string.profile_public_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(TestTags.PUBLIC_MISSING),
                )
                return@Column
            }
            val name = state.name.ifBlank { stringResource(R.string.detail_neighbor) }
            ProfileAvatar(url = state.avatarUrl, label = name, size = 96.dp, tag = TestTags.PUBLIC_AVATAR)
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(TestTags.PUBLIC_NAME),
            )
            if (state.showHandle) {
                Text("@${state.handle}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (state.bio.isNotBlank()) {
                Text(state.bio, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TestTags.PUBLIC_BIO))
            }
            if (state.homeArea.isNotBlank()) {
                Text(state.homeArea, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.website.isNotBlank()) {
                Text(state.website, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (state.tierLabel.isNotBlank()) {
                TierChip(label = state.tierLabel, tag = TestTags.PUBLIC_TIER)
            }
            state.badges.forEach { badge ->
                Text(
                    text = "${badge.icon} ${badge.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag(TestTags.PUBLIC_BADGE),
                )
            }
            Text(
                text = pluralStringResource(R.plurals.profile_public_drops, state.dropCount, state.dropCount),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(TestTags.PUBLIC_DROP_COUNT),
            )
            state.drops.forEach { drop ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDrop(drop.shortId) }
                        .testTag(TestTags.publicDrop(drop.shortId)),
                ) {
                    Text(drop.title, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (!state.mine) {
                TextButton(onClick = viewModel::toggleReport, modifier = Modifier.testTag(TestTags.PUBLIC_REPORT)) {
                    Text(stringResource(R.string.safety_report_user, state.handle))
                }
            }
            if (state.reporting) {
                ReportForm(
                    title = stringResource(R.string.safety_report_user_title, state.handle),
                    showAlsoHide = false,
                    sending = state.sending,
                    onSend = { reason, details, _ -> viewModel.report(reason, details) },
                    onDismiss = viewModel::toggleReport,
                )
            }
            if (state.reported) {
                Text(
                    text = stringResource(R.string.safety_report_sent),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(TestTags.PUBLIC_REPORTED),
                )
            }
            if (state.failed) {
                Text(stringResource(R.string.profile_edit_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
