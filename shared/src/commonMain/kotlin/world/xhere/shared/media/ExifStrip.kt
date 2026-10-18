package world.xhere.shared.media

/**
 * Removes location-bearing metadata before a photo is stored.
 * JPEG APP1 (Exif and XMP) and PNG text / eXIf chunks are dropped.
 * The Edge Function `strip-exif` does the same steps for uploads that go
 * through Supabase Storage. The in-memory path uses this copy so a build
 * with no project still does not keep GPS.
 */
object ExifStrip {
    fun strip(bytes: ByteArray, mimeType: String): ByteArray {
        if (bytes.size < 8) return bytes
        return when {
            isJpeg(bytes) -> stripJpeg(bytes)
            isPng(bytes) -> stripPng(bytes)
            mimeType.equals("image/jpeg", ignoreCase = true) -> stripJpeg(bytes)
            mimeType.equals("image/png", ignoreCase = true) -> stripPng(bytes)
            else -> bytes
        }
    }

    fun containsExifMarker(bytes: ByteArray): Boolean {
        val ascii = bytes.decodeToString()
        return "Exif" in ascii || "http://ns.adobe.com/xap" in ascii || bytes.containsChunk("eXIf")
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)

    private fun stripJpeg(bytes: ByteArray): ByteArray {
        if (!isJpeg(bytes)) return bytes
        val out = ArrayList<Byte>(bytes.size)
        out += 0xFF.toByte()
        out += 0xD8.toByte()
        var i = 2
        while (i + 1 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                out.addAll(bytes.copyOfRange(i, bytes.size).asList())
                break
            }
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++
            if (i >= bytes.size) break
            val marker = bytes[i].toInt() and 0xFF
            i++
            if (marker == 0xD9 || marker == 0xDA || marker == 0x01 || marker in 0xD0..0xD7) {
                out += 0xFF.toByte()
                out += marker.toByte()
                if (marker == 0xDA || marker == 0xD9) {
                    if (i < bytes.size) out.addAll(bytes.copyOfRange(i, bytes.size).asList())
                }
                if (marker == 0xDA || marker == 0xD9) break
                continue
            }
            if (i + 1 >= bytes.size) break
            val length = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (length < 2 || i + length > bytes.size) {
                out.addAll(bytes.copyOfRange(i - 2, bytes.size).asList())
                break
            }
            val segment = bytes.copyOfRange(i, i + length)
            val drop = marker == 0xE1 && isExifOrXmp(segment.copyOfRange(2, segment.size))
            if (!drop) {
                out += 0xFF.toByte()
                out += marker.toByte()
                out.addAll(segment.asList())
            }
            i += length
        }
        return out.toByteArray()
    }

    private fun isExifOrXmp(payload: ByteArray): Boolean {
        val head = payload.decodeToString()
        return head.startsWith("Exif") || head.startsWith("http://ns.adobe.com/xap")
    }

    private fun stripPng(bytes: ByteArray): ByteArray {
        if (!isPng(bytes)) return bytes
        val out = ArrayList<Byte>(bytes.size)
        out.addAll(PNG_SIGNATURE.asList())
        var i = 8
        while (i + 12 <= bytes.size) {
            val length = readInt(bytes, i)
            if (length < 0 || i + 12 + length > bytes.size) {
                out.addAll(bytes.copyOfRange(i, bytes.size).asList())
                break
            }
            val type = bytes.copyOfRange(i + 4, i + 8).decodeToString()
            val chunkEnd = i + 12 + length
            if (type !in PNG_DROP) out.addAll(bytes.copyOfRange(i, chunkEnd).asList())
            i = chunkEnd
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun ByteArray.containsChunk(type: String): Boolean {
        if (!isPng(this)) return false
        var i = 8
        while (i + 8 <= size) {
            val length = readInt(this, i)
            if (length < 0 || i + 12 + length > size) return false
            if (copyOfRange(i + 4, i + 8).decodeToString() == type) return true
            i += 12 + length
        }
        return false
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val PNG_DROP = setOf("eXIf", "tEXt", "iTXt", "zTXt")
}
