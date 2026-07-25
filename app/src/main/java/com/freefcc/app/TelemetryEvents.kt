package com.freefcc.app

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject

enum class TelemetrySourceMode(val wireName: String) {
    DumlLabControlOnly("duml_lab_control_only"),
    Rc2PublishStream("rc2_publish_8902"),
    BenchWrappedSnapshots("bench_wrapped_snapshot_1hz"),
    BenchWrappedSocket("bench_wrapped_socket"),
    BenchDirectSocket("bench_direct_socket");

    companion object {
        fun forPort(port: Int, streamKeepaliveEnabled: Boolean = false): TelemetrySourceMode =
            when (port) {
                CONTROL_ONLY_PORT -> DumlLabControlOnly
                DumlTransport.PORT_ALT_2 -> Rc2PublishStream
                DumlTransport.PORT_LED -> if (streamKeepaliveEnabled) {
                    BenchWrappedSnapshots
                } else {
                    BenchWrappedSocket
                }
                else -> BenchDirectSocket
            }
    }
}

data class TelemetryConfig(
    val host: String,
    val port: Int,
    val sourceId: String,
    val capturePort: Int = DumlTransport.PORT_ALT_2,
    val streamKeepaliveEnabled: Boolean = false,
    val sourceMode: TelemetrySourceMode = TelemetrySourceMode.forPort(capturePort, streamKeepaliveEnabled),
    val rawRelayEnabled: Boolean = true,
    val sampleIntervalMs: Long = 1_000,
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
    val controllerIdentity: ControllerIdentity = ControllerIdentity(null, null),
    val elapsedRealtimeNs: Long = 0
) : TelemetryEvent("HELLO") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "app_version" to appVersion,
        "source_mode" to config.sourceMode.wireName,
        "source_port" to config.capturePort,
        "source_policy" to when (config.sourceMode) {
            TelemetrySourceMode.DumlLabControlOnly -> "remote_bounded_recipes_no_capture"
            TelemetrySourceMode.Rc2PublishStream -> "persistent_read_only"
            TelemetrySourceMode.BenchWrappedSnapshots -> "one_00_01_per_connection_1hz"
            else -> "explicit_single_connection"
        },
        "stream_keepalive_enabled" to config.streamKeepaliveEnabled,
        "snapshot_mode_enabled" to
            (config.sourceMode == TelemetrySourceMode.BenchWrappedSnapshots),
        "raw_relay_enabled" to config.rawRelayEnabled,
        "sample_interval_ms" to config.sampleIntervalMs,
        "source_id" to config.sourceId,
        "controller_model" to controllerModel,
        "controller_serial" to controllerIdentity.serial.orEmpty(),
        "controller_serial_source" to controllerIdentity.serialSource.orEmpty(),
        "controller_firmware" to config.controllerFirmware,
        "dji_fly_version" to config.djiFlyVersion,
        "aircraft_model" to config.aircraftModel,
        "aircraft_firmware" to config.aircraftFirmware,
        "device" to (Build.DEVICE ?: "unknown"),
        "elapsed_realtime_ns" to elapsedRealtimeNs,
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
    val elapsedRealtimeNs: Long,
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
        "elapsed_realtime_ns" to elapsedRealtimeNs,
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

data class TelemetrySourceStatusEvent(
    val sessionId: String,
    val source: String,
    val port: Int,
    val status: String,
    val detail: String,
    val elapsedRealtimeNs: Long,
    val lastByteAgeMs: Long?
) : TelemetryEvent("SOURCE_STATUS") {
    override fun toJsonLine(): String = """
        {"type":"$type","schema":"$TELEMETRY_SCHEMA","session_id":"${esc(sessionId)}","wall_time_utc":"${utcNow()}","elapsed_realtime_ns":$elapsedRealtimeNs,"source":"${esc(source)}","port":$port,"status":"${esc(status)}","detail":"${esc(detail)}","last_byte_age_ms":${lastByteAgeMs ?: "null"}}
    """.trimIndent()
}

data class TelemetryTickEvent(
    val sessionId: String,
    val source: String,
    val port: Int,
    val elapsedRealtimeNs: Long,
    val sourceStatus: String,
    val lastByteAgeMs: Long?,
    val sourceClockMs: Long?
) : TelemetryEvent("TELEMETRY_TICK") {
    override fun toJsonLine(): String = """
        {"type":"$type","schema":"$TELEMETRY_SCHEMA","session_id":"${esc(sessionId)}","wall_time_utc":"${utcNow()}","elapsed_realtime_ns":$elapsedRealtimeNs,"source":"${esc(source)}","port":$port,"source_status":"${esc(sourceStatus)}","last_byte_age_ms":${lastByteAgeMs ?: "null"},"source_clock_ms":${sourceClockMs ?: "null"}}
    """.trimIndent()
}

data class Rc2RecordStatsEvent(
    val sessionId: String,
    val elapsedRealtimeNs: Long,
    val source: String,
    val port: Int,
    val totalRecords: Long,
    val f5Records: Long,
    val f6Records: Long,
    val f8Records: Long,
    val sourceClockMs: Long?
) : TelemetryEvent("STREAM_RECORD_STATS") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "wall_time_utc" to utcNow(),
        "elapsed_realtime_ns" to elapsedRealtimeNs,
        "source" to source,
        "port" to port,
        "total_records" to totalRecords,
        "f5_records" to f5Records,
        "f6_records" to f6Records,
        "f8_records" to f8Records,
        "source_clock_ms" to (sourceClockMs ?: -1)
    )
}

