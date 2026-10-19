package world.xhere.shared.voice

import world.xhere.shared.access.AccessAnswer
import world.xhere.shared.access.AccessSummary
import world.xhere.shared.model.DropCard

/**
 * The sentence Android TextToSpeech reads for "what's around me?" and
 * "any history here?". It uses the Here Card rows already on screen.
 * A locked drop contributes its title only.
 */
object HereSpeech {
    private val HISTORY = setOf(
        "historical_fact",
        "story",
        "memorial",
        "historical_marker",
    )

    fun script(
        question: VoiceQuestion,
        placeName: String?,
        cards: List<DropCard>,
        access: AccessSummary? = null,
    ): String {
        val where = placeName?.trim().orEmpty().ifEmpty { "this spot" }
        val rows = if (question == VoiceQuestion.HISTORY) {
            cards.filter { it.categorySlug in HISTORY }
        } else {
            cards
        }
        val lead = if (question == VoiceQuestion.HISTORY) {
            if (rows.isEmpty()) {
                "No history is saved at $where yet."
            } else {
                "History at $where. " + lines(rows)
            }
        } else {
            if (rows.isEmpty()) {
                "Nothing is saved around $where yet."
            } else {
                val count = rows.size
                val noun = if (count == 1) "thing" else "things"
                "$count $noun around $where. " + lines(rows)
            }
        }
        val accessLine = access?.let { spokenAccess(it) }.orEmpty()
        return listOf(lead, accessLine).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun lines(cards: List<DropCard>): String = cards.take(4).joinToString(". ") { card ->
        val body = card.body?.trim().orEmpty()
        if (card.revealed && body.isNotEmpty()) "${card.title}. $body" else card.title
    }

    fun spokenAccess(summary: AccessSummary): String {
        val confidence = (summary.confidence * 100.0).toInt().coerceIn(0, 100)
        return "Accessibility. Step-free entrance ${word(summary.stepFree)}, " +
            "ramp ${word(summary.ramp)}, accessible restroom ${word(summary.restroom)}. " +
            "Confidence $confidence percent."
    }

    private fun word(answer: AccessAnswer): String = when (answer) {
        AccessAnswer.YES -> "yes"
        AccessAnswer.NO -> "no"
        AccessAnswer.UNKNOWN -> "unknown"
    }
}
