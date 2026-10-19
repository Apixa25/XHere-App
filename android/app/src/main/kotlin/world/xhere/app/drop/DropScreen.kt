package world.xhere.app.drop

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import world.xhere.app.R
import world.xhere.app.safety.SafetyFooter
import world.xhere.app.ui.TestTags
import world.xhere.shared.live.RightNowDuration

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DropScreen(
    viewModel: DropViewModel = koinViewModel(),
    capture: DropCapture = koinInject(),
    pending: PendingDropSpot = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(pending.spot, pending.label, pending.signal) {
        val spot = pending.spot
        if (spot == null) viewModel.clearPin() else viewModel.pin(spot, pending.label, pending.signal)
    }
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.shutter(context)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        viewModel.onPhoto(bytes, mime)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(TestTags.SCREEN_DROP),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.drop_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.drop_body), style = MaterialTheme.typography.bodyLarge)
        state.pinnedLabel?.let { label ->
            Text(
                text = stringResource(R.string.drop_pinned, label),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.DROP_PINNED),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (state.phase == DropPhase.CAPTURE || state.phase == DropPhase.CHOOSE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (capture.needsPermission) {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        } else {
                            viewModel.shutter(context)
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.testTag(TestTags.DROP_SHUTTER),
                ) { Text(stringResource(R.string.drop_shutter)) }
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag(TestTags.DROP_PICK),
                ) { Text(stringResource(R.string.drop_pick)) }
            }
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onText,
                label = { Text(stringResource(R.string.drop_text_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.DROP_TEXT),
            )
            if (state.phase == DropPhase.CAPTURE) {
                Button(
                    onClick = viewModel::continueWithText,
                    enabled = !state.busy,
                    modifier = Modifier.testTag(TestTags.DROP_TEXT_CONTINUE),
                ) { Text(stringResource(R.string.drop_continue)) }
            }
        }
        if (state.phase == DropPhase.CHOOSE || state.phase == DropPhase.FORM) {
            Text(stringResource(R.string.drop_ttl_label), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.selectedSlug != "right_now") {
                    FilterChip(
                        selected = state.ttl == null,
                        onClick = { viewModel.onDuration(null) },
                        label = { Text(stringResource(R.string.drop_ttl_usual)) },
                        modifier = Modifier.testTag(TestTags.DROP_TTL_USUAL),
                    )
                }
                DurationChip(RightNowDuration.ONE_HOUR, R.string.drop_ttl_1h, state.ttl, viewModel::onDuration)
                DurationChip(RightNowDuration.FOUR_HOURS, R.string.drop_ttl_4h, state.ttl, viewModel::onDuration)
                DurationChip(RightNowDuration.ONE_DAY, R.string.drop_ttl_24h, state.ttl, viewModel::onDuration)
            }
        }
        if (state.suggestions.isNotEmpty()) {
            Text(stringResource(R.string.drop_suggestions), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.suggestions.forEach { suggestion ->
                    FilterChip(
                        selected = state.selectedSlug == suggestion.slug,
                        onClick = { viewModel.choose(suggestion.slug) },
                        label = { Text(suggestion.label) },
                        modifier = Modifier.testTag(TestTags.suggestion(suggestion.slug)),
                    )
                }
            }
            OutlinedButton(
                onClick = viewModel::showAllCategories,
                modifier = Modifier.testTag(TestTags.DROP_MORE),
            ) { Text(stringResource(R.string.drop_more)) }
        }
        if (state.showAll) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.categories.sortedBy { it.label }.forEach { category ->
                    FilterChip(
                        selected = state.selectedSlug == category.slug,
                        onClick = { viewModel.choose(category.slug) },
                        label = { Text(category.label) },
                        modifier = Modifier.testTag(TestTags.category(category.slug)),
                    )
                }
            }
        }
        if (state.phase == DropPhase.FORM || (state.phase == DropPhase.CHOOSE && state.selectedSlug != null && state.fields.isNotEmpty())) {
            state.postingNotice?.let { notice ->
                Text(
                    text = notice,
                    modifier = Modifier.testTag(TestTags.DROP_NOTICE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.safety?.let { panel ->
                if (state.phase == DropPhase.FORM) {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = viewModel::onText,
                        label = { Text(stringResource(R.string.drop_text_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TestTags.DROP_TEXT),
                    )
                }
                SafetyPanelCard(panel, showReminder = state.postingNotice == null)
            }
            AttributeForm(fields = state.fields, values = state.values, onChange = viewModel::onField)
            if (state.sensitive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.guidelinesChecked,
                        onCheckedChange = viewModel::onGuidelines,
                        modifier = Modifier.testTag(TestTags.DROP_GUIDELINES),
                    )
                    Text(stringResource(R.string.drop_guidelines))
                }
            }
            Button(
                onClick = viewModel::post,
                enabled = !state.busy && state.safety?.canPost != false,
                modifier = Modifier.testTag(TestTags.DROP_POST),
            ) { Text(stringResource(R.string.drop_post)) }
            Text(
                text = stringResource(R.string.drop_agreement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.DROP_AGREEMENT),
            )
        }
        state.error?.let { message ->
            Text(
                text = message,
                modifier = Modifier.testTag(TestTags.DROP_ERROR),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.phase == DropPhase.DONE) {
            Text(
                text = state.success.orEmpty(),
                modifier = Modifier.testTag(TestTags.DROP_SUCCESS),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.held) {
                Text(
                    text = stringResource(R.string.drop_held),
                    modifier = Modifier.testTag(TestTags.DROP_SAFETY_HELD),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (state.reducedTrust) {
                Text(
                    text = stringResource(R.string.drop_reduced_trust),
                    modifier = Modifier.testTag(TestTags.DROP_REDUCED),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            state.duplicate?.let { message ->
                Text(message, modifier = Modifier.testTag(TestTags.DROP_DUPLICATE), style = MaterialTheme.typography.bodyLarge)
            }
            state.fuzz?.let { message ->
                Text(message, modifier = Modifier.testTag(TestTags.DROP_FUZZ), style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = viewModel::another, modifier = Modifier.testTag(TestTags.DROP_ANOTHER)) {
                Text(stringResource(R.string.drop_another))
            }
        }
    }
}

@Composable
private fun DurationChip(
    preset: RightNowDuration,
    label: Int,
    selected: RightNowDuration?,
    onDuration: (String) -> Unit,
) {
    FilterChip(
        selected = selected == preset,
        onClick = { onDuration(preset.id) },
        label = { Text(stringResource(label)) },
        modifier = Modifier.testTag(TestTags.dropTtl(preset.id)),
    )
}

@Composable
private fun SafetyPanelCard(panel: SafetyPanel, showReminder: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(TestTags.DROP_SAFETY_PANEL),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.safety_panel_title), style = MaterialTheme.typography.titleSmall)
            if (showReminder) {
                Text(stringResource(R.string.safety_panel_reminder), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.safety_panel_disclaimer), style = MaterialTheme.typography.bodyMedium)
            if (panel.held) {
                Text(stringResource(R.string.safety_panel_held), style = MaterialTheme.typography.bodyMedium)
            }
            if (panel.fuzzM > 0) {
                Text(stringResource(R.string.safety_panel_fuzz, panel.fuzzM), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    panel.filterMessage?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(TestTags.DROP_SAFETY_FILTER),
        )
    }
    if (!panel.available) {
        Text(
            text = stringResource(R.string.safety_no_tip_line),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(TestTags.DROP_SAFETY_UNAVAILABLE),
        )
    }
    SafetyFooter(panel.resources)
}