data class SnapshotStatsEvent(
    val sessionId: String,
    val elapsedRealtimeNs: Long,
    val source: String,
    val port: Int,
    val attemptCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val rxBytes: Int,
    val frameCount: Int,
    val intervalMs: Long
) : TelemetryEvent("SNAPSHOT_STATS") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "wall_time_utc" to utcNow(),
        "elapsed_realtime_ns" to elapsedRealtimeNs,
        "source" to source,
        "port" to port,
        "attempt_count" to attemptCount,
        "success_count" to successCount,
        "failure_count" to failureCount,
        "last_rx_bytes" to rxBytes,
        "last_frame_count" to frameCount,
        "interval_ms" to intervalMs,
        "command_family" to "00/01",
        "route" to "02>06",
        "connection_policy" to "one_command_per_connection"
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

data class TelemetryRelayHeartbeatEvent(
    val sessionId: String,
    val elapsedRealtimeNs: Long
) : TelemetryEvent("RELAY_HEARTBEAT") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "captured_at" to utcNow(),
        "elapsed_realtime_ns" to elapsedRealtimeNs
    )
}

internal data class DjiFlyUiSnapshotEvent(
    val sessionId: String,
    val snapshot: DjiFlyUiSnapshot
) : TelemetryEvent("DJI_FLY_UI_SNAPSHOT") {
    override fun toJsonLine(): String = JSONObject()
        .put("type", type)
        .put("schema", TELEMETRY_SCHEMA)
        .put("session_id", sessionId)
        .put("source", "dji_fly_accessibility")
        .put("captured_at", snapshot.capturedAtUtc)
        .put("elapsed_realtime_ns", snapshot.elapsedRealtimeNs)
        .put("snapshot_seq", snapshot.sequence)
        .put("event_type", snapshot.eventType)
        .put("package", "dji.go.v5")
        .put("labels", JSONArray(snapshot.labels))
        .put("node_count", snapshot.visitedNodeCount)
        .put("truncated", snapshot.truncated)
        .put("quality", "ui_observation")
        .put("semantics", "unparsed")
        .toString()
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

internal data class TelemetryDumlResultEvent(
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

internal data class TelemetryDumlLabResultEvent(
    val sessionId: String,
    val requestId: String,
    val recipe: DumlLabRecipe,
    val result: DumlLabExecutionResult
) : TelemetryEvent("DUML_LAB_RESULT") {
    override fun toJsonLine(): String {
        val steps = JSONArray()
        result.steps.forEach { step ->
            steps.put(
                JSONObject()
                    .put("index", step.index)
                    .put("phase", step.phase)
                    .put("cycle", step.cycle ?: JSONObject.NULL)
                    .put("op", step.op)
                    .put("label", step.label)
                    .put("status", step.status)
                    .put("duration_ms", step.durationMs)
                    .put("byte_count", step.bytes?.size ?: 0)
                    .put(
                        "bytes_b64",
                        step.bytes?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL
                    )
                    .put("message", step.message)
            )
        }
        val frames = JSONArray()
        result.frames.forEach { observed ->
            frames.put(
                JSONObject()
                    .put("phase", observed.phase)
                    .put("cycle", observed.cycle ?: JSONObject.NULL)
                    .put("step_index", observed.stepIndex)
                    .put("direction", observed.direction)
                    .put("sender", observed.frame.sender)
                    .put("receiver", observed.frame.receiver)
                    .put("sequence", observed.frame.sequence)
                    .put("cmd_type", observed.frame.cmdType)
                    .put("cmd_set", observed.frame.cmdSet)
                    .put("cmd_id", observed.frame.cmdId)
                    .put("payload_length", observed.frame.payloadLength)
                    .put("validation_status", observed.frame.validationStatus)
                    .put(
                        "raw_frame_b64",
                        Base64.getEncoder().encodeToString(observed.frame.raw)
                    )
            )
        }
        val errors = JSONArray()
        result.parserErrors.forEach { observed ->
            errors.put(
                JSONObject()
                    .put("phase", observed.phase)
                    .put("cycle", observed.cycle ?: JSONObject.NULL)
                    .put("step_index", observed.stepIndex)
                    .put("direction", observed.direction)
                    .put("reason", observed.error.reason)
                    .put("byte_offset", observed.error.byteOffset)
                    .put("detail", observed.error.detail)
            )
        }
        return JSONObject()
            .put("type", type)
            .put("schema", TELEMETRY_SCHEMA)
            .put("session_id", sessionId)
            .put("request_id", requestId)
            .put("captured_at", utcNow())
            .put("safety", "bench_only_bounded_loopback")
            .put("status", result.status)
            .put("terminal_reason", result.terminalReason)
            .put("message", result.message)
            .put("duration_ms", result.durationMs)
            .put("connections_opened", result.connectionsOpened)
            .put("tx_bytes", result.txBytes)
            .put("rx_bytes", result.rxBytes)
            .put("recipe", recipe.toJsonObject())
            .put("steps", steps)
            .put("frames", frames)
            .put("parser_errors", errors)
            .put("dropped_frame_diagnostics", result.droppedFrames)
            .put("dropped_parser_error_diagnostics", result.droppedParserErrors)
            .toString()
    }
}

data class TelemetryDumlLabRejectedEvent(
    val sessionId: String,
    val requestId: String,
    val message: String
) : TelemetryEvent("DUML_LAB_RESULT") {
    override fun toJsonLine(): String = jsonObject(
        "type" to type,
        "schema" to TELEMETRY_SCHEMA,
        "session_id" to sessionId,
        "request_id" to requestId,
        "captured_at" to utcNow(),
        "safety" to "bench_only_bounded_loopback",
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

internal const val TELEMETRY_SCHEMA = "dji-rc2-telemetry/v2"
internal const val CONTROL_ONLY_PORT = 0

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
