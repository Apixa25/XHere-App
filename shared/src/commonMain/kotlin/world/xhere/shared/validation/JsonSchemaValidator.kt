package world.xhere.shared.validation

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import world.xhere.shared.json.parseTimestamp
import world.xhere.shared.text.charLength

/**
 * The slice of JSON Schema that category `attributes_schema` values actually use
 * (see `supabase/migrations/20261018000600_taxonomy.sql`). `pg_jsonschema` in
 * `create_drop` is the authority. This exists so the composer can mark a field
 * before the round trip.
 *
 * Supported on the root object: `type` (object), `properties`, `required`,
 * `additionalProperties`. On each property: `type` (`string`, `integer`, `number`,
 * `boolean`), `enum`, `minLength`, `maxLength`, `minimum`, `maximum`, `pattern`,
 * and `format` of `uri`, `date-time`, or `date`. Unknown formats are ignored,
 * matching JSON Schema's annotation rule. Nested objects and arrays are reported
 * as unsupported rather than treated as valid.
 */
object JsonSchemaValidator {
    fun validate(schema: JsonObject, instance: JsonElement): List<String> {
        val errors = mutableListOf<String>()
        validateElement(schema, instance, path = "", errors)
        return errors
    }

    private fun validateElement(
        schema: JsonObject,
        instance: JsonElement,
        path: String,
        errors: MutableList<String>,
    ) {
        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null && enumValues.none { it == instance }) {
            errors += "${label(path)} must be one of ${enumValues.joinToString { it.toString() }}"
        }

        val type = schema.string("type")
        if (type == null) {
            if (schema["properties"] != null && instance !is JsonObject) {
                errors += "${label(path)} must be a JSON object"
            }
            if (instance is JsonObject && schema["properties"] != null) {
                validateObject(schema, instance, path, errors)
            }
            return
        }

        when (type) {
            "object" -> {
                if (instance !is JsonObject) {
                    errors += "${label(path)} must be a JSON object"
                    return
                }
                validateObject(schema, instance, path, errors)
            }
            "string" -> validateString(schema, instance, path, errors)
            "integer" -> validateNumber(schema, instance, path, errors, integer = true)
            "number" -> validateNumber(schema, instance, path, errors, integer = false)
            "boolean" -> {
                val primitive = instance as? JsonPrimitive
                if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
                    errors += "${label(path)} must be a boolean"
                }
            }
            else -> errors += "${label(path)} uses unsupported schema type $type"
        }
    }

    private fun validateObject(
        schema: JsonObject,
        instance: JsonObject,
        path: String,
        errors: MutableList<String>,
    ) {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        val additional = schema.boolean("additionalProperties") ?: true

        for (name in required) {
            val value = instance[name]
            if (value == null || value is JsonNull) {
                errors += "missing required property ${qualify(path, name)}"
            }
        }

        for ((name, value) in instance) {
            val propertySchema = properties[name] as? JsonObject
            when {
                propertySchema != null -> validateElement(propertySchema, value, qualify(path, name), errors)
                !additional -> errors += "unexpected property ${qualify(path, name)}"
                else -> Unit
            }
        }
    }

    private fun validateString(
        schema: JsonObject,
        instance: JsonElement,
        path: String,
        errors: MutableList<String>,
    ) {
        val primitive = instance as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            errors += "${label(path)} must be a string"
            return
        }
        val text = primitive.content
        val length = text.charLength()
        schema.int("minLength")?.let { min ->
            if (length < min) errors += "${label(path)} must be at least $min characters"
        }
        schema.int("maxLength")?.let { max ->
            if (length > max) errors += "${label(path)} must be at most $max characters"
        }
        schema.string("pattern")?.let { pattern ->
            val regex = try {
                Regex(pattern)
            } catch (_: IllegalArgumentException) {
                errors += "${label(path)} has an invalid pattern"
                null
            }
            if (regex != null && !regex.containsMatchIn(text)) {
                errors += "${label(path)} must match $pattern"
            }
        }
        when (schema.string("format")) {
            "date-time" -> if (!isDateTime(text)) errors += "${label(path)} must be a date-time"
            "date" -> if (!isDate(text)) errors += "${label(path)} must be a date"
            "uri" -> if (!isUri(text)) errors += "${label(path)} must be a uri"
            else -> Unit
        }
    }

    private fun validateNumber(
        schema: JsonObject,
        instance: JsonElement,
        path: String,
        errors: MutableList<String>,
        integer: Boolean,
    ) {
        val primitive = instance as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.doubleOrNull == null) {
            errors += "${label(path)} must be ${if (integer) "an integer" else "a number"}"
            return
        }
        if (integer && !primitive.isJsonInteger()) {
            errors += "${label(path)} must be an integer"
            return
        }
        val value = primitive.double
        schema.double("minimum")?.let { min ->
            if (value < min) errors += "${label(path)} must be >= $min"
        }
        schema.double("maximum")?.let { max ->
            if (value > max) errors += "${label(path)} must be <= $max"
        }
    }

    private fun JsonPrimitive.isJsonInteger(): Boolean {
        if (isString || longOrNull == null) return false
        if (content.any { it == '.' || it == 'e' || it == 'E' }) return false
        return true
    }

    private fun isDateTime(text: String): Boolean = try {
        parseTimestamp(text)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun isDate(text: String): Boolean = try {
        LocalDate.parse(text)
        text.length == 10
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun isUri(text: String): Boolean = URI_PATTERN.matches(text)

    private fun label(path: String): String = if (path.isEmpty()) "value" else path

    private fun qualify(path: String, name: String): String = if (path.isEmpty()) name else "$path.$name"

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.longOrNull?.toInt()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private val JsonPrimitive.double: Double
        get() = doubleOrNull ?: 0.0

    private val URI_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*:\\S+$")
}
