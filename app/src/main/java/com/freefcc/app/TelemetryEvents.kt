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
    BenchWrappedSocket("bench_wrapped_socket"),
    BenchDirectSocket("bench_direct_socket");

    companion object {
        fun forPort(port: Int): TelemetrySourceMode =
            if (port == DumlTransport.PORT_LED) BenchWrappedSocket else BenchDirectSocket
    }
}

data class TelemetryConfig(
    val host: String,
    val port: Int,
    val sourceId: String,
    val capturePort: Int = DumlTransport.PORT_LED,
    val sourceMode: TelemetrySourceMode = TelemetrySourceMode.forPort(capturePort),
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
    val controllerModel: String,
    val controllerIdentity: ControllerIdentity = ControllerIdentity(null, null)
) : TelemetryEvent("HELLO") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "app_version" to appVersion,
        "source_mode" to config.sourceMode.wireName,
        "source_port" to config.capturePort,
        "source_id" to config.sourceId,
        "controller_model" to controllerModel,
        "controller_serial" to controllerIdentity.serial.orEmpty(),
        "controller_serial_source" to controllerIdentity.serialSource.orEmpty(),
        "controller_firmware" to config.controllerFirmware,
        "dji_fly_version" to config.djiFlyVersion,
        "aircraft_model" to config.aircraftModel,
        "aircraft_firmware" to config.aircraftFirmware,
        "device" to (Build.DEVICE ?: "unknown"),
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

data class TelemetryProbeResultEvent(
    val sessionId: String,
    val requestId: String,
    val status: String,
    val message: String,
    val payload: ByteArray? = null,
    val result: TelemetryProbeResult? = null,
    val exchange: DumlExchangeResult? = null
) : TelemetryEvent("PROBE_RESULT") {
    override fun toJsonLine(): String {
        val quality = if (result != null && status == "ok") "candidate" else "unavailable"
        return """
            {"type":"$type","schema":"$TELEMETRY_SCHEMA","session_id":"${esc(sessionId)}","request_id":"${esc(requestId)}","probe":"fc_osd_03_43_once","captured_at":"${utcNow()}","status":"${esc(status)}","message":"${esc(message)}","position":{"lat_deg":null,"lon_deg":null,"alt_m":null},"attitude":{"roll_deg":${numberOrNull(result?.rollCandidateDeg)},"pitch_deg":${numberOrNull(result?.pitchCandidateDeg)},"yaw_deg":${numberOrNull(result?.yawCandidateDeg)}},"gimbal":{"roll_deg":null,"pitch_deg":null,"yaw_deg":null},"quality":{"position":"unknown","attitude":"$quality","gimbal":"unknown"},"raw":{"message_family":"03/43","payload_b64":${nullable(payload?.let(::b64))},"longitude_raw_f64":${numberOrNull(result?.longitudeRaw)},"latitude_raw_f64":${numberOrNull(result?.latitudeRaw)},"longitude_if_radians_deg":${numberOrNull(result?.longitudeRadiansCandidateDeg)},"latitude_if_radians_deg":${numberOrNull(result?.latitudeRadiansCandidateDeg)},"relative_height_m_candidate":${numberOrNull(result?.relativeHeightCandidateM)}},"diagnostics":${exchangeDiagnostics(exchange)}}
        """.trimIndent()
    }
}

data class TelemetryDumlResultEvent(
    val sessionId: String,
    val requestId: String,
    val command: TelemetryRelayCommand.Duml,
    val status: String,
    val message: String,
    val responsePayload: ByteArray? = null,
    val exchange: DumlExchangeResult? = null
) : TelemetryEvent("DUML_RESULT") {
    override fun toJsonLine(): String = """
        {"type":"$type","schema":"$TELEMETRY_SCHEMA","session_id":"${esc(sessionId)}","request_id":"${esc(requestId)}","captured_at":"${utcNow()}","status":"${esc(status)}","message":"${esc(message)}","request":{"sender":${command.sender},"destination":${command.destination},"cmd_type":${command.cmdType},"cmd_set":${command.cmdSet},"cmd_id":${command.cmdId},"payload_b64":"${b64(command.payload)}","expect_response":${command.expectResponse},"read_window_ms":${command.readWindowMs},"port":${command.port}},"response":{"payload_b64":${nullable(responsePayload?.let(::b64))},"payload_length":${responsePayload?.size ?: 0}},"diagnostics":${exchangeDiagnostics(exchange)}}
    """.trimIndent()
}

data class TelemetryDumlRejectedEvent(
    val sessionId: String,
    val requestId: String,
    val message: String
) : TelemetryEvent("DUML_RESULT") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "request_id" to requestId,
        "captured_at" to utcNow(),
        "status" to "rejected",
        "message" to message
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

private fun numberOrNull(value: Double?): String =
    if (value != null && value.isFinite()) value.toString() else "null"

private fun exchangeDiagnostics(exchange: DumlExchangeResult?): String {
    if (exchange == null) return "null"
    val observations = exchange.observations.joinToString(prefix = "[", postfix = "]") {
        """{"validation":"${esc(it.validation)}","raw_frame_b64":"${b64(it.raw)}"}"""
    }
    return """{"terminal_reason":"${esc(exchange.terminalReason)}","observed_frame_count":${exchange.observations.size},"observed_frames":$observations}"""
}

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
