package com.freefcc.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject

internal enum class DumlLabWireFormat(val wireName: String) {
    Direct("direct"),
    Wrapped40007("wrapped_40007")
}

internal sealed interface DumlLabStep {
    val label: String

    data class Connect(override val label: String) : DumlLabStep
    data class Close(override val label: String) : DumlLabStep

    data class WriteDuml(
        override val label: String,
        val sender: Int,
        val destination: Int,
        val cmdType: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray,
        val wireFormat: DumlLabWireFormat
    ) : DumlLabStep

    data class WriteRaw(
        override val label: String,
        val bytes: ByteArray
    ) : DumlLabStep

    data class Read(
        override val label: String,
        val durationMs: Int,
        val idleTimeoutMs: Int,
        val maxBytes: Int
    ) : DumlLabStep

    data class Sleep(
        override val label: String,
        val durationMs: Int
    ) : DumlLabStep
}

internal data class DumlLabRecipe(
    val name: String,
    val port: Int,
    val connectTimeoutMs: Int,
    val totalTimeoutMs: Int,
    val maxRxBytes: Int,
    val setupSteps: List<DumlLabStep>,
    val cycleSteps: List<DumlLabStep>,
    val cycleCount: Int,
    val teardownSteps: List<DumlLabStep>
)

internal data class DumlLabRecipeParseResult(
    val recipe: DumlLabRecipe? = null,
    val error: String = ""
)

internal object DumlLabRecipeParser {
    fun parse(request: JSONObject): DumlLabRecipeParseResult {
        val recipeObject = request.optJSONObject("recipe")
            ?: return invalid("Missing recipe object")
        if (recipeObject.optString("schema") != RECIPE_SCHEMA) {
            return invalid("recipe.schema must be $RECIPE_SCHEMA")
        }
        if (
            recipeObject.has("host") &&
            recipeObject.optString("host") != LOOPBACK_HOST
        ) {
            return invalid("host is fixed to $LOOPBACK_HOST")
        }

        val name = recipeObject.optString("name", "").trim()
        if (name.isBlank() || name.length > MAX_NAME_CHARS) {
            return invalid("Recipe name must be 1..$MAX_NAME_CHARS characters")
        }
        val port = integer(recipeObject, "port")
            ?: return invalid("port must be an integer")
        if (port !in 1..65535) return invalid("port must be in 1..65535")

        val connectTimeoutMs = integer(recipeObject, "connect_timeout_ms", DEFAULT_CONNECT_TIMEOUT_MS)
            ?: return invalid("connect_timeout_ms must be an integer")
        if (connectTimeoutMs !in 50..MAX_CONNECT_TIMEOUT_MS) {
            return invalid("connect_timeout_ms must be in 50..$MAX_CONNECT_TIMEOUT_MS")
        }
        val totalTimeoutMs = integer(recipeObject, "total_timeout_ms", DEFAULT_TOTAL_TIMEOUT_MS)
            ?: return invalid("total_timeout_ms must be an integer")
        if (totalTimeoutMs !in 100..MAX_TOTAL_TIMEOUT_MS) {
            return invalid("total_timeout_ms must be in 100..$MAX_TOTAL_TIMEOUT_MS")
        }
        val maxRxBytes = integer(recipeObject, "max_rx_bytes", DEFAULT_MAX_RX_BYTES)
            ?: return invalid("max_rx_bytes must be an integer")
        if (maxRxBytes !in 1..MAX_RX_BYTES) {
            return invalid("max_rx_bytes must be in 1..$MAX_RX_BYTES")
        }

        val setup = parseSteps(recipeObject, "setup", maxRxBytes)
        if (setup.error.isNotEmpty()) return invalid(setup.error)
        val cycle = parseSteps(recipeObject, "cycle", maxRxBytes)
        if (cycle.error.isNotEmpty()) return invalid(cycle.error)
        val teardown = parseSteps(recipeObject, "teardown", maxRxBytes)
        if (teardown.error.isNotEmpty()) return invalid(teardown.error)

        val cycleCountDefault = if (cycle.steps.isEmpty()) 0 else 1
        val cycleCount = integer(recipeObject, "cycle_count", cycleCountDefault)
            ?: return invalid("cycle_count must be an integer")
        if (cycle.steps.isEmpty() && cycleCount != 0) {
            return invalid("cycle_count must be 0 when cycle is empty")
        }
        if (cycle.steps.isNotEmpty() && cycleCount !in 1..MAX_CYCLES) {
            return invalid("cycle_count must be in 1..$MAX_CYCLES")
        }

        val expandedSteps = setup.steps.size +
            (cycle.steps.size * cycleCount) +
            teardown.steps.size
        if (expandedSteps !in 1..MAX_EXPANDED_STEPS) {
            return invalid("Expanded recipe must contain 1..$MAX_EXPANDED_STEPS steps")
        }

        val expandedTxBytes = txBytes(setup.steps) +
            (txBytes(cycle.steps) * cycleCount) +
            txBytes(teardown.steps)
        if (expandedTxBytes > MAX_TX_BYTES) {
            return invalid("Expanded recipe transmits $expandedTxBytes bytes; max is $MAX_TX_BYTES")
        }

        return DumlLabRecipeParseResult(
            recipe = DumlLabRecipe(
                name = name,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                totalTimeoutMs = totalTimeoutMs,
                maxRxBytes = maxRxBytes,
                setupSteps = setup.steps,
                cycleSteps = cycle.steps,
                cycleCount = cycleCount,
                teardownSteps = teardown.steps
            )
        )
    }

