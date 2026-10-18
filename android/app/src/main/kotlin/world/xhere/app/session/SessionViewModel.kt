package world.xhere.app.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import world.xhere.app.config.ClientConfig
import world.xhere.app.onboarding.OnboardingStore
import world.xhere.shared.data.SessionInfo
import world.xhere.shared.data.XHereServices

sealed interface Gate {
    data object Loading : Gate
    data object SignedOut : Gate
    data object NeedsOnboarding : Gate
    data object Ready : Gate
}

/**
 * Session plus the one-time location rationale. When [ClientConfig.serverReady]
 * is false the placeholder client is not called, so the shell boots with no project.
 */
class SessionViewModel(
    private val services: XHereServices,
    private val onboarding: OnboardingStore,
    private val config: ClientConfig,
) : ViewModel() {
    private val session = MutableStateFlow<SessionInfo?>(null)
    private val onboardingComplete = MutableStateFlow(onboarding.isComplete())

    /** Placeholder config never touches the network, so the shell is ready immediately. */
    private val restored = MutableStateFlow(!config.serverReady)

    val gate: StateFlow<Gate> = combine(session, onboardingComplete, restored) { current, done, ready ->
        if (!ready) {
            Gate.Loading
        } else {
            when {
                current == null -> Gate.SignedOut
                !done -> Gate.NeedsOnboarding
                else -> Gate.Ready
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Gate.Loading)

    init {
        if (config.serverReady) {
            viewModelScope.launch {
                try {
                    session.value = services.auth.currentSession()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    session.value = null
                }
                restored.value = true
                try {
                    services.auth.sessionChanges().collect { session.value = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    session.value = null
                }
            }
        }
    }

    fun completeOnboarding() {
        onboarding.markComplete()
        onboardingComplete.value = true
    }
}
