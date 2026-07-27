package com.freefcc.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

data class TelemetryProbeResult(
    val payloadSize: Int,
    val rawPayloadHex: String,
    val longitudeRaw: Double?,
    val latitudeRaw: Double?,
    val longitudeRadiansCandidateDeg: Double?,
    val latitudeRadiansCandidateDeg: Double?,
    val relativeHeightCandidateM: Double,
    val pitchCandidateDeg: Double,
    val rollCandidateDeg: Double,
    val yawCandidateDeg: Double
)

/** Candidate-only decoder for FLYC OSD General Data (03/43). */
object TelemetryProbeDecoder {
    private const val MIN_PAYLOAD_SIZE = 30

    fun decode(payload: ByteArray): TelemetryProbeResult? {
        if (payload.size < MIN_PAYLOAD_SIZE) return null

        val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val longitude = data.getDouble(0).finiteOrNull()
        val latitude = data.getDouble(8).finiteOrNull()
        val relativeHeightRaw = data.getShort(16).toInt()
        val pitchRaw = data.getShort(24).toInt()
        val rollRaw = data.getShort(26).toInt()
        val yawRaw = data.getShort(28).toInt()

        return TelemetryProbeResult(
            payloadSize = payload.size,
            rawPayloadHex = payload.joinToString(" ") { "%02x".format(it) },
            longitudeRaw = longitude,
            latitudeRaw = latitude,
            longitudeRadiansCandidateDeg = longitude?.radiansCandidate(PI),
            latitudeRadiansCandidateDeg = latitude?.radiansCandidate(PI / 2),
            relativeHeightCandidateM = relativeHeightRaw * 0.1,
            pitchCandidateDeg = pitchRaw * 0.1,
            rollCandidateDeg = rollRaw * 0.1,
            yawCandidateDeg = yawRaw * 0.1
        )
    }

    private fun Double.finiteOrNull(): Double? = if (isFinite()) this else null

    private fun Double.radiansCandidate(bound: Double): Double? =
        if (isFinite() && kotlin.math.abs(this) <= bound) Math.toDegrees(this) else null
}
