package world.xhere.shared.voice

/**
 * Hands-free map speech (research §2 Waze voice reports, PLAN §4.4 quick drop).
 *
 * The phone recognizes speech on the device. This object only reads the words.
 * A question ("what's around me?", "any history here?") does not create a drop.
 * A report ("pothole here", "road closed", "great coffee at this place") becomes
 * a draft the person can post. Nothing here calls a server or a paid model.
 */
enum class VoiceSource {
    RULES,
    GEMINI_NANO,
}

enum class VoiceQuestion {
    AROUND,
    HISTORY,
}

data class VoiceParse(
    val transcript: String,
    val question: VoiceQuestion? = null,
    val categorySlug: String? = null,
    val title: String? = null,
    val body: String? = null,
    /** Hazard attribute. Null when the category is not `hazard`. */
    val hazardType: String? = null,
    val severity: String? = null,
    val ongoing: Boolean? = null,
    val source: VoiceSource = VoiceSource.RULES,
) {
    val isQuestion: Boolean get() = question != null

    val isDrop: Boolean get() = !categorySlug.isNullOrBlank() && !title.isNullOrBlank()
}

object VoiceIntents {
    const val TITLE_MAX: Int = 80

    fun parse(raw: String): VoiceParse? {
        val transcript = raw.trim().replace(Regex("\\s+"), " ")
        if (transcript.isEmpty()) return null
        val text = normalize(transcript)
        if (text.isEmpty()) return null
        question(text)?.let { kind ->
            return VoiceParse(transcript = transcript, question = kind)
        }
        return drop(transcript, text)
    }

    /** Lowercase words. Punctuation becomes a space so "what's" still matches. */
    fun normalize(raw: String): String = raw
        .lowercase()
        .replace("what's", "what is")
        .replace("whats", "what is")
        .replace("café", "cafe")
        .replace(Regex("[^a-z0-9' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun question(text: String): VoiceQuestion? {
        if (HISTORY.any { phrase -> text.contains(phrase) }) return VoiceQuestion.HISTORY
        if (AROUND.any { phrase -> text.contains(phrase) }) return VoiceQuestion.AROUND
        return null
    }

    private fun drop(transcript: String, text: String): VoiceParse? {
        val rule = RULES.firstOrNull { rule -> rule.matches(text) } ?: return fallback(transcript, text)
        return VoiceParse(
            transcript = transcript,
            categorySlug = rule.category,
            title = rule.title(text),
            body = transcript,
            hazardType = rule.hazardType,
            severity = rule.severity,
            ongoing = rule.ongoing,
        )
    }

    /** A short leftover phrase still becomes a note. A single word does not. */
    private fun fallback(transcript: String, text: String): VoiceParse? {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val title = clipTitle(transcript.trim().replaceFirstChar { char ->
            if (char.isLowerCase()) char.uppercase() else char.toString()
        })
        if (title.length < 3) return null
        return VoiceParse(
            transcript = transcript,
            categorySlug = "note",
            title = title,
            body = transcript,
        )
    }

    /** Whole words only, so "nice" is not "ice". */
    fun hasWord(text: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(text)

    fun clipTitle(title: String): String {
        val compact = title.trim().replace(Regex("\\s+"), " ")
        if (compact.length <= TITLE_MAX) return compact
        return compact.take(TITLE_MAX - 1).trimEnd() + "…"
    }

    private val HISTORY = listOf(
        "any history",
        "history here",
        "what history",
        "historic",
        "what happened",
        "happened here",
    )

    private val AROUND = listOf(
        "around me",
        "what is around",
        "what is nearby",
        "what is here",
        "what is near",
        "near me",
        "nearby",
        "anything here",
    )

    private val RULES = listOf(
        Rule("pothole", "hazard", { "Pothole" }, hazardType = "road", severity = "med", ongoing = true),
        Rule(
            matches = { text ->
                text.contains("road closed") || text.contains("street closed") ||
                    text.contains("road closure") || text.contains("closed road")
            },
            category = "hazard",
            title = { "Road closed" },
            hazardType = "road",
            severity = "high",
            ongoing = true,
        ),
        Rule(
            matches = { text -> text.contains("flood") },
            category = "hazard",
            title = { "Flood" },
            hazardType = "flood",
            severity = "high",
            ongoing = true,
        ),
        Rule(
            matches = { text -> text.contains("coffee") || text.contains("cafe") },
            category = "eatery_tip",
            title = { text -> if (text.contains("great")) "Great coffee" else "Coffee" },
        ),
        Rule(
            matches = { text ->
                text.contains("restaurant") || text.contains("eatery") ||
                    text.contains("food") || text.contains("meal")
            },
            category = "eatery_tip",
            title = { "Food tip" },
        ),
        Rule(
            matches = { text -> text.contains("yard sale") || text.contains("garage sale") },
            category = "yard_sale",
            title = { "Yard sale" },
        ),
        Rule(
            matches = { text -> text.contains("construction") || text.contains("road work") },
            category = "construction",
            title = { "Construction" },
        ),
        Rule(
            matches = { text ->
                text.contains("hazard") || text.contains("danger") ||
                    text.contains("debris") || text.contains("black ice") || hasWord(text, "ice")
            },
            category = "hazard",
            title = { text ->
                when {
                    text.contains("ice") -> "Ice"
                    text.contains("debris") -> "Debris"
                    else -> "Hazard"
                }
            },
            hazardType = "other",
            severity = "med",
            ongoing = true,
        ),
    )
}

private class Rule(
    val matches: (String) -> Boolean,
    val category: String,
    val title: (String) -> String,
    val hazardType: String? = null,
    val severity: String? = null,
    val ongoing: Boolean? = null,
) {
    constructor(
        phrase: String,
        category: String,
        title: (String) -> String,
        hazardType: String? = null,
        severity: String? = null,
        ongoing: Boolean? = null,
    ) : this({ text -> text.contains(phrase) }, category, title, hazardType, severity, ongoing)
}
