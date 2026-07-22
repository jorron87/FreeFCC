package com.freefcc.app

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32

enum class TelemetrySourceMode(val wireName: String) {
    BenchActiveSocket("bench_active_socket")
}

data class TelemetryConfig(
    val host: String,
    val port: Int,
    val sourceId: String,
    val sourceMode: TelemetrySourceMode = TelemetrySourceMode.BenchActiveSocket,
    val controllerFirmware: String = "",
    val djiFlyVersion: String = "",
    val aircraftModel: String = "",
    val aircraftFirmware: String = ""
)

sealed class TelemetryEvent(val type: String) {
    abstract fun toJsonLine(): String
}

data class TelemetryHelloEvent(
    val sessionId: String,
    val config: TelemetryConfig,
    val appVersion: String,
    val controllerModel: String
) : TelemetryEvent("HELLO") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "app_version" to appVersion,
        "source_mode" to config.sourceMode.wireName,
        "source_id" to config.sourceId,
        "controller_model" to controllerModel,
        "controller_firmware" to config.controllerFirmware,
        "dji_fly_version" to config.djiFlyVersion,
        "aircraft_model" to config.aircraftModel,
        "aircraft_firmware" to config.aircraftFirmware,
        "device" to Build.DEVICE,
        "created_at" to utcNow()
    )
}

data class RawChunkEvent(
    val sessionId: String,
    val seq: Long,
    val elapsedRealtimeNs: Long,
    val source: String,
    val direction: String,
    val port: Int,
    val bytes: ByteArray
) : TelemetryEvent("RAW_CHUNK") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "seq" to seq,
        "wall_time_utc" to utcNow(),
        "elapsed_realtime_ns" to elapsedRealtimeNs,
        "source" to source,
        "direction" to direction,
        "port" to port,
        "bytes_b64" to b64(bytes),
        "crc32" to crc32Hex(bytes)
    )
}

data class DumlFrameEvent(
    val sessionId: String,
    val rawSeq: Long,
    val frame: DumlFrameParser.ParsedFrame,
    val source: String,
    val direction: String,
    val port: Int
) : TelemetryEvent("DUML_FRAME") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "raw_seq" to rawSeq,
        "wall_time_utc" to utcNow(),
        "source" to source,
        "direction" to direction,
        "port" to port,
        "sender" to frame.sender,
        "receiver" to frame.receiver,
        "seq" to frame.sequence,
        "cmd_type" to frame.cmdType,
        "cmd_set" to frame.cmdSet,
        "cmd_id" to frame.cmdId,
        "payload_length" to frame.payloadLength,
        "validation_status" to frame.validationStatus,
        "raw_frame_b64" to b64(frame.raw),
        "quality" to "unknown"
    )
}

data class TelemetryCandidateEvent(
    val sessionId: String,
    val sourceId: String,
    val messageFamily: String?,
    val quality: String = "unknown"
) : TelemetryEvent("TELEMETRY_CANDIDATE") {
    override fun toJsonLine(): String = """
        {"type":"$type","schema":"$TELEMETRY_SCHEMA","session_id":"${esc(sessionId)}","source_id":"${esc(sourceId)}","captured_at":"${utcNow()}","position":{"lat_deg":null,"lon_deg":null,"alt_m":null},"attitude":{"roll_deg":null,"pitch_deg":null,"yaw_deg":null},"gimbal":{"roll_deg":null,"pitch_deg":null,"yaw_deg":null},"quality":{"position":"unknown","attitude":"$quality"},"raw":{"message_family":${nullable(messageFamily)},"message_version":null}}
    """.trimIndent()
}

data class TelemetryUnavailableEvent(
    val sessionId: String,
    val sourceId: String,
    val reason: String
) : TelemetryEvent("TELEMETRY_CANDIDATE") {
    override fun toJsonLine(): String = """
        {"type":"$type","schema":"$TELEMETRY_SCHEMA","session_id":"${esc(sessionId)}","source_id":"${esc(sourceId)}","captured_at":"${utcNow()}","position":{"lat_deg":null,"lon_deg":null,"alt_m":null},"attitude":{"roll_deg":null,"pitch_deg":null,"yaw_deg":null},"gimbal":{"roll_deg":null,"pitch_deg":null,"yaw_deg":null},"quality":{"position":"unavailable","attitude":"unavailable","gimbal":"unavailable"},"raw":{"message_family":null,"message_version":null},"unavailable_reason":"${esc(reason)}"}
    """.trimIndent()
}

data class TelemetryErrorEvent(
    val sessionId: String,
    val reason: String,
    val detail: String
) : TelemetryEvent("ERROR") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "wall_time_utc" to utcNow(),
        "reason" to reason,
        "detail" to detail
    )
}

fun newTelemetrySessionId(): String = UUID.randomUUID().toString()

fun crc32Hex(bytes: ByteArray): String {
    val crc = CRC32()
    crc.update(bytes)
    return "%08x".format(crc.value)
}

private const val TELEMETRY_SCHEMA = "dji-rc2-telemetry/v1"

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun nullable(value: String?): String = value?.let { "\"${esc(it)}\"" } ?: "null"

private fun jsonObject(vararg fields: Pair<String, Any>): String =
    fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        val rendered = when (value) {
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> "\"${esc(value.toString())}\""
        }
        "\"${esc(key)}\":$rendered"
    }

private fun utcNow(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
}

private fun esc(value: String): String = buildString(value.length + 8) {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}
