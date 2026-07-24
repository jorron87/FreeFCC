package com.freefcc.app

/**
 * Builds the wrapped General Version Inquiry used to keep a 40007 stream open.
 *
 * This is the same app-to-RC route used by the independently tested RM330 LAN
 * reader: 02 -> 06, 00/01, no ACK requested. A fresh sequence and CRC are
 * generated for every write. The caller owns the one persistent socket and
 * must stop on EOF or write failure rather than reconnecting automatically.
 *
 * Reference: stiad/dji-rc-linux@3acbcc33d2267f3247e36383a11958ed76ba7f96
 */
internal class SameSocketTelemetryKeepalive(
    private val builder: DumlBuilder = DumlBuilder()
) {
    fun next(): ByteArray = wrap(
        builder.buildFrame(
            DumlFrame(
                sender = 0x02,
                dst = 0x06,
                cmdType = 0x00,
                cmdSet = 0x00,
                cmdId = 0x01,
                payload = ByteArray(0)
            )
        )
    )

    companion object {
        fun wrap(inner: ByteArray): ByteArray {
            val result = ByteArray(8 + inner.size)
            result[0] = 0x55
            result[1] = 0xCC.toByte()
            result[2] = 0x30
            result[3] = 0x75
            result[4] = (inner.size and 0xFF).toByte()
            result[5] = ((inner.size ushr 8) and 0xFF).toByte()
            result[6] = ((inner.size ushr 16) and 0xFF).toByte()
            result[7] = ((inner.size ushr 24) and 0xFF).toByte()
            inner.copyInto(result, destinationOffset = 8)
            return result
        }
    }
}

internal class StreamKeepaliveIdleGate(
    private val idleTimeoutsBeforeSend: Int = 2
) {
    private var consecutiveTimeouts = 0

    init {
        require(idleTimeoutsBeforeSend > 0)
    }

    fun onBytesReceived() {
        consecutiveTimeouts = 0
    }

    fun onReadTimeout(): Boolean {
        consecutiveTimeouts += 1
        if (consecutiveTimeouts < idleTimeoutsBeforeSend) return false
        consecutiveTimeouts = 0
        return true
    }
}
