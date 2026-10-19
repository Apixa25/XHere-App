package world.xhere.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** One spoken line. Null when the person cancelled or the recognizer had nothing to say. */
fun interface SpeechCapture {
    suspend fun listen(): String?
}

/**
 * On-device speech. API 31+ uses the on-device recognizer when the phone has one.
 * Older phones still ask for offline recognition via [RecognizerIntent.EXTRA_PREFER_OFFLINE].
 * Nothing is uploaded by this class.
 */
class AndroidSpeechCapture(
    context: Context,
) : SpeechCapture {
    private val appContext = context.applicationContext

    override suspend fun listen(): String? = suspendCancellableCoroutine { continuation ->
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val recognizer = create()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }

            override fun onError(error: Int) {
                finish(null)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            private fun finish(text: String?) {
                if (!continuation.isActive) return
                continuation.resume(text?.trim()?.ifEmpty { null })
                runCatching { recognizer.destroy() }
            }
        })
        continuation.invokeOnCancellation {
            runCatching {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer.startListening(intent) }.onFailure {
            if (continuation.isActive) continuation.resume(null)
            runCatching { recognizer.destroy() }
        }
    }

    private fun create(): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        }
        return SpeechRecognizer.createSpeechRecognizer(appContext)
    }
}
