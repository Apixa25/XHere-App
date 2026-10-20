package world.xhere.shared.map

import world.xhere.shared.model.DropSource
import world.xhere.shared.model.MapFeature
import world.xhere.shared.places.PublicPlace
import world.xhere.shared.places.PublicPlaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterestingFirstTest {
    @Test
    fun accessibilityStaysOffUntilTheChipOrTheLayer() {
        val pin = pin("mall", InterestingFirst.ACCESS, "infrastructure")
        val history = pin("hall", "historical_fact", "history_stories")
        val all = InterestingFirst.arrange(listOf(pin, history), InterestingFirst.Lens(), zoom = 16)
        assertEquals(listOf("hall"), all.map { it.shortId })

        val chip = InterestingFirst.arrange(
            listOf(pin, history),
            InterestingFirst.Lens(selection = listOf(InterestingFirst.ACCESS)),
            zoom = 16,
        )
        assertEquals(setOf("hall", "mall"), chip.map { it.shortId }.toSet())

        val layer = InterestingFirst.arrange(
            listOf(pin),
            InterestingFirst.Lens(accessibility = true),
            zoom = 16,
        )
        assertEquals("mall", layer.single().shortId)

        val infrastructure = InterestingFirst.Lens(selection = listOf("infrastructure"))
        assertFalse(InterestingFirst.show(pin, infrastructure))
    }

    @Test
    fun crimeAndHazardsAreLayersUnlessTheSafetyChipAsks() {
        val crime = pin("theft", "incident_report", InterestingFirst.SAFETY_GROUP)
        val hazard = pin("ice", "hazard", InterestingFirst.SAFETY_GROUP)
        val story = pin("story", "story", "history_stories", source = DropSource.USER)
        val hidden = InterestingFirst.arrange(listOf(crime, hazard, story), InterestingFirst.Lens(), zoom = 15)
        assertEquals(listOf("story"), hidden.map { it.shortId })

        val crimeOn = InterestingFirst.arrange(
            listOf(crime, hazard),
            InterestingFirst.Lens(crime = true),
            zoom = 15,
        )
        assertEquals(listOf("theft"), crimeOn.map { it.shortId })

        val safetyChip = InterestingFirst.arrange(
            listOf(crime, hazard),
            InterestingFirst.Lens(selection = listOf(InterestingFirst.SAFETY_GROUP)),
            zoom = 15,
        )
        assertEquals(setOf("theft", "ice"), safetyChip.map { it.shortId }.toSet())
    }

    @Test
    fun interestingPinsOutrankPracticalOnesWhenTheCapIsTight() {
        val marker = pin("marker", "historical_marker", "history_stories", source = DropSource.OPEN_DATA)
        val note = pin("note", "note", "note", source = DropSource.USER)
        val hazard = pin("ice", "hazard", InterestingFirst.SAFETY_GROUP, source = DropSource.USER)
        assertTrue(InterestingFirst.score(marker) > InterestingFirst.score(note))
        assertTrue(InterestingFirst.score(note) > InterestingFirst.score(hazard))
        val crowd = (1..20).map { index ->
            pin("n$index", "note", "note", source = DropSource.USER)
        }
        val ranked = InterestingFirst.arrange(
            crowd + hazard + marker,
            InterestingFirst.Lens(safety = true),
            zoom = 10,
        )
        assertEquals(InterestingFirst.pinCap(10), ranked.size)
        assertEquals("marker", ranked.first().shortId)
        assertFalse(ranked.any { it.shortId == "ice" })
    }

    @Test
    fun aClusterOfOnlyHiddenPracticalPinsGoesAway() {
        val mixed = MapFeature(
            kind = "cluster",
            latitude = 1.0,
            longitude = 2.0,
            pointCount = 7,
            categoryCounts = mapOf("historical_fact" to 4, "hazard" to 3),
        )
        val onlyAccess = MapFeature(
            kind = "cluster",
            latitude = 1.0,
            longitude = 2.0,
            pointCount = 12,
            categoryCounts = mapOf(InterestingFirst.ACCESS to 12),
        )
        assertTrue(InterestingFirst.show(mixed, InterestingFirst.Lens()))
        assertFalse(InterestingFirst.show(onlyAccess, InterestingFirst.Lens()))
        assertTrue(InterestingFirst.show(onlyAccess, InterestingFirst.Lens(accessibility = true)))
        assertFalse(InterestingFirst.isSparse(listOf(mixed)))
    }

    @Test
    fun sparseViewsCanTakePublicPlacesThatAreNotAlreadyPinned() {
        val one = pin("bench", "note", "note")
        assertTrue(InterestingFirst.isSparse(listOf(one)))
        assertTrue(InterestingFirst.shouldFill(null))
        assertTrue(InterestingFirst.shouldFill(listOf("history_stories")))
        assertFalse(InterestingFirst.shouldFill(listOf("food_drink")))
        assertFalse(InterestingFirst.shouldFill(listOf(InterestingFirst.SAFETY_GROUP)))

        val article = place("enwiki:1", "Town Hall", 42.10, -79.30)
        val sameSpot = place("osm:node/9", "Town Hall steps", 42.1001, -79.3001)
        val photo = place("commons:2", "Winter market", 42.20, -79.10, PublicPlaceKind.PHOTO)
        val picked = InterestingFirst.pickPublic(listOf(one), listOf(article, sameSpot, photo))
        assertEquals(listOf("enwiki:1", "commons:2"), picked.map { it.id })

        val onTheBench = place("enwiki:3", "Bench story", one.latitude, one.longitude)
        assertTrue(InterestingFirst.pickPublic(listOf(one), listOf(onTheBench)).isEmpty())
    }

    private fun pin(
        id: String,
        category: String,
        group: String,
        source: DropSource = DropSource.OPEN_DATA,
    ) = MapFeature(
        kind = "drop",
        shortId = id,
        categorySlug = category,
        groupSlug = group,
        title = id,
        latitude = 42.0,
        longitude = -79.0,
        pointCount = 1,
        source = source,
    )

    private fun place(
        id: String,
        title: String,
        latitude: Double,
        longitude: Double,
        kind: PublicPlaceKind = PublicPlaceKind.ARTICLE,
    ) = PublicPlace(
        id = id,
        title = title,
        summary = "A short public summary.",
        latitude = latitude,
        longitude = longitude,
        imageUrl = null,
        pageUrl = "https://example.test/$id",
        attribution = "Example",
        license = "CC BY-SA 4.0",
        kind = kind,
    )
}
