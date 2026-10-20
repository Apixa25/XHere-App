package world.xhere.app.media

import java.nio.ByteBuffer

/**
 * Moves the moov atom ahead of mdat so a player can start without reading
 * the whole file. Chunk offsets inside the moov are shifted by the moov size.
 * A file that is already laid out that way is returned unchanged.
 * A file that is not a simple MP4 is returned unchanged.
 */
internal object Mp4FastStart {
    fun relocate(bytes: ByteArray): ByteArray {
        val boxes = topLevel(bytes) ?: return bytes
        val moovAt = boxes.indexOfFirst { it.type == "moov" }
        val mdatAt = boxes.indexOfFirst { it.type == "mdat" }
        if (moovAt < 0 || mdatAt < 0 || moovAt < mdatAt) return bytes
        val moov = boxes[moovAt]
        val mdat = boxes[mdatAt]
        val patched = patch(bytes.copyOfRange(moov.start, moov.end), moov.size)
        val out = ByteArray(bytes.size)
        var cursor = 0
        for (box in boxes) {
            if (box.start < mdat.start) {
                bytes.copyInto(out, cursor, box.start, box.end)
                cursor += box.size
            }
        }
        patched.copyInto(out, cursor)
        cursor += patched.size
        for (box in boxes) {
            if (box.start >= mdat.start && box.type != "moov") {
                bytes.copyInto(out, cursor, box.start, box.end)
                cursor += box.size
            }
        }
        return if (cursor == out.size) out else bytes
    }

    private class Box(val start: Int, val size: Int, val type: String) {
        val end: Int get() = start + size
    }

    private fun topLevel(bytes: ByteArray): List<Box>? {
        val found = ArrayList<Box>()
        var cursor = 0
        while (cursor + 8 <= bytes.size) {
            val size = u32(bytes, cursor)
            if (size < 8 || cursor + size > bytes.size) return null
            found += Box(cursor, size, type(bytes, cursor + 4))
            cursor += size
        }
        return if (cursor == bytes.size) found else null
    }

    private fun patch(moov: ByteArray, shift: Int): ByteArray {
        walk(moov, 0, moov.size, shift)
        return moov
    }

    private fun walk(data: ByteArray, start: Int, end: Int, shift: Int) {
        var cursor = start
        while (cursor + 8 <= end) {
            val size = u32(data, cursor)
            if (size < 8 || cursor + size > end) return
            val kind = type(data, cursor + 4)
            val payload = cursor + 8
            when (kind) {
                "stco" -> shiftEntries(data, payload, cursor + size, shift, wide = false)
                "co64" -> shiftEntries(data, payload, cursor + size, shift, wide = true)
                "moov", "trak", "mdia", "minf", "stbl", "edts" -> walk(data, payload, cursor + size, shift)
            }
            cursor += size
        }
    }

    private fun shiftEntries(data: ByteArray, payload: Int, end: Int, shift: Int, wide: Boolean) {
        if (payload + 8 > end) return
        val count = u32(data, payload + 4)
        var entry = payload + 8
        val width = if (wide) 8 else 4
        repeat(count) {
            if (entry + width > end) return
            if (wide) {
                val value = u64(data, entry) + shift.toLong()
                put64(data, entry, value)
            } else {
                put32(data, entry, u32(data, entry) + shift)
            }
            entry += width
        }
    }

    private fun type(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.ISO_8859_1)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).int

    private fun u64(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).long

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).putInt(value)
    }

    private fun put64(bytes: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(bytes, offset, 8).putLong(value)
    }
}
