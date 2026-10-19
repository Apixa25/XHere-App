package world.xhere.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.app.config.ClientConfig
import world.xhere.app.session.SignOutNotice
import world.xhere.shared.data.IdentityProvider
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.demo.JamestownDemo

data class AuthUiState(
    val email: String = "",
    val code: String = "",
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val showOfflineNotice: Boolean = false,
    /** False until a real GOOGLE_WEB_CLIENT_ID is set, so testers never see a button that can only fail. */
    val showGoogle: Boolean = true,
)

class AuthViewModel(
    private val services: XHereServices,
    private val google: GoogleIdTokenSource,
    private val config: ClientConfig,
    private val notice: SignOutNotice,
    private val strings: AuthStrings,
    private val draft: AuthDraft = MemoryAuthDraft(),
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    val banner: StateFlow<String?> = notice.message

    init {
        viewModelScope.launch {
            _state.collect { current -> draft.write(current.email, current.codeSent) }
        }
        if (config.serverReady) {
            viewModelScope.launch {
                services.auth.sessionChanges().drop(1).collect { session ->
                    if (session == null) resetForm()
                }
            }
        }
    }

    fun onEmail(value: String) {
        notice.clear()
        _state.update { it.copy(email = value, error = null) }
    }

    fun onCode(value: String) {
        _state.update { it.copy(code = value, error = null) }
    }

    fun useDifferentEmail() {
        _state.update { it.copy(codeSent = false, code = "", error = null) }
    }

    fun sendCode() {
        val email = _state.value.email.trim()
        if (!looksLikeEmail(email)) {
            _state.update { it.copy(error = strings.emailInvalid) }
            return
        }
        if (!config.serverReady) {
            _state.update { it.copy(error = strings.notConnected) }
            return
        }
        launchBusy {
            services.auth.signInWithEmailOtp(email)
            _state.update { it.copy(busy = false, codeSent = true, error = null) }
        }
    }

    fun verifyCode() {
        val email = _state.value.email.trim()
        val code = _state.value.code.trim()
        if (code.isEmpty()) {
            _state.update { it.copy(error = strings.codeRejected) }
            return
        }
        if (!config.serverReady) {
            _state.update { it.copy(error = strings.notConnected) }
            return
        }
        launchBusy {
            services.auth.verifyEmailOtp(email, code)
            _state.update { it.copy(busy = false) }
        }
    }

    /** Local Jamestown drops only. Shown when no Supabase project is configured. */
    fun previewJamestown() {
        if (config.serverReady) return
        launchBusy {
            services.auth.signInWithEmailOtp(JamestownDemo.PREVIEW_EMAIL)
            services.auth.verifyEmailOtp(JamestownDemo.PREVIEW_EMAIL, JamestownDemo.PREVIEW_CODE)
            _state.update { it.copy(busy = false) }
        }
    }

    fun signInWithGoogle() {
        if (!config.serverReady) {
            _state.update { it.copy(error = strings.notConnected) }
            return
        }
        if (!config.googleReady) {
            _state.update { it.copy(error = strings.googleUnconfigured) }
            return
        }
        launchBusy {
            when (val attempt = google.request()) {
                is GoogleSignInAttempt.Success -> {
                    // Same raw nonce returned with the token. Do not hash it and do not mint another.
                    services.auth.signInWithIdToken(
                        provider = IdentityProvider.GOOGLE,
                        idToken = attempt.idToken,
                        nonce = attempt.rawNonce,
                    )
                    _state.update { it.copy(busy = false) }
                }
                // Dismissing the account sheet is not a failure, so the form stays quiet.
                GoogleSignInAttempt.Cancelled -> _state.update { it.copy(busy = false, error = null) }
                is GoogleSignInAttempt.Failed -> _state.update { it.copy(busy = false, error = attempt.message) }
            }
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.toAuthMessage(strings)) }
            }
        }
    }

    private fun resetForm() {
        draft.write("", false)
        _state.value = initialState()
    }

    private fun initialState(): AuthUiState {
        val saved = draft.read()
        return AuthUiState(
            email = saved.email,
            codeSent = saved.codeSent && saved.email.isNotBlank(),
            showOfflineNotice = !config.serverReady,
            showGoogle = config.googleReady,
        )
    }
}

/** Resolved string resources, so the ViewModel stays free of a Context. */
data class AuthStrings(
    val emailInvalid: String,
    val codeRejected: String,
    val googleRejected: String,
    /** Reserved. A dismissed Google sheet shows no message. */
    val googleCancelled: String,
    val googleUnconfigured: String,
    val failed: String,
    val network: String,
    val notConnected: String,
)

internal fun looksLikeEmail(email: String): Boolean {
    val at = email.indexOf('@')
    if (at <= 0 || email.contains(' ')) return false
    val dot = email.indexOf('.', startIndex = at + 1)
    return dot > at + 1 && dot < email.lastIndex
}

internal fun Throwable.toAuthMessage(strings: AuthStrings): String {
    if (isConnectivityFailure()) return strings.network
    if (mentionsNonceMismatch()) return strings.googleRejected
    return when (this) {
        is XHereApiException -> when (code) {
            "invalid_email" -> strings.emailInvalid
            "otp_expired" -> strings.codeRejected
            "invalid_grant", "invalid_nonce" -> strings.googleRejected
            else -> strings.failed
        }
        else -> strings.failed
    }
}
