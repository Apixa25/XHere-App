package world.xhere.shared.model

import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import world.xhere.shared.json.UuidSerializer

/**
 * A physical object that can speak. [voiceId] is the 6-byte id on the air.
 * [seenCount] is an aggregate. This card never names who heard it.
 */
@Serializable
data class ThingCard(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    @SerialName("voice_id") val voiceId: String,
    val title: String,
    val story: String,
    @SerialName("category_slug") val categorySlug: String? = null,
    @SerialName("category_label") val categoryLabel: String? = null,
    @SerialName("place_name") val placeName: String? = null,
    @Serializable(with = UuidSerializer::class)
    @SerialName("drop_id") val dropId: Uuid? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val media: List<ThingMedia> = emptyList(),
    @SerialName("seen_count") val seenCount: Long = 0,
    @SerialName("is_mine") val isMine: Boolean = false,
    val url: String,
    val status: String = "active",
    val visibility: String = "public",
)

@Serializable
data class ThingMedia(
    val url: String,
    val alt: String? = null,
)

@Serializable
data class ResolvedThing(
    val key: String,
    val thing: ThingCard,
)

@Serializable
data class ThingList(
    val things: List<ResolvedThing> = emptyList(),
)

@Serializable
data class OwnedThings(
    val things: List<ThingCard> = emptyList(),
)

@Serializable
data class HearResult(
    val counted: Int = 0,
)

data class CreateThingRequest(
    val title: String,
    val story: String,
    val categorySlug: String? = null,
    val dropId: Uuid? = null,
    val placeName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val media: List<ThingMedia> = emptyList(),
    val visibility: String = "public",
)