    private data class ParsedSteps(
        val steps: List<DumlLabStep> = emptyList(),
        val error: String = ""
    )

    private fun parseSteps(
        recipe: JSONObject,
        phase: String,
        recipeMaxRxBytes: Int
    ): ParsedSteps {
        if (recipe.has(phase) && recipe.opt(phase) !is JSONArray) {
            return ParsedSteps(error = "$phase must be an array")
        }
        val array = recipe.optJSONArray(phase) ?: JSONArray()
        if (array.length() > MAX_PHASE_STEPS) {
            return ParsedSteps(error = "$phase has more than $MAX_PHASE_STEPS steps")
        }
        val result = mutableListOf<DumlLabStep>()
        for (index in 0 until array.length()) {
            val stepObject = array.optJSONObject(index)
                ?: return ParsedSteps(error = "$phase[$index] must be an object")
            val parsed = parseStep(stepObject, recipeMaxRxBytes)
            if (parsed.error.isNotEmpty()) {
                return ParsedSteps(error = "$phase[$index]: ${parsed.error}")
            }
            result += parsed.step ?: return ParsedSteps(error = "$phase[$index]: missing step")
        }
        return ParsedSteps(steps = result)
    }

    private data class ParsedStep(
        val step: DumlLabStep? = null,
        val error: String = ""
    )

