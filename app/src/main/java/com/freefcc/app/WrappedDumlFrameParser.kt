package com.freefcc.app

/**
 * Extracts CRC-valid DUML frames from either a direct stream or DJI's 40007
 * envelope: 55 cc 30 75 + little-endian u32 inner length + inner DUML.
 */
class WrappedDumlFrameParser {
    private var pending = ByteArray(0)
    private var consumedBytes = 0L

    fun feed(chunk: ByteArray): List<DumlFrameParser.Result> {
        if (chunk.isEmpty()) return emptyList()
        pending += chunk

        val results = mutableListOf<DumlFrameParser.Result>()
        while (pending.isNotEmpty()) {
            val marker = pending.indexOfFirst { it == DUML_MAGIC }
            if (marker < 0) {
                consumedBytes += pending.size
                pending = ByteArray(0)
                break
            }
            if (marker > 0) dropPrefix(marker)
            if (pending.size < 4) break

            if (hasOuterMagic(pending)) {
                if (pending.size < OUTER_HEADER_BYTES) break
                val innerLength = littleEndianU32(pending, 4)
                if (innerLength !in DumlFrameParser.MIN_FRAME_BYTES.toLong()..
                    DumlFrameParser.MAX_FRAME_BYTES.toLong()
                ) {
                    results += error("wrapped_invalid_length", "inner_length=$innerLength")
                    dropPrefix(1)
                    continue
                }

                val outerLength = OUTER_HEADER_BYTES + innerLength.toInt()
                if (pending.size < outerLength) break
                val inner = pending.copyOfRange(OUTER_HEADER_BYTES, outerLength)
                results += validateSingle(inner, wrapped = true)
                dropPrefix(outerLength)
                continue
            }

            if (pending.size < DumlFrameParser.MIN_FRAME_BYTES) break
            val frameLength = DumlFrameParser.frameLength(pending[1], pending[2])
            if (frameLength !in DumlFrameParser.MIN_FRAME_BYTES..DumlFrameParser.MAX_FRAME_BYTES) {
                results += error("invalid_length", "length=$frameLength")
                dropPrefix(1)
                continue
            }
            if (pending.size < frameLength) break

            val direct = pending.copyOfRange(0, frameLength)
            val result = validateSingle(direct, wrapped = false)
            results += result
            dropPrefix(if (result is DumlFrameParser.Result.Frame) frameLength else 1)
        }

        if (pending.size > MAX_BUFFER_BYTES) {
            val dropped = pending.size - MAX_BUFFER_BYTES
            dropPrefix(dropped)
            results += error("buffer_overflow", "dropped=$dropped")
        }
        return results
    }

    fun reset() {
        pending = ByteArray(0)
        consumedBytes = 0L
    }

    fun finish(): List<DumlFrameParser.Result> {
        if (pending.isEmpty()) return emptyList()
        val result = error("truncated_frame", "pending=${pending.size}")
        consumedBytes += pending.size
        pending = ByteArray(0)
        return listOf(result)
    }

    private fun validateSingle(
        bytes: ByteArray,
        wrapped: Boolean
    ): DumlFrameParser.Result {
        val parsed = DumlFrameParser().feed(bytes)
        val frame = parsed.filterIsInstance<DumlFrameParser.Result.Frame>().singleOrNull()
        if (frame != null && parsed.size == 1) return frame

        val parserError = parsed.filterIsInstance<DumlFrameParser.Result.Error>().firstOrNull()?.error
        val prefix = if (wrapped) "wrapped_" else ""
        return error(
            reason = prefix + (parserError?.reason ?: "invalid_frame"),
            detail = parserError?.detail.orEmpty()
        )
    }

    private fun error(reason: String, detail: String): DumlFrameParser.Result.Error =
        DumlFrameParser.Result.Error(
            DumlFrameParser.ParseError(
                reason = reason,
                byteOffset = consumedBytes,
                detail = detail
            )
        )

    private fun dropPrefix(count: Int) {
        pending = if (count >= pending.size) ByteArray(0) else pending.copyOfRange(count, pending.size)
        consumedBytes += count
    }

    private fun hasOuterMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x55.toByte() &&
            bytes[1] == 0xCC.toByte() &&
            bytes[2] == 0x30.toByte() &&
            bytes[3] == 0x75.toByte()

    private fun littleEndianU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    companion object {
        private const val DUML_MAGIC: Byte = 0x55
        private const val OUTER_HEADER_BYTES = 8
        private const val MAX_BUFFER_BYTES = 16 * 1024
    }
}
