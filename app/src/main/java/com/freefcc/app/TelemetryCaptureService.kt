package com.freefcc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TelemetryCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relay: TelemetryRelayClient? = null
    private var captureJob: Job? = null
    private var commandJob: Job? = null
    private var captureSocket: Socket? = null
    private var activeConfig: TelemetryConfig? = null
    private val probeTransport = DumlTransport()
    private val dumlLabEngine = DumlLabEngine()
    private var commandCancellation = DumlLabCancellation()
    private val commandInFlight = AtomicBoolean(false)
    private var lastCommandStartedElapsedMs = 0L
    private var sessionId: String = ""
    private var rawSeq = 0L
    private var rawChunks = 0L
    private var frameCount = 0L
    private var parserErrors = 0L
    private var byteCount = 0L
    private var recordCount = 0L
    private var f5RecordCount = 0L
    private var f6RecordCount = 0L
    private var f8RecordCount = 0L
    private var lastSourceClockMs: Long? = null
    private var sourceStatus = "stopped"
    private var lastByteElapsedNs = 0L
    private var lastTickElapsedNs = 0L
    private var streamKeepaliveCount = 0L
    private var lastKeepaliveStatsElapsedNs = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> startCapture(intent)
            else -> stopSelf()
        }
        return START_STICKY
    }

    @Synchronized
    private fun startCapture(intent: Intent) {
        stopCapture(closeService = false)
        commandCancellation = DumlLabCancellation()

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_RELAY_PORT)
        val capturePort = intent.getIntExtra(EXTRA_CAPTURE_PORT, DumlTransport.PORT_ALT_2)
        if (
            host.isBlank() ||
            port !in 1..65535 ||
            (capturePort != CONTROL_ONLY_PORT &&
                capturePort !in TelemetryRelayClient.RESEARCH_DUML_PORTS) ||
            (intent.getBooleanExtra(EXTRA_STREAM_KEEPALIVE_ENABLED, false) &&
                capturePort != DumlTransport.PORT_LED)
        ) {
            TelemetryStatusBus.update(
                TelemetryStatus(running = false, lastError = "Invalid Mac endpoint or DUML capture port")
            )
            stopSelf()
            return
        }

        val config = TelemetryConfig(
            host = host,
            port = port,
            sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty().ifBlank { "rc2-bench" },
            capturePort = capturePort,
            streamKeepaliveEnabled = intent.getBooleanExtra(EXTRA_STREAM_KEEPALIVE_ENABLED, false),
            controllerFirmware = intent.getStringExtra(EXTRA_CONTROLLER_FIRMWARE).orEmpty(),
            djiFlyVersion = intent.getStringExtra(EXTRA_DJI_FLY_VERSION).orEmpty(),
            aircraftModel = intent.getStringExtra(EXTRA_AIRCRAFT_MODEL).orEmpty(),
            aircraftFirmware = intent.getStringExtra(EXTRA_AIRCRAFT_FIRMWARE).orEmpty()
        )
        activeConfig = config
        sessionId = newTelemetrySessionId()
        rawSeq = 0L
        rawChunks = 0L
        frameCount = 0L
        parserErrors = 0L
        byteCount = 0L
        recordCount = 0L
        f5RecordCount = 0L
        f6RecordCount = 0L
        f8RecordCount = 0L
        lastSourceClockMs = null
        sourceStatus = "connecting"
        lastByteElapsedNs = 0L
        lastTickElapsedNs = 0L
        streamKeepaliveCount = 0L
        lastKeepaliveStatsElapsedNs = 0L

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Relay starting"))
        TelemetryStatusBus.update(
            TelemetryStatus(
                running = true,
                connecting = true,
                relayConnected = false,
                sessionId = sessionId,
                sourceMode = config.sourceMode.wireName,
                sourcePort = config.capturePort,
                sourceStatus = sourceStatus,
                captureActive = false,
                rawChunks = 0,
                frames = 0,
                keepalives = 0,
                parserErrors = 0,
                bytes = 0,
                queueDepth = 0,
                lastError = ""
            )
        )

        relay = TelemetryRelayClient(
            config = config,
            sessionId = sessionId,
            appVersion = FccViewModel.APP_VERSION,
            controllerModel = Build.DEVICE,
            controllerIdentity = ControllerIdentityReader.read(),
            scope = scope,
            onStatus = { TelemetryStatusBus.update(it) },
            onCommand = ::handleRelayCommand,
            onFatal = { reason -> failClosed(reason) }
        ).also { it.start() }

        if (config.sourceMode == TelemetrySourceMode.DumlLabControlOnly) {
            sourceStatus = "control_only"
            TelemetryStatusBus.update(
                TelemetryStatus(
                    connecting = true,
                    sourceStatus = sourceStatus,
                    captureActive = false,
                    lastError = ""
                )
            )
            emitSourceStatus(
                config,
                sourceStatus,
                "DUML Lab ready; no controller capture port is held"
            )
        } else {
            captureJob = scope.launch {
                runCapture(config)
            }
        }
    }

    @Synchronized
    private fun handleRelayCommand(command: TelemetryRelayCommand) {
        if (activeConfig == null) {
            sendRelayFailure(command, "stopped", "Telemetry relay is not running")
            return
        }
        if (!commandInFlight.compareAndSet(false, true)) {
            sendRelayFailure(command, "busy", "Another DUML command is already running")
            return
        }

        val cancellation = commandCancellation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var hardwareLease: HardwareLock.Lease? = null
            var portLease: DumlPortSessionLock.Lease? = null
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCommandStartedElapsedMs < MIN_COMMAND_INTERVAL_MS) {
                    sendRelayFailure(command, "rate_limited", "Wait before requesting another DUML command")
                    return@launch
                }
                lastCommandStartedElapsedMs = now

                hardwareLease = HardwareLock.tryBegin()
                if (hardwareLease == null) {
                    sendRelayFailure(command, "busy", "Controller hardware is busy")
                    return@launch
                }

                if (activeConfig == null) {
                    sendRelayFailure(command, "error", "Telemetry session configuration is unavailable")
                    return@launch
                }

                val commandPort = when (command) {
                    is TelemetryRelayCommand.Probe -> DumlTransport.PORT
                    is TelemetryRelayCommand.Duml -> command.port
                    is TelemetryRelayCommand.DumlLab -> command.recipe.port
                }
                portLease = DumlPortSessionLock.tryBegin(commandPort)
                if (portLease == null) {
                    sendRelayFailure(
                        command,
                        "port_busy",
                        "DUML port $commandPort is held by active capture or another command"
                    )
                    return@launch
                }

                when (command) {
                    is TelemetryRelayCommand.Probe -> executeFcOsdProbe(command)
                    is TelemetryRelayCommand.Duml -> executeDumlRequest(command)
                    is TelemetryRelayCommand.DumlLab -> executeDumlLab(command, cancellation)
                }
            } catch (e: Exception) {
                sendRelayFailure(command, "error", e.message.orEmpty().ifBlank { "DUML request failed" })
            } finally {
                portLease?.close()
                hardwareLease?.close()
            }
        }
        job.invokeOnCompletion { commandInFlight.set(false) }
        commandJob = job
        job.start()
    }

    private fun executeFcOsdProbe(command: TelemetryRelayCommand.Probe) {
        val profile = Profiles.load(this, "telemetry_fc_osd_probe.json")
        val frame = profile.frames.singleOrNull()
            ?: error("Probe profile must contain exactly one frame")
        val exchange = probeTransport.sendAndReceiveDetailed(
            frame,
            profile.readWindowMs,
            profile.port,
            pinPort = true
        )
        val payload = exchange.payload
        if (payload == null) {
            sendProbeResult(
                command.requestId,
                "no_response",
                "No matching 03/43 response; no retry sent",
                exchange = exchange
            )
            return
        }

        val decoded = TelemetryProbeDecoder.decode(payload)
        if (decoded == null) {
            sendProbeResult(
                command.requestId,
                "layout_mismatch",
                "03/43 payload was ${payload.size} bytes; expected at least 30",
                payload,
                exchange = exchange
            )
        } else {
            sendProbeResult(
                command.requestId,
                "ok",
                "Candidate data received; GPS remains unverified",
                payload,
                decoded,
                exchange
            )
        }
    }

    private fun executeDumlRequest(command: TelemetryRelayCommand.Duml) {
        val frame = DumlBuilder().buildFrame(
            DumlFrame(
                sender = command.sender,
                dst = command.destination,
                cmdType = command.cmdType,
                cmdSet = command.cmdSet,
                cmdId = command.cmdId,
                payload = command.payload
            )
        )
        if (command.expectResponse) {
            val exchange = probeTransport.sendAndReceiveDetailed(
                frame,
                command.readWindowMs,
                command.port,
                pinPort = true
            )
            val response = exchange.payload
            sendDumlResult(
                command = command,
                status = if (response == null) "no_response" else "ok",
                message = if (response == null) "No matching response; no retry sent" else "Matching response received",
                responsePayload = response,
                exchange = exchange
            )
        } else {
            val sent = probeTransport.sendFrames(
                frames = listOf(frame),
                rounds = 1,
                interFrameDelayMs = 0,
                interRoundDelayMs = 0,
                readWindowMs = command.readWindowMs,
                port = command.port,
                pinPort = true
            )
            sendDumlResult(
                command = command,
                status = if (sent) "sent" else "send_failed",
                message = if (sent) "One DUML frame sent" else "DUML frame could not be sent"
            )
        }
    }

    private fun executeDumlLab(
        command: TelemetryRelayCommand.DumlLab,
        cancellation: DumlLabCancellation
    ) {
        val result = dumlLabEngine.execute(command.recipe, cancellation)
        relay?.enqueue(
            TelemetryDumlLabResultEvent(
                sessionId = sessionId,
                requestId = command.requestId,
                recipe = command.recipe,
                result = result
            )
        )
    }

    private fun sendProbeResult(
        requestId: String,
        status: String,
        message: String,
        payload: ByteArray? = null,
        result: TelemetryProbeResult? = null,
        exchange: DumlExchangeResult? = null
    ) {
        relay?.enqueue(
            TelemetryProbeResultEvent(
                sessionId = sessionId,
                requestId = requestId,
                status = status,
                message = message,
                payload = payload,
                result = result,
                exchange = exchange
            )
        )
    }

    private fun sendDumlResult(
        command: TelemetryRelayCommand.Duml,
        status: String,
        message: String,
        responsePayload: ByteArray? = null,
        exchange: DumlExchangeResult? = null
    ) {
        relay?.enqueue(
            TelemetryDumlResultEvent(
                sessionId = sessionId,
                requestId = command.requestId,
                command = command,
                status = status,
                message = message,
                responsePayload = responsePayload,
                exchange = exchange
            )
        )
    }

    private fun sendRelayFailure(command: TelemetryRelayCommand, status: String, message: String) {
        when (command) {
            is TelemetryRelayCommand.Probe -> sendProbeResult(command.requestId, status, message)
            is TelemetryRelayCommand.Duml -> sendDumlResult(command, status, message)
            is TelemetryRelayCommand.DumlLab -> relay?.enqueue(
                TelemetryDumlLabRejectedEvent(
                    sessionId = sessionId,
                    requestId = command.requestId,
                    message = "$status: $message"
                )
            )
        }
    }

    private suspend fun runCapture(config: TelemetryConfig) {
        val isPublishStream = config.sourceMode == TelemetrySourceMode.Rc2PublishStream
        val buffer = ByteArray(if (isPublishStream) 32 * 1024 else 4 * 1024)
        val dumlParser = if (isPublishStream) null else WrappedDumlFrameParser()
        val publishParser = if (isPublishStream) Rc2PublishStreamParser() else null
        val sourcePort = config.capturePort
        val lease = DumlPortSessionLock.tryBegin(sourcePort)
        if (lease == null) {
            reportCaptureGap(config, "Controller port $sourcePort is already in use")
            return
        }
        val socket = Socket()
        captureSocket = socket
        var gapReason = "Source socket closed"
        try {
            socket.connect(InetSocketAddress(DUML_HOST, sourcePort), DUML_CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = if (config.streamKeepaliveEnabled) {
                STREAM_KEEPALIVE_READ_TIMEOUT_MS
            } else {
                SOURCE_READ_TIMEOUT_MS
            }
            val streamKeepalive = if (config.streamKeepaliveEnabled) {
                SameSocketTelemetryKeepalive()
            } else {
                null
            }
            val keepaliveIdleGate = if (streamKeepalive != null) {
                StreamKeepaliveIdleGate(STREAM_KEEPALIVE_IDLE_TIMEOUTS)
            } else {
                null
            }
            val output = if (streamKeepalive != null) socket.getOutputStream() else null
            if (streamKeepalive != null && output != null) {
                sendStreamKeepalive(
                    config,
                    streamKeepalive,
                    output,
                    SystemClock.elapsedRealtimeNanos()
                )
                sourceStatus = "keepalive_waiting"
                emitSourceStatus(
                    config,
                    sourceStatus,
                    "Connected; idle-based 00/01 keepalive active on the same socket"
                )
            } else {
                sourceStatus = "open_waiting"
                emitSourceStatus(config, "open_waiting", "Connected read-only; waiting for source bytes")
            }
            TelemetryStatusBus.update(
                TelemetryStatus(
                    connecting = false,
                    captureActive = true,
                    sourcePort = sourcePort,
                    sourceStatus = sourceStatus,
                    lastError = ""
                )
            )

            val input = socket.getInputStream()
            while (currentCoroutineContext().isActive && !socket.isClosed) {
                val beforeReadNs = SystemClock.elapsedRealtimeNanos()
                val n = try {
                    input.read(buffer)
                } catch (_: java.net.SocketTimeoutException) {
                    if (
                        streamKeepalive != null &&
                        keepaliveIdleGate?.onReadTimeout() == true &&
                        output != null
                    ) {
                        sendStreamKeepalive(config, streamKeepalive, output, beforeReadNs)
                    }
                    updateSourceSilence(config, beforeReadNs)
                    emitTickIfDue(config, beforeReadNs)
                    continue
                }
                if (n <= 0) break
                keepaliveIdleGate?.onBytesReceived()
                val capturedElapsedNs = SystemClock.elapsedRealtimeNanos()
                lastByteElapsedNs = capturedElapsedNs
                if (sourceStatus != "active") {
                    sourceStatus = "active"
                    emitSourceStatus(config, "active", "Receiving source bytes")
                }
                val bytes = buffer.copyOf(n)
                val seq = ++rawSeq
                rawChunks += 1
                byteCount += bytes.size

                if (config.rawRelayEnabled) {
                    relay?.enqueue(
                        RawChunkEvent(
                            sessionId = sessionId,
                            seq = seq,
                            elapsedRealtimeNs = capturedElapsedNs,
                            source = config.sourceMode.wireName,
                            direction = "controller_to_client",
                            port = sourcePort,
                            bytes = bytes
                        )
                    ) ?: break
                }

                if (publishParser != null) {
                    for (result in publishParser.feed(bytes)) {
                        when (result) {
                            is Rc2PublishStreamParser.Result.Parsed -> {
                                recordCount += 1
                                lastSourceClockMs = result.record.sourceClockMs
                                when (result.record.marker) {
                                    0xF5 -> f5RecordCount += 1
                                    0xF6 -> f6RecordCount += 1
                                    0xF8 -> f8RecordCount += 1
                                }
                            }
                            is Rc2PublishStreamParser.Result.Error -> {
                                parserErrors += 1
                                relay?.enqueue(
                                    TelemetryErrorEvent(
                                        sessionId = sessionId,
                                        reason = result.error.reason,
                                        detail = result.error.detail
                                    )
                                )
                            }
                        }
                    }
                } else if (dumlParser != null) {
                    for (result in dumlParser.feed(bytes)) {
                        when (result) {
                            is DumlFrameParser.Result.Frame -> {
                                frameCount += 1
                                relay?.enqueue(
                                    DumlFrameEvent(
                                        sessionId = sessionId,
                                        rawSeq = seq,
                                        elapsedRealtimeNs = capturedElapsedNs,
                                        frame = result.frame,
                                        source = config.sourceMode.wireName,
                                        direction = "controller_to_client",
                                        port = sourcePort
                                    )
                                )
                            }
                            is DumlFrameParser.Result.Error -> {
                                parserErrors += 1
                                relay?.enqueue(
                                    TelemetryErrorEvent(
                                        sessionId = sessionId,
                                        reason = result.error.reason,
                                        detail = result.error.detail
                                    )
                                )
                            }
                        }
                    }
                }

                emitTickIfDue(config, capturedElapsedNs)
                TelemetryStatusBus.update(
                    TelemetryStatus(
                        running = true,
                        captureActive = true,
                        sourceStatus = sourceStatus,
                        rawChunks = rawChunks,
                        frames = frameCount,
                        keepalives = streamKeepaliveCount,
                        records = recordCount,
                        parserErrors = parserErrors,
                        bytes = byteCount
                    )
                )
            }
            if (publishParser != null) {
                for (result in publishParser.finish()) {
                    if (result is Rc2PublishStreamParser.Result.Error) {
                        parserErrors += 1
                        relay?.enqueue(
                            TelemetryErrorEvent(
                                sessionId = sessionId,
                                reason = result.error.reason,
                                detail = result.error.detail
                            )
                        )
                    }
                }
            } else if (dumlParser != null) {
                for (result in dumlParser.finish()) {
                    if (result is DumlFrameParser.Result.Error) {
                        parserErrors += 1
                        relay?.enqueue(
                            TelemetryErrorEvent(
                                sessionId = sessionId,
                                reason = result.error.reason,
                                detail = result.error.detail
                            )
                        )
                    }
                }
            }
        } catch (e: IOException) {
            gapReason = "Bench socket failed: ${e.message.orEmpty()}"
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                failClosed("Telemetry capture failed: ${e.message.orEmpty()}")
            }
            return
        } finally {
            try { socket.close() } catch (_: Exception) {}
            if (captureSocket === socket) captureSocket = null
            lease.close()
        }

        if (!currentCoroutineContext().isActive) return
        reportCaptureGap(config, gapReason)
    }

    private fun updateSourceSilence(config: TelemetryConfig, nowNs: Long) {
        val ageMs = lastByteAgeMs(nowNs)
        val nextStatus = when {
            lastByteElapsedNs == 0L -> "open_silent"
            ageMs != null && ageMs >= SOURCE_GAP_AFTER_MS -> "gap"
            else -> sourceStatus
        }
        if (nextStatus == sourceStatus) return
        sourceStatus = nextStatus
        val detail = if (nextStatus == "open_silent") {
            "Socket is open but the source has not published bytes"
        } else {
            "No source bytes for ${ageMs ?: 0} ms"
        }
        emitSourceStatus(config, nextStatus, detail)
        if (nextStatus == "gap") {
            relay?.enqueue(
                TelemetryUnavailableEvent(
                    sessionId = sessionId,
                    sourceId = config.sourceId,
                    reason = detail
                )
            )
        }
        TelemetryStatusBus.update(
            TelemetryStatus(sourceStatus = nextStatus, lastError = "")
        )
    }

    private fun sendStreamKeepalive(
        config: TelemetryConfig,
        keepalive: SameSocketTelemetryKeepalive,
        output: java.io.OutputStream,
        elapsedNs: Long
    ) {
        val bytes = keepalive.next()
        output.write(bytes)
        output.flush()
        streamKeepaliveCount += 1
        if (
            lastKeepaliveStatsElapsedNs == 0L ||
            elapsedNs - lastKeepaliveStatsElapsedNs >= KEEPALIVE_STATS_INTERVAL_NS
        ) {
            lastKeepaliveStatsElapsedNs = elapsedNs
            TelemetryStatusBus.update(TelemetryStatus(keepalives = streamKeepaliveCount))
            relay?.enqueue(
                StreamKeepaliveStatsEvent(
                    sessionId = sessionId,
                    elapsedRealtimeNs = elapsedNs,
                    source = config.sourceMode.wireName,
                    port = config.capturePort,
                    sentCount = streamKeepaliveCount,
                    readTimeoutMs = STREAM_KEEPALIVE_READ_TIMEOUT_MS,
                    idleTimeoutsBeforeSend = STREAM_KEEPALIVE_IDLE_TIMEOUTS
                )
            )
        }
        if (streamKeepaliveCount == 1L) {
            val seq = ++rawSeq
            relay?.enqueue(
                RawChunkEvent(
                    sessionId = sessionId,
                    seq = seq,
                    elapsedRealtimeNs = elapsedNs,
                    source = config.sourceMode.wireName,
                    direction = "client_to_controller",
                    port = config.capturePort,
                    bytes = bytes
                )
            )
        }
    }

    private fun emitTickIfDue(config: TelemetryConfig, nowNs: Long) {
        val intervalNs = config.sampleIntervalMs * 1_000_000L
        if (lastTickElapsedNs != 0L && nowNs - lastTickElapsedNs < intervalNs) return
        lastTickElapsedNs = nowNs
        val ageMs = lastByteAgeMs(nowNs)
        relay?.enqueue(
            TelemetryTickEvent(
                sessionId = sessionId,
                source = config.sourceMode.wireName,
                port = config.capturePort,
                elapsedRealtimeNs = nowNs,
                sourceStatus = sourceStatus,
                lastByteAgeMs = ageMs,
                sourceClockMs = lastSourceClockMs
            )
        )
        if (config.sourceMode == TelemetrySourceMode.Rc2PublishStream) {
            relay?.enqueue(
                Rc2RecordStatsEvent(
                    sessionId = sessionId,
                    elapsedRealtimeNs = nowNs,
                    source = config.sourceMode.wireName,
                    port = config.capturePort,
                    totalRecords = recordCount,
                    f5Records = f5RecordCount,
                    f6Records = f6RecordCount,
                    f8Records = f8RecordCount,
                    sourceClockMs = lastSourceClockMs
                )
            )
        }
    }

    private fun emitSourceStatus(config: TelemetryConfig, status: String, detail: String) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        relay?.enqueue(
            TelemetrySourceStatusEvent(
                sessionId = sessionId,
                source = config.sourceMode.wireName,
                port = config.capturePort,
                status = status,
                detail = detail,
                elapsedRealtimeNs = nowNs,
                lastByteAgeMs = lastByteAgeMs(nowNs)
            )
        )
        updateNotification(
            when (status) {
                "active" -> "Metadata active on ${config.capturePort}"
                "open_silent" -> "Port ${config.capturePort} open; waiting for data"
                "gap" -> "Metadata gap on ${config.capturePort}"
                "unavailable" -> "Metadata unavailable"
                else -> detail
            }
        )
    }

    private fun lastByteAgeMs(nowNs: Long): Long? =
        if (lastByteElapsedNs == 0L) null else ((nowNs - lastByteElapsedNs) / 1_000_000L)

    private fun reportCaptureGap(config: TelemetryConfig, reason: String) {
        sourceStatus = "unavailable"
        emitSourceStatus(config, sourceStatus, reason)
        relay?.enqueue(
            TelemetryErrorEvent(
                sessionId = sessionId,
                reason = "capture_gap",
                detail = "$reason; explicit restart required"
            )
        )
        relay?.enqueue(
            TelemetryUnavailableEvent(
                sessionId = sessionId,
                sourceId = config.sourceId,
                reason = reason
            )
        )
        TelemetryStatusBus.update(
            TelemetryStatus(
                running = true,
                connecting = false,
                captureActive = false,
                sourceStatus = sourceStatus,
                lastError = "$reason; metadata unavailable, restart relay explicitly"
            )
        )
    }

    private fun failClosed(reason: String) {
        TelemetryStatusBus.update(
            TelemetryStatus(
                running = false,
                relayConnected = false,
                connecting = false,
                captureActive = false,
                sourceStatus = "unavailable",
                lastError = reason
            )
        )
        stopCapture()
    }

    @Synchronized
    private fun stopCapture(closeService: Boolean = true) {
        commandCancellation.cancel()
        commandJob?.cancel()
        commandJob = null
        captureJob?.cancel()
        captureJob = null
        try { captureSocket?.close() } catch (_: Exception) {}
        captureSocket = null
        relay?.stop()
        relay = null
        activeConfig = null
        TelemetryStatusBus.update(
            TelemetryStatus(
                running = false,
                connecting = false,
                relayConnected = false,
                captureActive = false,
                sourceStatus = "stopped",
                queueDepth = 0
            )
        )
        if (closeService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telemetry Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Streams raw DJI RC2 telemetry research data to a local Mac"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return builder
            .setContentTitle("Telemetry Research")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        stopCapture(closeService = false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "telemetry_relay"
        private const val NOTIFICATION_ID = 9020
        private const val DUML_HOST = "127.0.0.1"
        private const val DUML_CONNECT_TIMEOUT_MS = 2_000
        private const val SOURCE_READ_TIMEOUT_MS = 250
        private const val SOURCE_GAP_AFTER_MS = 2_000L
        private const val STREAM_KEEPALIVE_READ_TIMEOUT_MS = 20
        private const val STREAM_KEEPALIVE_IDLE_TIMEOUTS = 2
        private const val KEEPALIVE_STATS_INTERVAL_NS = 1_000_000_000L
        private const val MIN_COMMAND_INTERVAL_MS = 500L
        private const val DEFAULT_RELAY_PORT = 8765

        private const val ACTION_START = "com.freefcc.app.telemetry.START"
        private const val ACTION_STOP = "com.freefcc.app.telemetry.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_CAPTURE_PORT = "capture_port"
        private const val EXTRA_STREAM_KEEPALIVE_ENABLED = "stream_keepalive_enabled"
        private const val EXTRA_CONTROLLER_FIRMWARE = "controller_firmware"
        private const val EXTRA_DJI_FLY_VERSION = "dji_fly_version"
        private const val EXTRA_AIRCRAFT_MODEL = "aircraft_model"
        private const val EXTRA_AIRCRAFT_FIRMWARE = "aircraft_firmware"

        fun start(context: Context, config: TelemetryConfig) {
            val intent = Intent(context, TelemetryCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, config.host)
                putExtra(EXTRA_PORT, config.port)
                putExtra(EXTRA_SOURCE_ID, config.sourceId)
                putExtra(EXTRA_CAPTURE_PORT, config.capturePort)
                putExtra(EXTRA_STREAM_KEEPALIVE_ENABLED, config.streamKeepaliveEnabled)
                putExtra(EXTRA_CONTROLLER_FIRMWARE, config.controllerFirmware)
                putExtra(EXTRA_DJI_FLY_VERSION, config.djiFlyVersion)
                putExtra(EXTRA_AIRCRAFT_MODEL, config.aircraftModel)
                putExtra(EXTRA_AIRCRAFT_FIRMWARE, config.aircraftFirmware)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TelemetryCaptureService::class.java).apply { action = ACTION_STOP })
        }
    }
}