    private fun parseStep(obj: JSONObject, recipeMaxRxBytes: Int): ParsedStep {
        val op = obj.optString("op", "").trim()
        val label = obj.optString("label", "").trim()
        if (label.length > MAX_LABEL_CHARS) {
            return ParsedStep(error = "label exceeds $MAX_LABEL_CHARS characters")
        }
        return when (op) {
            "connect" -> ParsedStep(DumlLabStep.Connect(label))
            "close" -> ParsedStep(DumlLabStep.Close(label))
            "write_duml" -> parseWriteDuml(obj, label)
            "write_raw" -> {
                val bytes = decodeBase64(obj, "bytes_b64", MAX_RAW_WRITE_BYTES)
                    ?: return ParsedStep(error = "bytes_b64 is invalid")
                if (bytes.isEmpty() || bytes.size > MAX_RAW_WRITE_BYTES) {
                    ParsedStep(error = "write_raw must contain 1..$MAX_RAW_WRITE_BYTES bytes")
                } else {
                    ParsedStep(DumlLabStep.WriteRaw(label, bytes))
                }
            }
            "read" -> {
                val durationMs = integer(obj, "duration_ms", DEFAULT_READ_DURATION_MS)
                    ?: return ParsedStep(error = "duration_ms must be an integer")
                val idleTimeoutMs = integer(obj, "idle_timeout_ms", DEFAULT_IDLE_TIMEOUT_MS)
                    ?: return ParsedStep(error = "idle_timeout_ms must be an integer")
                val maxBytes = integer(obj, "max_bytes", minOf(DEFAULT_STEP_MAX_RX_BYTES, recipeMaxRxBytes))
                    ?: return ParsedStep(error = "max_bytes must be an integer")
                when {
                    durationMs !in 1..MAX_READ_DURATION_MS ->
                        ParsedStep(error = "duration_ms must be in 1..$MAX_READ_DURATION_MS")
                    idleTimeoutMs !in 1..MAX_IDLE_TIMEOUT_MS ->
                        ParsedStep(error = "idle_timeout_ms must be in 1..$MAX_IDLE_TIMEOUT_MS")
                    maxBytes !in 1..minOf(MAX_STEP_RX_BYTES, recipeMaxRxBytes) ->
                        ParsedStep(error = "max_bytes exceeds the bounded receive limit")
                    else -> ParsedStep(
                        DumlLabStep.Read(label, durationMs, idleTimeoutMs, maxBytes)
                    )
                }
            }
            "sleep" -> {
                val durationMs = integer(obj, "duration_ms")
                    ?: return ParsedStep(error = "duration_ms must be an integer")
                if (durationMs !in 0..MAX_SLEEP_MS) {
                    ParsedStep(error = "duration_ms must be in 0..$MAX_SLEEP_MS")
                } else {
                    ParsedStep(DumlLabStep.Sleep(label, durationMs))
                }
            }
            else -> ParsedStep(error = "Unknown op '$op'")
        }
    }

    private fun parseWriteDuml(obj: JSONObject, label: String): ParsedStep {
        fun byteField(name: String): Int? = integer(obj, name)?.takeIf { it in 0..255 }
        val sender = byteField("sender") ?: return ParsedStep(error = "sender must be a byte")
        val destination = byteField("destination")
            ?: return ParsedStep(error = "destination must be a byte")
        val cmdType = byteField("cmd_type") ?: return ParsedStep(error = "cmd_type must be a byte")
        val cmdSet = byteField("cmd_set") ?: return ParsedStep(error = "cmd_set must be a byte")
        val cmdId = byteField("cmd_id") ?: return ParsedStep(error = "cmd_id must be a byte")
        val payload = decodeBase64(obj, "payload_b64", MAX_DUML_PAYLOAD_BYTES)
            ?: return ParsedStep(error = "payload_b64 is invalid")
        if (payload.size > MAX_DUML_PAYLOAD_BYTES) {
            return ParsedStep(error = "DUML payload exceeds $MAX_DUML_PAYLOAD_BYTES bytes")
        }
        val wireFormat = when (obj.optString("wire", "direct")) {
            "direct" -> DumlLabWireFormat.Direct
            "wrapped", "wrapped_40007" -> DumlLabWireFormat.Wrapped40007
            else -> return ParsedStep(error = "wire must be direct or wrapped_40007")
        }
        return ParsedStep(
            DumlLabStep.WriteDuml(
                label = label,
                sender = sender,
                destination = destination,
                cmdType = cmdType,
                cmdSet = cmdSet,
                cmdId = cmdId,
                payload = payload,
                wireFormat = wireFormat
            )
        )
    }

    private fun integer(obj: JSONObject, name: String, default: Int? = null): Int? {
        if (!obj.has(name)) return default
        val raw = obj.opt(name)
        if (raw !is Number) return null
        val longValue = raw.toLong()
        if (
            raw.toDouble() != longValue.toDouble() ||
            longValue !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        ) {
            return null
        }
        return longValue.toInt()
    }

