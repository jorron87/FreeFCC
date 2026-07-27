package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedDumlFrameParserTest {
    @Test
    fun `wrapped frame split across chunks is extracted once`() {
        val frame = DumlBuilder().buildFrame(
            DumlFrame(0xEE, 0x00, 0x51, 0x14, 0x82, byteArrayOf(1, 2, 3))
        )
        val wrapped = wrap(frame)
        val parser = WrappedDumlFrameParser()

        assertTrue(parser.feed(wrapped.copyOfRange(0, 7)).isEmpty())
        val result = parser.feed(wrapped.copyOfRange(7, wrapped.size))

        val parsed = result.single() as DumlFrameParser.Result.Frame
        assertEquals(0x51, parsed.frame.cmdSet)
        assertEquals(0x14, parsed.frame.cmdId)
        assertTrue(frame.contentEquals(parsed.frame.raw))
    }

    @Test
    fun `direct frame fallback remains supported`() {
        val frame = DumlBuilder().buildFrame(
            DumlFrame(0x03, 0x00, 0x03, 0x43, 0x82, ByteArray(85))
        )

        val parsed = WrappedDumlFrameParser().feed(frame).single()

        assertTrue(parsed is DumlFrameParser.Result.Frame)
    }

    @Test
    fun `bad wrapped inner crc is reported and next frame is recovered`() {
        val bad = DumlBuilder().buildFrame(
            DumlFrame(0x03, 0x00, 0x03, 0x43, 0x82, ByteArray(85))
        )
        bad[bad.lastIndex] = (bad.last() + 1).toByte()
        val good = DumlBuilder().buildFrame(
            DumlFrame(0xEE, 0x00, 0x51, 0x14, 0x82, ByteArray(51))
        )

        val results = WrappedDumlFrameParser().feed(wrap(bad) + wrap(good))

        assertTrue(
            results.any {
                it is DumlFrameParser.Result.Error && it.error.reason == "wrapped_crc16_mismatch"
            }
        )
        assertEquals(1, results.filterIsInstance<DumlFrameParser.Result.Frame>().size)
    }

    @Test
    fun `oversized wrapped length is rejected`() {
        val bytes = byteArrayOf(
            0x55, 0xCC.toByte(), 0x30, 0x75,
            0x00, 0x04, 0x00, 0x00
        ) + ByteArray(13)

        val results = WrappedDumlFrameParser().feed(bytes)

        assertTrue(
            results.any {
                it is DumlFrameParser.Result.Error && it.error.reason == "wrapped_invalid_length"
            }
        )
    }

    private fun wrap(frame: ByteArray): ByteArray {
        val length = frame.size
        return byteArrayOf(
            0x55, 0xCC.toByte(), 0x30, 0x75,
            (length and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            ((length shr 16) and 0xFF).toByte(),
            ((length shr 24) and 0xFF).toByte()
        ) + frame
    }
}
