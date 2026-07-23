package com.freefcc.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Immutable UI state for the entire app.
 *
 * The ViewModel updates this via copy() and the Compose layer observes it
 * with collectAsStateWithLifecycle(). Every field here represents something
 * the UI needs to render.
 */
data class AppState(
    val status: String = "idle",
    val message: String = "",
    val isConnected: Boolean = false,
    val isFccEnabled: Boolean = false,
    val is4gBusy: Boolean = false,
    val fourGMessage: String = "",
    val isBusy: Boolean = false,
    val isHardwareBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val aircraftSerial: String = "",
    val controllerModel: String = "",
    val deviceInfo: String = "",
    val isQueryingInfo: Boolean = false,
    val autoFcc: Boolean = false,
    val isLedBusy: Boolean = false,
    val ledStatus: String = "",
    val logMessages: List<String> = emptyList(),
    // Update state
    val updateInfo: UpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Float = 0f,
    val isUpdateDownloaded: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateChecked: Boolean = false,
    val updateEndpoint: String = UpdateChecker.DEFAULT_API_URL,
    // Keepalive state
    val isKeepaliveRunning: Boolean = false,
    // Telemetry research state
    val telemetryHost: String = "",
    val telemetryPort: String = "8765",
    val telemetryCapturePort: String = "8902",
    val telemetrySourceId: String = "rc2-bench",
    val telemetryControllerFirmware: String = "",
    val telemetryDjiFlyVersion: String = "",
    val telemetryAircraftModel: String = "",
    val telemetryAircraftFirmware: String = "",
    val telemetryRuntime: TelemetryRuntimeState = TelemetryRuntimeState(),
    val telemetryProbeBusy: Boolean = false,
    val telemetryProbeResult: TelemetryProbeResult? = null,
    val telemetryProbeMessage: String = ""
)

/**
 * Manages all app state and business logic.
 *
 * The UI never touches the transport layer directly. It calls methods on
 * this ViewModel, which runs operations on a background thread (Dispatchers.IO)
 * and updates the observable [state] flow. The UI reacts to state changes
 * automatically via Compose's collectAsStateWithLifecycle().
 *
 * @param app The Application context, used for SharedPreferences and asset loading
 */
class FccViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        const val APP_VERSION = "1.5.3-research.8"

        /**
         * Aircraft model codes known to support DJI Cellular Dongle 2 / 4G.
         * The Mini series (wa150, wa140, wm16x) does NOT support 4G — the
         * cellular module is enterprise hardware only. Sending 4G frames to a
         * non-4G aircraft wastes the user's time and produces a confusing
         * "frames written but 4G didn't activate" message.
         *
         * Sources: DJI product list, captured profiles (only wa341 confirmed
         * working on real hardware). wa233/wa234 = Matrice 300/350 series,
         * wm630 = Inspire 3, wa341 = Mavic 4 Pro. All are DJI enterprise models
         * that ship with or accept the Cellular Dongle 2.
         */
        private val MODELS_WITH_4G = setOf("wa341", "wa233", "wa234", "wm630", "wa140")
    }

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val transport = DumlTransport()
    private val prefs = app.getSharedPreferences("freefcc", Context.MODE_PRIVATE)

    init {
        // MainActivity.onCreate() calls init() below on every Activity re-creation
        // (e.g. config change), but this class init{} runs exactly once per
        // ViewModel instance — the collector must live here, not in init().
        viewModelScope.launch {
            HardwareLock.busy.collect { busy -> update { copy(isHardwareBusy = busy) } }
        }
        viewModelScope.launch {
            TelemetryStatusBus.updates.collect { runtime -> update { copy(telemetryRuntime = runtime) } }
        }
        // Restore the cached aircraft serial from a previous session so the
        // user does not have to re-probe before 4G if the drone is the same.
        val cachedSerial = prefs.getString("aircraft_serial", "").orEmpty()
        if (cachedSerial.isNotEmpty()) {
            update { copy(aircraftSerial = cachedSerial) }
        }
        val storedUpdateEndpoint = prefs.getString("update_endpoint", "").orEmpty()
        val updateEndpoint = when (storedUpdateEndpoint) {
            "", UpdateChecker.LEGACY_API_URL -> UpdateChecker.DEFAULT_API_URL
            else -> storedUpdateEndpoint
        }
        if (storedUpdateEndpoint == UpdateChecker.LEGACY_API_URL) {
            prefs.edit().putString("update_endpoint", updateEndpoint).apply()
        }
        val storedCapturePort = prefs.getString("telemetry_capture_port", "8902")
            .orEmpty()
            .ifBlank { "8902" }
        val capturePort = if (
            !prefs.getBoolean("telemetry_source_migrated_8902", false) &&
            storedCapturePort == "40007"
        ) {
            prefs.edit()
                .putString("telemetry_capture_port", "8902")
                .putBoolean("telemetry_source_migrated_8902", true)
                .apply()
            "8902"
        } else {
            storedCapturePort
        }
        update {
            copy(
                telemetryHost = prefs.getString("telemetry_host", "").orEmpty(),
                telemetryPort = prefs.getString("telemetry_port", "8765").orEmpty().ifBlank { "8765" },
                telemetryCapturePort = capturePort,
                telemetrySourceId = prefs.getString("telemetry_source_id", "rc2-bench").orEmpty().ifBlank { "rc2-bench" },
                telemetryControllerFirmware = prefs.getString("telemetry_controller_firmware", "").orEmpty(),
                telemetryDjiFlyVersion = prefs.getString("telemetry_dji_fly_version", "").orEmpty(),
                telemetryAircraftModel = prefs.getString("telemetry_aircraft_model", "").orEmpty(),
                telemetryAircraftFirmware = prefs.getString("telemetry_aircraft_firmware", "").orEmpty(),
                updateEndpoint = updateEndpoint
            )
        }
    }

    private var hardwareLease: HardwareLock.Lease? = null

    /** Claims the shared hardware lock for one operation. */
    @Synchronized
    private fun beginHardwareOp(): Boolean {
        if (hardwareLease != null) return false
        hardwareLease = HardwareLock.tryBegin() ?: return false
        return true
    }

    /** Releases only the lease owned by this ViewModel operation. */
    @Synchronized
    private fun endHardwareOp() {
        hardwareLease?.close()
        hardwareLease = null
    }

    fun init() {
        val model = try { Build.DEVICE } catch (_: Exception) { "unknown" }
        val autoEnabled = prefs.getBoolean("auto_fcc", false)
        // Sync the keepalive toggle with the persistent flag so the UI is
        // correct after a process restart (e.g. low-memory kill + sticky restart).
        val keepaliveRunning = FccKeepaliveService.isRunningFlagSet(app) &&
            FccKeepaliveService.isDjiFlyTextAccessEnabled(app)
        update { copy(controllerModel = model, status = "disconnected", autoFcc = autoEnabled, isKeepaliveRunning = keepaliveRunning) }

        if (autoEnabled) {
            FccKeepaliveService.start(app)
            val armed = FccKeepaliveService.isDjiFlyTextAccessEnabled(app)
            update { copy(isKeepaliveRunning = armed) }
            log(
                if (armed) {
                    "Auto-FCC armed; waiting for DJI Fly Home Point without DUML polling"
                } else {
                    "Auto-FCC selected; enable FreeFCC Home Point in Accessibility"
                }
            )
        }

        checkForUpdates()
    }

    // --- Telemetry research ---

    fun updateTelemetryHost(value: String) {
        prefs.edit().putString("telemetry_host", value.trim()).apply()
        update { copy(telemetryHost = value.trim()) }
    }

    fun updateTelemetryPort(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(5)
        prefs.edit().putString("telemetry_port", cleaned).apply()
        update { copy(telemetryPort = cleaned) }
    }

    fun updateTelemetryCapturePort(value: String) {
        val cleaned = value.filter { it.isDigit() }.take(5)
        prefs.edit().putString("telemetry_capture_port", cleaned).apply()
        update { copy(telemetryCapturePort = cleaned) }
    }

    fun updateTelemetrySourceId(value: String) {
        val cleaned = value.trim().ifBlank { "rc2-bench" }
        prefs.edit().putString("telemetry_source_id", cleaned).apply()
        update { copy(telemetrySourceId = cleaned) }
    }

    fun updateTelemetryControllerFirmware(value: String) {
        prefs.edit().putString("telemetry_controller_firmware", value.trim()).apply()
        update { copy(telemetryControllerFirmware = value.trim()) }
    }

    fun updateTelemetryDjiFlyVersion(value: String) {
        prefs.edit().putString("telemetry_dji_fly_version", value.trim()).apply()
        update { copy(telemetryDjiFlyVersion = value.trim()) }
    }

    fun updateTelemetryAircraftModel(value: String) {
        prefs.edit().putString("telemetry_aircraft_model", value.trim()).apply()
        update { copy(telemetryAircraftModel = value.trim()) }
    }

    fun updateTelemetryAircraftFirmware(value: String) {
        prefs.edit().putString("telemetry_aircraft_firmware", value.trim()).apply()
        update { copy(telemetryAircraftFirmware = value.trim()) }
    }

    fun startTelemetryRelay() {
        val current = _state.value
        val port = current.telemetryPort.toIntOrNull()
        val capturePort = current.telemetryCapturePort.toIntOrNull()
        if (
            current.telemetryHost.isBlank() ||
            port == null || port !in 1..65535 ||
            capturePort == null || capturePort !in TelemetryRelayClient.RESEARCH_DUML_PORTS
        ) {
            update { copy(message = "Enter a valid Mac endpoint and supported read-only source port.") }
            log("Telemetry relay not started - invalid Mac endpoint or source port")
            return
        }

        val sourceUsesFccPort = capturePort == DumlTransport.PORT
        if (current.autoFcc && sourceUsesFccPort) {
            prefs.edit().putBoolean("auto_fcc", false).apply()
            update { copy(autoFcc = false) }
            log("Auto-FCC disabled because telemetry selected port 40009")
        }
        if (current.isKeepaliveRunning && sourceUsesFccPort) {
            FccKeepaliveService.stop(app)
            update { copy(isKeepaliveRunning = false) }
            log("FCC keepalive stopped because telemetry selected port 40009")
        }

        val config = TelemetryConfig(
            host = current.telemetryHost,
            port = port,
            sourceId = current.telemetrySourceId.ifBlank { "rc2-bench" },
            capturePort = capturePort,
            controllerFirmware = current.telemetryControllerFirmware,
            djiFlyVersion = current.telemetryDjiFlyVersion,
            aircraftModel = current.telemetryAircraftModel,
            aircraftFirmware = current.telemetryAircraftFirmware
        )
        TelemetryCaptureService.start(app, config)
        log("Telemetry relay starting: ${config.sourceMode.wireName} -> ${config.host}:${config.port}")
    }

    fun stopTelemetryRelay() {
        TelemetryCaptureService.stop(app)
        TelemetryStatusBus.reset()
        log("Telemetry relay stopped")
    }

    /**
     * Sends exactly one read-only 03/43 request on a separate port lease.
     * This intentionally has no retry loop; continuous polling remains disabled.
     */
    fun probeTelemetryFcOsd() {
        if (
            _state.value.telemetryRuntime.running &&
            _state.value.telemetryRuntime.sourcePort == DumlTransport.PORT
        ) {
            update {
                copy(
                    telemetryProbeResult = null,
                    telemetryProbeMessage = "Capture already holds port 40009. Use passive 8902 or stop the relay first."
                )
            }
            log("Bench probe rejected - active capture holds DUML port 40009")
            return
        }
        if (!beginHardwareOp()) {
            log("Telemetry probe skipped - another hardware operation is running")
            return
        }

        update {
            copy(
                telemetryProbeBusy = true,
                telemetryProbeResult = null,
                telemetryProbeMessage = "Sending one 03/43 request on port 40009..."
            )
        }
        log("Bench probe: preparing one read-only 03/43 request on pinned port 40009")

        runOnIO {
            var portLease: DumlPortSessionLock.Lease? = null
            try {
                portLease = DumlPortSessionLock.tryBegin(DumlTransport.PORT)
                if (portLease == null) {
                    update {
                        copy(
                            telemetryProbeResult = null,
                            telemetryProbeMessage = "Port 40009 is busy; no request was sent."
                        )
                    }
                    log("Bench probe skipped - DUML port 40009 is busy")
                    return@runOnIO
                }

                val profile = Profiles.load(app, "telemetry_fc_osd_probe.json")
                val frame = profile.frames.singleOrNull()
                    ?: error("Probe profile must contain exactly one frame")
                val response = transport.sendAndReceiveDetailed(
                    frame,
                    profile.readWindowMs,
                    profile.port,
                    pinPort = true
                ).payload

                if (response == null) {
                    update {
                        copy(
                            telemetryProbeResult = null,
                            telemetryProbeMessage = "No matching 03/43 response. No retry was sent."
                        )
                    }
                    log("Bench probe: no matching 03/43 response; no retry sent")
                } else {
                    val decoded = TelemetryProbeDecoder.decode(response)
                    if (decoded == null) {
                        update {
                            copy(
                                telemetryProbeResult = null,
                                telemetryProbeMessage = "03/43 response was ${response.size} bytes; candidate layout needs at least 30."
                            )
                        }
                        log("Bench probe: 03/43 response too short (${response.size} bytes)")
                    } else {
                        update {
                            copy(
                                telemetryProbeResult = decoded,
                                telemetryProbeMessage = "Candidate data received. GPS remains unverified."
                            )
                        }
                        log("Bench probe: candidate 03/43 payload received (${response.size} bytes)")
                    }
                }
            } catch (e: Exception) {
                update {
                    copy(
                        telemetryProbeResult = null,
                        telemetryProbeMessage = "Probe error: ${e.message.orEmpty()}"
                    )
                }
                log("Bench probe error: ${e.message.orEmpty()}")
            } finally {
                portLease?.close()
                endHardwareOp()
                update { copy(telemetryProbeBusy = false) }
            }
        }
    }

    // --- Auto-FCC ---

    /**
     * Toggles auto-FCC on or off. When enabled, the app will automatically
     * connect to the controller and apply FCC mode every time it launches.
     * The setting is saved to SharedPreferences and persists across restarts.
     */
    fun toggleAutoFcc() {
        val newValue = !_state.value.autoFcc
        prefs.edit().putBoolean("auto_fcc", newValue).apply()
        if (newValue) {
            FccKeepaliveService.start(app)
            val accessEnabled = FccKeepaliveService.isDjiFlyTextAccessEnabled(app)
            update { copy(autoFcc = true, isKeepaliveRunning = accessEnabled) }
            if (accessEnabled) {
                log("Auto-FCC armed; waiting for DJI Fly Home Point")
            } else {
                log("Auto-FCC needs Accessibility; opening settings")
                app.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            }
        } else {
            FccKeepaliveService.stop(app)
            update { copy(autoFcc = false, isKeepaliveRunning = false) }
            log("Auto-FCC disabled")
        }
    }

    // --- Connection ---

    /**
     * Connects to the DUML proxy, auto-detecting the correct port.
     * Probes for the aircraft serial number after connecting.
     */
    fun connect() {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        update { copy(status = "connecting", message = "Connecting to controller...") }
        log("Connecting to controller...")

        runOnIO {
            try {
                if (transport.connect()) {
                    log("Controller connected")
                    val detectedPort = transport.getDetectedPort()
                    if (detectedPort > 0) {
                        log("DUML port detected: $detectedPort")
                    }
                    val serial = transport.probeSerial(1500)
                    if (serial.isNotEmpty()) {
                        prefs.edit().putString("aircraft_serial", serial).apply()
                    }
                    update {
                        copy(
                            status = "connected",
                            message = if (serial.isNotEmpty()) "Connected — $serial" else "Connected. Ready to apply FCC.",
                            isConnected = true,
                            aircraftSerial = serial
                        )
                    }
                    if (serial.isNotEmpty()) log("Aircraft serial: $serial")
                } else {
                    update {
                        copy(
                            status = "disconnected",
                            message = "Controller not found. Make sure the drone is powered on and linked.",
                            isConnected = false
                        )
                    }
                    log("Connection failed — is the drone powered on?")
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- FCC ---

    /**
     * Sends the 21-frame FCC unlock profile (2 rounds, 150ms between frames).
     * The profile already runs 2 rounds internally for reliability.
     */
    fun enableFcc() {
        if (
            _state.value.telemetryRuntime.captureActive &&
            _state.value.telemetryRuntime.sourcePort == DumlTransport.PORT
        ) {
            log("FCC apply blocked because telemetry holds port 40009")
            return
        }
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Enabling FCC mode...") }
        log("Enabling FCC mode...")

        runOnIO {
            var portLease: DumlPortSessionLock.Lease? = null
            try {
                val profile = Profiles.load(app, "fcc.json")
                portLease = DumlPortSessionLock.tryBegin(profile.port)
                if (portLease == null) {
                    update { copy(status = "connected", message = "Port ${profile.port} is busy", isBusy = false) }
                    return@runOnIO
                }
                log("Loaded FCC profile: ${profile.frames.size} frames, ${profile.rounds} rounds")

                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    interFrameDelayMs = profile.interFrameDelay,
                    interRoundDelayMs = profile.interRoundDelay,
                    readWindowMs = profile.readWindowMs,
                    port = profile.port
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            status = "fcc_enabled",
                            message = "FCC mode enabled",
                            isFccEnabled = true,
                            isBusy = false,
                            busyProgress = 1f,
                            isConnected = true
                        )
                    }
                    log("FCC mode enabled — ${profile.frames.size} frames sent")
                } else {
                    update {
                        copy(
                            status = "connected",
                            message = "FCC apply failed — RC link unreachable. Make sure the drone is on and linked.",
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log("FCC apply failed — writes failed")
                }
            } catch (e: Exception) {
                log("FCC apply error: ${e.message}")
                update { copy(status = "connected", message = "FCC apply error: ${e.message}", isBusy = false, busyProgress = 0f) }
            } finally {
                portLease?.close()
                endHardwareOp()
            }
        }
    }

    /** Sends the CE restore command: a single frame that resets to factory region. */
    fun disableFcc() {
        if (
            _state.value.telemetryRuntime.captureActive &&
            _state.value.telemetryRuntime.sourcePort == DumlTransport.PORT
        ) {
            log("CE restore blocked because telemetry holds port 40009")
            return
        }
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        // Disarm Auto FCC first so a later Home Point event cannot undo CE.
        if (_state.value.isKeepaliveRunning) {
            stopKeepalive()
        }
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = "Restoring CE mode...") }
        log("Restoring CE mode...")

        runOnIO {
            var portLease: DumlPortSessionLock.Lease? = null
            try {
                val profile = Profiles.load(app, "ce_restore.json")
                portLease = DumlPortSessionLock.tryBegin(profile.port)
                if (portLease == null) {
                    update { copy(status = "connected", message = "Port ${profile.port} is busy", isBusy = false) }
                    return@runOnIO
                }
                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    readWindowMs = profile.readWindowMs
                )

                if (success) {
                    update { copy(status = "connected", message = "CE mode restored", isFccEnabled = false, isBusy = false) }
                    log("CE mode restored")
                } else {
                    update { copy(status = "connected", message = "CE restore failed — RC link unreachable", isBusy = false) }
                    log("CE restore failed")
                }
            } catch (e: Exception) {
                log("CE restore error: ${e.message}")
                update { copy(status = "connected", message = "CE restore error: ${e.message}", isBusy = false) }
            } finally {
                portLease?.close()
                endHardwareOp()
            }
        }
    }

    // --- FCC Keepalive ---

    /** Arms event-driven FCC apply after DJI Fly records a Home Point. */
    fun startKeepalive() {
        if (_state.value.isKeepaliveRunning) {
            log("Keepalive already running")
            return
        }
        FccKeepaliveService.start(app)
        val accessEnabled = FccKeepaliveService.isDjiFlyTextAccessEnabled(app)
        update { copy(isKeepaliveRunning = accessEnabled) }
        if (accessEnabled) {
            log("Auto-FCC armed; no DUML socket is opened while waiting")
        } else {
            log("Enable FreeFCC Home Point in Accessibility to arm Auto-FCC")
            app.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
    }

    /** Stops the keepalive foreground service. */
    fun stopKeepalive() {
        FccKeepaliveService.stop(app)
        update { copy(isKeepaliveRunning = false) }
        log("FCC keepalive stopped")
    }

    // --- Launch DJI Fly ---

    /**
     * Launches DJI Fly. When Auto FCC is armed, its foreground service waits
     * for a Home Point event and performs at most one complete FCC apply.
     */
    fun launchDjiFly() {
        val pm = app.packageManager
        // Try the standard launch intent first
        var intent = pm.getLaunchIntentForPackage("dji.go.v5")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log("Launched DJI Fly")
                return
            } catch (_: Exception) {}
        }

        // Fallback: try explicit component — DJI Fly's main activity
        for (activityName in listOf(
            "dji.pilot2.lite.LauncherActivity",
            "dji.go.v5.MainActivity",
            "dji.pilot2.lite.LiteLauncherActivity",
            "dji.go.v5.SplashActivity"
        )) {
            val explicitIntent = android.content.Intent().apply {
                component = android.content.ComponentName("dji.go.v5", activityName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                app.startActivity(explicitIntent)
                log("Launched DJI Fly")
                return
            } catch (_: Exception) {}
        }

        // Fallback 2: try dji.go.v4
        intent = pm.getLaunchIntentForPackage("dji.go.v4")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log("Launched DJI Go 4")
                return
            } catch (_: Exception) {}
        }

        log("DJI Fly not installed or cannot launch on this controller")
    }

    // --- 4G ---

    /**
     * Sends the 128-frame 4G activation profile.
     * The aircraft serial is embedded in each frame's payload at runtime.
     * 4G frames are sent via Unix domain socket (/duss/mb/0x205), not TCP.
     *
     * The socket does not respond, so this can only confirm the frames were
     * written — never confirm the aircraft actually activated 4G. There is
     * no "off" action: no send-only command exists to reliably deactivate it.
     *
     * Guards (added to stop the most common failure modes early):
     * 1. Aircraft serial must be non-empty AND at least 6 chars. A 6-char
     *    W[AM]xxx model code (WA341, WM630) is the minimum the 4G payload
     *    format needs — a shorter string would produce a malformed payload.
     * 2. Model code must be in the 4G-capable set. The Mini series rejects
     *    4G at the firmware level; we tell the user up front rather than
     *    fail after writing 128 frames.
     * 3. The 4G dongle must be present (the abstract socket must be
     *    connectable). If `/duss/mb/0x205` does not exist, the cellular
     *    module is not attached and no frame can succeed.
     */
    fun send4gActivationFrames() {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        update { copy(is4gBusy = true, busyProgress = 0f, fourGMessage = "") }
        log("Sending 4G activation frames...")

        runOnIO {
            try {
                val serial = getOrProbeSerial()

                // Guard 1: serial present and long enough for the 4G payload format.
                if (serial.length < 6) {
                    update {
                        copy(is4gBusy = false, fourGMessage = "4G needs the aircraft connected. Power on the drone, link it, and tap Connect first.")
                    }
                    log("4G activation failed — aircraft serial too short ('$serial'); need at least a W[AM]xxx model code")
                    return@runOnIO
                }

                // Guard 2: model must be in the 4G-capable set.
                // The model code is the W[AM]xxx prefix (first 5-6 chars).
                val modelCode = serial.take(5).lowercase()
                if (modelCode !in MODELS_WITH_4G) {
                    update {
                        copy(is4gBusy = false, fourGMessage = "4G is not supported on $modelCode. It requires a DJI Cellular Dongle 2, which is only available on Mavic 4 Pro / Matrice / Inspire 3.")
                    }
                    log("4G activation aborted — model $modelCode is not in the 4G-capable set $MODELS_WITH_4G")
                    return@runOnIO
                }

                // Guard 3: dongle pre-check — fast-fail if the socket does not exist.
                if (!transport.is4gDonglePresent()) {
                    update {
                        copy(is4gBusy = false, fourGMessage = "4G dongle not detected. Connect a DJI Cellular Dongle 2 to the aircraft and try again.")
                    }
                    log("4G activation aborted — 4G socket /duss/mb/0x205 not connectable (no dongle?)")
                    return@runOnIO
                }

                val profile = Profiles.load4g(app, serial)
                log("Loaded 4G profile: ${profile.frames.size} frames (serial: $serial, model: $modelCode)")

                // 4G uses Unix domain socket, not TCP
                val success = transport.sendFramesUnix(
                    frames = profile.frames,
                    interFrameDelayMs = profile.interFrameDelay
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            is4gBusy = false,
                            busyProgress = 0f,
                            fourGMessage = "All activation frames written successfully — check 4G status on the aircraft."
                        )
                    }
                    log("4G activation: all ${profile.frames.size} frames written successfully via Unix socket")
                } else {
                    update { copy(is4gBusy = false, fourGMessage = "4G apply failed — is the 4G dongle connected?") }
                    log("4G activation failed — at least one frame write failed on the Unix socket")
                }
            } catch (e: Exception) {
                log("4G activation error: ${e.message}")
                update { copy(is4gBusy = false, fourGMessage = "4G error: ${e.message}") }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- LED ---

    /**
     * Turns the aircraft arm LEDs on or off.
     * Uses port 40007 (different from the standard 40009 DUML port).
     * Requires DJI Fly running with the aircraft connected.
     *
     * Sends the LED command in 2 bursts of 5 writes each (10 total), with
     * 100ms between writes — matching the reference app's pattern for
     * reliability.
     *
     * @param on true for LED ON, false for LED OFF
     */
    fun setLed(on: Boolean) {
        if (
            _state.value.telemetryRuntime.captureActive &&
            _state.value.telemetryRuntime.sourcePort == DumlTransport.PORT_LED
        ) {
            log("LED command blocked because telemetry holds port 40007")
            return
        }
        if (_state.value.isLedBusy) {
            log("LED busy — please wait.")
            return
        }
        update { copy(isLedBusy = true, ledStatus = if (on) "Turning LEDs on..." else "Turning LEDs off...") }
        log(if (on) "Turning LEDs on..." else "Turning LEDs off...")

        runOnIO {
            var portLease: DumlPortSessionLock.Lease? = null
            try {
                val fileName = if (on) "led_on.json" else "led_off.json"
                val profile = Profiles.load(app, fileName)
                portLease = DumlPortSessionLock.tryBegin(profile.port)
                if (portLease == null) {
                    update { copy(isLedBusy = false, ledStatus = "Port ${profile.port} busy") }
                    return@runOnIO
                }
                log("Loaded LED profile: ${profile.frames.size} frames (port ${profile.port})")

                // Separate transport instance — the LED command on port 40007
                // must not share state with the FCC transport on port 40009.
                val ledTransport = DumlTransport()

                var anySuccess = false

                // 2 connection bursts × 5 writes each = 10 total sends, with
                // 100ms between writes and 100ms between bursts. Matches the
                // reference app's reliability pattern.
                for (attempt in 0 until 2) {
                    if (attempt > 0) delay(100)

                    val success = ledTransport.sendFrames(
                        frames = profile.frames,
                        rounds = 5,
                        interFrameDelayMs = 100,
                        interRoundDelayMs = 0,
                        readWindowMs = 100,
                        port = profile.port
                    )

                    if (success) anySuccess = true
                }

                if (anySuccess) {
                    update { copy(isLedBusy = false, ledStatus = if (on) "ON" else "OFF") }
                    log(if (on) "LEDs turned on" else "LEDs turned off")
                } else {
                    update { copy(isLedBusy = false, ledStatus = "Failed — is DJI Fly running?") }
                    log("LED command failed — make sure DJI Fly is running with aircraft connected")
                }
            } catch (e: Exception) {
                log("LED error: ${e.message}")
                update { copy(isLedBusy = false, ledStatus = "Error: ${e.message}") }
            } finally {
                portLease?.close()
            }
        }
    }

    // --- Device Info ---

    /**
     * Queries the controller for hardware version, bootloader version, and
     * firmware version via the GENERAL VersionInquiry command
     * (cmd_set=0, cmd_id=1). Uses sendAndReceive to capture the response.
     */
    fun queryDeviceInfo() {
        if (!isControllerReachable()) return
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }

        update { copy(isQueryingInfo = true) }
        log("Querying device info...")

        runOnIO {
            try {
                val profile = Profiles.load(app, "device_info.json")
                if (profile.frames.isEmpty()) {
                    update { copy(isQueryingInfo = false, deviceInfo = "device_info.json is empty") }
                    log("Device info: profile has no frames")
                    return@runOnIO
                }
                val frame = profile.frames.first()

                val response = transport.sendAndReceive(frame, profile.readWindowMs)

                if (response == null || response.isEmpty()) {
                    update { copy(isQueryingInfo = false, deviceInfo = "No response from controller") }
                    log("Device info: no response")
                    return@runOnIO
                }

                val info = formatVersionResponse(response)
                update { copy(isQueryingInfo = false, deviceInfo = info) }
                log("Device info received: ${response.size} bytes")
            } catch (e: Exception) {
                log("Device info error: ${e.message}")
                update { copy(isQueryingInfo = false, deviceInfo = "Error: ${e.message}") }
            } finally {
                endHardwareOp()
            }
        }
    }

    fun probeSerial() {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        log("Probing for aircraft serial...")
        runOnIO {
            try {
                val serial = transport.probeSerial(2000)
                if (serial.isNotEmpty()) {
                    update { copy(aircraftSerial = serial) }
                    prefs.edit().putString("aircraft_serial", serial).apply()
                    log("Aircraft serial: $serial (cached)")
                } else {
                    log("No serial detected — is the aircraft powered on?")
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- Updates ---

    fun checkForUpdates(force: Boolean = false) {
        // Rate-limit: don't hit GitHub API more than once per hour.
        // Unauthenticated limit is 60 requests/hour per IP.
        // The timestamp is saved ONLY on success — a failed check does NOT
        // consume the rate-limit window, so the user can retry immediately.
        val lastCheck = prefs.getLong("last_update_check", 0)
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60 * 60 * 1000 && _state.value.updateChecked && _state.value.updateInfo != null) {
            return
        }
        update { copy(isCheckingUpdate = true) }
        log("Checking for updates...")

        runOnIO {
            val endpoint = _state.value.updateEndpoint.ifBlank { UpdateChecker.DEFAULT_API_URL }
            val info = UpdateChecker.fetchLatest(endpoint)
            if (info == null) {
                // Don't save lastCheck on failure — let the user retry immediately.
                update { copy(isCheckingUpdate = false, updateChecked = true) }
                log("Update check failed — no internet or GitHub unreachable. Tap Retry to try again.")
                return@runOnIO
            }

            // Save the timestamp only on success.
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()

            val isNewer = info.isNewerThan(APP_VERSION)
            update {
                copy(
                    updateInfo = info,
                    isCheckingUpdate = false,
                    updateChecked = true,
                    updateAvailable = isNewer
                )
            }
            if (isNewer) {
                log("Update available: v${info.version}")
            } else {
                log("App is up to date (v$APP_VERSION)")
            }
        }
    }

    fun updateReleaseEndpoint(value: String) {
        val cleaned = value.trim()
        prefs.edit().putString("update_endpoint", cleaned).apply()
        update { copy(updateEndpoint = cleaned, updateChecked = false, updateInfo = null, updateAvailable = false) }
    }

    fun resetReleaseEndpoint() {
        prefs.edit().putString("update_endpoint", UpdateChecker.DEFAULT_API_URL).apply()
        update { copy(updateEndpoint = UpdateChecker.DEFAULT_API_URL, updateChecked = false, updateInfo = null, updateAvailable = false) }
        log("Release channel reset to the FreeFCC research channel")
    }

    private var downloadedApk: java.io.File? = null

    /**
     * Checks if the app can install packages. If not, opens the system
     * Settings page for "Install unknown apps" so the user can grant it.
     * Returns true if permission is already granted (proceed with download),
     * false if we opened Settings (user needs to grant, then tap Download again).
     */
    fun ensureInstallPermission(): Boolean {
        val pm = app.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            log("Install permission needed — opening Settings. Grant 'Install unknown apps' for FreeFCC, then tap Download again.")
            val settingsIntent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            ).apply {
                data = android.net.Uri.parse("package:${app.packageName}")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                app.startActivity(settingsIntent)
            } catch (_: Exception) {
                log("Settings page not available — you may need to install updates via SD card + FileManager.")
            }
            return false
        }
        return true
    }

    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: return
        if (_state.value.isDownloadingUpdate) return
        // Check install permission BEFORE downloading so the user can grant
        // it first, then come back and tap Download again.
        if (!ensureInstallPermission()) {
            return
        }
        update { copy(isDownloadingUpdate = true, updateDownloadProgress = 0f, isUpdateDownloaded = false) }
        log("Downloading update v${info.version}...")

        runOnIO {
            val file = UpdateChecker.downloadApk(app, info) { progress ->
                update { copy(updateDownloadProgress = progress) }
            }

            if (file == null) {
                update { copy(isDownloadingUpdate = false, updateDownloadProgress = 0f) }
                log("Update download failed — check your Wi-Fi connection. The RC2 needs Wi-Fi to download updates.")
                return@runOnIO
            }

            downloadedApk = file
            update { copy(isDownloadingUpdate = false, updateDownloadProgress = 1f, isUpdateDownloaded = true) }
            log("Update downloaded — tap Install to apply")
        }
    }

    /** Re-downloads the update after a failed install. Resets the downloaded state first. */
    fun reDownloadUpdate() {
        if (_state.value.isDownloadingUpdate) return
        downloadedApk = null
        update { copy(isUpdateDownloaded = false) }
        downloadUpdate()
    }

    fun installUpdate() {
        val file = downloadedApk ?: run {
            log("No downloaded APK found — download first")
            return
        }
        if (!file.exists()) {
            log("Downloaded APK file missing — download again")
            downloadedApk = null
            update { copy(isUpdateDownloaded = false) }
            return
        }
        update { copy(isBusy = true, message = "Preparing install...") }
        runOnIO {
            try {
                val pm = app.packageManager

                // Check 1: does this app have permission to install packages?
                // On Android 8+ the user must grant "Install unknown apps"
                // per-app. The RC2 may hide this Settings page — if so, the
                // user needs to install via SD card + FileManager instead.
                if (android.os.Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
                    log("Install blocked — FreeFCC needs 'Install unknown apps' permission.")
                    log("Opening Settings to grant it. If the Settings page doesn't appear,")
                    log("install the update via SD card + FileManager instead.")
                    val settingsIntent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                    ).apply {
                        data = android.net.Uri.parse("package:${app.packageName}")
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        app.startActivity(settingsIntent)
                    } catch (_: Exception) {
                        log("Settings page unavailable — install via SD card + FileManager.")
                    }
                    update { copy(isBusy = false, message = "Grant install permission in Settings, then tap Install again. Or install via SD card.") }
                    return@runOnIO
                }

                // Copy the APK to a location the installer can access.
                // The RC2's package installer may not handle content:// URIs
                // from app-private cacheDir (a known Android issue). Copying
                // to the app-specific external directory is more reliable.
                val extDir = java.io.File(app.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val extFile = java.io.File(extDir, "freefcc_update.apk")
                try {
                    file.copyTo(extFile, overwrite = true)
                } catch (e: Exception) {
                    log("Could not copy APK to external storage: ${e.message}")
                    // Fall back to the cache file
                }
                val installFile = if (extFile.exists()) extFile else file

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    app, "${app.packageName}.fileprovider", installFile
                )
                val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                // Check 2: does a package installer actually exist?
                if (viewIntent.resolveActivity(pm) == null) {
                    log("No package installer found. Sideload 01_PackageInstaller from the SD card, reboot, then retry.")
                    update { copy(isBusy = false, message = "No installer. Sideload 01_PackageInstaller from SD card, reboot, retry.") }
                    return@runOnIO
                }

                // Grant URI permission to all resolved installer activities —
                // some OEM forks don't honor FLAG_GRANT_READ_URI_PERMISSION
                // alone for the staging step.
                val targets = pm.queryIntentActivities(viewIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                for (info in targets) {
                    app.grantUriPermission(
                        info.activityInfo.packageName, uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                try {
                    app.startActivity(viewIntent)
                    log("Launching installer...")
                    update { copy(isBusy = false, message = "Installer launched — follow the on-screen prompts.") }
                } catch (e: android.content.ActivityNotFoundException) {
                    log("No package installer on this device. Install via SD card + FileManager.")
                    update { copy(isBusy = false, message = "No installer. Install via SD card + FileManager.") }
                } catch (e: Exception) {
                    log("Install failed: ${e.message}")
                    update { copy(isBusy = false, message = "Install failed: ${e.message}") }
                }
            } catch (e: Exception) {
                log("Install error: ${e.message}")
                update { copy(isBusy = false, message = "Install error: ${e.message}") }
            }
        }
    }

    // --- Helpers ---

    /** Returns true if the controller is connected, logs a hint if not. */
    private fun isControllerReachable(): Boolean {
        if (_state.value.isConnected) return true
        log("Connect to the controller first")
        return false
    }

    /** Returns the cached serial, or probes the controller if not yet known. Caches the result in SharedPreferences. */
    private fun getOrProbeSerial(): String {
        var serial = _state.value.aircraftSerial
        if (serial.isEmpty()) {
            // Fall back to a serial persisted in a previous session.
            serial = prefs.getString("aircraft_serial", "").orEmpty()
            if (serial.isNotEmpty()) {
                update { copy(aircraftSerial = serial) }
            }
        }
        if (serial.isEmpty()) {
            log("Probing for aircraft serial...")
            serial = transport.probeSerial(2000)
            if (serial.isNotEmpty()) {
                update { copy(aircraftSerial = serial) }
                prefs.edit().putString("aircraft_serial", serial).apply()
                log("Aircraft serial: $serial (cached)")
            }
        }
        return serial
    }

    /**
     * Parses a DUML VersionInquiry response payload into a human-readable string.
     *
     * Response layout (from dji-firmware-tools DJIPayload_General_VersionInquiryRe):
     *   byte  0-1    unknown
     *   bytes 2-17   hardware version (16-char ASCII string)
     *   bytes 18-21  bootloader version (uint32 LE)
     *   bytes 22-25  firmware version (uint32 LE)
     */
    private fun formatVersionResponse(payload: ByteArray): String {
        val lines = mutableListOf<String>()

        if (payload.size >= 18) {
            val hwVersion = String(payload, 2, 16, Charsets.US_ASCII).trimEnd('\u0000')
            lines.add("Hardware: $hwVersion")
        }

        if (payload.size >= 22) {
            val ldrVersion = readUInt32LE(payload, 18)
            lines.add("Bootloader: ${formatVersion(ldrVersion)}")
        }

        if (payload.size >= 26) {
            val appVersion = readUInt32LE(payload, 22)
            lines.add("Firmware: ${formatVersion(appVersion)}")
        }

        lines.add("")
        lines.add("Raw payload (${payload.size} bytes):")
        lines.add(payload.joinToString(" ") { "%02x".format(it) })

        return lines.joinToString("\n")
    }

    /** Reads a 32-bit little-endian unsigned integer from a byte array. */
    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF)) or
               ((data[offset + 1].toLong() and 0xFF) shl 8) or
               ((data[offset + 2].toLong() and 0xFF) shl 16) or
               ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /** Formats a DJI firmware version uint32 as major.minor.patch.build. */
    private fun formatVersion(version: Long): String {
        val major = (version shr 24) and 0xFF
        val minor = (version shr 16) and 0xFF
        val patch = (version shr 8) and 0xFF
        val build = version and 0xFF
        return "$major.$minor.$patch.$build"
    }

    /** Atomically updates the state via a copy() block. */
    private fun update(block: AppState.() -> AppState) {
        _state.value = _state.value.block()
    }

    /** Adds a timestamped entry to the activity log (most recent first, max 50). */
    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] $message"
        update { copy(logMessages = (listOf(entry) + logMessages).take(50)) }
    }

    /** Launches a coroutine on Dispatchers.IO for network operations. */
    private fun runOnIO(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
