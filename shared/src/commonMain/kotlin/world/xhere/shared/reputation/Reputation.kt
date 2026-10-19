package world.xhere.shared.reputation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.model.UserTier

/**
 * Reputation for M11 (PLAN §3 and §7, research recommendation #7).
 *
 * The database is the authority (`public.reputation_rules` and
 * `private.recompute_tiers`). These constants match that row so the level-up
 * sheet and the in-memory client can explain the same numbers without a
 * round trip. pgTAP asserts the table, and [ReputationRulesTest] asserts
 * this object.
 *
 * Points come from other people's signals: a still-true confirmation, a
 * unique view, a finished walk, a finished quest. "Not anymore" and a
 * moderator removal subtract. Imported public records never count, and a
 * person's own taps on their own drops never count.
 *
 * The same person confirming you again is worth less each time (8, then 3,
 * then 1, then nothing). If two people only confirm each other, only the
 * earlier direction scores. A nullable `spoof_flag` on the action row is how
 * a later anti-spoofing pass leaves that action out (`true` does not count).
 *
 * Tiers stay the existing enum. Regular is shown as Local.
 */
object Reputation {
    const val CONFIRM_FIRST: Int = 8
    const val CONFIRM_SECOND: Int = 3
    const val CONFIRM_THIRD: Int = 1
    const val VIEW_FIRST: Int = 2
    const val VIEW_NEXT: Int = 1
    const val VIEW_CAP: Int = 3
    const val DENY_FIRST: Int = 6
    const val DENY_SECOND: Int = 2
    const val MODERATION_PENALTY: Int = 15
    const val WALK_POINTS: Int = 12
    const val QUEST_POINTS: Int = 10
    const val LOCAL_AT: Int = 24
    const val TRUSTED_AT: Int = 80
    const val GUIDE_AT: Int = 200
    const val TRUSTED_EXTRA_REPORTS: Int = 2
    const val BONUS_GUIDE_M: Int = 80
    const val BONUS_TRUSTED_M: Int = 40
    const val BONUS_LOCAL_M: Int = 15

    /** Points for a still-true, given how many earlier scoring confirms this person already gave this author. */
    fun confirmPoints(priorFromThisPerson: Int): Int = when (priorFromThisPerson) {
        0 -> CONFIRM_FIRST
        1 -> CONFIRM_SECOND
        2 -> CONFIRM_THIRD
        else -> 0
    }

    /**
     * Points for one unique view. [priorFromThisPerson] is how many of this
     * author's other drops this viewer already opened. The first [VIEW_CAP]
     * views score; the rest do not.
     */
    fun viewPoints(priorFromThisPerson: Int): Int = when {
        priorFromThisPerson <= 0 -> VIEW_FIRST
        priorFromThisPerson < VIEW_CAP -> VIEW_NEXT
        else -> 0
    }

    /** Positive amount to subtract for a "not anymore" from this person. */
    fun denyPoints(priorFromThisPerson: Int): Int = when (priorFromThisPerson) {
        0 -> DENY_FIRST
        1 -> DENY_SECOND
        else -> 0
    }

    fun tierFor(points: Int): UserTier = when {
        points >= GUIDE_AT -> UserTier.GUIDE
        points >= TRUSTED_AT -> UserTier.TRUSTED
        points >= LOCAL_AT -> UserTier.REGULAR
        else -> UserTier.NEW
    }

    /** Name on the chip. Regular is Local. */
    fun label(tier: UserTier): String = when (tier) {
        UserTier.NEW -> "New"
        UserTier.REGULAR -> "Local"
        UserTier.TRUSTED -> "Trusted"
        UserTier.GUIDE -> "Guide"
    }

    fun nextTier(tier: UserTier): UserTier? = when (tier) {
        UserTier.NEW -> UserTier.REGULAR
        UserTier.REGULAR -> UserTier.TRUSTED
        UserTier.TRUSTED -> UserTier.GUIDE
        UserTier.GUIDE -> null
    }

    fun threshold(tier: UserTier): Int = when (tier) {
        UserTier.NEW -> 0
        UserTier.REGULAR -> LOCAL_AT
        UserTier.TRUSTED -> TRUSTED_AT
        UserTier.GUIDE -> GUIDE_AT
    }

