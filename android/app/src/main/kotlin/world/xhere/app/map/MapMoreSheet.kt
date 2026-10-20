package world.xhere.app.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags

/**
 * Time Machine, voice, Town Hall, Accessibility, AR, and Things around you
 * live here so the map keeps Drop, Near me, recenter, and this one button.
 */
@Composable
internal fun MapMoreSheet(
    accessibilityOn: Boolean,
    townHallOn: Boolean,
    onTime: () -> Unit,
    onVoice: () -> Unit,
    onTownHall: () -> Unit,
    onAccess: () -> Unit,
    onAr: () -> Unit,
    onThings: () -> Unit,
    onLayers: () -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(onDismiss = onDismiss, tag = TestTags.MAP_MORE_SHEET) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.map_more), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.map_more_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoreRow(stringResource(R.string.time_machine), TestTags.MAP_TIME, onTime)
            MoreRow(stringResource(R.string.map_voice), TestTags.VOICE_MIC, onVoice)
            MoreRow(stringResource(R.string.map_town_hall), TestTags.MAP_TOWN_HALL, onTownHall, townHallOn)
            MoreRow(stringResource(R.string.access_layer), TestTags.MAP_ACCESS, onAccess, accessibilityOn)
            MoreRow(stringResource(R.string.map_ar), TestTags.MAP_AR, onAr)
            MoreRow(stringResource(R.string.things_around), TestTags.THINGS_AROUND, onThings)
            MoreRow(stringResource(R.string.map_layers), TestTags.MAP_LAYERS, onLayers)
        }
    }
}

@Composable
internal fun MapLayersSheet(
    layers: MapLayers,
    onAccessibility: (Boolean) -> Unit,
    onCrime: (Boolean) -> Unit,
    onSafety: (Boolean) -> Unit,
    onLive: (Boolean) -> Unit,
    onTownHall: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(onDismiss = onDismiss, tag = TestTags.MAP_LAYERS_SHEET) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.map_layers_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.map_layers_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LayerRow(stringResource(R.string.access_layer), layers.accessibility, TestTags.MAP_LAYER_ACCESS, onAccessibility)
            LayerRow(stringResource(R.string.map_layer_crime), layers.crime, TestTags.MAP_LAYER_CRIME, onCrime)
            LayerRow(stringResource(R.string.map_layer_safety), layers.safety, TestTags.MAP_LAYER_SAFETY, onSafety)
            LayerRow(stringResource(R.string.map_layer_live), layers.liveConditions, TestTags.MAP_LAYER_LIVE, onLive)
            LayerRow(stringResource(R.string.map_town_hall), layers.townHall, TestTags.MAP_LAYER_TOWN, onTownHall)
        }
    }
}

@Composable
private fun MoreRow(label: String, tag: String, onClick: () -> Unit, selected: Boolean = false) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
            .testTag(tag),
        style = MaterialTheme.typography.titleMedium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun LayerRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
