package world.xhere.app.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.BadgeAward
import world.xhere.shared.model.PublicDrop
import world.xhere.shared.model.ReportReason
import world.xhere.shared.model.ReportTarget
import world.xhere.shared.profile.AuthorNames
import world.xhere.shared.reputation.Reputation
import kotlin.uuid.Uuid

data class PublicProfileUi(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val name: String = "",
    val handle: String = "",
    val showHandle: Boolean = false,
    val bio: String = "",
    val homeArea: String = "",
    val website: String = "",
    val avatarUrl: String? = null,
    val tierLabel: String = "",
    val badges: List<BadgeAward> = emptyList(),
    val dropCount: Int = 0,
    val drops: List<PublicDrop> = emptyList(),
    val mine: Boolean = false,
    val reporting: Boolean = false,
    val sending: Boolean = false,
    val reported: Boolean = false,
    val failed: Boolean = false,
)

class PublicProfileViewModel(
    private val handle: String,
    private val services: XHereServices,
) : ViewModel() {
    private val _state = MutableStateFlow(PublicProfileUi())
    val state: StateFlow<PublicProfileUi> = _state.asStateFlow()
    private var authorId: Uuid? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val card = runCatching { services.profiles.publicProfile(handle) }.getOrNull()
            val mine = runCatching { services.profiles.currentProfile().id }.getOrNull()
            if (card == null) {
                _state.value = PublicProfileUi(loading = false, missing = true, handle = handle)
                return@launch
            }
            authorId = card.id
            _state.value = PublicProfileUi(
                loading = false,
                name = AuthorNames.label(card.displayName, card.handle).orEmpty(),
                handle = card.handle,
                showHandle = AuthorNames.label(null, card.handle) != null,
                bio = card.bio.orEmpty(),
                homeArea = card.homeArea.orEmpty(),
                website = card.website.orEmpty(),
                avatarUrl = services.media.avatarUrl(card.avatarPath),
                tierLabel = Reputation.label(card.tier),
                badges = card.badges,
                dropCount = card.dropCount,
                drops = card.recentDrops,
                mine = card.id == mine,
            )
        }
    }

    fun toggleReport() {
        if (_state.value.mine) return
        _state.update { it.copy(reporting = !it.reporting, failed = false) }
    }

    fun report(reason: ReportReason, details: String?) {
        val id = authorId ?: return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, failed = false) }
            try {
                services.reports.report(ReportTarget.PROFILE, id, reason, details)
                _state.update { it.copy(sending = false, reporting = false, reported = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(sending = false, failed = true) }
            }
        }
    }
}
