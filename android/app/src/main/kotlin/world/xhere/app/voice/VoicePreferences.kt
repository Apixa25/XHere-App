package world.xhere.app.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Background hearing is off until the signed-in person turns it on. */
class VoicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _background = MutableStateFlow(prefs.getBoolean(KEY, false))
    val background: StateFlow<Boolean> = _background.asStateFlow()

    fun setBackground(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        _background.value = enabled
    }

    private companion object {
        const val FILE = "xhere_voice"
        const val KEY = "background_scan"
    }
}
