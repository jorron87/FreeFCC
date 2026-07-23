package com.freefcc.app

/**
 * Parses the passive RC2 publish stream observed on TCP 8902.
 *
 * Records start with F5/F6/F8 64, followed by a little-endian total length and
 * a little-endian 32-bit controller clock in milliseconds. This is not DUML.
 */
class Rc2PublishStreamParser(
    private val maxRecordLength: Int = MAX_RECORD_LENGTH,
    private val maxPendingBytes: Int = MAX_PENDING_BYTES
) {
    data class Record(
        val marker: Int,
        val length: Int,
        val sourceClockMs: Long,
        val raw: ByteArray
    )

    data class ParseError(val reason: String, val detail: String)

    sealed interface Result {
        data class Parsed(val record: Record) : Result
        data class Error(val error: ParseError) : Result
    }

    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<Result> {
        if (length <= 0) return emptyList()
        require(length <= bytes.size)

        val accepted = if (length == bytes.size) bytes else bytes.copyOf(length)
        pending = if (pending.isEmpty()) accepted.copyOf() else pending + accepted

        val results = mutableListOf<Result>()
        if (pending.size > maxPendingBytes) {
            val keep = pending.takeLast(MAX_HEADER_SIZE - 1).toByteArray()
            results += Result.Error(
                ParseError(
                    "publish_buffer_overflow",
                    "pending=${pending.size} max=$maxPendingBytes"
                )
            )
            pending = keep
        }

        while (pending.size >= 2) {
            val markerOffset = findMarker(pending)
            if (markerOffset < 0) {
                pending = if (isMarkerPrefix(pending.last())) {
                    byteArrayOf(pending.last())
                } else {
                    ByteArray(0)
                }
                break
            }
            if (markerOffset > 0) {
                results += Result.Error(
                    ParseError("publish_resync", "discarded=$markerOffset")
                )
                pending = pending.copyOfRange(markerOffset, pending.size)
            }
            if (pending.size < MAX_HEADER_SIZE) break

            val recordLength = u16Le(pending, 2)
            if (recordLength !in MAX_HEADER_SIZE..maxRecordLength) {
                results += Result.Error(
                    ParseError(
                        "publish_invalid_length",
                        "marker=${markerName(pending[0])} length=$recordLength"
                    )
                )
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < recordLength) break

            val raw = pending.copyOfRange(0, recordLength)
            results += Result.Parsed(
                Record(
                    marker = raw[0].toInt() and 0xFF,
                    length = recordLength,
                    sourceClockMs = u32Le(raw, 4),
                    raw = raw
                )
            )
            pending = pending.copyOfRange(recordLength, pending.size)
        }
        return results
    }

    fun finish(): List<Result> {
        if (pending.isEmpty()) return emptyList()
        val error = Result.Error(
            ParseError("publish_truncated_record", "remaining=${pending.size}")
        )
        pending = ByteArray(0)
        return listOf(error)
    }

    private fun findMarker(bytes: ByteArray): Int {
        for (index in 0 until bytes.lastIndex) {
            if (isMarkerPrefix(bytes[index]) && bytes[index + 1] == MARKER_SUFFIX) {
                return index
            }
        }
        return -1
    }

    private fun isMarkerPrefix(value: Byte): Boolean =
        value == MARKER_F5 || value == MARKER_F6 || value == MARKER_F8

    private fun markerName(value: Byte): String =
        "%02X64".format(value.toInt() and 0xFF)

    private fun u16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32Le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    companion object {
        private const val MAX_HEADER_SIZE = 8
        private const val MAX_RECORD_LENGTH = 65_535
        private const val MAX_PENDING_BYTES = 256 * 1024
        private val MARKER_F5 = 0xF5.toByte()
        private val MARKER_F6 = 0xF6.toByte()
        private val MARKER_F8 = 0xF8.toByte()
        private val MARKER_SUFFIX = 0x64.toByte()
    }
}
