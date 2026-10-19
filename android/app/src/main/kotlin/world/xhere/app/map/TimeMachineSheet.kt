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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.history.History
import world.xhere.shared.history.HistoryPhoto
import world.xhere.shared.history.HistorySheet

internal val TimeSemantics = SemanticsPropertyKey<String>("TimeMachine")

/**
 * Year slider for the cached Sanborn sheets and photos at one spot.
 * A sheet is drawn on the map only when [TimeMachineUi.overlay] is set.
 */
@Composable
internal fun TimeMachineSheet(
    ui: TimeMachineUi,
    onYear: (Int) -> Unit,
    onSheet: (String) -> Unit,
    onOpenViewer: (String) -> Unit,
    onOpacity: (Float) -> Unit,
    onCloseViewer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BottomSheet(
        onDismiss = onDismiss,
        tag = TestTags.TIME_SHEET,
        semantics = { set(TimeSemantics, describeTime(ui)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(R.string.time_machine),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.TIME_CLOSE)) {
                    Text(stringResource(R.string.here_close))
                }
            }
            when {
                ui.loading -> CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp), strokeWidth = 2.dp)
                ui.failed -> Text(
                    text = stringResource(R.string.time_failed),
                    modifier = Modifier.testTag(TestTags.TIME_FAILED),
                    style = MaterialTheme.typography.bodyLarge,
                )
                !ui.place.hasAnything -> Text(
                    text = stringResource(R.string.time_empty),
                    modifier = Modifier.testTag(TestTags.TIME_EMPTY),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> {
                    if (ui.eras.size >= 2) {
                        Text(stringResource(R.string.time_year, ui.year ?: ui.eras.last()), style = MaterialTheme.typography.titleMedium)
                        val index = ui.eras.indexOf(ui.year).coerceAtLeast(0)
                        Slider(
                            value = index.toFloat(),
                            onValueChange = { raw ->
                                val next = ui.eras[raw.toInt().coerceIn(0, ui.eras.lastIndex)]
                                if (next != ui.year) onYear(next)
                            },
                            valueRange = 0f..ui.eras.lastIndex.toFloat(),
                            steps = (ui.eras.size - 2).coerceAtLeast(0),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ui.eras.forEach { year ->
                            TextButton(
                                onClick = { onYear(year) },
                                modifier = Modifier.testTag(TestTags.timeYear(year)),
                            ) {
                                Text(year.toString())
                            }
                        }
                    }
                    ui.sheets.forEach { sheet ->
                        SheetRow(
                            sheet = sheet,
                            selected = sheet.id == ui.sheetId,
                            onSelect = { onSheet(sheet.id) },
                            onOpen = {
                                if (sheet.iiifService.isNullOrBlank()) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sheet.itemUrl)))
                                } else {
                                    onOpenViewer(sheet.id)
                                }
                            },
                        )
                    }
                    val overlay = ui.overlay
                    if (overlay != null) {
                        Text(stringResource(R.string.time_opacity), style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = ui.opacity,
                            onValueChange = onOpacity,
                            valueRange = 0.15f..0.9f,
                            modifier = Modifier.testTag(TestTags.TIME_OPACITY),
                        )
                        Text(
                            text = overlay.attribution,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (ui.sheets.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.time_not_lined_up),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ui.photos.forEach { photo -> PhotoRow(photo) }
                    Text(
                        text = History.SANBORN_ATTRIBUTION,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (ui.viewerService != null) {
        IiifViewer(
            service = ui.viewerService,
            title = ui.viewerTitle,
            year = ui.viewerYear,
            onDismiss = onCloseViewer,
        )
    }
}

@Composable
private fun SheetRow(
    sheet: HistorySheet,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.timeSheet(sheet.id))) {
        Text(sheet.title, style = MaterialTheme.typography.titleSmall)
        val whenLabel = sheet.dateLabel.ifBlank { sheet.year?.toString().orEmpty() }
        if (whenLabel.isNotEmpty()) {
            Text(whenLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(sheet.attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = onSelect) { Text(stringResource(if (selected) R.string.time_on_map else R.string.time_show_on_map)) }
            TextButton(onClick = onOpen, modifier = Modifier.testTag(TestTags.timeSheet(sheet.id) + "_open")) {
                Text(stringResource(if (sheet.iiifService.isNullOrBlank()) R.string.time_open_loc else R.string.time_open_viewer))
            }
        }
    }
}

@Composable
private fun PhotoRow(photo: HistoryPhoto) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(photo.title, style = MaterialTheme.typography.titleSmall)
        val bits = listOfNotNull(photo.dateLabel.ifBlank { null }, photo.license)
        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(photo.attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.pageUrl))) }) {
            Text(stringResource(R.string.time_open_photo))
        }
    }
}
