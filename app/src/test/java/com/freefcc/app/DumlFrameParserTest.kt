package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DumlFrameParserTest {

    private fun buildRawFrame(
        sender: Int = 0x03,
        receiver: Int = 0x43,
        seq: Int = 42,
        cmdType: Int = 0x00,
        cmdSet: Int = 0x03,
        cmdId: Int = 0x43,
        payload: ByteArray = byteArrayOf(1, 2, 3, 4)
    ): ByteArray {
        val totalLength = payload.size + 13
        val out = ByteArray(totalLength)
        out[0] = 0x55
        out[1] = (totalLength and 0xFF).toByte()
        out[2] = (((totalLength shr 8) and 0x03) or 0x04).toByte()
        out[3] = DumlBuilder.crc8(out, 0, 3).toByte()
        out[4] = sender.toByte()
        out[5] = receiver.toByte()
        out[6] = (seq and 0xFF).toByte()
        out[7] = ((seq shr 8) and 0xFF).toByte()
        out[8] = cmdType.toByte()
        out[9] = cmdSet.toByte()
        out[10] = cmdId.toByte()
        System.arraycopy(payload, 0, out, 11, payload.size)
        val crc = DumlBuilder.crc16(out, 0, totalLength - 2)
        out[totalLength - 2] = (crc and 0xFF).toByte()
        out[totalLength - 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    @Test
    fun `valid frame split across chunks is extracted once`() {
        val parser = DumlFrameParser()
        val frame = buildRawFrame()

        assertTrue(parser.feed(frame.copyOfRange(0, 5)).isEmpty())
        val results = parser.feed(frame.copyOfRange(5, frame.size))

        assertEquals(1, results.size)
        val parsed = (results.single() as DumlFrameParser.Result.Frame).frame
        assertEquals(0x03, parsed.sender)
        assertEquals(0x43, parsed.receiver)
        assertEquals(42, parsed.sequence)
        assertEquals(0x03, parsed.cmdSet)
        assertEquals(0x43, parsed.cmdId)
        assertEquals(4, parsed.payloadLength)
    }

    @Test
    fun `crc16 mismatch is reported and parser resynchronizes`() {
        val parser = DumlFrameParser()
        val bad = buildRawFrame()
        bad[bad.size - 1] = (bad[bad.size - 1] + 1).toByte()
        val good = buildRawFrame(seq = 43)

        val results = parser.feed(bad + good)

        assertTrue(results.any { it is DumlFrameParser.Result.Error && it.error.reason == "crc16_mismatch" })
        val parsed = results.filterIsInstance<DumlFrameParser.Result.Frame>().single().frame
        assertEquals(43, parsed.sequence)
    }

    @Test
    fun `under minimum length is rejected without allocating frame`() {
        val parser = DumlFrameParser()
        val bytes = ByteArray(13)
        bytes[0] = 0x55
        bytes[1] = 0x0c
        bytes[2] = 0x00

        val results = parser.feed(bytes)

        assertTrue(results.any { it is DumlFrameParser.Result.Error && it.error.reason == "invalid_length" })
    }

    @Test
    fun `truncated frame is reported when stream finishes`() {
        val parser = DumlFrameParser()
        val frame = buildRawFrame()

        assertTrue(parser.feed(frame.copyOf(frame.size - 2)).isEmpty())
        val results = parser.finish()

        assertTrue(
            results.single() is DumlFrameParser.Result.Error &&
                (results.single() as DumlFrameParser.Result.Error).error.reason == "truncated_frame"
        )
    }
}