    /**
     * Meters subtracted from distance when ordering the map list and the Here
     * Card. A trusted drop a short walk farther can outrank a brand-new one.
     * Public records and anonymous drops pass null and stay on raw distance.
     */
    fun rankBonusMeters(tier: UserTier?): Int = when (tier) {
        UserTier.GUIDE -> BONUS_GUIDE_M
        UserTier.TRUSTED -> BONUS_TRUSTED_M
        UserTier.REGULAR -> BONUS_LOCAL_M
        UserTier.NEW, null -> 0
    }

    fun adjustedMeters(distanceM: Double, tier: UserTier?): Double =
        distanceM - rankBonusMeters(tier)

    /** Reports required before a drop is held. Trusted and Guide need two more. */
    fun reportsToHide(categoryReports: Int, authorTier: UserTier?): Int {
        val extra = if (authorTier != null && authorTier.isAtLeast(UserTier.TRUSTED)) TRUSTED_EXTRA_REPORTS else 0
        return categoryReports + extra
    }

    fun ladder(): List<ReputationRung> = listOf(
        ReputationRung(UserTier.NEW, label(UserTier.NEW), 0),
        ReputationRung(UserTier.REGULAR, label(UserTier.REGULAR), LOCAL_AT),
        ReputationRung(UserTier.TRUSTED, label(UserTier.TRUSTED), TRUSTED_AT),
        ReputationRung(UserTier.GUIDE, label(UserTier.GUIDE), GUIDE_AT),
    )

    fun standing(points: Int, tier: UserTier = tierFor(points)): ReputationStanding {
        val next = nextTier(tier)
        return ReputationStanding(
            points = points,
            tier = tier,
            tierLabel = label(tier),
            nextTier = next,
            nextLabel = next?.let(::label),
            nextAt = next?.let(::threshold),
            ladder = ladder(),
            confirmFirst = CONFIRM_FIRST,
            confirmSecond = CONFIRM_SECOND,
            confirmThird = CONFIRM_THIRD,
            viewFirst = VIEW_FIRST,
            viewNext = VIEW_NEXT,
            viewCap = VIEW_CAP,
            denyFirst = DENY_FIRST,
            denySecond = DENY_SECOND,
            moderationPenalty = MODERATION_PENALTY,
            walkPoints = WALK_POINTS,
            questPoints = QUEST_POINTS,
            trustedExtraReports = TRUSTED_EXTRA_REPORTS,
        )
    }
}

@Serializable
data class ReputationRung(
    val tier: UserTier,
    val label: String,
    val at: Int,
)

/**
 * `my_reputation()`. [tier] can sit above [Reputation.tierFor] when a
 * moderator pinned it. The ladder is the public thresholds.
 */
@Serializable
data class ReputationStanding(
    val points: Int = 0,
    val tier: UserTier = UserTier.NEW,
    @SerialName("tier_label") val tierLabel: String = "New",
    @SerialName("next_tier") val nextTier: UserTier? = UserTier.REGULAR,
    @SerialName("next_label") val nextLabel: String? = "Local",
    @SerialName("next_at") val nextAt: Int? = Reputation.LOCAL_AT,
    val ladder: List<ReputationRung> = emptyList(),
    @SerialName("confirm_first") val confirmFirst: Int = Reputation.CONFIRM_FIRST,
    @SerialName("confirm_second") val confirmSecond: Int = Reputation.CONFIRM_SECOND,
    @SerialName("confirm_third") val confirmThird: Int = Reputation.CONFIRM_THIRD,
    @SerialName("view_first") val viewFirst: Int = Reputation.VIEW_FIRST,
    @SerialName("view_next") val viewNext: Int = Reputation.VIEW_NEXT,
    @SerialName("view_cap") val viewCap: Int = Reputation.VIEW_CAP,
    @SerialName("deny_first") val denyFirst: Int = Reputation.DENY_FIRST,
    @SerialName("deny_second") val denySecond: Int = Reputation.DENY_SECOND,
    @SerialName("moderation_penalty") val moderationPenalty: Int = Reputation.MODERATION_PENALTY,
    @SerialName("walk_points") val walkPoints: Int = Reputation.WALK_POINTS,
    @SerialName("quest_points") val questPoints: Int = Reputation.QUEST_POINTS,
    @SerialName("trusted_extra_reports") val trustedExtraReports: Int = Reputation.TRUSTED_EXTRA_REPORTS,
)
