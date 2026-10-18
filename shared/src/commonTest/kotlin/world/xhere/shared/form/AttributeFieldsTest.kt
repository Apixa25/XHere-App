package world.xhere.shared.form

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttributeFieldsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun eventSchemaRendersRequiredDateFieldsAndOptionalText() {
        val fields = AttributeFields.fromSchema(schema(EVENT))
        assertEquals(listOf("starts_at", "ends_at", "cost", "link"), fields.map { it.name })
        val starts = fields.first { it.name == "starts_at" }
        assertEquals(FieldKind.DATE_TIME, starts.kind)
        assertTrue(starts.required)
        assertEquals("Starts At", starts.label)
        assertEquals(FieldKind.URI, fields.first { it.name == "link" }.kind)
        assertEquals(false, fields.first { it.name == "cost" }.required)
    }

    @Test
    fun hazardSchemaRendersEnumBooleanAndDateTime() {
        val fields = AttributeFields.fromSchema(schema(HAZARD))
        assertEquals(FieldKind.DATE_TIME, fields.first { it.name == "occurred_at" }.kind)
        assertTrue(fields.first { it.name == "occurred_at" }.required)
        val severity = fields.first { it.name == "severity" }
        assertEquals(FieldKind.ENUM, severity.kind)
        assertEquals(listOf("low", "med", "high"), severity.options)
        assertEquals(FieldKind.BOOLEAN, fields.first { it.name == "ongoing" }.kind)
        assertEquals(FieldKind.ENUM, fields.first { it.name == "hazard_type" }.kind)
    }

    @Test
    fun historicalFactRendersIntegerAndText() {
        val fields = AttributeFields.fromSchema(schema(HISTORY))
        assertEquals(FieldKind.TEXT, fields.first { it.name == "era" }.kind)
        assertEquals(60, fields.first { it.name == "era" }.maxLength)
        assertEquals(FieldKind.INTEGER, fields.first { it.name == "year" }.kind)
        assertEquals(-5000.0, fields.first { it.name == "year" }.minimum)
        assertEquals(FieldKind.URI, fields.first { it.name == "source_url" }.kind)
    }

    @Test
    fun valuesBecomeAnAttributesObjectAndBlankOptionalsAreOmitted() {
        val fields = AttributeFields.fromSchema(schema(HAZARD))
        val attributes = AttributeFields.toAttributes(
            fields,
            mapOf("occurred_at" to "2026-10-18T12:00:00Z", "severity" to "high", "ongoing" to "true", "hazard_type" to ""),
        )
        assertEquals("2026-10-18T12:00:00Z", attributes.getValue("occurred_at").jsonPrimitive.content)
        assertEquals("high", attributes.getValue("severity").jsonPrimitive.content)
        assertEquals(true, attributes.getValue("ongoing").jsonPrimitive.content.toBoolean())
        assertTrue("hazard_type" !in attributes)
    }

    @Test
    fun noteSchemaHasNoFields() {
        val fields = AttributeFields.fromSchema(schema("""{"type":"object","additionalProperties":false,"properties":{}}"""))
        assertTrue(fields.isEmpty())
    }

    private fun schema(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private companion object {
        const val EVENT = """{"type":"object","additionalProperties":false,"required":["starts_at","ends_at"],"properties":{
            "starts_at":{"type":"string","format":"date-time"},
            "ends_at":{"type":"string","format":"date-time"},
            "cost":{"type":"string","maxLength":60},
            "link":{"type":"string","format":"uri","maxLength":500}}}"""
        const val HAZARD = """{"type":"object","additionalProperties":false,"required":["occurred_at"],"properties":{
            "occurred_at":{"type":"string","format":"date-time"},
            "severity":{"enum":["low","med","high"]},
            "ongoing":{"type":"boolean"},
            "hazard_type":{"enum":["road","sidewalk","flood","other"]}}}"""
        const val HISTORY = """{"type":"object","additionalProperties":false,"properties":{
            "era":{"type":"string","maxLength":60},
            "year":{"type":"integer","minimum":-5000,"maximum":2100},
            "source_url":{"type":"string","format":"uri","maxLength":500},
            "citation":{"type":"string","maxLength":300}}}"""
    }
}
