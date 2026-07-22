package com.freefcc.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryProbeDecoderTest {

    @Test
    fun `decodes 03 43 fields as candidates without verifying position`() {
        val payload = ByteArray(30)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).apply {
            putDouble(0, Math.toRadians(10.7522))
            putDouble(8, Math.toRadians(59.9139))
            putShort(16, 123)
            putShort(24, (-45).toShort())
            putShort(26, 12.toShort())
            putShort(28, 1799.toShort())
        }

        val result = requireNotNull(TelemetryProbeDecoder.decode(payload))

        assertEquals(30, result.payloadSize)
        assertEquals(10.7522, result.longitudeRadiansCandidateDeg!!, 0.000001)
        assertEquals(59.9139, result.latitudeRadiansCandidateDeg!!, 0.000001)
        assertEquals(12.3, result.relativeHeightCandidateM, 0.000001)
        assertEquals(-4.5, result.pitchCandidateDeg, 0.000001)
        assertEquals(1.2, result.rollCandidateDeg, 0.000001)
        assertEquals(179.9, result.yawCandidateDeg, 0.000001)
    }

    @Test
    fun `rejects truncated payload`() {
        assertNull(TelemetryProbeDecoder.decode(ByteArray(29)))
    }

    @Test
    fun `keeps implausible radians conversion null`() {
        val payload = ByteArray(30)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).apply {
            putDouble(0, PI + 0.01)
            putDouble(8, PI)
        }

        val result = requireNotNull(TelemetryProbeDecoder.decode(payload))

        assertNull(result.longitudeRadiansCandidateDeg)
        assertNull(result.latitudeRadiansCandidateDeg)
    }
}
