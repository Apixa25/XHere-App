package world.xhere.app.voice

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import world.xhere.shared.voice.VoiceLinks
import world.xhere.shared.voice.VoiceProtocol

/**
 * Optional close-range extra. Two URI records: https first, then xhere://.
 * A phone hears the object from the BLE broadcast. This write is not that.
 */
object NfcSticker {
    fun message(voiceId: String): NdefMessage {
        require(VoiceProtocol.isVoiceId(voiceId))
        return NdefMessage(
            arrayOf(
                NdefRecord.createUri(VoiceLinks.https(voiceId)),
                NdefRecord.createUri(VoiceLinks.custom(voiceId)),
            ),
        )
    }

    fun write(tag: Tag, voiceId: String): Boolean {
        val ndef = Ndef.get(tag) ?: return false
        return runCatching {
            ndef.connect()
            ndef.writeNdefMessage(message(voiceId))
            true
        }.getOrDefault(false).also {
            runCatching { ndef.close() }
        }
    }
}
