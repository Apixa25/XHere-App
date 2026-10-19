package world.xhere.shared.suggest

import world.xhere.shared.civic.Civic
import world.xhere.shared.model.Category

/**
 * Blended category score from PLAN §4.4: embedding cosine, nearby-category
 * counts, the poster's recent picks, and a small time-of-day prior.
 *
 * Weights match `public.suggest_categories` and `supabase/functions/_shared/rank.ts`.
 * `note` is the catch-all when the text is empty or the best cosine is below
 * [NOTE_FLOOR] (PLAN §4.2: the default when the suggestion cannot decide).
 */
internal object CategoryRanker {
    const val NEARBY_WEIGHT: Double = 0.18
    const val RECENT_WEIGHT: Double = 0.12
    const val TIME_WEIGHT: Double = 0.08
    const val NOTE_FLOOR: Double = 0.02
    const val TOP_N: Int = 3

    fun suggest(
        categories: List<Category>,
        query: SuggestionQuery,
        nearbyCounts: Map<String, Int> = emptyMap(),
    ): List<CategorySuggestion> {
        val active = categories.filter { it.active && it.slug !in Civic.RESERVED_SLUGS }
        val documents = active.map { category ->
            RankDocument(
                slug = category.slug,
                label = category.label,
                document = SuggestionCatalog.document(category.slug, category.label, category.description),
            )
        }
        return rank(
            text = query.combinedText(),
            documents = documents,
            nearbyCounts = nearbyCounts,
            recentSlugs = query.recentSlugs,
            hour = query.hour,
        )
    }

    fun rank(
        text: String,
        documents: List<RankDocument>,
        nearbyCounts: Map<String, Int> = emptyMap(),
        recentSlugs: List<String> = emptyList(),
        hour: Int? = null,
        limit: Int = TOP_N,
    ): List<CategorySuggestion> {
        if (documents.isEmpty()) return emptyList()
        val blank = HashEmbedding.tokenize(text).isEmpty()
        val query = HashEmbedding.embed(text)
        val maxNearby = nearbyCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val recent = recentSlugs.toSet()
        val scored = documents.map { document ->
            val cosine = if (blank) 0.0 else HashEmbedding.cosine(query, HashEmbedding.embed(document.document))
            val nearby = if (nearbyCounts.isEmpty()) 0.0 else nearbyCounts[document.slug].orZero() / maxNearby.toDouble()
            val recentBoost = if (document.slug in recent) 1.0 else 0.0
            val time = if (hour != null && timeMatch(document.slug, hour)) 1.0 else 0.0
            val score = cosine + NEARBY_WEIGHT * nearby + RECENT_WEIGHT * recentBoost + TIME_WEIGHT * time
            Scored(document.slug, document.label, score, cosine)
        }
        val bestCosine = scored.maxOf { it.cosine }
        val ordered = scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.slug })
        val promoted = if (blank || bestCosine < NOTE_FLOOR) {
            val note = ordered.filter { it.slug == "note" }
            val rest = ordered.filter { it.slug != "note" }
            note + rest
        } else {
            ordered
        }
        return promoted.take(limit.coerceIn(1, 10)).map { CategorySuggestion(it.slug, it.label, it.score) }
    }

    fun timeMatch(slug: String, hour: Int): Boolean = when (slug) {
        "food_truck", "market", "eatery_tip" -> hour in 11..14 || hour in 17..20
        "event", "popup", "music_spot" -> hour in 16..22
        "yard_sale", "free_stuff", "for_sale" -> hour in 8..16
        "wildlife" -> hour in 5..9 || hour in 17..20
        else -> false
    }

    private fun Int?.orZero(): Int = this ?: 0
}

internal data class RankDocument(
    val slug: String,
    val label: String,
    val document: String,
)

private data class Scored(
    val slug: String,
    val label: String,
    val score: Double,
    val cosine: Double,
)
