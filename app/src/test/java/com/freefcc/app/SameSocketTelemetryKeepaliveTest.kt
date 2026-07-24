package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SameSocketTelemetryKeepaliveTest {
    @Test
    fun `each keepalive has a fresh sequence and valid wrapped version inquiry`() {
        val keepalive = SameSocketTelemetryKeepalive()
        val parser = WrappedDumlFrameParser()

        val first = parser.feed(keepalive.next()).single()
        val second = parser.feed(keepalive.next()).single()

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

    @Test
    fun `idle gate sends after two timeouts and resets on data`() {
        val gate = StreamKeepaliveIdleGate(idleTimeoutsBeforeSend = 2)

        assertTrue(!gate.onReadTimeout())
        gate.onBytesReceived()
        assertTrue(!gate.onReadTimeout())
        assertTrue(gate.onReadTimeout())
        assertTrue(!gate.onReadTimeout())
    }
}
