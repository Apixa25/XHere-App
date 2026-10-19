package world.xhere.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.app.session.SignOutNotice
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.canModerate

enum class ExportStatus { WORKING, READY, FAILED }

data class SettingsUiState(
    val email: String? = null,
    val handle: String? = null,
    val locationGranted: Boolean = false,
    val busy: Boolean = false,
    val confirmingDelete: Boolean = false,
    val error: String? = null,
    val isModerator: Boolean = false,
    val export: ExportStatus? = null,
    /** From `my_badges`. The switch stays off the screen until that read returns. */
    val showOnLeaderboard: Boolean = true,
    val leaderboardReady: Boolean = false,
)

/**
 * Sign-out and account deletion. Deletion calls [world.xhere.shared.data.AccountRepository.deleteMyAccount],
 * which the Supabase client implements as the `delete_my_account` RPC and then signs out.
 * Download my data calls `export_my_data` and hands the JSON to [exporter].
 */
class SettingsViewModel(
    private val services: XHereServices,
    private val notice: SignOutNotice,
    private val deletedMessage: String,
    private val deleteFailed: String,
    private val signOutFailed: String,
    private val exporter: DataExporter,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val email = runCatching { services.auth.currentSession()?.email }.getOrNull()
            val profile = runCatching { services.profiles.currentProfile() }.getOrNull()
            val shelf = runCatching { services.campaigns.myBadges() }.getOrNull()
            _state.update {
                it.copy(
                    email = email,
                    handle = profile?.handle,
                    isModerator = profile?.canModerate() == true,
                    showOnLeaderboard = shelf?.showOnLeaderboard ?: it.showOnLeaderboard,
                    leaderboardReady = shelf != null,
                )
            }
        }
    }

    /** Hides or shows the display name on every campaign board. The drop still counts. */
    fun setShowOnLeaderboard(show: Boolean) {
        if (!_state.value.leaderboardReady || _state.value.showOnLeaderboard == show) return
        val previous = _state.value.showOnLeaderboard
        _state.update { it.copy(showOnLeaderboard = show) }
        viewModelScope.launch {
            try {
                services.campaigns.setShowOnLeaderboard(show)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(showOnLeaderboard = previous) }
            }
        }
    }

    fun setLocationGranted(granted: Boolean) {
        _state.update { it.copy(locationGranted = granted) }
    }

    fun exportData() {
        if (_state.value.export == ExportStatus.WORKING) return
        viewModelScope.launch {
            _state.update { it.copy(export = ExportStatus.WORKING) }
            try {
                val json = services.account.exportMyData()
                val day = clock.now().toString().take(10)
                exporter.export("xhere-export-$day.json", json)
                _state.update { it.copy(export = ExportStatus.READY) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(export = ExportStatus.FAILED) }
            }
        }
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
