package world.xhere.shared.voice

import world.xhere.shared.model.Category

/**
 * Optional on-device Gemini Nano reply. The prompt asks for four lines.
 * A missing model, or a category that is not in the taxonomy, falls back to
 * [VoiceIntents]. Nothing is posted from this object.
 */
object VoiceDrafts {
    fun fromPrompt(raw: String, categories: List<Category>): VoiceParse? {
        val fields = parsePrompt(raw)
        val transcript = fields["TRANSCRIPT"].orEmpty().ifBlank { raw.trim() }
        if (transcript.isBlank()) return null
        val intent = fields["INTENT"]?.lowercase()?.replace(' ', '_')
        if (intent == "around" || intent == "question_around" || intent == "nearby") {
            return VoiceParse(transcript = transcript, question = VoiceQuestion.AROUND, source = VoiceSource.GEMINI_NANO)
        }
        if (intent == "history" || intent == "question_history") {
            return VoiceParse(transcript = transcript, question = VoiceQuestion.HISTORY, source = VoiceSource.GEMINI_NANO)
        }
        val allowed = categories.filter { it.active }.map { it.slug }.toSet()
        val prompted = fields["CATEGORY"]
            ?.lowercase()
            ?.trim()
            ?.replace(' ', '_')
            ?.replace('-', '_')
        val promptedKnown = prompted != null && prompted in allowed
        val rules = VoiceIntents.parse(listOfNotNull(fields["TITLE"], fields["DESCRIPTION"], transcript).joinToString(". "))
        val slug = when {
            promptedKnown -> prompted
            rules?.categorySlug != null && rules.categorySlug in allowed -> rules.categorySlug
            else -> null
        } ?: return rules?.copy(source = VoiceSource.RULES)
        val title = VoiceIntents.clipTitle(
            fields["TITLE"].orEmpty().ifBlank { rules?.title.orEmpty() },
        ).ifBlank { return rules }
        val body = fields["DESCRIPTION"].orEmpty().ifBlank { transcript }.trim()
        val hazard = if (slug == "hazard") rules else null
        return VoiceParse(
            transcript = transcript,
            categorySlug = slug,
            title = title,
            body = body.ifBlank { transcript },
            hazardType = hazard?.hazardType ?: if (slug == "hazard") "other" else null,
            severity = hazard?.severity ?: if (slug == "hazard") "med" else null,
            ongoing = hazard?.ongoing ?: if (slug == "hazard") true else null,
            source = if (promptedKnown) VoiceSource.GEMINI_NANO else VoiceSource.RULES,
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

    fun promptFor(categories: List<Category>): String {
        val slugs = categories.filter { it.active }.joinToString(", ") { it.slug }
        return """
            You turn one spoken line into a map drop or a question. Reply with exactly four lines:
            INTENT: drop, around, or history
            TITLE: a short title, at most 80 characters, or none
            CATEGORY: one slug from this list, or none: $slugs
            DESCRIPTION: one short sentence, or none
            Use around for questions about what is nearby. Use history for questions about the past of this spot.
            Use drop for a report such as a pothole, a closure, or a place tip.
            Do not add any other lines.
        """.trimIndent()
    }

    private val LINE = Regex("""^(INTENT|TITLE|CATEGORY|DESCRIPTION|TRANSCRIPT)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
}
