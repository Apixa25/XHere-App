package world.xhere.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExifStripTest {
    @Test
    fun jpegLosesTheExifSegmentAndKeepsTheRest() {
        val jpeg = jpegWithExif()
        assertTrue(ExifStrip.containsExifMarker(jpeg))
        val stripped = ExifStrip.strip(jpeg, "image/jpeg")
        assertEquals(0xFF.toByte(), stripped[0])
        assertEquals(0xD8.toByte(), stripped[1])
        assertFalse(ExifStrip.containsExifMarker(stripped))
        assertTrue(stripped.containsSequence(byteArrayOf(0xFF.toByte(), 0xDA.toByte())))
        assertEquals(0xD9.toByte(), stripped.last())
    }

    @Test
    fun jpegWithoutExifIsUnchanged() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertTrue(ExifStrip.strip(jpeg, "image/jpeg").contentEquals(jpeg))
    }

    @Test
    fun pngDropsTheExifChunkAndKeepsTheImageChunks() {
        val png = pngWithExif()
        assertTrue(ExifStrip.containsExifMarker(png))
        val stripped = ExifStrip.strip(png, "image/png")
        assertFalse(stripped.decodeToString().contains("eXIf"))
        assertTrue(stripped.decodeToString().contains("IHDR"))
        assertTrue(stripped.decodeToString().contains("IEND"))
    }
}

internal fun jpegWithExif(): ByteArray {
    // APP1 length covers the two length bytes plus "Exif\0\0GPS".
    val payload = "Exif\u0000\u0000GPS".encodeToByteArray()
    val length = payload.size + 2
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) +
        payload +
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x00, 0xFF.toByte(), 0xD9.toByte())
}

private fun pngWithExif(): ByteArray {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    return signature +
        chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)) +
        chunk("eXIf", "GPS".encodeToByteArray()) +
        chunk("IEND", byteArrayOf())
}

private fun chunk(type: String, data: ByteArray): ByteArray {
    val length = byteArrayOf(
        (data.size shr 24).toByte(),
        (data.size shr 16).toByte(),
        (data.size shr 8).toByte(),
        data.size.toByte(),
    )
    val body = type.encodeToByteArray() + data
    return length + body + byteArrayOf(0, 0, 0, 0)
}

private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (i in 0..size - needle.size) {
        if (copyOfRange(i, i + needle.size).contentEquals(needle)) return true
    }
    return false
}
