package com.freefcc.app

internal object WrappedSnapshotPolicy {
    fun failureReason(
        transportFailure: String?,
        receivedBytes: Int,
        frameCount: Int,
        parserErrorCount: Long
    ): String? = transportFailure ?: when {
        receivedBytes == 0 -> "Snapshot returned no source bytes"
        parserErrorCount > 0 -> "Snapshot contained $parserErrorCount parser error(s)"
        frameCount == 0 -> "Snapshot contained no CRC-valid DUML frames"
        else -> null
    }

    fun remainingDelayMs(intervalMs: Long, elapsedMs: Long): Long =
        (intervalMs - elapsedMs).coerceAtLeast(0L)
}
