package world.xhere.shared.suggest

import world.xhere.shared.model.Category

/**
 * A photo suggestion the person can edit and then post.
 * Nothing in here posts a drop. [SnapSource.GEMINI_NANO] is on-device Gemini
 * Nano (ML Kit image description or the prompt API). [SnapSource.IMAGE_LABELS]
 * is the bundled ML Kit labeler used when Gemini Nano is not available.
 */
enum class SnapSource {
    GEMINI_NANO,
    IMAGE_LABELS,
}

data class SnapDraft(
    val title: String,
    val categorySlug: String,
    val description: String,
    val altText: String,
    val source: SnapSource,
)

/**
 * Turns model text or image labels into a [SnapDraft] whose category is one
 * of [categories]. Returns null when nothing maps, so the composer can show
 * the ordinary form instead of inventing a category.
 */
object SnapDrafts {
    fun fromLabels(labels: List<String>, categories: List<Category>): SnapDraft? {
        val allowed = allowed(categories)
        val slug = LabelCategoryMap.categorySlug(labels, allowed) ?: return null
        val title = LabelCategoryMap.titleFromLabels(labels) ?: return null
        val alt = LabelCategoryMap.altText(labels) ?: return null
        return SnapDraft(
            title = title,
            categorySlug = slug,
            description = LabelCategoryMap.descriptionFromLabels(labels),
            altText = alt,
            source = SnapSource.IMAGE_LABELS,
        )
    }

    /**
     * Image Description API text. The description is the alt text. The category
     * comes from labels when those map, otherwise from the same ranker the
     * composer already uses on the description.
     */
    fun fromImageDescription(
        description: String,
        labels: List<String>,
        categories: List<Category>,
    ): SnapDraft? {
        val text = LabelCategoryMap.clip(description, LabelCategoryMap.DESCRIPTION_MAX)
        if (text.isEmpty()) return fromLabels(labels, categories)
        val slug = categoryFor(text, labels, categories) ?: return null
        val title = titleFromDescription(text, labels)
        val alt = LabelCategoryMap.clip(text, LabelCategoryMap.ALT_MAX)
        return SnapDraft(
            title = title,
            categorySlug = slug,
            description = text,
            altText = alt,
            source = SnapSource.GEMINI_NANO,
        )
    }

    /**
     * Prompt API text. Expected lines are `TITLE:`, `CATEGORY:`, `DESCRIPTION:`,
     * and `ALT:`. A category that is not in the taxonomy is ignored and the
     * label map is used instead. A reply that still has no category returns null.
     */
    fun fromPrompt(raw: String, labels: List<String>, categories: List<Category>): SnapDraft? {
        val fields = parsePrompt(raw)
        val allowed = allowed(categories)
        val prompted = fields["CATEGORY"]?.let { LabelCategoryMap.normalize(it).replace(' ', '_') }
        val slug = when {
            prompted != null && prompted in allowed -> prompted
            else -> LabelCategoryMap.categorySlug(labels, allowed)
                ?: categoryFor(fields["DESCRIPTION"].orEmpty(), labels, categories)
        } ?: return null
        val description = LabelCategoryMap.clip(
            fields["DESCRIPTION"].orEmpty().ifBlank { LabelCategoryMap.descriptionFromLabels(labels) },
            LabelCategoryMap.DESCRIPTION_MAX,
        )
        val title = LabelCategoryMap.clip(
            fields["TITLE"].orEmpty().ifBlank { titleFromDescription(description, labels) },
            LabelCategoryMap.TITLE_MAX,
        ).ifBlank { return null }
        val alt = LabelCategoryMap.clip(
            fields["ALT"].orEmpty().ifBlank { description.ifBlank { LabelCategoryMap.altText(labels).orEmpty() } },
            LabelCategoryMap.ALT_MAX,
        ).ifBlank { return null }
        return SnapDraft(
            title = title,
            categorySlug = slug,
            description = description,
            altText = alt,
            source = SnapSource.GEMINI_NANO,
        )
    }

    fun parsePrompt(raw: String): Map<String, String> {
        val result = linkedMapOf<String, StringBuilder>()
        var current: String? = null
        for (line in raw.lines()) {
            val match = LINE.matchEntire(line.trim())
            if (match != null) {
                current = match.groupValues[1].uppercase()
                result.getOrPut(current) { StringBuilder() }.append(match.groupValues[2].trim())
            } else if (current != null && line.isNotBlank()) {
                val buffer = result.getOrPut(current) { StringBuilder() }
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(line.trim())
            }
        }
        return result.mapValues { it.value.toString().trim() }
    }

    private fun categoryFor(text: String, labels: List<String>, categories: List<Category>): String? {
        val allowed = allowed(categories)
        LabelCategoryMap.categorySlug(labels, allowed)?.let { return it }
        if (categories.isEmpty() || text.isBlank()) return null
        return CategoryRanker.suggest(
            categories,
            SuggestionQuery(text = text, labels = labels),
        ).firstOrNull()?.slug?.takeIf { it in allowed }
    }

    private fun titleFromDescription(description: String, labels: List<String>): String {
        val sentence = description.substringBefore('.').substringBefore('!').trim()
        val fromSentence = LabelCategoryMap.clip(sentence, LabelCategoryMap.TITLE_MAX)
        if (fromSentence.isNotEmpty()) return fromSentence
        return LabelCategoryMap.titleFromLabels(labels).orEmpty()
    }

    private fun allowed(categories: List<Category>): Set<String> =
        categories.filter { it.active }.map { it.slug }.toSet()

    private val LINE = Regex("""^(TITLE|CATEGORY|DESCRIPTION|ALT)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
}