    private fun decodeBase64(obj: JSONObject, name: String, maxDecodedBytes: Int): ByteArray? {
        val encoded = obj.optString(name, "")
        val maxEncodedChars = ((maxDecodedBytes + 2) / 3) * 4
        if (encoded.length > maxEncodedChars) return null
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun txBytes(steps: List<DumlLabStep>): Int = steps.sumOf { step ->
        when (step) {
            is DumlLabStep.WriteDuml ->
                DumlFrameParser.MIN_FRAME_BYTES + step.payload.size +
                    if (step.wireFormat == DumlLabWireFormat.Wrapped40007) 8 else 0
            is DumlLabStep.WriteRaw -> step.bytes.size
            else -> 0
        }
    }

    private fun invalid(message: String) = DumlLabRecipeParseResult(error = message)

    const val RECIPE_SCHEMA = "duml-lab/v1"
    const val MAX_TOTAL_TIMEOUT_MS = 30_000
    const val MAX_RX_BYTES = 256 * 1024
    const val MAX_TX_BYTES = 64 * 1024
    const val MAX_EXPANDED_STEPS = 512
    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val MAX_CYCLES = 200
    private const val MAX_PHASE_STEPS = 64
    private const val MAX_NAME_CHARS = 64
    private const val MAX_LABEL_CHARS = 64
    private const val MAX_CONNECT_TIMEOUT_MS = 5_000
    private const val MAX_DUML_PAYLOAD_BYTES = 1010
    private const val MAX_RAW_WRITE_BYTES = 4 * 1024
    private const val MAX_READ_DURATION_MS = 5_000
    private const val MAX_IDLE_TIMEOUT_MS = 1_000
    private const val MAX_STEP_RX_BYTES = 64 * 1024
    private const val MAX_SLEEP_MS = 5_000
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 2_000
    private const val DEFAULT_TOTAL_TIMEOUT_MS = 10_000
    private const val DEFAULT_MAX_RX_BYTES = 256 * 1024
    private const val DEFAULT_READ_DURATION_MS = 1_000
    private const val DEFAULT_IDLE_TIMEOUT_MS = 100
    private const val DEFAULT_STEP_MAX_RX_BYTES = 64 * 1024
}

internal data class DumlLabStepResult(
    val index: Int,
    val phase: String,
    val cycle: Int?,
    val op: String,
    val label: String,
    val status: String,
    val durationMs: Long,
    val bytes: ByteArray? = null,
    val message: String = ""
)

internal data class DumlLabObservedFrame(
    val phase: String,
    val cycle: Int?,
    val stepIndex: Int,
    val direction: String,
    val frame: DumlFrameParser.ParsedFrame
)

internal data class DumlLabObservedParserError(
    val phase: String,
    val cycle: Int?,
    val stepIndex: Int,
    val direction: String,
    val error: DumlFrameParser.ParseError
)

internal data class DumlLabExecutionResult(
    val status: String,
    val terminalReason: String,
    val message: String,
    val durationMs: Long,
    val connectionsOpened: Int,
    val txBytes: Int,
    val rxBytes: Int,
    val steps: List<DumlLabStepResult>,
    val frames: List<DumlLabObservedFrame>,
    val parserErrors: List<DumlLabObservedParserError>,
    val droppedFrames: Int = 0,
    val droppedParserErrors: Int = 0
)

internal class DumlLabCancellation {
    private val cancelled = AtomicBoolean(false)
    private val activeSocket = AtomicReference<Socket?>(null)

    fun cancel() {
        cancelled.set(true)
        try {
            activeSocket.getAndSet(null)?.close()
        } catch (_: IOException) {
        }
    }

    fun attach(socket: Socket): Boolean {
        if (cancelled.get()) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            return false
        }
        check(activeSocket.compareAndSet(null, socket)) {
            "Only one DUML Lab socket may be active"
        }
        if (cancelled.get() && activeSocket.compareAndSet(socket, null)) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            return false
        }
        return true
    }

    fun detach(socket: Socket) {
        activeSocket.compareAndSet(socket, null)
    }

    fun throwIfCancelled() {
        if (cancelled.get()) throw DumlLabCancelledException()
    }
}

