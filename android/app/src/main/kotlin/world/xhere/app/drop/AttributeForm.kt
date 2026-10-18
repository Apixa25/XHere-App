package world.xhere.app.drop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import world.xhere.app.ui.TestTags
import world.xhere.shared.form.AttributeField
import world.xhere.shared.form.FieldKind

/** Controls drawn from a category `attributes_schema`. The tag is `field_<name>`. */
@Composable
fun AttributeForm(
    fields: List<AttributeField>,
    values: Map<String, String>,
    onChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { field ->
            FieldControl(field, values[field.name].orEmpty(), onChange)
        }
    }
}

@Composable
private fun FieldControl(
    field: AttributeField,
    value: String,
    onChange: (String, String) -> Unit,
) {
    val label = if (field.required) "${field.label} *" else field.label
    when (field.kind) {
        FieldKind.BOOLEAN -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.field(field.name)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = value == "true", onCheckedChange = { onChange(field.name, if (it) "true" else "false") })
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        FieldKind.ENUM -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(TestTags.field(field.name))) {
                field.options.forEach { option ->
                    FilterChip(
                        selected = value == option,
                        onClick = { onChange(field.name, option) },
                        label = { Text(option) },
                    )
                }
            }
        }
        else -> OutlinedTextField(
            value = value,
            onValueChange = { onChange(field.name, it) },
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.field(field.name)),
            singleLine = field.kind != FieldKind.TEXT,
        )
    }
}
