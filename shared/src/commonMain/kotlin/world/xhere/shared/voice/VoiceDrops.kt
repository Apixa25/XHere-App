package world.xhere.shared.voice

import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Attributes for a voice drop. Hazards get `occurred_at` set to the moment of the report. */
object VoiceDrops {
    fun attributes(parse: VoiceParse, occurredAt: Instant): kotlinx.serialization.json.JsonObject {
        if (parse.categorySlug != "hazard") return buildJsonObject { }
        return buildJsonObject {
            put("occurred_at", occurredAt.toString())
            parse.hazardType?.let { put("hazard_type", it) }
            parse.severity?.let { put("severity", it) }
            parse.ongoing?.let { put("ongoing", JsonPrimitive(it)) }
        }
    }
}
