package com.freefcc.app

/**
 * Incrementally extracts bounded DUML frames from arbitrary byte chunks.
 *
 * Capture must stay conservative: this parser validates structure and CRCs only.
 * It does not promote fields such as position or attitude to telemetry values.
 */
class DumlFrameParser {

    data class ParsedFrame(
        val raw: ByteArray,
        val sender: Int,
        val receiver: Int,
        val sequence: Int,
        val cmdType: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payloadLength: Int,
        val validationStatus: String = "valid"
    )

    data class ParseError(
        val reason: String,
        val byteOffset: Long,
        val detail: String = ""
    )

    sealed class Result {
        data class Frame(val frame: ParsedFrame) : Result()
        data class Error(val error: ParseError) : Result()
    }

    private val buffer = ArrayList<Byte>(MAX_BUFFER_BYTES)
    private var consumedBytes = 0L

    fun feed(chunk: ByteArray): List<Result> {
        if (chunk.isEmpty()) return emptyList()
        chunk.forEach { buffer.add(it) }

        val results = mutableListOf<Result>()
        while (buffer.isNotEmpty()) {
            val magicIndex = buffer.indexOf(MAGIC)
            if (magicIndex < 0) {
                consumedBytes += buffer.size
                buffer.clear()
                break
            }
            if (magicIndex > 0) {
                consumedBytes += magicIndex.toLong()
                buffer.subList(0, magicIndex).clear()
            }
            if (buffer.size < MIN_FRAME_BYTES) break

            val totalLength = frameLength(buffer[1], buffer[2])
            if (totalLength < MIN_FRAME_BYTES || totalLength > MAX_FRAME_BYTES) {
                results.add(Result.Error(ParseError("invalid_length", consumedBytes, "length=$totalLength")))
                buffer.removeAt(0)
                consumedBytes += 1
                continue
            }
            if (buffer.size < totalLength) break

            val raw = ByteArray(totalLength)
            for (i in 0 until totalLength) raw[i] = buffer[i]

            val headerCrc = DumlBuilder.crc8(raw, 0, 3)
            if (headerCrc != (raw[3].toInt() and 0xFF)) {
                results.add(Result.Error(ParseError("crc8_mismatch", consumedBytes, "length=$totalLength")))
                buffer.removeAt(0)
                consumedBytes += 1
                continue
            }

            val expectedCrc16 = DumlBuilder.crc16(raw, 0, totalLength - 2)
            val actualCrc16 = (raw[totalLength - 2].toInt() and 0xFF) or
                ((raw[totalLength - 1].toInt() and 0xFF) shl 8)
            if (expectedCrc16 != actualCrc16) {
                results.add(Result.Error(ParseError("crc16_mismatch", consumedBytes, "length=$totalLength")))
                buffer.removeAt(0)
                consumedBytes += 1
                continue
            }

            results.add(
                Result.Frame(
                    ParsedFrame(
                        raw = raw,
                        sender = raw[4].toInt() and 0xFF,
                        receiver = raw[5].toInt() and 0xFF,
                        sequence = (raw[6].toInt() and 0xFF) or ((raw[7].toInt() and 0xFF) shl 8),
                        cmdType = raw[8].toInt() and 0xFF,
                        cmdSet = raw[9].toInt() and 0xFF,
                        cmdId = raw[10].toInt() and 0xFF,
                        payloadLength = totalLength - MIN_FRAME_BYTES
                    )
                )
            )
            buffer.subList(0, totalLength).clear()
            consumedBytes += totalLength.toLong()
        }

        if (buffer.size > MAX_BUFFER_BYTES) {
            val drop = buffer.size - MAX_BUFFER_BYTES
            buffer.subList(0, drop).clear()
            consumedBytes += drop.toLong()
            results.add(Result.Error(ParseError("buffer_overflow", consumedBytes, "dropped=$drop")))
        }

        return results
    }

    fun reset() {
        buffer.clear()
        consumedBytes = 0L
    }

    fun finish(): List<Result> {
        if (buffer.isEmpty()) return emptyList()
        val pending = buffer.size
        val offset = consumedBytes
        buffer.clear()
        consumedBytes += pending
        return listOf(
            Result.Error(
                ParseError(
                    reason = "truncated_frame",
                    byteOffset = offset,
                    detail = "pending=$pending"
                )
            )
        )
    }

    companion object {
        private const val MAGIC: Byte = 0x55
        const val MIN_FRAME_BYTES = 13
        const val MAX_FRAME_BYTES = 1023
        private const val MAX_BUFFER_BYTES = 4096

        fun frameLength(lo: Byte, hi: Byte): Int =
            (lo.toInt() and 0xFF) or ((hi.toInt() and 0x03) shl 8)
    }
}
