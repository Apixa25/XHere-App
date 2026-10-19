package world.xhere.app.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.Job
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
import world.xhere.shared.reputation.Reputation
import world.xhere.shared.reputation.ReputationStanding

data class ProfileUi(
    val displayName: String = "",
    val handle: String = "",
    val tierLabel: String = "",
    val email: String? = null,
    val notices: List<ImpactNotice> = emptyList(),
    val impactLoaded: Boolean = false,
    val badges: List<BadgeAward> = emptyList(),
    val campaigns: List<CampaignProgress> = emptyList(),
    val standing: ReputationStanding? = null,
)

class ProfileViewModel(
    private val services: XHereServices,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUi())
    val state: StateFlow<ProfileUi> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val previous = _state.value
            val email = runCatching { services.auth.currentSession()?.email }
            val profile = runCatching { services.profiles.currentProfile() }
            val standing = runCatching { services.profiles.reputation() }
            val notices = runCatching { services.impact.myNotices() }
            val badges = runCatching { services.campaigns.myBadges() }
            val campaigns = runCatching { services.campaigns.myCampaigns() }
            if (profile.isSuccess) {
                val person = profile.getOrNull()
                _state.value = person.toUi(
                    email = email.getOrElse { previous.email },
                    notices = notices.getOrElse { previous.notices },
                    badges = badges.getOrNull()?.badges ?: previous.badges,
                    campaigns = campaigns.getOrElse { previous.campaigns },
                    standing = standing.getOrNull()
                        ?: person?.let { Reputation.standing(it.points, it.tier) }
                        ?: previous.standing,
                ).copy(impactLoaded = notices.isSuccess || previous.impactLoaded)
            } else {
                _state.value = previous.copy(
                    email = email.getOrElse { previous.email },
                    notices = notices.getOrElse { previous.notices },
                    badges = badges.getOrNull()?.badges ?: previous.badges,
                    campaigns = campaigns.getOrElse { previous.campaigns },
                    standing = standing.getOrNull() ?: previous.standing,
                    impactLoaded = notices.isSuccess || previous.impactLoaded,
                )
            }
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
    standing: ReputationStanding?,
): ProfileUi {
    if (this == null) {
        return ProfileUi(
            email = email,
            notices = notices,
            impactLoaded = true,
            badges = badges,
            campaigns = campaigns,
            standing = standing,
            tierLabel = standing?.tierLabel.orEmpty(),
        )
    }
    val name = displayName?.takeIf { it.isNotBlank() } ?: handle
    val label = standing?.tierLabel ?: Reputation.label(tier)
    return ProfileUi(
        displayName = name,
        handle = handle,
        tierLabel = label,
        email = email,
        notices = notices,
        impactLoaded = true,
        badges = badges,
        campaigns = campaigns,
        standing = standing ?: Reputation.standing(points, tier),
    )
}
