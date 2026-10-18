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

data class AuthUiState(
    val email: String = "",
    val code: String = "",
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val showOfflineNotice: Boolean = false,
)

class AuthViewModel(
    private val services: XHereServices,
    private val google: GoogleIdTokenSource,
    private val config: ClientConfig,
    private val notice: SignOutNotice,
    private val strings: AuthStrings,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState(showOfflineNotice = !config.serverReady))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    val banner: StateFlow<String?> = notice.message

    init {
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
                    services.auth.signInWithIdToken(
                        provider = IdentityProvider.GOOGLE,
                        idToken = attempt.idToken,
                        nonce = attempt.rawNonce,
                    )
                    _state.update { it.copy(busy = false) }
                }
                GoogleSignInAttempt.Cancelled -> _state.update { it.copy(busy = false, error = strings.googleCancelled) }
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
        _state.value = AuthUiState(showOfflineNotice = !config.serverReady)
    }
}

/** Resolved string resources, so the ViewModel stays free of a Context. */
data class AuthStrings(
    val emailInvalid: String,
    val codeRejected: String,
    val googleRejected: String,
    val googleCancelled: String,
    val googleUnconfigured: String,
    val failed: String,
    val notConnected: String,
)

internal fun looksLikeEmail(email: String): Boolean {
    val at = email.indexOf('@')
    if (at <= 0 || email.contains(' ')) return false
    val dot = email.indexOf('.', startIndex = at + 1)
    return dot > at + 1 && dot < email.lastIndex
}

internal fun Throwable.toAuthMessage(strings: AuthStrings): String = when (this) {
    is XHereApiException -> when (code) {
        "invalid_email" -> strings.emailInvalid
        "otp_expired" -> strings.codeRejected
        "invalid_grant" -> strings.googleRejected
        else -> strings.failed
    }
    else -> strings.failed
}
