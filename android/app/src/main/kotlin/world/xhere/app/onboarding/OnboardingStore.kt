package world.xhere.app.onboarding

import android.content.Context

/** Device-local flag. Signing out does not show the rationale again. */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = prefs.getBoolean(KEY, false)

    fun markComplete() {
        prefs.edit().putBoolean(KEY, true).apply()
    }

    companion object {
        const val PREFS = "xhere_onboarding"
        const val KEY = "complete"
    }
}
