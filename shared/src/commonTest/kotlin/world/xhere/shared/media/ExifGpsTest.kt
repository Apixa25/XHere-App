package world.xhere.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import world.xhere.shared.geo.LatLng

class ExifGpsTest {
    @Test
    fun readsLittleEndianGpsAndIgnoresAPhotoWithoutIt() {
        val point = ExifGps.read(jpegWithGps(latitude = 40.0, longitude = 100.0, west = true))
        assertEquals(LatLng(40.0, -100.0), point)
        assertNull(ExifGps.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }

    @Test
    fun stripRemovesTheCoordinatesThatWereJustRead() {
        val bytes = jpegWithGps(latitude = 40.0, longitude = 100.0, west = true)
        assertEquals(LatLng(40.0, -100.0), ExifGps.read(bytes))
        assertNull(ExifGps.read(ExifStrip.strip(bytes, "image/jpeg")))
    }
}

/** JPEG APP1 with a little-endian GPS IFD. Degrees only, no minutes. */
private fun jpegWithGps(latitude: Int, longitude: Int, west: Boolean): ByteArray = jpegWithGps(
    latitude = latitude.toDouble(),
    longitude = longitude.toDouble(),
    west = west,
)

private fun jpegWithGps(latitude: Double, longitude: Double, west: Boolean): ByteArray {
    val tiff = tiffGps(latitude.toInt(), longitude.toInt(), west)
    val payload = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0) + tiff
    val length = payload.size + 2
    return byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xE1.toByte(),
        (length shr 8).toByte(),
        (length and 0xFF).toByte(),
    ) + payload + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x00, 0xFF.toByte(), 0xD9.toByte())
}

private fun tiffGps(latitude: Int, longitude: Int, west: Boolean): ByteArray {
    val gpsIfd = 26
    val latOffset = 80
    val lngOffset = 104
    val bytes = ByteArray(128)
    bytes[0] = 'I'.code.toByte()
    bytes[1] = 'I'.code.toByte()
    put16(bytes, 2, 42)
    put32(bytes, 4, 8)
    put16(bytes, 8, 1)
    put16(bytes, 10, 0x8825)
    put16(bytes, 12, 4)
    put32(bytes, 14, 1)
    put32(bytes, 18, gpsIfd)
    put16(bytes, gpsIfd, 4)
    var entry = gpsIfd + 2
    entry = asciiRef(bytes, entry, tag = 1, value = 'N')
    entry = rationalPointer(bytes, entry, tag = 2, offset = latOffset)
    entry = asciiRef(bytes, entry, tag = 3, value = if (west) 'W' else 'E')
    rationalPointer(bytes, entry, tag = 4, offset = lngOffset)
    writeDegrees(bytes, latOffset, latitude)
    writeDegrees(bytes, lngOffset, longitude)
    return bytes
}

private fun asciiRef(bytes: ByteArray, entry: Int, tag: Int, value: Char): Int {
    put16(bytes, entry, tag)
    put16(bytes, entry + 2, 2)
    put32(bytes, entry + 4, 2)
    bytes[entry + 8] = value.code.toByte()
    return entry + 12
}

private fun rationalPointer(bytes: ByteArray, entry: Int, tag: Int, offset: Int): Int {
    put16(bytes, entry, tag)
    put16(bytes, entry + 2, 5)
    put32(bytes, entry + 4, 3)
    put32(bytes, entry + 8, offset)
    return entry + 12
}

private fun writeDegrees(bytes: ByteArray, offset: Int, degrees: Int) {
    put32(bytes, offset, degrees)
    put32(bytes, offset + 4, 1)
    put32(bytes, offset + 12, 1)
    put32(bytes, offset + 20, 1)
}

private fun put16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun put32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
}
