package world.xhere.shared.model

import kotlinx.serialization.Serializable

/**
 * A reverse-geocoded name for one map cell (`place_label`).
 * [attribution] is stamped by the database for OpenStreetMap. Show it on the card.
 */
@Serializable
data class PlaceLabel(
    val name: String,
    val address: String? = null,
    val attribution: String,
    val license: String,
    val provider: String,
)
