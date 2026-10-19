package world.xhere.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads a Here Card summary out loud. The engine is the one on the phone. */
interface HereSpeaker {
    fun speak(text: String)

    fun stop()
}

class AndroidHereSpeaker(
    context: Context,
) : HereSpeaker {
    private val engine: TextToSpeech

    init {
        lateinit var created: TextToSpeech
        created = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { created.language = Locale.getDefault() }
            }
        }
        engine = created
    }

    override fun speak(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, "xhere-here")
    }

    override fun stop() {
        runCatching { engine.stop() }
    }
}
