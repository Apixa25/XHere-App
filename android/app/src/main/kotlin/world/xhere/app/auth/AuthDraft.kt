package world.xhere.app.auth

import android.content.Context

/** Email and whether the code step is showing. The code itself is not stored. */
data class AuthDraftState(
    val email: String = "",
    val codeSent: Boolean = false,
)

/** Survives process death so a half-finished sign-in is still on the code step. */
interface AuthDraft {
    fun read(): AuthDraftState

    fun write(email: String, codeSent: Boolean)
}

class MemoryAuthDraft : AuthDraft {
    private var state = AuthDraftState()

    override fun read(): AuthDraftState = state

    override fun write(email: String, codeSent: Boolean) {
        state = AuthDraftState(email, codeSent)
    }
}

class PrefsAuthDraft(context: Context) : AuthDraft {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): AuthDraftState = AuthDraftState(
        email = prefs.getString(EMAIL, "").orEmpty(),
        codeSent = prefs.getBoolean(CODE_SENT, false),
    )

    override fun write(email: String, codeSent: Boolean) {
        prefs.edit().putString(EMAIL, email).putBoolean(CODE_SENT, codeSent).apply()
    }

    companion object {
        const val PREFS = "xhere_auth_draft"
        private const val EMAIL = "email"
        private const val CODE_SENT = "code_sent"
    }
}
