package world.xhere.app.voice

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.withTimeout
import world.xhere.shared.model.Category
import world.xhere.shared.voice.VoiceDrafts
import world.xhere.shared.voice.VoiceIntents
import world.xhere.shared.voice.VoiceParse

/** Turns one transcript into a drop or a question. Rules always work. */
interface VoiceRefiner {
    suspend fun refine(transcript: String, categories: List<Category>): VoiceParse?
}

/** Keyword rules. No model and no network. */
class RulesVoiceRefiner : VoiceRefiner {
    override suspend fun refine(transcript: String, categories: List<Category>): VoiceParse? =
        VoiceIntents.parse(transcript)
}

/**
 * Gemini Nano through ML Kit, same gate as Snap-to-drop.
 * The prompt runs only when [FeatureStatus.AVAILABLE]. A missing model,
 * a timeout, or an unknown category falls back to [RulesVoiceRefiner].
 * Nothing is sent to a server and nothing is paid for.
 */
class GeminiNanoVoiceRefiner(
    context: Context,
    private val rules: VoiceRefiner = RulesVoiceRefiner(),
) : VoiceRefiner {
    @Suppress("unused")
    private val appContext = context.applicationContext

    override suspend fun refine(transcript: String, categories: List<Category>): VoiceParse? {
        val fallback = rules.refine(transcript, categories)
        val prompted = runCatching { withTimeout(PROMPT_TIMEOUT_MS) { prompt(transcript, categories) } }.getOrNull()
        return prompted ?: fallback
    }

    private suspend fun prompt(transcript: String, categories: List<Category>): VoiceParse? {
        val model = Generation.getClient()
        try {
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return null
            val response = model.generateContent(
                generateContentRequest(TextPart(VoiceDrafts.promptFor(categories) + "\nLINE: " + transcript)) {
                    temperature = 0.2f
                    maxOutputTokens = 180
                },
            )
            val text = response.candidates.firstOrNull()?.text.orEmpty()
            if (text.isBlank()) return null
            return VoiceDrafts.fromPrompt("TRANSCRIPT: $transcript\n$text", categories)
        } finally {
            runCatching { model.close() }
        }
    }

    private companion object {
        const val PROMPT_TIMEOUT_MS = 8_000L
    }
}
