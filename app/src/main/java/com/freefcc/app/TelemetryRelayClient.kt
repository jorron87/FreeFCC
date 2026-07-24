package com.freefcc.app

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal sealed interface TelemetryRelayCommand {
    val requestId: String

    data class Probe(override val requestId: String) : TelemetryRelayCommand

    data class Duml(
        override val requestId: String,
        val sender: Int,
        val destination: Int,
        val cmdType: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray,
        val expectResponse: Boolean,
        val readWindowMs: Int,
        val port: Int
    ) : TelemetryRelayCommand

    data class DumlLab(
        override val requestId: String,
        val recipe: DumlLabRecipe
    ) : TelemetryRelayCommand
}

internal data class SerializedTelemetryEvent(val line: String, val bytes: Long)

internal fun serializeTelemetryEvent(event: TelemetryEvent): SerializedTelemetryEvent {
    val line = event.toJsonLine()
    return SerializedTelemetryEvent(
        line = line,
        bytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L
    )
}

internal class TelemetryRelayClient(
    private val config: TelemetryConfig,
    private val sessionId: String,
    private val appVersion: String,
    private val controllerModel: String,
    private val controllerIdentity: ControllerIdentity,
    private val scope: CoroutineScope,
    private val onStatus: (TelemetryStatus) -> Unit,
    private val onCommand: (TelemetryRelayCommand) -> Unit,
    private val onFatal: (String) -> Unit
) {
    private val queue = ArrayBlockingQueue<SerializedTelemetryEvent>(MAX_QUEUE_EVENTS)
    private val queuedBytes = AtomicLong(0)
    private var writerJob: Job? = null

    fun start() {
        writerJob?.cancel()
        writerJob = scope.launch(Dispatchers.IO) {
            var reconnectDelayMs = 250L
            while (isActive) {
                var socket: Socket? = null
                try {
                    onStatus(TelemetryStatus(connecting = true, relayConnected = false))
                    val connectedSocket = Socket()
                    socket = connectedSocket
                    connectedSocket.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
                    connectedSocket.tcpNoDelay = true
                    connectedSocket.keepAlive = true
                    val writer = BufferedWriter(OutputStreamWriter(connectedSocket.getOutputStream(), Charsets.UTF_8))
                    onStatus(
                        TelemetryStatus(
                            connecting = false,
                            relayConnected = true,
                            queueDepth = queue.size,
                            lastError = ""
                        )
                    )
                    reconnectDelayMs = 250L

                    writeLine(
                        writer,
                        TelemetryHelloEvent(
                            sessionId,
                            config,
                            appVersion,
                            controllerModel,
                            controllerIdentity,
                            SystemClock.elapsedRealtimeNanos()
                        ).toJsonLine()
                    )
                    val readerJob = scope.launch(Dispatchers.IO) {
                        try {
                            connectedSocket.getInputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                                lines.forEach(::handleCommandLine)
                            }
                        } catch (_: Exception) {
                            // The writer loop owns reconnect/status handling.
                        } finally {
                            try { connectedSocket.close() } catch (_: Exception) {}
                        }
                    }
                    var lastHeartbeatElapsedMs = SystemClock.elapsedRealtime()
                    while (isActive && !connectedSocket.isClosed) {
                        val queued = queue.poll(500, TimeUnit.MILLISECONDS)
                        if (queued != null) {
                            queuedBytes.addAndGet(-queued.bytes)
                            writeLine(writer, queued.line)
                            onStatus(TelemetryStatus(relayConnected = true, queueDepth = queue.size))
                        }
                        val nowElapsedMs = SystemClock.elapsedRealtime()
                        if (nowElapsedMs - lastHeartbeatElapsedMs >= RELAY_HEARTBEAT_INTERVAL_MS) {
                            writeLine(
                                writer,
                                TelemetryRelayHeartbeatEvent(
                                    sessionId = sessionId,
                                    elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
                                ).toJsonLine()
                            )
                            lastHeartbeatElapsedMs = nowElapsedMs
                        }
                    }
                    readerJob.cancel()
                } catch (e: Exception) {
                    onStatus(TelemetryStatus(connecting = false, relayConnected = false, lastError = e.message.orEmpty(), queueDepth = queue.size))
                    delay(reconnectDelayMs)
                    reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(5_000L)
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    fun stop() {
        writerJob?.cancel()
        writerJob = null
        queue.clear()
        queuedBytes.set(0)
    }

    fun enqueue(event: TelemetryEvent): Boolean {
        val serialized = serializeTelemetryEvent(event)
        val totalBytes = queuedBytes.addAndGet(serialized.bytes)
        if (totalBytes > MAX_QUEUE_BYTES) {
            queuedBytes.addAndGet(-serialized.bytes)
            onFatal("Telemetry relay byte queue full; stopping to avoid stale metadata")
            return false
        }
        val accepted = queue.offer(serialized)
        if (!accepted) {
            queuedBytes.addAndGet(-serialized.bytes)
            onFatal("Telemetry relay queue full; stopping to avoid stale metadata")
        } else {
            onStatus(TelemetryStatus(queueDepth = queue.size))
        }
        return accepted
    }

    private fun writeLine(writer: BufferedWriter, line: String) {
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    private fun handleCommandLine(line: String) {
        val obj = try {
            JSONObject(line)
        } catch (_: Exception) {
            enqueueRejected("unknown", "Invalid command JSON")
            return
        }
        val requestId = obj.optString("request_id", "unknown").take(128)
        val type = obj.optString("type")
        when (type) {
            "PROBE_REQUEST" -> {
                if (obj.optString("probe") != ALLOWED_PROBE) {
                    enqueueRejected(requestId, "Unknown probe")
                } else {
                    onCommand(TelemetryRelayCommand.Probe(requestId))
                }
            }
            "DUML_REQUEST" -> {
                val command = parseDumlCommand(requestId, obj)
                if (command == null) {
                    enqueueDumlRejected(requestId, "Invalid or oversized DUML request")
                } else {
                    onCommand(command)
                }
            }
            "DUML_LAB_REQUEST" -> {
                if (config.sourceMode != TelemetrySourceMode.DumlLabControlOnly) {
                    enqueueDumlLabRejected(
                        requestId,
                        "Select Lab only and restart the relay before running arbitrary recipes"
                    )
                    return
                }
                val parsed = DumlLabRecipeParser.parse(obj)
                val recipe = parsed.recipe
                if (recipe == null) {
                    enqueueDumlLabRejected(requestId, parsed.error.ifBlank { "Invalid DUML Lab recipe" })
                } else {
                    onCommand(TelemetryRelayCommand.DumlLab(requestId, recipe))
                }
            }
            else -> enqueueRejected(requestId, "Unknown relay command type")
        }
    }

    private fun parseDumlCommand(requestId: String, obj: JSONObject): TelemetryRelayCommand.Duml? {
        fun byteField(name: String): Int? = obj.optInt(name, -1).takeIf { it in 0..255 }
        val sender = byteField("sender") ?: return null
        val destination = byteField("destination") ?: return null
        val cmdType = byteField("cmd_type") ?: return null
        val cmdSet = byteField("cmd_set") ?: return null
        val cmdId = byteField("cmd_id") ?: return null
        val payload = try {
            Base64.getDecoder().decode(obj.optString("payload_b64", ""))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (payload.size > MAX_REMOTE_PAYLOAD_BYTES) return null
        val readWindowMs = obj.optInt("read_window_ms", 1000).coerceIn(20, 5_000)
        val port = obj.optInt("port", DumlTransport.PORT)
        if (port !in RESEARCH_DUML_PORTS) return null
        return TelemetryRelayCommand.Duml(
            requestId = requestId,
            sender = sender,
            destination = destination,
            cmdType = cmdType,
            cmdSet = cmdSet,
            cmdId = cmdId,
            payload = payload,
            expectResponse = obj.optBoolean("expect_response", true),
            readWindowMs = readWindowMs,
            port = port
        )
    }

    private fun enqueueRejected(requestId: String, message: String) {
        enqueue(
            TelemetryProbeResultEvent(
                sessionId = sessionId,
                requestId = requestId,
                status = "rejected",
                message = message
            )
        )
    }

    private fun enqueueDumlRejected(requestId: String, message: String) {
        enqueue(
            TelemetryDumlRejectedEvent(
                sessionId = sessionId,
                requestId = requestId,
                message = message
            )
        )
    }

    private fun enqueueDumlLabRejected(requestId: String, message: String) {
        enqueue(
            TelemetryDumlLabRejectedEvent(
                sessionId = sessionId,
                requestId = requestId,
                message = message
            )
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val MAX_QUEUE_EVENTS = 512
        private const val MAX_QUEUE_BYTES = 8L * 1024 * 1024
        private const val RELAY_HEARTBEAT_INTERVAL_MS = 5_000L
        private const val ALLOWED_PROBE = "fc_osd_03_43_once"
        private const val MAX_REMOTE_PAYLOAD_BYTES = 512
        val RESEARCH_DUML_PORTS = setOf(40009, 40007, 8901, 8902, 8903, 8904)
    }
}

data class TelemetryStatus(
    val running: Boolean? = null,
    val connecting: Boolean? = null,
    val relayConnected: Boolean? = null,
    val sessionId: String? = null,
    val sourceMode: String? = null,
    val sourcePort: Int? = null,
    val sourceStatus: String? = null,
    val captureActive: Boolean? = null,
    val rawChunks: Long? = null,
    val frames: Long? = null,
    val keepalives: Long? = null,
    val records: Long? = null,
    val parserErrors: Long? = null,
    val bytes: Long? = null,
    val queueDepth: Int? = null,
    val lastError: String? = null
)

data class TelemetryRuntimeState(
    val running: Boolean = false,
    val connecting: Boolean = false,
    val relayConnected: Boolean = false,
    val sessionId: String = "",
    val sourceMode: String = "",
    val sourcePort: Int = DumlTransport.PORT_ALT_2,
    val sourceStatus: String = "stopped",
    val captureActive: Boolean = false,
    val rawChunks: Long = 0,
    val frames: Long = 0,
    val keepalives: Long = 0,
    val records: Long = 0,
    val parserErrors: Long = 0,
    val bytes: Long = 0,
    val queueDepth: Int = 0,
    val lastError: String = ""
) {
    fun merge(status: TelemetryStatus): TelemetryRuntimeState = copy(
        running = status.running ?: running,
        connecting = status.connecting ?: connecting,
        relayConnected = status.relayConnected ?: relayConnected,
        sessionId = status.sessionId ?: sessionId,
        sourceMode = status.sourceMode ?: sourceMode,
        sourcePort = status.sourcePort ?: sourcePort,
        sourceStatus = status.sourceStatus ?: sourceStatus,
        captureActive = status.captureActive ?: captureActive,
        rawChunks = status.rawChunks ?: rawChunks,
        frames = status.frames ?: frames,
        keepalives = status.keepalives ?: keepalives,
        records = status.records ?: records,
        parserErrors = status.parserErrors ?: parserErrors,
        bytes = status.bytes ?: bytes,
        queueDepth = status.queueDepth ?: queueDepth,
        lastError = status.lastError ?: lastError
    )
}

object TelemetryStatusBus {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(TelemetryRuntimeState())
    val updates: kotlinx.coroutines.flow.StateFlow<TelemetryRuntimeState> = state

    fun update(status: TelemetryStatus) {
        state.value = state.value.merge(status)
    }

    fun reset() {
        state.value = TelemetryRuntimeState()
    }
}
