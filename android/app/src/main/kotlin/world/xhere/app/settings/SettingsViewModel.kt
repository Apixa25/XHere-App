package world.xhere.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.app.session.SignOutNotice
import world.xhere.shared.data.XHereServices

data class SettingsUiState(
    val email: String? = null,
    val handle: String? = null,
    val locationGranted: Boolean = false,
    val busy: Boolean = false,
    val confirmingDelete: Boolean = false,
    val error: String? = null,
)

/**
 * Sign-out and account deletion. Deletion calls [world.xhere.shared.data.AccountRepository.deleteMyAccount],
 * which the Supabase client implements as the `delete_my_account` RPC and then signs out.
 */
class SettingsViewModel(
    private val services: XHereServices,
    private val notice: SignOutNotice,
    private val deletedMessage: String,
    private val deleteFailed: String,
    private val signOutFailed: String,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val email = runCatching { services.auth.currentSession()?.email }.getOrNull()
            val handle = runCatching { services.profiles.currentProfile().handle }.getOrNull()
            _state.update { it.copy(email = email, handle = handle) }
        }
    }

    fun setLocationGranted(granted: Boolean) {
        _state.update { it.copy(locationGranted = granted) }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                services.auth.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = signOutFailed) }
            }
        }
    }

    fun askDelete() {
        _state.update { it.copy(confirmingDelete = true, error = null) }
    }

    fun dismissDelete() {
        _state.update { it.copy(confirmingDelete = false) }
    }

    fun confirmDelete() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, confirmingDelete = false, error = null) }
            try {
                services.account.deleteMyAccount()
                notice.show(deletedMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = deleteFailed) }
            }
        }
    }
}
