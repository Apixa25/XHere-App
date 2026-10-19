package world.xhere.app.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.model.CategoryGroup

@Composable
internal fun CategoryChipBar(
    groups: List<CategoryGroup>,
    presets: List<FilterPreset>,
    selection: FilterSelection,
    onSelectAll: () -> Unit,
    onSelectPreset: (FilterPreset) -> Unit,
    onSelectGroup: (String) -> Unit,
    onLongPressGroup: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(TestTags.CHIP_BAR),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapChip(
            text = stringResource(R.string.map_filter_all),
            selected = selection.slugs == null,
            tag = TestTags.CHIP_ALL,
            onClick = onSelectAll,
        )
        presets.forEach { preset ->
            MapChip(
                text = preset.name,
                selected = selection.activePreset == preset.name,
                tag = TestTags.preset(preset.name),
                onClick = { onSelectPreset(preset) },
            )
        }
        groups.forEach { group ->
            val label = listOfNotNull(group.icon, group.label).joinToString(" ")
            MapChip(
                text = label,
                selected = selection.activeGroup == group.slug && selection.activePreset == null,
                tag = TestTags.group(group.slug),
                description = group.label,
                onClick = { onSelectGroup(group.slug) },
                onLongClick = { onLongPressGroup(group.slug) },
            )
        }
        if (!selection.slugs.isNullOrEmpty()) {
            MapChip(
                text = stringResource(R.string.map_filter_save),
                selected = false,
                tag = TestTags.SAVE_PRESET,
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun MapChip(
    text: String,
    selected: Boolean,
    tag: String,
    description: String = text,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .testTag(tag)
            .semantics { contentDescription = description }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        ),
        shadowElevation = 2.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
