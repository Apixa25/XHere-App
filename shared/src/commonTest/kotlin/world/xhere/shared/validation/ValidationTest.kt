package world.xhere.shared.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import world.xhere.shared.Fixtures

class JsonSchemaValidatorTest {
    @Test
    fun noteSchemaAcceptsAnEmptyObject() {
        assertEquals(emptyList(), JsonSchemaValidator.validate(Fixtures.note.attributesSchema, JsonObject(emptyMap())))
    }

    @Test
    fun eventSchemaRequiresStartAndEnd() {
        val errors = JsonSchemaValidator.validate(Fixtures.event.attributesSchema, JsonObject(emptyMap()))
        assertTrue(errors.any { it.contains("starts_at") })
        assertTrue(errors.any { it.contains("ends_at") })
    }

    @Test
    fun eventSchemaRejectsABadLinkAndAcceptsARealOne() {
        val bad = buildJsonObject {
            put("starts_at", "2026-10-18T12:00:00Z")
            put("ends_at", "2026-10-18T18:00:00Z")
            put("link", "not a url")
            put("cost", "x".repeat(61))
        }
        val errors = JsonSchemaValidator.validate(Fixtures.event.attributesSchema, bad)
        assertTrue(errors.any { it.contains("link") && it.contains("uri") })
        assertTrue(errors.any { it.contains("cost") && it.contains("60") })

        val good = buildJsonObject {
            put("starts_at", "2026-10-18T12:00:00Z")
            put("ends_at", "2026-10-18T18:00:00Z")
            put("link", "https://xhere.world/event")
        }
        assertEquals(emptyList(), JsonSchemaValidator.validate(Fixtures.event.attributesSchema, good))
    }

    @Test
    fun rejectsUnexpectedPropertiesIntegersAndEnums() {
        val extra = buildJsonObject {
            put("occurred_at", "2026-10-18T03:00:00Z")
            put("suspect", "nope")
        }
        val errors = JsonSchemaValidator.validate(Fixtures.incident.attributesSchema, extra)
        assertTrue(errors.any { it.contains("unexpected property suspect") })

        val badEnum = buildJsonObject {
            put("occurred_at", "2026-10-18T03:00:00Z")
            put("severity", "extreme")
            put("ongoing", "yes")
        }
        val enumErrors = JsonSchemaValidator.validate(Fixtures.incident.attributesSchema, badEnum)
        assertTrue(enumErrors.any { it.contains("severity") })
        assertTrue(enumErrors.any { it.contains("ongoing") && it.contains("boolean") })
    }

    @Test
    fun integerRejectsAFractionAndNumberHonorsRange() {
        val schema = Fixtures.schema(
            """{"type":"object","additionalProperties":true,"properties":{
            "year":{"type":"integer","minimum":-5000,"maximum":2100},
            "price":{"type":"number","minimum":0,"maximum":10}}}""",
        )
        val fraction = buildJsonObject { put("year", 1.5) }
        assertTrue(JsonSchemaValidator.validate(schema, fraction).any { it.contains("integer") })

        val year = buildJsonObject { put("year", 2101) }
        assertTrue(JsonSchemaValidator.validate(schema, year).any { it.contains("2100") })

        val price = buildJsonObject { put("price", -1) }
        assertTrue(JsonSchemaValidator.validate(schema, price).any { it.contains("price") })

        val ok = buildJsonObject {
            put("year", 1850)
            put("price", 0)
            put("extra", true)
        }
        assertEquals(emptyList(), JsonSchemaValidator.validate(schema, ok))
    }

    @Test
    fun currencyPatternAndUnknownFormat() {
        val bad = buildJsonObject { put("currency", "usd") }
        assertTrue(JsonSchemaValidator.validate(Fixtures.yardSale.attributesSchema, bad).any { it.contains("currency") })
        val good = buildJsonObject { put("currency", "USD") }
        assertEquals(emptyList(), JsonSchemaValidator.validate(Fixtures.yardSale.attributesSchema, good))

        val emailish = Fixtures.schema("""{"type":"string","format":"email"}""")
        assertEquals(emptyList(), JsonSchemaValidator.validate(emailish, JsonPrimitive("not-an-email")))
    }

    @Test
    fun datesMinLengthAndABrokenPattern() {
        val schema = Fixtures.schema(
            """{"type":"object","properties":{
            "when":{"type":"string","format":"date","minLength":10},
            "code":{"type":"string","pattern":"["}}}""",
        )
        val bad = buildJsonObject {
            put("when", "18-10-2026")
            put("code", "a")
        }
        val errors = JsonSchemaValidator.validate(schema, bad)
        assertTrue(errors.any { it.contains("when") && it.contains("date") })
        assertTrue(errors.any { it.contains("invalid pattern") })

        val good = buildJsonObject { put("when", "2026-10-18") }
        assertTrue(JsonSchemaValidator.validate(schema, good).none { it.contains("when") })
    }

    @Test
    fun rootMustBeAnObjectAndUnsupportedTypesAreReported() {
        val errors = JsonSchemaValidator.validate(Fixtures.note.attributesSchema, JsonPrimitive("nope"))
        assertTrue(errors.any { it.contains("object") })
        val weird = Fixtures.schema("""{"type":"array"}""")
        assertTrue(JsonSchemaValidator.validate(weird, Json.parseToJsonElement("[]")).any { it.contains("unsupported") })
    }
}

class PosixPatternTest {
    @Test
    fun contactFilterMatchesPhonesAndEmailsOnly() {
        assertTrue(PosixPattern.matches(Fixtures.contactFilter.pattern, "call 212-555-1212 today"))
        assertTrue(PosixPattern.matches(Fixtures.contactFilter.pattern, "write me at Ada@Example.com"))
        assertTrue(!PosixPattern.matches(Fixtures.contactFilter.pattern, "room 12 is open"))
    }

    @Test
    fun personFilterMatchesDescriptorsAndNotTheIncident() {
        assertTrue(PosixPattern.matches(Fixtures.personFilter.pattern, "a tall man in a hoodie"))
        assertTrue(PosixPattern.matches(Fixtures.personFilter.pattern, "someone 14 yo"))
        assertTrue(!PosixPattern.matches(Fixtures.personFilter.pattern, "a broken window on the sidewalk"))
    }

    @Test
    fun wordEdgesBecomeWordBoundaries() {
        assertEquals("\\bman\\b", PosixPattern.translate("\\mman\\M"))
        assertTrue(PosixPattern.translate("[[:alnum:]]+@[[:alpha:]]+").contains("[0-9A-Za-z]"))
        assertNull(PosixPattern.compile("("))
    }
}
