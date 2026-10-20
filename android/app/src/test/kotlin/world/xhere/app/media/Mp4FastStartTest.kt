package world.xhere.app.media

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4FastStartTest {
    @Test
    fun moovMovesAheadOfMdatAndChunkOffsetsFollow() {
        val ftyp = box("ftyp", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val mdat = box("mdat", byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9))
        val payloadStart = ftyp.size + 8
        val stco = stco(payloadStart)
        val moov = box("moov", stco)
        val original = ftyp + mdat + moov
        val moved = Mp4FastStart.relocate(original)
        assertEquals("ftyp", typeAt(moved, 0))
        assertEquals("moov", typeAt(moved, ftyp.size))
        assertEquals("mdat", typeAt(moved, ftyp.size + moov.size))
        val newOffset = u32(moved, ftyp.size + 8 + 8 + 4 + 4)
        assertEquals(payloadStart + moov.size, newOffset)
    }

    @Test
    fun aFileThatAlreadyStartsFastIsLeftAlone() {
        val ftyp = box("ftyp", byteArrayOf(1, 2, 3, 4))
        val moov = box("moov", stco(20))
        val mdat = box("mdat", byteArrayOf(5, 5, 5, 5))
        val original = ftyp + moov + mdat
        val moved = Mp4FastStart.relocate(original)
        assertTrue(moved.contentEquals(original))
    }

    private fun stco(offset: Int): ByteArray {
        val body = ByteArray(4 + 4 + 4)
        put32(body, 4, 1)
        put32(body, 8, offset)
        return box("stco", body)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        put32(out, 0, out.size)
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun typeAt(bytes: ByteArray, offset: Int): String =
        String(bytes, offset + 4, 4, Charsets.ISO_8859_1)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).int

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).putInt(value)
    }
}
