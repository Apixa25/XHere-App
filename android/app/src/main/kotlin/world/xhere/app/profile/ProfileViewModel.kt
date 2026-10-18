package world.xhere.app.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.Profile

data class ProfileUi(
    val displayName: String = "",
    val handle: String = "",
    val tierLabel: String = "",
    val email: String? = null,
)

class ProfileViewModel(
    private val services: XHereServices,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUi())
    val state: StateFlow<ProfileUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val email = runCatching { services.auth.currentSession()?.email }.getOrNull()
            val profile = runCatching { services.profiles.currentProfile() }.getOrNull()
            _state.value = profile.toUi(email)
        }
    }
}

private fun Profile?.toUi(email: String?): ProfileUi {
    if (this == null) return ProfileUi(email = email)
    val name = displayName?.takeIf { it.isNotBlank() } ?: handle
    return ProfileUi(
        displayName = name,
        handle = handle,
        tierLabel = tier.name.lowercase().replaceFirstChar { it.uppercase() },
        email = email,
    )
}
