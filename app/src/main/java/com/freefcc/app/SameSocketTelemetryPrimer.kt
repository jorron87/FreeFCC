package com.freefcc.app

/**
 * Builds a fresh wrapped 03/44 request for the existing 40007 connection.
 *
 * A new DUML sequence and CRC are generated for every refresh. This never
 * opens a socket and must only be used by an explicitly primed bench source.
 */
internal class SameSocketTelemetryPrimer(
    private val builder: DumlBuilder = DumlBuilder()
) {
    fun next(): ByteArray = wrap(
        builder.buildFrame(
            DumlFrame(
                sender = 0x02,
                dst = 0x03,
                cmdType = 0x40,
                cmdSet = 0x03,
                cmdId = 0x44,
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
