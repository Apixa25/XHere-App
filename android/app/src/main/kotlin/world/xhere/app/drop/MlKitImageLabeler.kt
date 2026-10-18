package world.xhere.app.drop

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bundled on-device labels (ML Kit image labeling). A failure returns no labels
 * so the person can still type a category. Nothing is uploaded to Google.
 */
class MlKitImageLabeler : ImageLabeler {
    private val client by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }

    override suspend fun labels(bytes: ByteArray, mimeType: String): List<String> {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            client.process(image)
                .addOnSuccessListener { labeled ->
                    if (continuation.isActive) continuation.resume(labeled.map { it.text })
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }
    }
}
