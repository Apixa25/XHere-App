package world.xhere.app.voice

import android.app.Activity
import android.nfc.NfcAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.map.BottomSheet
import world.xhere.app.safety.ReportForm
import world.xhere.app.ui.TestTags
import world.xhere.shared.voice.DistanceBand

@Composable
fun SpeakingBanner(onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.THINGS_SPEAKING),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.things_phone_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onStop, modifier = Modifier.testTag(TestTags.THINGS_STOP)) {
                Text(stringResource(R.string.things_phone_stop))
            }
        }
    }
}

@Composable
fun ThingsAroundSheet(
    state: ThingsUiState,
    latitude: Double?,
    longitude: Double?,
    onDismiss: () -> Unit,
    onGive: () -> Unit,
    onCloseGive: () -> Unit,
    onCreate: (String, String) -> Unit,
    onSelect: (NearbyThing) -> Unit,
    onBind: (String) -> Unit,
    onSpeak: () -> Unit,
    onNfc: () -> Unit,
) {
    BottomSheet(onDismiss = onDismiss, tag = TestTags.THINGS_SHEET) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
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
                    text = stringResource(R.string.things_around),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.THINGS_CLOSE)) {
                    Text(stringResource(R.string.things_close))
                }
            }
            Text(
                text = stringResource(R.string.things_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.nearby.isEmpty()) {
                Text(
                    text = stringResource(R.string.things_around_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(TestTags.THINGS_EMPTY),
                )
            }
            state.nearby.forEach { thing ->
                TextButton(
                    onClick = { onSelect(thing) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.thingRow(thing.thing.voiceId)),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(thing.thing.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = distanceLabel(thing.band),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (!state.giving) {
                Button(
                    onClick = onGive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.THINGS_GIVE),
                ) {
                    Text(stringResource(R.string.things_give))
                }
            } else {
                GiveVoiceForm(
                    latitude = latitude,
                    longitude = longitude,
                    state = state,
                    onCreate = onCreate,
                    onClose = onCloseGive,
                    onBind = onBind,
                    onSpeak = onSpeak,
                    onNfc = onNfc,
                )
            }
            state.errorCode?.let { code ->
                Text(
                    text = errorText(code),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.THINGS_ERROR),
                )
            }
            noticeText(state.notice)?.let { text ->
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(TestTags.THINGS_NOTICE))
            }
        }
    }
}

@Composable
fun ThingStoryCard(
    thing: NearbyThing,
    reporting: Boolean,
    busy: Boolean,
    onReport: () -> Unit,
    onSendReport: (world.xhere.shared.model.ReportReason, String?) -> Unit,
    onDismissReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(onDismiss = onDismiss, tag = TestTags.THINGS_CARD) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
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
                    text = thing.thing.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.THINGS_TITLE),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.things_close))
                }
            }
            Text(
                text = distanceLabel(thing.band),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(TestTags.THINGS_DISTANCE),
            )
            thing.thing.placeName?.let { place ->
                Text(place, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = thing.thing.story,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(TestTags.THINGS_STORY),
            )
            Text(
                text = stringResource(R.string.things_seen, thing.thing.seenCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!thing.thing.isMine) {
                TextButton(onClick = onReport, modifier = Modifier.testTag(TestTags.THINGS_REPORT)) {
                    Text(stringResource(R.string.things_report))
                }
            }
            if (reporting) {
                ReportForm(
                    title = stringResource(R.string.things_report_title),
                    showAlsoHide = false,
                    sending = busy,
                    onSend = { reason, details, _ -> onSendReport(reason, details) },
                    onDismiss = onDismissReport,
                )
            }
        }
    }
}

@Composable
fun NfcWriteEffect(voiceId: String?, onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(voiceId) {
        if (voiceId == null) return@DisposableEffect onDispose { }
        val activity = context as? Activity
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        if (activity == null || adapter == null || !adapter.isEnabled) {
            onDone(false)
            return@DisposableEffect onDispose { }
        }
        adapter.enableReaderMode(
            activity,
            { tag -> onDone(NfcSticker.write(tag, voiceId)) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        onDispose { runCatching { adapter.disableReaderMode(activity) } }
    }
}

@Composable
private fun GiveVoiceForm(
    latitude: Double?,
    longitude: Double?,
    state: ThingsUiState,
    onCreate: (String, String) -> Unit,
    onClose: () -> Unit,
    onBind: (String) -> Unit,
    onSpeak: () -> Unit,
    onNfc: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var story by rememberSaveable { mutableStateOf("") }
    Text(
        text = stringResource(R.string.things_indoor),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = title,
        onValueChange = { title = it.take(80) },
        label = { Text(stringResource(R.string.things_title_hint)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.THINGS_TITLE_FIELD),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    OutlinedTextField(
        value = story,
        onValueChange = { story = it.take(2000) },
        label = { Text(stringResource(R.string.things_story_hint)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.THINGS_STORY_FIELD),
        minLines = 3,
    )
    Button(
        onClick = { onCreate(title, story) },
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.THINGS_CREATE),
    ) {
        Text(stringResource(R.string.things_create))
    }
    if (latitude == null || longitude == null) {
        Text(
            text = stringResource(R.string.things_no_pin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.created?.let { created ->
        Text(created.title, style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = onSpeak,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.THINGS_PHONE),
        ) {
            Text(stringResource(R.string.things_phone))
        }
        state.unresolved.forEach { key ->
            TextButton(
                onClick = { onBind(key) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.THINGS_BIND),
            ) {
                Text(stringResource(R.string.things_bind))
            }
        }
        TextButton(
            onClick = onNfc,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.THINGS_NFC),
        ) {
            Text(stringResource(R.string.things_nfc))
        }
        if (state.nfcWaiting) {
            Text(
                text = stringResource(R.string.things_nfc_waiting),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    TextButton(onClick = onClose) {
        Text(stringResource(R.string.things_close))
    }
}

@Composable
private fun distanceLabel(band: DistanceBand?): String = when (band) {
    DistanceBand.IMMEDIATE -> stringResource(R.string.things_distance_immediate)
    DistanceBand.NEAR -> stringResource(R.string.things_distance_near)
    DistanceBand.FAR -> stringResource(R.string.things_distance_far)
    null -> stringResource(R.string.things_distance_link)
}

@Composable
private fun errorText(code: String): String = when (code) {
    "category_not_allowed" -> stringResource(R.string.things_error_category)
    "invalid_story", "invalid_title" -> stringResource(R.string.things_error_story)
    "rate_limited" -> stringResource(R.string.things_error_rate)
    "not_owner" -> stringResource(R.string.things_error_owner)
    else -> stringResource(R.string.things_error)
}

@Composable
private fun noticeText(notice: String?): String? = when (notice) {
    "created" -> stringResource(R.string.things_created)
    "bound" -> stringResource(R.string.things_bound)
    "reported" -> stringResource(R.string.things_report_sent)
    "nfc" -> stringResource(R.string.things_nfc_done)
    "nfc_unavailable" -> stringResource(R.string.things_nfc_unavailable)
    else -> null
}
