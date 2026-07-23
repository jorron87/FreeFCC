package com.freefcc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
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
    private var captureSocket: Socket? = null
    private var activeConfig: TelemetryConfig? = null
    private val probeTransport = DumlTransport()
    private val commandInFlight = AtomicBoolean(false)
    private var lastCommandStartedElapsedMs = 0L
    private var sessionId: String = ""
    private var rawSeq = 0L
    private var rawChunks = 0L
    private var frameCount = 0L
    private var parserErrors = 0L
    private var byteCount = 0L

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

    private fun startCapture(intent: Intent) {
        stopCapture(closeService = false)

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_RELAY_PORT)
        val capturePort = intent.getIntExtra(EXTRA_CAPTURE_PORT, DumlTransport.PORT_LED)
        if (
            host.isBlank() ||
            port !in 1..65535 ||
            capturePort !in TelemetryRelayClient.RESEARCH_DUML_PORTS
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
                captureActive = false,
                rawChunks = 0,
                frames = 0,
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

        captureJob = scope.launch {
            runBenchSocketCapture(config)
        }
    }

    private fun handleRelayCommand(command: TelemetryRelayCommand) {
        if (!commandInFlight.compareAndSet(false, true)) {
            sendRelayFailure(command, "busy", "Another DUML command is already running")
            return
        }

        scope.launch {
            var hardwareLockHeld = false
            var portLease: DumlPortSessionLock.Lease? = null
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCommandStartedElapsedMs < MIN_COMMAND_INTERVAL_MS) {
                    sendRelayFailure(command, "rate_limited", "Wait before requesting another DUML command")
                    return@launch
                }
                lastCommandStartedElapsedMs = now

                if (!HardwareLock.tryBegin()) {
                    sendRelayFailure(command, "busy", "Controller hardware is busy")
                    return@launch
                }
                hardwareLockHeld = true

                if (activeConfig == null) {
                    sendRelayFailure(command, "error", "Telemetry session configuration is unavailable")
                    return@launch
                }

                val commandPort = when (command) {
                    is TelemetryRelayCommand.Probe -> DumlTransport.PORT
                    is TelemetryRelayCommand.Duml -> command.port
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
                }
            } catch (e: Exception) {
                sendRelayFailure(command, "error", e.message.orEmpty().ifBlank { "DUML request failed" })
            } finally {
                portLease?.close()
                if (hardwareLockHeld) HardwareLock.end()
                commandInFlight.set(false)
            }
        }
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
        }
    }

    private suspend fun runBenchSocketCapture(config: TelemetryConfig) {
        val buffer = ByteArray(4096)
        val parser = WrappedDumlFrameParser()
        val sourcePort = config.capturePort
        val lease = DumlPortSessionLock.tryBegin(sourcePort)
        if (lease == null) {
            reportCaptureGap(config, "DUML port $sourcePort is already in use")
            return
        }
        val socket = Socket()
        captureSocket = socket
        var gapReason = "Bench socket closed"
        try {
            socket.connect(InetSocketAddress(DUML_HOST, sourcePort), DUML_CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = 500
            TelemetryStatusBus.update(
                TelemetryStatus(
                    connecting = false,
                    captureActive = true,
                    sourcePort = sourcePort,
                    lastError = ""
                )
            )

            val input = socket.getInputStream()
            while (currentCoroutineContext().isActive && !socket.isClosed) {
                val n = try {
                    input.read(buffer)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                if (n <= 0) break
                val bytes = buffer.copyOf(n)
                val seq = ++rawSeq
                rawChunks += 1
                byteCount += bytes.size

                relay?.enqueue(
                    RawChunkEvent(
                        sessionId = sessionId,
                        seq = seq,
                        elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                        source = config.sourceMode.wireName,
                        direction = "controller_to_client",
                        port = sourcePort,
                        bytes = bytes
                    )
                ) ?: break

                for (result in parser.feed(bytes)) {
                    when (result) {
                        is DumlFrameParser.Result.Frame -> {
                            frameCount += 1
                            relay?.enqueue(
                                DumlFrameEvent(
                                    sessionId = sessionId,
                                    rawSeq = seq,
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

                TelemetryStatusBus.update(
                    TelemetryStatus(
                        running = true,
                        captureActive = true,
                        rawChunks = rawChunks,
                        frames = frameCount,
                        parserErrors = parserErrors,
                        bytes = byteCount
                    )
                )
            }
            for (result in parser.finish()) {
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

    private fun reportCaptureGap(config: TelemetryConfig, reason: String) {
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
                lastError = reason
            )
        )
        stopCapture()
    }

    private fun stopCapture(closeService: Boolean = true) {
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
        return builder
            .setContentTitle("Telemetry Research")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
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
        private const val MIN_COMMAND_INTERVAL_MS = 500L
        private const val DEFAULT_RELAY_PORT = 8765

        private const val ACTION_START = "com.freefcc.app.telemetry.START"
        private const val ACTION_STOP = "com.freefcc.app.telemetry.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_CAPTURE_PORT = "capture_port"
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
