package world.xhere.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import world.xhere.app.R
import world.xhere.app.location.ForegroundLocation
import world.xhere.app.ui.TestTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
                    IconButton(onClick = onBack) {
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
            Text(stringResource(R.string.settings_location_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_location_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    if (state.locationGranted) R.string.settings_location_allowed else R.string.settings_location_denied,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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