private class DumlLabCancelledException : RuntimeException()

/**
 * Executes one bounded recipe against controller localhost only.
 *
 * Socket lifecycle is entirely explicit in the recipe. The engine never
 * reconnects on EOF or an I/O failure; another `connect` step is required.
 */
internal class DumlLabEngine(
    private val builder: DumlBuilder = DumlBuilder()
) {
    fun execute(
        recipe: DumlLabRecipe,
        cancellation: DumlLabCancellation = DumlLabCancellation()
    ): DumlLabExecutionResult {
        val startedNs = System.nanoTime()
        val deadlineNs = startedNs + recipe.totalTimeoutMs * 1_000_000L
        val stepResults = mutableListOf<DumlLabStepResult>()
        val observations = DumlLabObservationCollector()
        var socket: Socket? = null
        var parser: WrappedDumlFrameParser? = null
        var connectionsOpened = 0
        var totalTxBytes = 0
        var totalRxBytes = 0
        var expandedIndex = 0
        var fatalReason: String? = null
        var fatalMessage = ""

        fun closeSocket(phase: String, cycle: Int?, stepIndex: Int) {
            parser?.finish()?.forEach {
                observations.record(
                    it,
                    phase,
                    cycle,
                    stepIndex,
                    "controller_to_client"
                )
            }
            parser = null
            val connected = socket
            if (connected != null) cancellation.detach(connected)
            try {
                connected?.close()
            } catch (_: IOException) {
            }
            socket = null
        }

        fun fail(reason: String, message: String) {
            if (fatalReason == null) {
                fatalReason = reason
                fatalMessage = message
            }
        }

        fun executeStep(step: DumlLabStep, phase: String, cycle: Int?): Boolean {
            if (System.nanoTime() >= deadlineNs) {
                fail("total_timeout", "Recipe exceeded ${recipe.totalTimeoutMs} ms")
                return false
            }
            expandedIndex += 1
            val stepStartedNs = System.nanoTime()
            var status = "ok"
            var bytes: ByteArray? = null
            var message = ""
            val op = operationName(step)
            try {
                cancellation.throwIfCancelled()
                when (step) {
                    is DumlLabStep.Connect -> {
                        if (socket != null) {
                            fail("already_connected", "connect requires the current socket to be closed")
                        } else {
                            val remainingMs = remainingMs(deadlineNs)
                            val connected = Socket()
                            if (!cancellation.attach(connected)) {
                                throw DumlLabCancelledException()
                            }
                            socket = connected
                            connected.connect(
                                InetSocketAddress(LOOPBACK_HOST, recipe.port),
                                minOf(recipe.connectTimeoutMs, remainingMs)
                            )
                            cancellation.throwIfCancelled()
                            connected.tcpNoDelay = true
                            connected.keepAlive = true
                            parser = WrappedDumlFrameParser()
                            connectionsOpened += 1
                        }
                    }
                    is DumlLabStep.Close -> {
                        if (socket == null) {
                            status = "already_closed"
                        } else {
                            closeSocket(phase, cycle, expandedIndex)
                        }
                    }
                    is DumlLabStep.WriteDuml -> {
                        val connected = socket
                        if (connected == null) {
                            fail("not_connected", "write_duml requires a connected socket")
                        } else {
                            val direct = builder.buildFrame(
                                DumlFrame(
                                    sender = step.sender,
                                    dst = step.destination,
                                    cmdType = step.cmdType,
                                    cmdSet = step.cmdSet,
                                    cmdId = step.cmdId,
                                    payload = step.payload
                                )
                            )
                            bytes = if (step.wireFormat == DumlLabWireFormat.Wrapped40007) {
                                SameSocketTelemetryKeepalive.wrap(direct)
                            } else {
                                direct
                            }
                            connected.getOutputStream().apply {
                                write(bytes)
                                flush()
                            }
                            totalTxBytes += bytes.size
                        }
                    }
                    is DumlLabStep.WriteRaw -> {
                        val connected = socket
                        if (connected == null) {
                            fail("not_connected", "write_raw requires a connected socket")
                        } else {
                            bytes = step.bytes
                            connected.getOutputStream().apply {
                                write(step.bytes)
                                flush()
                            }
                            totalTxBytes += step.bytes.size
                        }
                    }
                    is DumlLabStep.Read -> {
                        val connected = socket
                        if (connected == null) {
                            fail("not_connected", "read requires a connected socket")
                        } else {
                            val readResult = readStep(
                                connected = connected,
                                parser = parser ?: WrappedDumlFrameParser().also { parser = it },
                                step = step,
                                phase = phase,
                                cycle = cycle,
                                stepIndex = expandedIndex,
                                recipeDeadlineNs = deadlineNs,
                                recipeRxRemaining = recipe.maxRxBytes - totalRxBytes,
                                observations = observations,
                                cancellation = cancellation
                            )
                            bytes = readResult.bytes
                            totalRxBytes += readResult.bytes.size
                            status = readResult.status
                            message = readResult.message
                            if (readResult.eof) {
                                closeSocket(phase, cycle, expandedIndex)
                            }
                            if (readResult.limitReached) {
                                fail("rx_limit", "Recipe reached its ${recipe.maxRxBytes}-byte receive limit")
                            }
                        }
                    }
                    is DumlLabStep.Sleep -> {
                        val remainingMs = remainingMs(deadlineNs)
                        if (step.durationMs > remainingMs) {
                            sleepCancellable(remainingMs, cancellation)
                            fail("total_timeout", "Recipe timed out during sleep")
                        } else if (step.durationMs > 0) {
                            sleepCancellable(step.durationMs, cancellation)
                        }
                    }
                }
            } catch (_: DumlLabCancelledException) {
                status = "cancelled"
                fail("cancelled", "Recipe cancelled because the telemetry relay stopped")
            } catch (e: SocketTimeoutException) {
                status = "timeout"
                fail("socket_timeout", e.message.orEmpty().ifBlank { "Socket timed out" })
            } catch (e: IOException) {
                try {
                    cancellation.throwIfCancelled()
                    status = "io_error"
                    fail(
                        "io_error:${e.javaClass.simpleName}",
                        e.message.orEmpty().ifBlank { "Socket I/O failed" }
                    )
                } catch (_: DumlLabCancelledException) {
                    status = "cancelled"
                    fail("cancelled", "Recipe cancelled because the telemetry relay stopped")
                }
                closeSocket(phase, cycle, expandedIndex)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                status = "cancelled"
                fail("cancelled", "Recipe cancelled because the telemetry relay stopped")
            } catch (e: Exception) {
                status = "error"
                fail("error:${e.javaClass.simpleName}", e.message.orEmpty().ifBlank { "Recipe step failed" })
            }
            if (fatalReason != null && status == "ok") status = "failed"
            stepResults += DumlLabStepResult(
                index = expandedIndex,
                phase = phase,
                cycle = cycle,
                op = op,
                label = step.label,
                status = status,
                durationMs = elapsedMs(stepStartedNs),
                bytes = bytes,
                message = message.ifBlank {
                    if (fatalReason != null) fatalMessage else ""
                }
            )
            return fatalReason == null
        }

        fun executePhase(steps: List<DumlLabStep>, phase: String, cycle: Int? = null): Boolean {
            for (step in steps) {
                if (!executeStep(step, phase, cycle)) return false
            }
            return true
        }

        try {
            if (executePhase(recipe.setupSteps, "setup")) {
                for (cycle in 1..recipe.cycleCount) {
                    if (!executePhase(recipe.cycleSteps, "cycle", cycle)) break
                }
            }
            if (fatalReason == null) {
                executePhase(recipe.teardownSteps, "teardown")
            }
            if (fatalReason == null && System.nanoTime() >= deadlineNs) {
                fail("total_timeout", "Recipe exceeded ${recipe.totalTimeoutMs} ms")
            }
        } finally {
            closeSocket("finalize", null, expandedIndex)
        }

        val reason = fatalReason ?: "completed"
        val status = when (reason) {
            "completed" -> "ok"
            "total_timeout", "rx_limit" -> "limit_reached"
            "cancelled" -> "cancelled"
            else -> "failed"
        }
        return DumlLabExecutionResult(
            status = status,
            terminalReason = reason,
            message = if (status == "ok") {
                "Recipe completed without automatic reconnect"
            } else {
                fatalMessage.ifBlank { reason }
            },
            durationMs = elapsedMs(startedNs),
            connectionsOpened = connectionsOpened,
            txBytes = totalTxBytes,
            rxBytes = totalRxBytes,
            steps = stepResults,
            frames = observations.frames,
            parserErrors = observations.parserErrors,
            droppedFrames = observations.droppedFrames,
            droppedParserErrors = observations.droppedParserErrors
        )
    }

    private data class ReadResult(
        val bytes: ByteArray,
        val status: String,
        val message: String,
        val eof: Boolean,
        val limitReached: Boolean
    )

    private fun readStep(
        connected: Socket,
        parser: WrappedDumlFrameParser,
        step: DumlLabStep.Read,
        phase: String,
        cycle: Int?,
        stepIndex: Int,
        recipeDeadlineNs: Long,
        recipeRxRemaining: Int,
        observations: DumlLabObservationCollector,
        cancellation: DumlLabCancellation
    ): ReadResult {
        if (recipeRxRemaining <= 0) {
            return ReadResult(ByteArray(0), "rx_limit", "Recipe receive limit reached", false, true)
        }
        val stepDeadlineNs = minOf(
            recipeDeadlineNs,
            System.nanoTime() + step.durationMs * 1_000_000L
        )
        val stepLimit = minOf(step.maxBytes, recipeRxRemaining)
        val captured = ByteArrayOutputStream(minOf(stepLimit, 4096))
        val buffer = ByteArray(4096)
        var status = "duration_elapsed"
        var message = ""
        var eof = false

        while (captured.size() < stepLimit) {
            cancellation.throwIfCancelled()
            val remainingMs = remainingMsOrZero(stepDeadlineNs)
            if (remainingMs <= 0) break
            connected.soTimeout = minOf(step.idleTimeoutMs, remainingMs)
            val allowed = minOf(buffer.size, stepLimit - captured.size())
            val count = try {
                connected.getInputStream().read(buffer, 0, allowed)
            } catch (_: SocketTimeoutException) {
                cancellation.throwIfCancelled()
                status = "idle_timeout"
                message = "No bytes for ${connected.soTimeout} ms"
                break
            }
            if (count < 0) {
                status = "eof"
                message = "Controller closed the socket"
                eof = true
                break
            }
            if (count == 0) continue
            val chunk = buffer.copyOf(count)
            captured.write(chunk)
            parser.feed(chunk).forEach {
                observations.record(
                    it,
                    phase,
                    cycle,
                    stepIndex,
                    "controller_to_client"
                )
            }
        }
        val limitReached = captured.size() >= recipeRxRemaining
        if (captured.size() >= stepLimit && !limitReached) {
            status = "step_max_bytes"
            message = "Read step reached $stepLimit bytes"
        }
        return ReadResult(captured.toByteArray(), status, message, eof, limitReached)
    }

    private fun sleepCancellable(durationMs: Int, cancellation: DumlLabCancellation) {
        val deadlineNs = System.nanoTime() + durationMs * 1_000_000L
        while (true) {
            cancellation.throwIfCancelled()
            val remainingMs = remainingMsOrZero(deadlineNs)
            if (remainingMs <= 0) return
            Thread.sleep(minOf(remainingMs, CANCELLATION_POLL_MS).toLong())
        }
    }

    private fun operationName(step: DumlLabStep): String = when (step) {
        is DumlLabStep.Connect -> "connect"
        is DumlLabStep.Close -> "close"
        is DumlLabStep.WriteDuml -> "write_duml"
        is DumlLabStep.WriteRaw -> "write_raw"
        is DumlLabStep.Read -> "read"
        is DumlLabStep.Sleep -> "sleep"
    }

    private fun remainingMs(deadlineNs: Long): Int =
        remainingMsOrZero(deadlineNs).coerceAtLeast(1)

    private fun remainingMsOrZero(deadlineNs: Long): Int {
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0) return 0
        return ceil(remainingNs / 1_000_000.0).toInt().coerceAtMost(Int.MAX_VALUE)
    }

    private fun elapsedMs(startedNs: Long): Long =
        ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0)

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CANCELLATION_POLL_MS = 50
    }
}

