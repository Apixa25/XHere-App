package world.xhere.app.drop

import android.content.Context

/** A photo the composer is about to label and upload. Tests supply bytes; the device uses CameraX. */
interface DropCapture {
    /** CameraX asks for CAMERA. The photo picker and the test fake do not. */
    val needsPermission: Boolean

    suspend fun takePhoto(context: Context): CapturedPhoto
}

data class CapturedPhoto(
    val bytes: ByteArray,
    val mimeType: String,
)

/** On-device labels. ML Kit on a phone; a fixed list in tests. Failures are an empty list, not a crash. */
interface ImageLabeler {
    suspend fun labels(bytes: ByteArray, mimeType: String): List<String>
}
