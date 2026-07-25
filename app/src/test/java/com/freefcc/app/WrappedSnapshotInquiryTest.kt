package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedSnapshotInquiryTest {
    @Test
    fun `each snapshot inquiry has a fresh sequence and valid wrapped version inquiry`() {
        val inquiry = WrappedSnapshotInquiry()
        val parser = WrappedDumlFrameParser()

        val first = parser.feed(inquiry.next()).single()
        val second = parser.feed(inquiry.next()).single()

        assertTrue(first is DumlFrameParser.Result.Frame)
        assertTrue(second is DumlFrameParser.Result.Frame)
        first as DumlFrameParser.Result.Frame
        second as DumlFrameParser.Result.Frame
        assertNotEquals(first.frame.sequence, second.frame.sequence)
        assertEquals(0x02, first.frame.sender)
        assertEquals(0x06, first.frame.receiver)
        assertEquals(0x00, first.frame.cmdType)
        assertEquals(0x00, first.frame.cmdSet)
        assertEquals(0x01, first.frame.cmdId)
        assertEquals("valid", first.frame.validationStatus)
    }
}
