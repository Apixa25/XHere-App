package world.xhere.app.voice

import android.content.Context
import world.xhere.shared.voice.VoiceAutoPost

/**
 * Driving mode lives on the phone. Off unless the person turns it on.
 * It is not an account setting and it is not sent to the server.
 */
class VoicePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoPost(): Boolean = prefs.getBoolean(AUTO_POST, VoiceAutoPost.DEFAULT_ENABLED)

    fun setAutoPost(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_POST, enabled).apply()
    }

    private companion object {
        const val PREFS = "xhere_voice"
        const val AUTO_POST = "auto_post"
    }
}
