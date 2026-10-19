package world.xhere.app.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import world.xhere.app.R
import world.xhere.app.location.ForegroundLocation
import world.xhere.app.ui.TestTags
import world.xhere.app.voice.VoicePreferences
import world.xhere.app.voice.VoiceSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onGuidelines: () -> Unit = {},
    onBlocked: () -> Unit = {},
    onModeration: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
    voicePrefs: VoicePreferences = koinInject(),
    voice: VoiceSession = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hearInBackground by voicePrefs.background.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.setLocationGranted(ForegroundLocation.isGranted(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setLocationGranted(ForegroundLocation.isGranted(context))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.settings_delete_title)) },
            text = { Text(stringResource(R.string.settings_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    modifier = Modifier.testTag(TestTags.SETTINGS_DELETE_CONFIRM),
                ) {
                    Text(stringResource(R.string.settings_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissDelete,
                    modifier = Modifier.testTag(TestTags.SETTINGS_DELETE_KEEP),
                ) {
                    Text(stringResource(R.string.settings_delete_keep))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.SETTINGS_BACK)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.handle?.let { handle ->
                Text("@$handle", style = MaterialTheme.typography.titleMedium)
            }
            state.email?.let { email ->
                Text(
                    email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.leaderboardReady) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_leaderboard), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.settings_leaderboard_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.showOnLeaderboard,
                        onCheckedChange = viewModel::setShowOnLeaderboard,
                        modifier = Modifier.testTag(TestTags.SETTINGS_LEADERBOARD),
                    )
                }
            }
            Text(stringResource(R.string.settings_voice_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_voice_body), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_voice_switch), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = hearInBackground,
                    onCheckedChange = { enabled ->
                        voicePrefs.setBackground(enabled)
                        voice.setBackgroundOptIn(enabled)
                    },
                    modifier = Modifier.testTag(TestTags.SETTINGS_VOICE),
                )
            }
            Text(stringResource(R.string.settings_location_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_location_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    if (state.locationGranted) R.string.settings_location_allowed else R.string.settings_location_denied,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.settings_safety_title), style = MaterialTheme.typography.titleMedium)
            SettingsRow(R.string.settings_guidelines, TestTags.SETTINGS_GUIDELINES, onGuidelines)
            SettingsRow(R.string.settings_blocked, TestTags.SETTINGS_BLOCKED, onBlocked)
            if (state.isModerator) {
                SettingsRow(R.string.settings_moderation, TestTags.SETTINGS_MODERATION, onModeration)
            }
            Text(stringResource(R.string.settings_export_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = viewModel::exportData,
                enabled = state.export != ExportStatus.WORKING,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.SETTINGS_EXPORT),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.settings_export))
            }
            when (state.export) {
                ExportStatus.READY -> R.string.settings_export_ready
                ExportStatus.FAILED -> R.string.settings_export_failed
                else -> null
            }?.let { message ->
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.export == ExportStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.testTag(TestTags.SETTINGS_EXPORT_STATUS),
                )
            }
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(
                onClick = viewModel::signOut,
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.SETTINGS_SIGN_OUT),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.settings_sign_out))
            }
            Text(
                text = stringResource(R.string.settings_delete_export_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = viewModel::askDelete,
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.SETTINGS_DELETE),
            ) {
                Text(
                    stringResource(R.string.settings_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(label: Int, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
