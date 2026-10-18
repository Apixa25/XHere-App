package world.xhere.shared.form

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The fields a category's `attributes_schema` asks the composer to show.
 * This is the slice of JSON Schema the taxonomy actually uses
 * (`supabase/migrations/20261018000600_taxonomy.sql`). `create_drop` still
 * validates the object with pg_jsonschema; this only decides which controls to draw.
 */
object AttributeFields {
    fun fromSchema(schema: JsonObject): List<AttributeField> {
        val properties = schema["properties"] as? JsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.toSet()
        return properties.mapNotNull { (name, element) ->
            val property = element as? JsonObject ?: return@mapNotNull null
            val enumValues = (property["enum"] as? JsonArray)?.mapNotNull { item ->
                (item as? JsonPrimitive)?.contentOrNull()
            }.orEmpty()
            val type = (property["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val format = (property["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val kind = when {
                enumValues.isNotEmpty() -> FieldKind.ENUM
                type == "boolean" -> FieldKind.BOOLEAN
                type == "integer" -> FieldKind.INTEGER
                type == "number" -> FieldKind.NUMBER
                format == "date-time" -> FieldKind.DATE_TIME
                format == "date" -> FieldKind.DATE
                format == "uri" -> FieldKind.URI
                else -> FieldKind.TEXT
            }
            AttributeField(
                name = name,
                label = humanize(name),
                kind = kind,
                required = name in required,
                maxLength = (property["maxLength"] as? JsonPrimitive)?.longOrNull(),
                minimum = (property["minimum"] as? JsonPrimitive)?.doubleOrNull(),
                maximum = (property["maximum"] as? JsonPrimitive)?.doubleOrNull(),
                options = enumValues,
            )
        }
    }

    /** Omits blank optional fields. Booleans use "true" / "false". Numbers stay decimal text. */
    fun toAttributes(fields: List<AttributeField>, values: Map<String, String>): JsonObject = buildJsonObject {
        for (field in fields) {
            val raw = values[field.name]?.trim().orEmpty()
            if (raw.isEmpty()) continue
            when (field.kind) {
                FieldKind.BOOLEAN -> put(field.name, raw == "true")
                FieldKind.INTEGER -> raw.toLongOrNull()?.let { put(field.name, it) }
                FieldKind.NUMBER -> raw.toDoubleOrNull()?.let { put(field.name, it) }
                else -> put(field.name, raw)
            }
        }
    }

    fun humanize(name: String): String = name.split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun JsonPrimitive.contentOrNull(): String? = when {
        isString -> content
        else -> content
    }

    private fun JsonPrimitive.longOrNull(): Int? = if (isString) null else content.toIntOrNull()

    private fun JsonPrimitive.doubleOrNull(): Double? = if (isString) null else content.toDoubleOrNull()
}

enum class FieldKind {
    TEXT,
    INTEGER,
    NUMBER,
    BOOLEAN,
    ENUM,
    DATE,
    DATE_TIME,
    URI,
}

data class AttributeField(
    val name: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean,
    val maxLength: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val options: List<String> = emptyList(),
)
