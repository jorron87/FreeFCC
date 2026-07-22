package com.freefcc.app

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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class TelemetryRelayClient(
    private val config: TelemetryConfig,
    private val sessionId: String,
    private val appVersion: String,
    private val controllerModel: String,
    private val scope: CoroutineScope,
    private val onStatus: (TelemetryStatus) -> Unit,
    private val onFatal: (String) -> Unit
) {
    private val queue = ArrayBlockingQueue<TelemetryEvent>(MAX_QUEUE_EVENTS)
    private var writerJob: Job? = null

    fun start() {
        writerJob?.cancel()
        writerJob = scope.launch(Dispatchers.IO) {
            var reconnectDelayMs = 250L
            while (isActive) {
                var socket: Socket? = null
                try {
                    onStatus(TelemetryStatus(connecting = true, relayConnected = false))
                    socket = Socket()
                    socket.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
                    socket.tcpNoDelay = true
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                    onStatus(
                        TelemetryStatus(
                            connecting = false,
                            relayConnected = true,
                            queueDepth = queue.size,
                            lastError = ""
                        )
                    )
                    reconnectDelayMs = 250L

                    writeLine(writer, TelemetryHelloEvent(sessionId, config, appVersion, controllerModel))
                    while (isActive && !socket.isClosed) {
                        val event = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        writeLine(writer, event)
                        onStatus(TelemetryStatus(relayConnected = true, queueDepth = queue.size))
                    }
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
    }

    fun enqueue(event: TelemetryEvent): Boolean {
        val accepted = queue.offer(event)
        if (!accepted) {
            onFatal("Telemetry relay queue full; stopping to avoid stale metadata")
        } else {
            onStatus(TelemetryStatus(queueDepth = queue.size))
        }
        return accepted
    }

    private fun writeLine(writer: BufferedWriter, event: TelemetryEvent) {
        writer.write(event.toJsonLine())
        writer.newLine()
        writer.flush()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val MAX_QUEUE_EVENTS = 512
    }
}

data class TelemetryStatus(
    val running: Boolean? = null,
    val connecting: Boolean? = null,
    val relayConnected: Boolean? = null,
    val sessionId: String? = null,
    val sourceMode: String? = null,
    val rawChunks: Long? = null,
    val frames: Long? = null,
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
    val rawChunks: Long = 0,
    val frames: Long = 0,
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
        rawChunks = status.rawChunks ?: rawChunks,
        frames = status.frames ?: frames,
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
