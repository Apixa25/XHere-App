package world.xhere.app.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import world.xhere.shared.data.XHereApiException
import world.xhere.shared.data.XHereServices
import world.xhere.shared.model.DropStatus
import world.xhere.shared.model.ModerateDropAction
import world.xhere.shared.model.ModerationQueueItem
import world.xhere.shared.model.QueueItemKind
import world.xhere.shared.integrity.LocationIntegrityFlag
import world.xhere.shared.model.ClaimQueueItem
import world.xhere.shared.model.ReportStatus

enum class ModAction(val slug: String) {
    APPROVE("approve"),
    REMOVE("remove"),
    RESTORE("restore"),
    DISMISS("dismiss"),
    ACTION("action"),
    SUSPEND("suspend"),
    UNSUSPEND("unsuspend"),
}

enum class ModMessage { DONE, FAILED }

data class ModerationUiState(
    val loading: Boolean = true,
    val denied: Boolean = false,
    val failed: Boolean = false,
    val items: List<ModerationQueueItem> = emptyList(),
    val flags: List<LocationIntegrityFlag> = emptyList(),
    val claims: List<ClaimQueueItem> = emptyList(),
    val busy: Boolean = false,
    val message: ModMessage? = null,
)

/** Stable id for tags: the short id for drops, the handle for profiles. */
val ModerationQueueItem.key: String get() = shortId ?: subjectHandle ?: targetId.toString()

/**
 * The moderator queue. The server checks the role on every call
 * (`moderation_queue`, `moderate_drop`, `resolve_report`, `suspend_user`)
 * and writes the audit row; this screen only picks the action.
 */
class ModerationViewModel(
    private val services: XHereServices,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val _state = MutableStateFlow(ModerationUiState())
    val state: StateFlow<ModerationUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    /** What a moderator can do with [item] right now. */
    fun actionsFor(item: ModerationQueueItem): List<ModAction> = buildList {
        if (item.kind == QueueItemKind.DROP) {
            if (item.isPending) add(ModAction.APPROVE)
            if (item.status == DropStatus.DISPUTED) add(ModAction.RESTORE)
            add(ModAction.REMOVE)
            if (!item.isPending && item.reportIds.isNotEmpty()) add(ModAction.DISMISS)
        } else if (item.reportIds.isNotEmpty()) {
            add(ModAction.DISMISS)
            add(ModAction.ACTION)
        }
        if (item.subjectId != null) {
            add(if (item.isSubjectSuspended(clock.now())) ModAction.UNSUSPEND else ModAction.SUSPEND)
        }
    }

    fun approveClaim(item: ClaimQueueItem) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                if (item.kind == "claim") {
                    services.desk.review(item.id, approve = true, note = null)
                } else {
                    services.desk.resolveDispute(item.id, dismiss = false)
                }
                reload()
                _state.update { it.copy(busy = false, message = ModMessage.DONE) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reload()
                _state.update { it.copy(busy = false, message = ModMessage.FAILED) }
            }
        }
    }

    fun rejectClaim(item: ClaimQueueItem) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                if (item.kind == "claim") {
                    services.desk.review(item.id, approve = false, note = null)
                } else {
                    services.desk.resolveDispute(item.id, dismiss = true)
                }
                reload()
                _state.update { it.copy(busy = false, message = ModMessage.DONE) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reload()
                _state.update { it.copy(busy = false, message = ModMessage.FAILED) }
            }
        }
    }

    fun revokeClaim(claimId: kotlin.uuid.Uuid) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                services.desk.revoke(claimId, "revoked")
                reload()
                _state.update { it.copy(busy = false, message = ModMessage.DONE) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reload()
                _state.update { it.copy(busy = false, message = ModMessage.FAILED) }
            }
        }
    }

    fun act(item: ModerationQueueItem, action: ModAction) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                when (action) {
                    ModAction.APPROVE -> services.moderation.moderateDrop(item.targetId, ModerateDropAction.APPROVE)
                    ModAction.REMOVE -> services.moderation.moderateDrop(item.targetId, ModerateDropAction.REMOVE)
                    ModAction.RESTORE -> services.moderation.moderateDrop(item.targetId, ModerateDropAction.RESTORE)
                    ModAction.DISMISS -> item.reportIds.forEach { services.moderation.resolveReport(it, ReportStatus.DISMISSED) }
                    ModAction.ACTION -> item.reportIds.forEach { services.moderation.resolveReport(it, ReportStatus.ACTIONED) }
                    ModAction.SUSPEND -> item.subjectId?.let {
                        services.moderation.suspendUser(it, clock.now() + SUSPENSION, "moderation queue")
                    }
                    ModAction.UNSUSPEND -> item.subjectId?.let { services.moderation.suspendUser(it, null) }
                }
                reload()
                _state.update { it.copy(busy = false, message = ModMessage.DONE) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reload()
                _state.update { it.copy(busy = false, message = ModMessage.FAILED) }
            }
        }
    }

    private suspend fun reload() {
        try {
            val items = services.moderation.queue()
            val flags = runCatching { services.moderation.integrityQueue() }.getOrDefault(emptyList())
            val claims = try {
                services.desk.reviewQueue()
            } catch (e: XHereApiException) {
                if (e.code == "not_admin") emptyList() else throw e
            }
            _state.update {
                it.copy(loading = false, denied = false, failed = false, items = items, flags = flags, claims = claims)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: XHereApiException) {
            val denied = e.code == "not_moderator"
            _state.update { it.copy(loading = false, denied = denied, failed = !denied, items = emptyList()) }
        } catch (_: Exception) {
            _state.update { it.copy(loading = false, failed = true) }
        }
    }

    private companion object {
        val SUSPENSION = 7.days
    }
}
