package world.xhere.app.drop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import world.xhere.shared.model.Category
import world.xhere.shared.suggest.SnapDraft
import world.xhere.shared.suggest.SnapDrafts

/** On-device photo suggestion. A null draft means the composer shows the normal form. */
interface SnapSuggester {
    suspend fun suggest(bytes: ByteArray, mimeType: String, categories: List<Category>): SnapOutcome
}

data class SnapOutcome(
    val draft: SnapDraft?,
    val labels: List<String>,
)

/** Bundled ML Kit image labeling. Free, on every device, nothing uploaded. */
class LabelSnapSuggester(
    private val labeler: ImageLabeler,
) : SnapSuggester {
    override suspend fun suggest(bytes: ByteArray, mimeType: String, categories: List<Category>): SnapOutcome {
        val labels = runCatching { labeler.labels(bytes, mimeType) }.getOrDefault(emptyList())
        return SnapOutcome(SnapDrafts.fromLabels(labels, categories), labels)
    }
}

/**
 * Gemini Nano through ML Kit GenAI, on the device only.
 *
 * Prompt API is tried first when [FeatureStatus.AVAILABLE]. It can return a
 * title, a category slug, a short description, and alt text. Image Description
 * is the fallback Gemini Nano API: a short description used as alt text, with
 * the category taken from labels or the existing ranker.
 *
 * Google's device list (ML Kit GenAI, checked 2026-10-16) is the floor, and
 * [FeatureStatus] is the runtime check. Image Description includes Pixel 9 and
 * later and Galaxy S25 and later. Prompt API includes Pixel 9 and later; Galaxy
 * S25 is on Image Description and not on the Prompt API list (Galaxy S26 is).
 * A model that is missing, still downloading, or not supported is skipped.
 * Nothing is sent to a server and nothing is paid for. The bundled labeler
 * then suggests a category and alt text, or the normal form is shown.
 */
class GeminiNanoSnapSuggester(
    context: Context,
    private val labels: LabelSnapSuggester,
) : SnapSuggester {
    private val appContext = context.applicationContext

    override suspend fun suggest(bytes: ByteArray, mimeType: String, categories: List<Category>): SnapOutcome {
        val labeled = labels.suggest(bytes, mimeType, categories)
        val bitmap = withContext(Dispatchers.Default) { decode(bytes) } ?: return labeled
        val prompted = runCatching { withTimeout(PROMPT_TIMEOUT_MS) { prompt(bitmap, categories, labeled.labels) } }.getOrNull()
        if (prompted != null) return SnapOutcome(prompted, labeled.labels)
        val described = runCatching { withTimeout(PROMPT_TIMEOUT_MS) { describe(bitmap, labeled.labels, categories) } }.getOrNull()
        if (described != null) return SnapOutcome(described, labeled.labels)
        return labeled
    }

    private suspend fun prompt(bitmap: Bitmap, categories: List<Category>, labels: List<String>): SnapDraft? {
        val model = Generation.getClient()
        try {
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return null
            val response = model.generateContent(
                generateContentRequest(ImagePart(bitmap), TextPart(promptFor(categories))) {
                    temperature = 0.2f
                    maxOutputTokens = 220
                },
            )
            val text = response.candidates.firstOrNull()?.text.orEmpty()
            if (text.isBlank()) return null
            return SnapDrafts.fromPrompt(text, labels, categories)
        } finally {
            runCatching { model.close() }
        }
    }

    private suspend fun describe(bitmap: Bitmap, labels: List<String>, categories: List<Category>): SnapDraft? {
        val client = ImageDescription.getClient(ImageDescriberOptions.builder(appContext).build())
        try {
            val status = client.checkFeatureStatus().awaitResult() ?: return null
            if (status != FeatureStatus.AVAILABLE) return null
            val request = ImageDescriptionRequest.builder(bitmap).build()
            val description = client.runInference(request).awaitResult()?.description?.trim().orEmpty()
            if (description.isEmpty()) return null
            return SnapDrafts.fromImageDescription(description, labels, categories)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun promptFor(categories: List<Category>): String {
        val slugs = categories.filter { it.active }.joinToString(", ") { "${it.slug} (${it.label})" }
        return """
            Look at this photo and suggest a map drop. Reply with exactly four lines:
            TITLE: a short title, at most 80 characters
            CATEGORY: one slug from this list: $slugs
            DESCRIPTION: one short sentence
            ALT: alt text a blind person can use to understand the photo
            Do not add any other lines.
        """.trimIndent()
    }

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private companion object {
        const val PROMPT_TIMEOUT_MS = 8_000L
    }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(): T? = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            if (!continuation.isActive) return@addListener
            try {
                continuation.resume(get())
            } catch (_: Throwable) {
                continuation.resume(null)
            }
        },
        MoreExecutors.directExecutor(),
    )
    continuation.invokeOnCancellation { cancel(true) }
}
