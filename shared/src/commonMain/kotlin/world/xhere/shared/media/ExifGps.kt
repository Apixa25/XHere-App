package world.xhere.shared.media

import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng

/**
 * Reads GPS latitude and longitude out of a JPEG Exif block.
 * Call this before [ExifStrip.strip]. The coordinates are compared to the drop
 * and then dropped. They are not written to Storage or to a location trail.
 * A photo with no GPS, or a block this reader does not understand, returns null.
 */
object ExifGps {
    fun read(bytes: ByteArray): LatLng? {
        if (bytes.size < 16 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var index = 2
        while (index + 4 < bytes.size) {
            if (bytes[index] != 0xFF.toByte()) return null
            while (index < bytes.size && bytes[index] == 0xFF.toByte()) index++
            if (index >= bytes.size) return null
            val marker = bytes[index].toInt() and 0xFF
            index++
            if (marker == 0xDA || marker == 0xD9) return null
            if (index + 1 >= bytes.size) return null
            val length = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            if (length < 2 || index + length > bytes.size) return null
            if (marker == 0xE1) {
                val payload = bytes.copyOfRange(index + 2, index + length)
                readExif(payload)?.let { return it }
            }
            index += length
        }
        return null
    }

    private fun readExif(payload: ByteArray): LatLng? {
        if (payload.size < 14) return null
        if (payload[0] != 'E'.code.toByte() || payload[1] != 'x'.code.toByte() ||
            payload[2] != 'i'.code.toByte() || payload[3] != 'f'.code.toByte() ||
            payload[4] != 0.toByte() || payload[5] != 0.toByte()
        ) {
            return null
        }
        return readTiff(payload.copyOfRange(6, payload.size))
    }

    private fun readTiff(tiff: ByteArray): LatLng? {
        if (tiff.size < 8) return null
        val little = when {
            tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() -> true
            tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (u16(tiff, 2, little) != 42) return null
        val ifd = u32(tiff, 4, little).toInt()
        val gpsOffset = findIfdOffset(tiff, ifd, little, GPS_POINTER) ?: return null
        return readGpsIfd(tiff, gpsOffset, little)
    }

    private fun findIfdOffset(tiff: ByteArray, ifd: Int, little: Boolean, tag: Int): Int? {
        if (ifd < 0 || ifd + 2 > tiff.size) return null
        val count = u16(tiff, ifd, little)
        var entry = ifd + 2
        repeat(count) {
            if (entry + 12 > tiff.size) return null
            if (u16(tiff, entry, little) == tag) {
                val offset = u32(tiff, entry + 8, little).toInt()
                return offset.takeIf { it in 0 until tiff.size }
            }
            entry += 12
        }
        return null
    }

    private fun readGpsIfd(tiff: ByteArray, ifd: Int, little: Boolean): LatLng? {
        if (ifd + 2 > tiff.size) return null
        val count = u16(tiff, ifd, little)
        var latRef: String? = null
        var lngRef: String? = null
        var lat: Double? = null
        var lng: Double? = null
        var entry = ifd + 2
        repeat(count) {
            if (entry + 12 > tiff.size) return null
            val tag = u16(tiff, entry, little)
            val type = u16(tiff, entry + 2, little)
            val n = u32(tiff, entry + 4, little).toInt()
            when (tag) {
                1 -> if (type == ASCII) latRef = ascii(tiff, entry + 8, little, n)
                3 -> if (type == ASCII) lngRef = ascii(tiff, entry + 8, little, n)
                2 -> if (type == RATIONAL && n == 3) lat = rationals(tiff, u32(tiff, entry + 8, little).toInt(), little)
                4 -> if (type == RATIONAL && n == 3) lng = rationals(tiff, u32(tiff, entry + 8, little).toInt(), little)
            }
            entry += 12
        }
        val latitude = lat ?: return null
        val longitude = lng ?: return null
        val signedLat = if (latRef == "S") -latitude else latitude
        val signedLng = if (lngRef == "W") -longitude else longitude
        val point = LatLng(signedLat, signedLng)
        return point.takeIf { Geo.isValid(it) }
    }

    private fun ascii(tiff: ByteArray, valueOffset: Int, little: Boolean, count: Int): String? {
        if (count <= 0 || count > 4) {
            val offset = u32(tiff, valueOffset, little).toInt()
            if (offset < 0 || offset + count > tiff.size) return null
            return tiff.decodeToString(offset, offset + count).trim('\u0000', ' ')
        }
        return tiff.decodeToString(valueOffset, (valueOffset + count).coerceAtMost(tiff.size)).trim('\u0000', ' ')
    }

    private fun rationals(tiff: ByteArray, offset: Int, little: Boolean): Double? {
        if (offset < 0 || offset + 24 > tiff.size) return null
        var value = 0.0
        var scale = 1.0
        repeat(3) { part ->
            val base = offset + part * 8
            val numerator = u32(tiff, base, little).toDouble()
            val denominator = u32(tiff, base + 4, little).toDouble()
            if (denominator == 0.0) return null
            value += numerator / denominator / scale
            scale *= 60.0
        }
        return value
    }

    private fun u16(bytes: ByteArray, offset: Int, little: Boolean): Int {
        if (offset + 2 > bytes.size) return -1
        val a = bytes[offset].toInt() and 0xFF
        val b = bytes[offset + 1].toInt() and 0xFF
        return if (little) a or (b shl 8) else (a shl 8) or b
    }

    private fun u32(bytes: ByteArray, offset: Int, little: Boolean): Long {
        if (offset + 4 > bytes.size) return -1
        var value = 0L
        if (little) {
            for (shift in 0..3) value = value or ((bytes[offset + shift].toLong() and 0xFF) shl (shift * 8))
        } else {
            for (index in 0..3) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private const val ASCII: Int = 2
    private const val RATIONAL: Int = 5
    private const val GPS_POINTER: Int = 0x8825
}
