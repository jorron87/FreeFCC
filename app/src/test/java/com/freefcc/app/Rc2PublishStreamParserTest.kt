package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Rc2PublishStreamParserTest {
    @Test
    fun `split record is emitted once`() {
        val record = record(0xF5, 71, 0x0001_FFFEL)
        val parser = Rc2PublishStreamParser()

        assertTrue(parser.feed(record.copyOfRange(0, 5)).isEmpty())
        val parsed = parser.feed(record.copyOfRange(5, record.size))
            .filterIsInstance<Rc2PublishStreamParser.Result.Parsed>()
            .single()
            .record

        assertEquals(0xF5, parsed.marker)
        assertEquals(71, parsed.length)
        assertEquals(0x0001_FFFEL, parsed.sourceClockMs)
        assertTrue(record.contentEquals(parsed.raw))
    }

    @Test
    fun `all observed marker families are accepted`() {
        val results = Rc2PublishStreamParser().feed(
            record(0xF5, 12, 1) + record(0xF6, 12, 2) + record(0xF8, 12, 3)
        )

        assertEquals(
            listOf(0xF5, 0xF6, 0xF8),
            results.filterIsInstance<Rc2PublishStreamParser.Result.Parsed>()
                .map { it.record.marker }
        )
    }

    @Test
    fun `invalid length resyncs to following record`() {
        val invalid = byteArrayOf(
            0xF5.toByte(), 0x64, 0x04, 0x00, 0, 0, 0, 0
        )
        val results = Rc2PublishStreamParser().feed(invalid + record(0xF6, 16, 99))

        assertTrue(
            results.filterIsInstance<Rc2PublishStreamParser.Result.Error>()
                .any { it.error.reason == "publish_invalid_length" }
        )
        assertEquals(
            99,
            results.filterIsInstance<Rc2PublishStreamParser.Result.Parsed>()
                .single().record.sourceClockMs
        )
    }

    @Test
    fun `truncated record is reported on finish`() {
        val parser = Rc2PublishStreamParser()
        parser.feed(record(0xF8, 32, 9).copyOfRange(0, 20))

        val error = parser.finish()
            .filterIsInstance<Rc2PublishStreamParser.Result.Error>()
            .single()

        assertEquals("publish_truncated_record", error.error.reason)
    }

    private fun record(marker: Int, length: Int, clockMs: Long): ByteArray =
        ByteArray(length).also {
            it[0] = marker.toByte()
            it[1] = 0x64
            it[2] = (length and 0xFF).toByte()
            it[3] = ((length shr 8) and 0xFF).toByte()
            it[4] = (clockMs and 0xFF).toByte()
            it[5] = ((clockMs shr 8) and 0xFF).toByte()
            it[6] = ((clockMs shr 16) and 0xFF).toByte()
            it[7] = ((clockMs shr 24) and 0xFF).toByte()
        }
}
