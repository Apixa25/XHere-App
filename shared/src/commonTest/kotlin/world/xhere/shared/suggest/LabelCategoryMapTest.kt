package world.xhere.shared.suggest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import world.xhere.shared.model.Category

class LabelCategoryMapTest {
    private val categories = listOf(
        category("food_truck", "Food truck"),
        category("eatery_tip", "Eatery tip"),
        category("wildlife", "Wildlife sighting"),
        category("historical_fact", "Historical fact"),
        category("memorial", "Memorial"),
        category("historical_marker", "Historical marker"),
        category("street_art", "Street art"),
        category("public_art", "Public art"),
        category("place_of_worship", "Place of worship"),
        category("viewpoint", "Viewpoint"),
        category("hazard", "Hazard"),
        category("note", "Note"),
    )
    private val allowed = categories.map { it.slug }.toSet()

    @Test
    fun foodTruckLabelMapsBeforeAGenericVehicle() {
        assertEquals("food_truck", LabelCategoryMap.categorySlug(listOf("Food truck", "Vehicle"), allowed))
        assertEquals("food_truck", LabelCategoryMap.categorySlug(listOf("FOOD-TRUCK"), allowed))
    }

    @Test
    fun commonLabelsMapOntoTheExistingCategoryList() {
        assertEquals("eatery_tip", LabelCategoryMap.categorySlug(listOf("Food"), allowed))
        assertEquals("wildlife", LabelCategoryMap.categorySlug(listOf("Flower", "Sky"), allowed))
        assertEquals("historical_fact", LabelCategoryMap.categorySlug(listOf("Building"), allowed))
        assertEquals("memorial", LabelCategoryMap.categorySlug(listOf("Monument"), allowed))
        assertEquals("historical_marker", LabelCategoryMap.categorySlug(listOf("Plaque"), allowed))
        assertEquals("historical_marker", LabelCategoryMap.categorySlug(listOf("Historical marker"), allowed))
        assertEquals("street_art", LabelCategoryMap.categorySlug(listOf("Graffiti"), allowed))
        assertEquals("public_art", LabelCategoryMap.categorySlug(listOf("Sculpture"), allowed))
        assertEquals("place_of_worship", LabelCategoryMap.categorySlug(listOf("Church"), allowed))
        assertEquals("viewpoint", LabelCategoryMap.categorySlug(listOf("Lake"), allowed))
        assertEquals("hazard", LabelCategoryMap.categorySlug(listOf("Flood"), allowed))
    }

    @Test
    fun unmappedLabelsDoNotInventACategory() {
        assertNull(LabelCategoryMap.categorySlug(listOf("Sky", "Person", "Vehicle"), allowed))
        assertNull(LabelCategoryMap.categorySlug(emptyList(), allowed))
        assertNull(LabelCategoryMap.categorySlug(listOf("Plaque"), allowed = setOf("note")))
    }

    @Test
    fun altTextNamesWhatTheLabelsSaw() {
        assertEquals(
            "Photo of Food truck, Vehicle.",
            LabelCategoryMap.altText(listOf("Food truck", "Vehicle")),
        )
        assertNull(LabelCategoryMap.altText(listOf("  ", "")))
    }

    @Test
    fun labelDraftIsEditableAndDoesNotLeaveTheCategoryList() {
        val draft = SnapDrafts.fromLabels(listOf("Food truck", "Vehicle"), categories)
        assertEquals("food_truck", draft?.categorySlug)
        assertEquals("Food truck", draft?.title)
        assertEquals("Photo of Food truck, Vehicle.", draft?.altText)
        assertEquals(SnapSource.IMAGE_LABELS, draft?.source)
        assertNull(SnapDrafts.fromLabels(listOf("Sky", "Person"), categories))
    }

    @Test
    fun promptReplyUsesAKnownSlugAndIgnoresAnInventedOne() {
        val raw = """
            TITLE: Taco truck on Third
            CATEGORY: food_truck
            DESCRIPTION: A truck serving food at the curb.
            ALT: A food truck parked at the curb.
        """.trimIndent()
        val draft = SnapDrafts.fromPrompt(raw, labels = emptyList(), categories)
        assertEquals("Taco truck on Third", draft?.title)
        assertEquals("food_truck", draft?.categorySlug)
        assertEquals("A truck serving food at the curb.", draft?.description)
        assertEquals("A food truck parked at the curb.", draft?.altText)
        assertEquals(SnapSource.GEMINI_NANO, draft?.source)

        val invented = """
            TITLE: Something
            CATEGORY: not_a_category
            DESCRIPTION: A plaque on a stone.
            ALT: A plaque on a stone.
        """.trimIndent()
        val fallback = SnapDrafts.fromPrompt(invented, labels = listOf("Plaque"), categories)
        assertEquals("historical_marker", fallback?.categorySlug)
        assertEquals("Something", fallback?.title)
    }

    @Test
    fun imageDescriptionBecomesAltTextAndAKnownCategory() {
        val draft = SnapDrafts.fromImageDescription(
            description = "A small bronze plaque on a stone wall.",
            labels = listOf("Plaque", "Building"),
            categories = categories,
        )
        assertEquals("historical_marker", draft?.categorySlug)
        assertEquals("A small bronze plaque on a stone wall.", draft?.altText)
        assertEquals(SnapSource.GEMINI_NANO, draft?.source)
        assertEquals("A small bronze plaque on a stone wall", draft?.title)
    }

    @Test
    fun promptParserKeepsWrappedLines() {
        val parsed = SnapDrafts.parsePrompt(
            """
            TITLE: Door
            CATEGORY: note
            DESCRIPTION: A blue
            door.
            ALT: Blue door.
            """.trimIndent(),
        )
        assertEquals("Door", parsed["TITLE"])
        assertEquals("note", parsed["CATEGORY"])
        assertEquals("A blue door.", parsed["DESCRIPTION"])
        assertEquals("Blue door.", parsed["ALT"])
    }

    private fun category(slug: String, label: String) = Category(
        id = Uuid.random(),
        groupId = Uuid.random(),
        slug = slug,
        label = label,
        attributesSchema = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
        defaultRevealM = 100,
        maxRevealM = 100,
    )
}
