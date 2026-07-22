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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class TelemetryCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relay: TelemetryRelayClient? = null
    private var captureJob: Job? = null
    private var captureSocket: Socket? = null
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
        if (host.isBlank() || port !in 1..65535) {
            TelemetryStatusBus.update(TelemetryStatus(running = false, lastError = "Invalid Mac host or port"))
            stopSelf()
            return
        }

        val config = TelemetryConfig(
            host = host,
            port = port,
            sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty().ifBlank { "rc2-bench" },
            controllerFirmware = intent.getStringExtra(EXTRA_CONTROLLER_FIRMWARE).orEmpty(),
            djiFlyVersion = intent.getStringExtra(EXTRA_DJI_FLY_VERSION).orEmpty(),
            aircraftModel = intent.getStringExtra(EXTRA_AIRCRAFT_MODEL).orEmpty(),
            aircraftFirmware = intent.getStringExtra(EXTRA_AIRCRAFT_FIRMWARE).orEmpty()
        )
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
            scope = scope,
            onStatus = { TelemetryStatusBus.update(it) },
            onFatal = { reason -> failClosed(reason) }
        ).also { it.start() }

        captureJob = scope.launch {
            runBenchSocketCapture(config)
        }
    }

    private suspend fun runBenchSocketCapture(config: TelemetryConfig) {
        val buffer = ByteArray(4096)
        var reconnectDelayMs = BENCH_RECONNECT_INITIAL_MS

        while (currentCoroutineContext().isActive) {
            val parser = DumlFrameParser()
            val socket = Socket()
            captureSocket = socket
            var receivedData = false
            var gapReason = "Bench socket closed"
            try {
                socket.connect(InetSocketAddress(DUML_HOST, DUML_PORT), DUML_CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.soTimeout = 500
                TelemetryStatusBus.update(TelemetryStatus(connecting = false, lastError = ""))

                val input = socket.getInputStream()
                while (currentCoroutineContext().isActive && !socket.isClosed) {
                    val n = try {
                        input.read(buffer)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    if (n <= 0) break
                    receivedData = true
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
                            port = DUML_PORT,
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
                                        port = DUML_PORT
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
                            rawChunks = rawChunks,
                            frames = frameCount,
                            parserErrors = parserErrors,
                            bytes = byteCount
                        )
                    )
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
            }

            if (!currentCoroutineContext().isActive) return

            relay?.enqueue(
                TelemetryErrorEvent(
                    sessionId = sessionId,
                    reason = "capture_gap",
                    detail = "$gapReason; reconnecting"
                )
            )
            relay?.enqueue(
                TelemetryUnavailableEvent(
                    sessionId = sessionId,
                    sourceId = config.sourceId,
                    reason = gapReason
                )
            )
            TelemetryStatusBus.update(
                TelemetryStatus(
                    running = true,
                    connecting = true,
                    lastError = "$gapReason; metadata unavailable while reconnecting"
                )
            )

            if (receivedData) reconnectDelayMs = BENCH_RECONNECT_INITIAL_MS
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(BENCH_RECONNECT_MAX_MS)
        }
    }

    private fun failClosed(reason: String) {
        TelemetryStatusBus.update(TelemetryStatus(running = false, relayConnected = false, connecting = false, lastError = reason))
        stopCapture()
    }

    private fun stopCapture(closeService: Boolean = true) {
        captureJob?.cancel()
        captureJob = null
        try { captureSocket?.close() } catch (_: Exception) {}
        captureSocket = null
        relay?.stop()
        relay = null
        TelemetryStatusBus.update(TelemetryStatus(running = false, connecting = false, relayConnected = false, queueDepth = 0))
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
        private const val DUML_PORT = 40009
        private const val DUML_CONNECT_TIMEOUT_MS = 2_000
        private const val BENCH_RECONNECT_INITIAL_MS = 1_000L
        private const val BENCH_RECONNECT_MAX_MS = 5_000L
        private const val DEFAULT_RELAY_PORT = 8765

        private const val ACTION_START = "com.freefcc.app.telemetry.START"
        private const val ACTION_STOP = "com.freefcc.app.telemetry.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_SOURCE_ID = "source_id"
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
