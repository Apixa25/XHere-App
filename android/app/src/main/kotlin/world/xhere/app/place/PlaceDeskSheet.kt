package world.xhere.app.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.koin.androidx.compose.koinViewModel
import world.xhere.app.R
import world.xhere.app.map.BottomSheet
import world.xhere.app.ui.TestTags
import world.xhere.shared.capsule.OralHistory
import world.xhere.shared.geo.LatLng

internal sealed class DeskRequest {
    data class Claim(val spot: LatLng, val anchorDropId: Uuid?) : DeskRequest()
    data class Memory(val spot: LatLng, val oral: Boolean) : DeskRequest()
}

@Composable
internal fun PlaceDeskSheet(
    request: DeskRequest,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PlaceDeskViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(request) { viewModel.reset() }
    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }
    when (request) {
        is DeskRequest.Claim -> ClaimSheet(request, state, viewModel, onDismiss)
        is DeskRequest.Memory -> MemorySheet(request, state, viewModel, onDismiss)
    }
}

@Composable
private fun ClaimSheet(
    request: DeskRequest.Claim,
    state: PlaceDeskUi,
    viewModel: PlaceDeskViewModel,
    onDismiss: () -> Unit,
) {
    var name by remember(request) { mutableStateOf("") }
    var website by remember(request) { mutableStateOf("") }
    BottomSheet(onDismiss = onDismiss, tag = TestTags.CLAIM_SHEET) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.claim_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.claim_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.claim_name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.CLAIM_NAME),
                singleLine = true,
            )
            OutlinedTextField(
                value = website,
                onValueChange = { website = it },
                label = { Text(stringResource(R.string.claim_website)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.CLAIM_WEBSITE),
                singleLine = true,
            )
            val site = website.trim().ifEmpty { null }
            Button(
                onClick = { viewModel.claim(request.spot, name, site, request.anchorDropId, "email") },
                enabled = !state.busy && (request.anchorDropId != null || name.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.CLAIM_EMAIL),
            ) {
                Text(stringResource(R.string.claim_email))
            }
            OutlinedButton(
                onClick = { viewModel.claim(request.spot, name, site, request.anchorDropId, "postcard") },
                enabled = !state.busy && (request.anchorDropId != null || name.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.CLAIM_POSTCARD),
            ) {
                Text(stringResource(R.string.claim_postcard))
            }
            TextButton(
                onClick = { viewModel.claim(request.spot, name, site, request.anchorDropId, "review") },
                enabled = !state.busy && (request.anchorDropId != null || name.isNotBlank()),
                modifier = Modifier.testTag(TestTags.CLAIM_REVIEW),
            ) {
                Text(stringResource(R.string.claim_review))
            }
            state.postcard?.let {
                Text(
                    text = stringResource(R.string.claim_postcard_pending),
                    modifier = Modifier.testTag(TestTags.CLAIM_POSTCARD_NOTE),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            state.error?.let { code ->
                Text(code, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.CLAIM_ERROR))
            }
        }
    }
}

@Composable
private fun MemorySheet(
    request: DeskRequest.Memory,
    state: PlaceDeskUi,
    viewModel: PlaceDeskViewModel,
    onDismiss: () -> Unit,
) {
    var title by remember(request) { mutableStateOf(if (request.oral) "A story from this place" else "") }
    var body by remember(request) { mutableStateOf("") }
    var mode by remember(request) { mutableStateOf("visit") }
    var date by remember(request) { mutableStateOf("") }
    BottomSheet(onDismiss = onDismiss, tag = TestTags.CAPSULE_SHEET) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(if (request.oral) R.string.capsule_oral_title else R.string.capsule_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.capsule_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (request.oral) {
                Text(
                    text = stringResource(R.string.capsule_prompts),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(TestTags.CAPSULE_PROMPTS),
                )
                OralHistory.questions.forEach { question ->
                    Text(question, style = MaterialTheme.typography.bodyLarge)
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.capsule_title_field)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.CAPSULE_TITLE),
                singleLine = true,
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.capsule_body)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.CAPSULE_BODY),
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("visit", R.string.capsule_mode_visit, mode, TestTags.CAPSULE_MODE_VISIT) { mode = it }
                ModeChip("date", R.string.capsule_mode_date, mode, TestTags.CAPSULE_MODE_DATE) { mode = it }
                ModeChip("both", R.string.capsule_mode_both, mode, TestTags.CAPSULE_MODE_BOTH) { mode = it }
            }
            if (mode != "visit") {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text(stringResource(R.string.capsule_date)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.CAPSULE_DATE),
                    singleLine = true,
                )
            }
            Button(
                onClick = {
                    viewModel.leaveMemory(
                        spot = request.spot,
                        title = title,
                        body = body,
                        mode = mode,
                        oral = request.oral,
                        unlockAt = date.trim().takeIf { it.isNotEmpty() }?.let { raw ->
                            runCatching { Instant.parse("${raw}T00:00:00Z") }.getOrNull()
                        },
                    )
                },
                enabled = !state.busy && title.isNotBlank() && body.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.CAPSULE_SAVE),
            ) {
                Text(stringResource(R.string.capsule_save))
            }
            state.error?.let { code ->
                Text(code, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.CAPSULE_ERROR))
            }
        }
    }
}

@Composable
private fun ModeChip(value: String, label: Int, selected: String, tag: String, onPick: (String) -> Unit) {
    val button: @Composable () -> Unit = {
        Text(stringResource(label))
    }
    if (selected == value) {
        Button(onClick = { onPick(value) }, modifier = Modifier.testTag(tag), content = { button() })
    } else {
        OutlinedButton(onClick = { onPick(value) }, modifier = Modifier.testTag(tag), content = { button() })
    }
}
