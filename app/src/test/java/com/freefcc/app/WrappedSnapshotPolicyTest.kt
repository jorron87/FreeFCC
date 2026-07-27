package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrappedSnapshotPolicyTest {
    @Test
    fun `only clean crc-valid response is fresh`() {
        assertNull(
            WrappedSnapshotPolicy.failureReason(
                transportFailure = null,
                receivedBytes = 4116,
                frameCount = 92,
                parserErrorCount = 0
            )
        )
        assertEquals(
            "Snapshot returned no source bytes",
            WrappedSnapshotPolicy.failureReason(null, 0, 0, 0)
        )
        assertEquals(
            "Snapshot contained no CRC-valid DUML frames",
            WrappedSnapshotPolicy.failureReason(null, 20, 0, 0)
        )
        assertEquals(
            "Snapshot contained 1 parser error(s)",
            WrappedSnapshotPolicy.failureReason(null, 20, 1, 1)
        )
        assertEquals(
            "Snapshot socket failed: reset",
            WrappedSnapshotPolicy.failureReason("Snapshot socket failed: reset", 4116, 92, 0)
        )
    }

    @Test
    fun `cadence delay is measured from snapshot start and never negative`() {
        assertEquals(800L, WrappedSnapshotPolicy.remainingDelayMs(1000, 200))
        assertEquals(0L, WrappedSnapshotPolicy.remainingDelayMs(1000, 1200))
    }
}
