package world.xhere.app.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.BadgeAward
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.ImpactNotice
import world.xhere.shared.model.Profile

data class ProfileUi(
    val displayName: String = "",
    val handle: String = "",
    val tierLabel: String = "",
    val email: String? = null,
    val notices: List<ImpactNotice> = emptyList(),
    val impactLoaded: Boolean = false,
    val badges: List<BadgeAward> = emptyList(),
    val campaigns: List<CampaignProgress> = emptyList(),
)

class ProfileViewModel(
    private val services: XHereServices,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUi())
    val state: StateFlow<ProfileUi> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val email = runCatching { services.auth.currentSession()?.email }.getOrNull()
            val profile = runCatching { services.profiles.currentProfile() }.getOrNull()
            val notices = runCatching { services.impact.myNotices() }.getOrDefault(emptyList())
            val badges = runCatching { services.campaigns.myBadges() }.getOrNull()?.badges.orEmpty()
            val campaigns = runCatching { services.campaigns.myCampaigns() }.getOrDefault(emptyList())
            _state.value = profile.toUi(email, notices, badges, campaigns)
        }
    }

    /** Marks the note read, then opens the drop. One note per drop per day, so this is not a second ping. */
    fun openNotice(notice: ImpactNotice, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { services.impact.markRead(notice.id) }
            _state.update { current ->
                current.copy(
                    notices = current.notices.map { row ->
                        if (row.id == notice.id && row.readAt == null) row.copy(readAt = Clock.System.now()) else row
                    },
                )
            }
            onOpen(notice.shortId)
        }
    }
}

private fun Profile?.toUi(
    email: String?,
    notices: List<ImpactNotice>,
    badges: List<BadgeAward>,
    campaigns: List<CampaignProgress>,
): ProfileUi {
    if (this == null) {
        return ProfileUi(email = email, notices = notices, impactLoaded = true, badges = badges, campaigns = campaigns)
    }
    val name = displayName?.takeIf { it.isNotBlank() } ?: handle
    return ProfileUi(
        displayName = name,
        handle = handle,
        tierLabel = tier.name.lowercase().replaceFirstChar { it.uppercase() },
        email = email,
        notices = notices,
        impactLoaded = true,
        badges = badges,
        campaigns = campaigns,
    )
}
