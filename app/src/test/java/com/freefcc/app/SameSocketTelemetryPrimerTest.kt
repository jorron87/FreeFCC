package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SameSocketTelemetryPrimerTest {
    @Test
    fun `each primer has a fresh sequence and valid wrapped frame`() {
        val primer = SameSocketTelemetryPrimer()
        val parser = WrappedDumlFrameParser()

        val first = parser.feed(primer.next()).single()
        val second = parser.feed(primer.next()).single()

        assertTrue(first is DumlFrameParser.Result.Frame)
        assertTrue(second is DumlFrameParser.Result.Frame)
        first as DumlFrameParser.Result.Frame
        second as DumlFrameParser.Result.Frame
        assertNotEquals(first.frame.sequence, second.frame.sequence)
        assertEquals(0x02, first.frame.sender)
        assertEquals(0x03, first.frame.receiver)
        assertEquals(0x40, first.frame.cmdType)
        assertEquals(0x03, first.frame.cmdSet)
        assertEquals(0x44, first.frame.cmdId)
        assertEquals("valid", first.frame.validationStatus)
    }
}
