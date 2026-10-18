package world.xhere.shared.suggest

/** What the composer sends to `suggest-category` (PLAN §4.4 and §7 M5). */
data class SuggestionQuery(
    val text: String = "",
    val labels: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val hour: Int? = null,
    val recentSlugs: List<String> = emptyList(),
) {
    fun combinedText(): String = listOf(text.trim(), labels.joinToString(" ").trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

data class CategorySuggestion(
    val slug: String,
    val label: String,
    val score: Double,
)
