package world.xhere.app.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.model.Category
import world.xhere.shared.model.DropCard

@Composable
internal fun NearMeSheet(
    cards: List<DropCard>,
    onOpen: (DropCard) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        onDismiss = onDismiss,
        tag = TestTags.NEAR_ME_LIST,
        semantics = { set(NearMeOrderSemantics, describeNearMe(cards)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.map_near_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.map_near_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cards.isEmpty()) {
                Text(
                    stringResource(R.string.map_near_empty),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    cards.forEach { card ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(card) }
                                .padding(vertical = 12.dp)
                                .testTag(TestTags.nearRow(card.shortId)),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(card.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    card.categorySlug,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.detail_impact, card.viewCount, card.confirmCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            card.distanceM?.let { meters ->
                                Text(formatMeters(meters), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubcategorySheet(
    groupLabel: String,
    categories: List<Category>,
    initiallySelected: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    BottomSheet(onDismiss = onDismiss, tag = TestTags.SUBCATEGORY_SHEET) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(groupLabel, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.map_subcategory_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            categories.forEach { category ->
                val checked = category.slug in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (checked) selected - category.slug else selected + category.slug
                        }
                        .padding(vertical = 4.dp)
                        .testTag(TestTags.subcategory(category.slug)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(
                        text = listOfNotNull(category.icon, category.label).joinToString(" "),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Button(
                onClick = { onApply(selected) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag(TestTags.SUBCATEGORY_APPLY),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.map_subcategory_apply))
            }
        }
    }
}

/** Docked sheet. A plain surface, so the sheet settles immediately under UI tests. */
@Composable
private fun BottomSheet(
    onDismiss: () -> Unit,
    tag: String,
    semantics: (SemanticsPropertyReceiver.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(tag)
            .then(if (semantics != null) Modifier.semantics(properties = semantics) else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SavePresetDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.map_preset_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.PRESET_NAME),
                label = { Text(stringResource(R.string.map_preset_name)) },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_teaser_close)) }
                TextButton(
                    onClick = { onSave(name) },
                    modifier = Modifier.testTag(TestTags.PRESET_SAVE),
                ) {
                    Text(stringResource(R.string.map_preset_save))
                }
            }
        }
    }
}
