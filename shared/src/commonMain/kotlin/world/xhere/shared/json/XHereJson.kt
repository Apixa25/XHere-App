package world.xhere.shared.json

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * JSON used on the wire with PostgREST.
 *
 * Null fields are omitted so optional RPC arguments fall through to the SQL defaults
 * (`p_categories` null means every category). Unknown columns are ignored so a later
 * migration can add fields without breaking this client.
 */
val XHereJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    serializersModule = SerializersModule {
        contextual(UuidSerializer)
        contextual(InstantSerializer)
    }
}

/** Pretty-printed JSON for files a person saves, such as the data export. */
val ExportJson: Json = Json {
    prettyPrint = true
    explicitNulls = true
}

object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Uuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uuid = Uuid.parse(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = parseTimestamp(decoder.decodeString())
}

/**
 * PostgREST emits `timestamptz` as ISO-8601, sometimes with a space instead of `T`.
 * The server remains the source of truth; this only has to read what it sends back.
 */
fun parseTimestamp(text: String): Instant {
    val trimmed = text.trim()
    val normalized = if (' ' in trimmed && 'T' !in trimmed) trimmed.replace(' ', 'T') else trimmed
    return Instant.parse(normalized)
}
