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
import kotlinx.coroutines.CancellationException
import world.xhere.app.map.MapPins
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.BadgeAward
import world.xhere.shared.model.CampaignProgress
import world.xhere.shared.model.ImpactNotice
import world.xhere.shared.model.OwnDrop
import world.xhere.shared.model.Profile
import world.xhere.shared.profile.AuthorNames
import world.xhere.shared.reputation.Reputation
import world.xhere.shared.reputation.ReputationStanding

data class ProfileUi(
    val displayName: String = "",
    val handle: String = "",
    val tierLabel: String = "",
    val email: String? = null,
    val notices: List<ImpactNotice> = emptyList(),
    val civicNotices: List<world.xhere.shared.civic.CivicNotice> = emptyList(),
    val impactLoaded: Boolean = false,
    val badges: List<BadgeAward> = emptyList(),
    val campaigns: List<CampaignProgress> = emptyList(),
    val standing: ReputationStanding? = null,
    val myDrops: List<OwnDrop> = emptyList(),
    val dropsLoaded: Boolean = false,
    val needsName: Boolean = false,
    val nameDraft: String = "",
    val handleDraft: String = "",
    val savingName: Boolean = false,
    val nameFailed: Boolean = false,
    val undoToken: Int = 0,
    val canUndoDelete: Boolean = false,
)

class ProfileViewModel(
    private val services: XHereServices,
    private val mapPins: MapPins,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUi())
    val state: StateFlow<ProfileUi> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var undoDrop: OwnDrop? = null
    private var undoArmed: Boolean = false

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
            val civicNotices = runCatching { services.civic.notices() }
            val badges = runCatching { services.campaigns.myBadges() }
            val campaigns = runCatching { services.campaigns.myCampaigns() }
            val mine = runCatching { services.drops.myDrops() }
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
                ).copy(
                    civicNotices = civicNotices.getOrElse { previous.civicNotices },
                    impactLoaded = notices.isSuccess || previous.impactLoaded,
                    myDrops = mine.getOrElse { previous.myDrops },
                    dropsLoaded = mine.isSuccess || previous.dropsLoaded,
                    undoToken = previous.undoToken,
                    canUndoDelete = previous.canUndoDelete,
                )
            } else {
                _state.value = previous.copy(
                    email = email.getOrElse { previous.email },
                    notices = notices.getOrElse { previous.notices },
                    civicNotices = civicNotices.getOrElse { previous.civicNotices },
                    badges = badges.getOrNull()?.badges ?: previous.badges,
                    campaigns = campaigns.getOrElse { previous.campaigns },
                    standing = standing.getOrNull() ?: previous.standing,
                    impactLoaded = notices.isSuccess || previous.impactLoaded,
                    myDrops = mine.getOrElse { previous.myDrops },
                    dropsLoaded = mine.isSuccess || previous.dropsLoaded,
                )
            }
        }
    }

    fun markCivicRead(id: kotlin.uuid.Uuid) {
        viewModelScope.launch {
            runCatching { services.civic.markRead(id) }
            _state.update { current ->
                current.copy(
                    civicNotices = current.civicNotices.map { row ->
                        if (row.id == id && row.readAt == null) row.copy(readAt = Clock.System.now()) else row
                    },
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

    fun onNameDraft(value: String) {
        _state.update { it.copy(nameDraft = value.take(50), nameFailed = false) }
    }

    fun onHandleDraft(value: String) {
        _state.update { it.copy(handleDraft = value.take(24), nameFailed = false) }
    }

    fun saveName() {
        val snapshot = _state.value
        if (snapshot.savingName || snapshot.nameDraft.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(savingName = true, nameFailed = false) }
            try {
                val saved = services.profiles.setPublicName(
                    snapshot.nameDraft,
                    snapshot.handleDraft.trim().ifBlank { null },
                )
                _state.update {
                    it.copy(
                        savingName = false,
                        displayName = saved.displayName?.takeIf { name -> name.isNotBlank() } ?: saved.handle,
                        handle = saved.handle,
                        needsName = AuthorNames.needsChoice(saved.displayName, saved.handle),
                        nameDraft = saved.displayName.orEmpty(),
                        handleDraft = saved.handle,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(savingName = false, nameFailed = true) }
            }
        }
    }

    fun deleteDrop(drop: OwnDrop) {
        undoDrop = drop
        undoArmed = true
        mapPins.hide(drop.shortId)
        _state.update {
            it.copy(
                myDrops = it.myDrops.filter { row -> row.id != drop.id },
                canUndoDelete = true,
                undoToken = it.undoToken + 1,
            )
        }
        viewModelScope.launch {
            try {
                services.drops.deleteMyDrop(drop.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                undoArmed = false
                mapPins.restore(drop.shortId)
                refresh()
            }
        }
    }

    fun undoDelete() {
        if (!undoArmed) return
        undoArmed = false
        val drop = undoDrop ?: return
        viewModelScope.launch {
            runCatching { services.drops.restoreMyDrop(drop.id) }
            mapPins.restore(drop.shortId)
            _state.update { it.copy(canUndoDelete = false) }
            refresh()
        }
    }

    fun expireUndo() {
        if (!undoArmed) return
        undoArmed = false
        _state.update { it.copy(canUndoDelete = false) }
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
    val chosen = displayName?.takeIf { it.isNotBlank() }
    val name = chosen ?: AuthorNames.label(null, handle).orEmpty()
    val label = standing?.tierLabel ?: Reputation.label(tier)
    return ProfileUi(
        displayName = name,
        handle = handle,
        needsName = AuthorNames.needsChoice(displayName, handle),
        nameDraft = chosen.orEmpty(),
        handleDraft = if (AuthorNames.needsChoice(displayName, handle)) "" else handle,
        tierLabel = label,
        email = email,
        notices = notices,
        impactLoaded = true,
        badges = badges,
        campaigns = campaigns,
        standing = standing ?: Reputation.standing(points, tier),
    )
}
