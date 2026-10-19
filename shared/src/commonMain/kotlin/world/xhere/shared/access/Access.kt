package world.xhere.shared.access

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.geo.LatLng
import world.xhere.shared.here.Here
import world.xhere.shared.json.InstantSerializer
import kotlin.uuid.Uuid

/**
 * A 3-tap accessibility check (PLAN §4 nature `accessibility` field, extended
 * to any place). Answers are yes, no, or unknown. The map color comes from
 * the majority, not from a single person.
 *
 * Confidence is the share of decisive answers that agree with the majority,
 * averaged across the questions that received a yes or a no. A tie is not a
 * majority. Unknown answers do not vote. [lastConfirmedAt] is the newest check.
 */
@Serializable
enum class AccessAnswer {
    @SerialName("yes") YES,
    @SerialName("no") NO,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class AccessStatus {
    @SerialName("accessible") ACCESSIBLE,
    @SerialName("partial") PARTIAL,
    @SerialName("not_accessible") NOT_ACCESSIBLE,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class AccessSummary(
    @SerialName("place_key") val placeKey: String,
    @SerialName("step_free") val stepFree: AccessAnswer,
    val ramp: AccessAnswer,
    val restroom: AccessAnswer,
    val status: AccessStatus,
    val confidence: Double,
    @SerialName("sample_size") val sampleSize: Int,
    @Serializable(with = InstantSerializer::class)
    @SerialName("last_confirmed_at") val lastConfirmedAt: Instant? = null,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    /** OpenStreetMap line, or "People here". */
    val attribution: String = PEOPLE,
    /** The signed-in person's own note. Absent for everyone else. */
    @SerialName("my_notes") val myNotes: String? = null,
    val limited: Boolean = false,
) {
    val location: LatLng get() = LatLng(latitude, longitude)
}

data class AccessVote(
    val stepFree: AccessAnswer,
    val ramp: AccessAnswer,
    val restroom: AccessAnswer,
    val at: Instant? = null,
    val limited: Boolean = false,
)

data class FieldRollup(
    val answer: AccessAnswer,
    val confidence: Double,
)

object AccessRollup {
    const val PEOPLE: String = "People here"
    const val OSM: String = "© OpenStreetMap contributors, ODbL"

    fun summarize(
        placeKey: String,
        votes: List<AccessVote>,
        latitude: Double,
        longitude: Double,
        title: String,
        attribution: String = PEOPLE,
        myNotes: String? = null,
    ): AccessSummary {
        val step = field(votes.map { it.stepFree })
        val ramp = field(votes.map { it.ramp })
        val restroom = field(votes.map { it.restroom })
        val limited = votes.any { it.limited }
        val status = status(step.answer, ramp.answer, restroom.answer, limited)
        return AccessSummary(
            placeKey = placeKey,
            stepFree = step.answer,
            ramp = ramp.answer,
            restroom = restroom.answer,
            status = status,
            confidence = confidence(listOf(step, ramp, restroom)),
            sampleSize = votes.size,
            lastConfirmedAt = votes.mapNotNull { it.at }.maxOrNull(),
            latitude = latitude,
            longitude = longitude,
            title = title,
            attribution = attribution,
            myNotes = myNotes,
            limited = limited,
        )
    }

    /**
     * Majority among yes and no. Unknown is ignored. An even split is not a
     * majority: the answer stays unknown and confidence is 0.5 so a tie still
     * lowers the overall score. No decisive votes means confidence 0.
     */
    fun field(answers: List<AccessAnswer>): FieldRollup {
        val decisive = answers.filter { it != AccessAnswer.UNKNOWN }
        if (decisive.isEmpty()) return FieldRollup(AccessAnswer.UNKNOWN, 0.0)
        val yes = decisive.count { it == AccessAnswer.YES }
        val no = decisive.size - yes
        if (yes == no) return FieldRollup(AccessAnswer.UNKNOWN, 0.5)
        val winner = if (yes > no) AccessAnswer.YES else AccessAnswer.NO
        return FieldRollup(winner, maxOf(yes, no).toDouble() / decisive.size.toDouble())
    }

    fun status(
        stepFree: AccessAnswer,
        ramp: AccessAnswer,
        restroom: AccessAnswer,
        limited: Boolean = false,
    ): AccessStatus {
        val known = listOf(stepFree, ramp, restroom).filter { it != AccessAnswer.UNKNOWN }
        if (known.isEmpty()) return AccessStatus.UNKNOWN
        val yes = known.count { it == AccessAnswer.YES }
        val no = known.count { it == AccessAnswer.NO }
        val raw = when {
            yes > 0 && no == 0 && stepFree == AccessAnswer.YES -> AccessStatus.ACCESSIBLE
            no > 0 && yes == 0 && stepFree == AccessAnswer.NO -> AccessStatus.NOT_ACCESSIBLE
            else -> AccessStatus.PARTIAL
        }
        return if (limited && raw == AccessStatus.ACCESSIBLE) AccessStatus.PARTIAL else raw
    }

    fun confidence(fields: List<FieldRollup>): Double {
        val used = fields.filter { it.confidence > 0.0 }
        if (used.isEmpty()) return 0.0
        return used.sumOf { it.confidence } / used.size.toDouble()
    }

    /** Wheelchair ring color. Green, amber, red, or gray. */
    fun colorHex(status: AccessStatus): String = when (status) {
        AccessStatus.ACCESSIBLE -> "#1B7F4E"
        AccessStatus.PARTIAL -> "#C47B0A"
        AccessStatus.NOT_ACCESSIBLE -> "#B3261E"
        AccessStatus.UNKNOWN -> "#5F6B66"
    }
}

private const val PEOPLE: String = AccessRollup.PEOPLE

/** Stable id for a place. A drop, an OSM feature, or a map cell. */
object AccessPlaces {
    private val KEY = Regex("^[A-Za-z0-9:._/-]{3,160}$")

    fun drop(id: Uuid): String = "drop:$id"

    /** Same 0.001° cell as the Here Card place-name cache. */
    fun cell(point: LatLng): String = "cell:${Here.cell(point.latitude)}:${Here.cell(point.longitude)}"

    fun isCell(placeKey: String): Boolean = placeKey.startsWith("cell:")

    fun valid(placeKey: String): Boolean = KEY.matches(placeKey)

    /** `https://www.openstreetmap.org/node/1` becomes `osm:node/1`. */
    fun fromOsmUrl(url: String?): String? {
        val match = OSM_URL.find(url?.trim().orEmpty()) ?: return null
        val key = "osm:${match.groupValues[1]}/${match.groupValues[2]}"
        return key.takeIf { valid(it) }
    }

    private val OSM_URL = Regex("""openstreetmap\.org/(node|way|relation)/(\d+)""", RegexOption.IGNORE_CASE)
}

/**
 * The 3-tap check. Each answer moves to the next question.
 * Notes are optional and stay on the last step.
 */
enum class SurveyQuestion {
    STEP_FREE,
    RAMP,
    RESTROOM,
    NOTES,
}

data class SurveyDraft(
    val stepFree: AccessAnswer? = null,
    val ramp: AccessAnswer? = null,
    val restroom: AccessAnswer? = null,
    val notes: String = "",
    val question: SurveyQuestion = SurveyQuestion.STEP_FREE,
) {
    val ready: Boolean
        get() = stepFree != null && ramp != null && restroom != null && question == SurveyQuestion.NOTES
}

object AccessSurvey {
    const val NOTE_MAX: Int = 280

    fun answer(draft: SurveyDraft, choice: AccessAnswer): SurveyDraft = when (draft.question) {
        SurveyQuestion.STEP_FREE -> draft.copy(stepFree = choice, question = SurveyQuestion.RAMP)
        SurveyQuestion.RAMP -> draft.copy(ramp = choice, question = SurveyQuestion.RESTROOM)
        SurveyQuestion.RESTROOM -> draft.copy(restroom = choice, question = SurveyQuestion.NOTES)
        SurveyQuestion.NOTES -> draft
    }

    fun notes(draft: SurveyDraft, raw: String): SurveyDraft =
        draft.copy(notes = raw.take(NOTE_MAX))
}

/**
 * OpenStreetMap tags. `wheelchair=*` is the entrance. `toilets:wheelchair=*`
 * is the restroom. `ramp=*` or `ramp:wheelchair=*` is the ramp. `limited`
 * never paints the pin fully accessible.
 */
object OsmAccess {
    fun vote(tags: Map<String, String>): AccessVote? {
        val wheelchair = tags["wheelchair"]?.lowercase()
        val toilets = tags["toilets:wheelchair"]?.lowercase()
        val ramp = (tags["ramp"] ?: tags["ramp:wheelchair"])?.lowercase()
        if (wheelchair == null && toilets == null && ramp == null) return null
        return AccessVote(
            stepFree = when (wheelchair) {
                "yes", "limited" -> AccessAnswer.YES
                "no" -> AccessAnswer.NO
                else -> AccessAnswer.UNKNOWN
            },
            ramp = when (ramp) {
                "yes" -> AccessAnswer.YES
                "no" -> AccessAnswer.NO
                else -> AccessAnswer.UNKNOWN
            },
            restroom = when (toilets) {
                "yes" -> AccessAnswer.YES
                "no" -> AccessAnswer.NO
                else -> AccessAnswer.UNKNOWN
            },
            limited = wheelchair == "limited",
        )
    }

    fun summary(placeKey: String, tags: Map<String, String>, latitude: Double, longitude: Double, title: String): AccessSummary? {
        val vote = vote(tags) ?: return null
        return AccessRollup.summarize(
            placeKey = placeKey,
            votes = listOf(vote),
            latitude = latitude,
            longitude = longitude,
            title = title,
            attribution = AccessRollup.OSM,
        )
    }
}
