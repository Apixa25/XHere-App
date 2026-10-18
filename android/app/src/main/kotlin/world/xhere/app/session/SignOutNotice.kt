package world.xhere.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot copy shown on the sign-in screen after account deletion. */
class SignOutNotice {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun show(text: String) {
        _message.value = text
    }

    fun clear() {
        _message.value = null
    }
}
