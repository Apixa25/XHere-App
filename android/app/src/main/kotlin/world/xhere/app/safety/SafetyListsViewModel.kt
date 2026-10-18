package world.xhere.app.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.BlockedUser
import world.xhere.shared.model.HiddenDrop

data class SafetyListsUiState(
    val loading: Boolean = true,
    val blocked: List<BlockedUser> = emptyList(),
    val hidden: List<HiddenDrop> = emptyList(),
    val failed: Boolean = false,
)

/** Settings, Blocked and hidden: undo a block or a hide-for-me. */
class SafetyListsViewModel(private val services: XHereServices) : ViewModel() {
    private val _state = MutableStateFlow(SafetyListsUiState())
    val state: StateFlow<SafetyListsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun unblock(user: BlockedUser) = act { services.blocks.unblockUser(user.userId) }

    fun unhide(drop: HiddenDrop) = act { services.hidden.unhideDrop(drop.dropId) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(failed = true) }
            }
        }
    }

    private suspend fun reload() {
        try {
            val blocked = services.blocks.myBlocks()
            val hidden = services.hidden.myHiddenDrops()
            _state.value = SafetyListsUiState(loading = false, blocked = blocked, hidden = hidden)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(loading = false, failed = true) }
        }
    }
}
