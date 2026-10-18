package world.xhere.shared.suggest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same phrases are scored in `supabase/functions/suggest-category/suggest_test.ts`
 * and `supabase/tests/database/07_suggestions.test.sql`. Top-3 at or above 70% is the M5 bar.
 */
class CategoryRankerTest {
    @Test
    fun fixtureSetClearsTheTop3Bar() {
        val documents = SuggestionCatalog.entries.map { entry ->
            RankDocument(entry.slug, entry.label, entry.document())
        }
        var top3 = 0
        var top1 = 0
        val misses = mutableListOf<String>()
        for ((text, expected) in FIXTURES) {
            val ranked = CategoryRanker.rank(text, documents)
            val slugs = ranked.map { it.slug }
            if (expected in slugs) top3++ else misses += "$expected !in $slugs for \"$text\""
            if (slugs.firstOrNull() == expected) top1++
        }
        val ratio = top3.toDouble() / FIXTURES.size
        println("suggestion fixture top-3 $top3/${FIXTURES.size} = ${"%.1f".format(ratio * 100)}% (top-1 $top1/${FIXTURES.size})")
        assertTrue(ratio >= 0.70, misses.joinToString("\n"))
        assertEquals(FIXTURES.size, top3)
    }

    @Test
    fun emptyTextDefaultsToNote() {
        val documents = SuggestionCatalog.entries.map { RankDocument(it.slug, it.label, it.document()) }
        val ranked = CategoryRanker.rank("", documents)
        assertEquals("note", ranked.first().slug)
    }

    @Test
    fun nearbyPriorCanLiftACloseCategory() {
        val documents = listOf(
            RankDocument("note", "Note", "note miscellaneous other general"),
            RankDocument("food_truck", "Food truck", "food truck cart lunch"),
        )
        val plain = CategoryRanker.rank("lunch", documents)
        val boosted = CategoryRanker.rank("lunch", documents, nearbyCounts = mapOf("food_truck" to 6))
        assertEquals("food_truck", boosted.first().slug)
        assertTrue(boosted.first().score >= plain.first { it.slug == "food_truck" }.score)
    }

    @Test
    fun hashEmbedIsStableAndNormalized() {
        val once = HashEmbedding.embed("food truck")
        val twice = HashEmbedding.embed("food truck")
        assertEquals(HashEmbedding.DIMENSIONS, once.size)
        assertTrue(once.contentEquals(twice))
        val norm = kotlin.math.sqrt(once.sumOf { it * it })
        assertTrue(norm in 0.99..1.01)
        val close = HashEmbedding.cosine(once, HashEmbedding.embed("food truck cart"))
        val far = HashEmbedding.cosine(once, HashEmbedding.embed("church mosque temple"))
        assertTrue(close > far)
    }
}

/** Phrases a photo label or a typed line might produce, and the category a person would pick. */
internal val FIXTURES: List<Pair<String, String>> = listOf(
    "food truck street food" to "food_truck",
    "monument sculpture statue" to "public_art",
    "flood on the road" to "hazard",
    "farmers market stalls" to "market",
    "owl in the oak tree" to "wildlife",
    "mural on the alley wall" to "street_art",
    "garage sale this weekend" to "yard_sale",
    "drinking fountain by the path" to "water_source",
    "busker with a guitar" to "music_spot",
    "church service on sunday" to "place_of_worship",
    "tunnel under the railroad" to "underground",
    "construction crane blocking the lane" to "construction",
    "hidden courtyard easy to miss" to "hidden_gem",
    "museum says this happened in 1890" to "historical_fact",
    "popup shop until friday" to "popup",
    "restaurant menu what to order" to "eatery_tip",
    "muddy trail after the turn" to "trail_note",
    "free couch on the curb" to "free_stuff",
    "public library community hall" to "community_space",
    "scenic overlook of the lake" to "viewpoint",
    "theft break-in last night" to "incident_report",
    "bridge utility work" to "infrastructure",
    "memorial plaque for the town" to "memorial",
    "local shortcut only locals know" to "tip",
    "community flyer on the notice board" to "notice_board",
    "a general note about the benches" to "note",
    "personal tale my grandmother told" to "story",
    "item for sale pickup here" to "for_sale",
    "live festival happening tonight" to "event",
    "ice warning sidewalk closed" to "hazard",
    "safety alert caution nearby" to "safety_alert",
    "taco trailer lunch vehicle" to "food_truck",
)
