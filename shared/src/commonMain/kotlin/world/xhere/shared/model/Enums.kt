package world.xhere.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors `public.user_tier`. Enum order is significant: `new < regular < trusted < guide`,
 * the same order Postgres uses for tier comparisons.
 */
@Serializable
enum class UserTier {
    @SerialName("new") NEW,
    @SerialName("regular") REGULAR,
    @SerialName("trusted") TRUSTED,
    @SerialName("guide") GUIDE,
    ;

    fun isAtLeast(minimum: UserTier): Boolean = ordinal >= minimum.ordinal
}

@Serializable
enum class AppRole {
    @SerialName("user") USER,
    @SerialName("moderator") MODERATOR,
    @SerialName("admin") ADMIN,
}

/** Mirrors `public.drop_status`. */
@Serializable
enum class DropStatus {
    @SerialName("active") ACTIVE,
    @SerialName("pending_review") PENDING_REVIEW,
    @SerialName("confirmed") CONFIRMED,
    @SerialName("disputed") DISPUTED,
    @SerialName("expired") EXPIRED,
    @SerialName("removed") REMOVED,
}

@Serializable
enum class DropSource {
    @SerialName("user") USER,
    @SerialName("official") OFFICIAL,
    @SerialName("open_data") OPEN_DATA,
    @SerialName("business") BUSINESS,
}

/** Mirrors `public.moderation_mode`. */
@Serializable
enum class ModerationMode {
    @SerialName("post") POST,
    @SerialName("pre") PRE,
    @SerialName("pre_for_new") PRE_FOR_NEW,
}

@Serializable
enum class ConfirmationVerdict {
    @SerialName("still_true") STILL_TRUE,
    @SerialName("not_true") NOT_TRUE,
}

@Serializable
enum class MediaKind {
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("audio") AUDIO,
    @SerialName("file") FILE,
}

@Serializable
enum class ReportTarget {
    @SerialName("drop") DROP,
    @SerialName("profile") PROFILE,
    @SerialName("contribution") CONTRIBUTION,
}

@Serializable
enum class ReportReason {
    @SerialName("spam") SPAM,
    @SerialName("harassment") HARASSMENT,
    @SerialName("hate") HATE,
    @SerialName("violence") VIOLENCE,
    @SerialName("sexual") SEXUAL,
    @SerialName("illegal") ILLEGAL,
    @SerialName("personal_info") PERSONAL_INFO,
    @SerialName("misinformation") MISINFORMATION,
    @SerialName("dangerous") DANGEROUS,
    @SerialName("other") OTHER,
}

@Serializable
enum class ReportStatus {
    @SerialName("open") OPEN,
    @SerialName("actioned") ACTIONED,
    @SerialName("dismissed") DISMISSED,
}

@Serializable
enum class ModerationActionType {
    @SerialName("approve") APPROVE,
    @SerialName("reject") REJECT,
    @SerialName("remove") REMOVE,
    @SerialName("restore") RESTORE,
    @SerialName("auto_hide") AUTO_HIDE,
    @SerialName("dismiss_report") DISMISS_REPORT,
    @SerialName("action_report") ACTION_REPORT,
    @SerialName("suspend_user") SUSPEND_USER,
    @SerialName("unsuspend_user") UNSUSPEND_USER,
    @SerialName("set_tier") SET_TIER,
    @SerialName("set_role") SET_ROLE,
}

/** `moderate_drop` accepts these three actions. Reject is recorded when a pending drop is removed. */
enum class ModerateDropAction(val wire: String) {
    APPROVE("approve"),
    RESTORE("restore"),
    REMOVE("remove"),
}