private class DumlLabObservationCollector {
    val frames = mutableListOf<DumlLabObservedFrame>()
    val parserErrors = mutableListOf<DumlLabObservedParserError>()
    var droppedFrames = 0
        private set
    var droppedParserErrors = 0
        private set

    fun record(
        result: DumlFrameParser.Result,
        phase: String,
        cycle: Int?,
        stepIndex: Int,
        direction: String
    ) {
        when (result) {
            is DumlFrameParser.Result.Frame -> {
                if (frames.size >= MAX_FRAMES) {
                    droppedFrames += 1
                } else {
                    frames += DumlLabObservedFrame(
                        phase = phase,
                        cycle = cycle,
                        stepIndex = stepIndex,
                        direction = direction,
                        frame = result.frame
                    )
                }
            }
            is DumlFrameParser.Result.Error -> {
                if (parserErrors.size >= MAX_PARSER_ERRORS) {
                    droppedParserErrors += 1
                } else {
                    parserErrors += DumlLabObservedParserError(
                        phase = phase,
                        cycle = cycle,
                        stepIndex = stepIndex,
                        direction = direction,
                        error = result.error
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_FRAMES = 2_048
        private const val MAX_PARSER_ERRORS = 512
    }
}

internal fun DumlLabRecipe.toJsonObject(): JSONObject = JSONObject()
    .put("schema", DumlLabRecipeParser.RECIPE_SCHEMA)
    .put("name", name)
    .put("host", "127.0.0.1")
    .put("port", port)
    .put("connect_timeout_ms", connectTimeoutMs)
    .put("total_timeout_ms", totalTimeoutMs)
    .put("max_rx_bytes", maxRxBytes)
    .put("setup", JSONArray(setupSteps.map(DumlLabStep::toJsonObject)))
    .put("cycle", JSONArray(cycleSteps.map(DumlLabStep::toJsonObject)))
    .put("cycle_count", cycleCount)
    .put("teardown", JSONArray(teardownSteps.map(DumlLabStep::toJsonObject)))

private fun DumlLabStep.toJsonObject(): JSONObject {
    val obj = JSONObject()
        .put(
            "op",
            when (this) {
                is DumlLabStep.Connect -> "connect"
                is DumlLabStep.Close -> "close"
                is DumlLabStep.WriteDuml -> "write_duml"
                is DumlLabStep.WriteRaw -> "write_raw"
                is DumlLabStep.Read -> "read"
                is DumlLabStep.Sleep -> "sleep"
            }
        )
    if (label.isNotEmpty()) obj.put("label", label)
    when (this) {
        is DumlLabStep.WriteDuml -> obj
            .put("sender", sender)
            .put("destination", destination)
            .put("cmd_type", cmdType)
            .put("cmd_set", cmdSet)
            .put("cmd_id", cmdId)
            .put("payload_b64", Base64.getEncoder().encodeToString(payload))
            .put("wire", wireFormat.wireName)
        is DumlLabStep.WriteRaw -> obj
            .put("bytes_b64", Base64.getEncoder().encodeToString(bytes))
        is DumlLabStep.Read -> obj
            .put("duration_ms", durationMs)
            .put("idle_timeout_ms", idleTimeoutMs)
            .put("max_bytes", maxBytes)
        is DumlLabStep.Sleep -> obj.put("duration_ms", durationMs)
        else -> Unit
    }
    return obj
}
