package world.xhere.app.drop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.app.ui.TestTags
import world.xhere.shared.form.AttributeFields

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttributeFormTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun event_schema_renders_the_required_times_and_writes_them_back() {
        val schema = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"required":["starts_at","ends_at"],"properties":{
            "starts_at":{"type":"string","format":"date-time"},
            "ends_at":{"type":"string","format":"date-time"},
            "cost":{"type":"string","maxLength":60}}}""",
        ).jsonObject
        val fields = AttributeFields.fromSchema(schema)
        var values by mutableStateOf(mapOf<String, String>())
        rule.setContent {
            MaterialTheme {
                AttributeForm(
                    fields = fields,
                    values = values,
                    onChange = { name, value -> values = values + (name to value) },
                )
            }
        }
        rule.onNodeWithTag(TestTags.field("starts_at")).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.field("ends_at")).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.field("starts_at")).performTextInput("2026-10-18T18:00:00Z")
        rule.onNodeWithTag(TestTags.field("cost")).performTextInput("free")
        rule.waitForIdle()
        val attributes = AttributeFields.toAttributes(fields, values)
        assertEquals("2026-10-18T18:00:00Z", attributes["starts_at"]?.toString()?.trim('"'))
        assertEquals("free", attributes["cost"]?.toString()?.trim('"'))
    }

    @Test
    fun hazard_schema_renders_the_severity_choices() {
        val schema = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"required":["occurred_at"],"properties":{
            "occurred_at":{"type":"string","format":"date-time"},
            "severity":{"enum":["low","med","high"]},
            "ongoing":{"type":"boolean"}}}""",
        ).jsonObject
        val fields = AttributeFields.fromSchema(schema)
        rule.setContent {
            MaterialTheme {
                AttributeForm(fields = fields, values = emptyMap(), onChange = { _, _ -> })
            }
        }
        rule.onNodeWithTag(TestTags.field("occurred_at")).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.field("severity")).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.field("ongoing")).assertIsDisplayed()
        rule.onNodeWithText("high").performClick()
    }
}
