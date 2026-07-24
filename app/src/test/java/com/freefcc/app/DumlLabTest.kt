package com.freefcc.app

import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumlLabTest {
    @Test
    fun `parser accepts arbitrary loopback port and explicit socket strategy`() {
        val request = JSONObject()
            .put(
                "recipe",
                JSONObject()
                    .put("schema", "duml-lab/v1")
                    .put("name", "same socket wrapped")
                    .put("port", 49123)
                    .put("total_timeout_ms", 12_000)
                    .put("max_rx_bytes", 8_192)
                    .put(
                        "setup",
                        JSONArray()
                            .put(JSONObject().put("op", "connect"))
                            .put(
                                JSONObject()
                                    .put("op", "write_duml")
                                    .put("sender", 0x02)
                                    .put("destination", 0x06)
                                    .put("cmd_type", 0x00)
                                    .put("cmd_set", 0x00)
                                    .put("cmd_id", 0x01)
                                    .put("payload_b64", "")
                                    .put("wire", "wrapped_40007")
                            )
                    )
                    .put(
                        "cycle",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("op", "read")
                                    .put("duration_ms", 20)
                                    .put("idle_timeout_ms", 20)
                                    .put("max_bytes", 4_096)
                            )
                            .put(JSONObject().put("op", "sleep").put("duration_ms", 5))
                    )
                    .put("cycle_count", 20)
                    .put("teardown", JSONArray().put(JSONObject().put("op", "close")))
            )

        val parsed = DumlLabRecipeParser.parse(request)

        assertEquals("", parsed.error)
        assertNotNull(parsed.recipe)
        assertEquals(49123, parsed.recipe?.port)
        assertEquals(20, parsed.recipe?.cycleCount)
        val write = parsed.recipe?.setupSteps?.get(1) as DumlLabStep.WriteDuml
        assertEquals(DumlLabWireFormat.Wrapped40007, write.wireFormat)
    }

    @Test
    fun `parser rejects recipes whose expanded cycle exceeds step bound`() {
        val cycle = JSONArray()
            .put(JSONObject().put("op", "sleep").put("duration_ms", 0))
            .put(JSONObject().put("op", "sleep").put("duration_ms", 0))
            .put(JSONObject().put("op", "sleep").put("duration_ms", 0))
        val request = JSONObject()
            .put(
                "recipe",
                JSONObject()
                    .put("schema", "duml-lab/v1")
                    .put("name", "too many")
                    .put("port", 40007)
                    .put("cycle", cycle)
                    .put("cycle_count", 200)
            )

        val parsed = DumlLabRecipeParser.parse(request)

        assertTrue(parsed.recipe == null)
        assertTrue(parsed.error.contains("512"))
    }

    @Test
    fun `engine keeps one socket and captures wrapped validated frames`() {
        val server = ServerSocket(0)
        val serverError = AtomicReference<Throwable?>()
        val received = AtomicReference(ByteArray(0))
        val response = DumlBuilder().buildFrame(
            DumlFrame(
                sender = 0x06,
                dst = 0x02,
                cmdType = 0x80,
                cmdSet = 0x00,
                cmdId = 0x01,
                payload = "version".toByteArray()
            )
        )
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    val requestBytes = ByteArray(13)
                    var offset = 0
                    while (offset < requestBytes.size) {
                        val count = socket.getInputStream().read(
                            requestBytes,
                            offset,
                            requestBytes.size - offset
                        )
                        if (count < 0) break
                        offset += count
                    }
                    received.set(requestBytes.copyOf(offset))
                    socket.getOutputStream().apply {
                        write(SameSocketTelemetryKeepalive.wrap(response))
                        flush()
                    }
                }
            } catch (error: Throwable) {
                serverError.set(error)
            } finally {
                server.close()
            }
        }
        serverThread.start()

        val requestFrame = DumlLabStep.WriteDuml(
            label = "version",
            sender = 0x02,
            destination = 0x06,
            cmdType = 0x00,
            cmdSet = 0x00,
            cmdId = 0x01,
            payload = ByteArray(0),
            wireFormat = DumlLabWireFormat.Direct
        )
        val result = DumlLabEngine().execute(
            DumlLabRecipe(
                name = "engine integration",
                port = server.localPort,
                connectTimeoutMs = 1_000,
                totalTimeoutMs = 3_000,
                maxRxBytes = 4_096,
                setupSteps = listOf(DumlLabStep.Connect("connect"), requestFrame),
                cycleSteps = listOf(DumlLabStep.Read("read", 1_000, 100, 4_096)),
                cycleCount = 1,
                teardownSteps = listOf(DumlLabStep.Close("close"))
            )
        )
        serverThread.join(2_000)

        assertEquals(null, serverError.get())
        assertEquals("ok", result.status)
        assertEquals(1, result.connectionsOpened)
        assertEquals(13, result.txBytes)
        assertEquals(13, received.get().size)
        assertEquals(1, result.frames.size)
        assertEquals(0x00, result.frames.single().frame.cmdSet)
        assertEquals(0x01, result.frames.single().frame.cmdId)
        assertTrue(result.rxBytes > response.size)
    }

    @Test
    fun `engine does not reconnect after controller eof`() {
        val server = ServerSocket(0)
        val acceptedConnections = AtomicReference(0)
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    acceptedConnections.set(acceptedConnections.get() + 1)
                    socket.getOutputStream().flush()
                }
            }
        }
        serverThread.start()

        val result = DumlLabEngine().execute(
            DumlLabRecipe(
                name = "no reconnect",
                port = server.localPort,
                connectTimeoutMs = 1_000,
                totalTimeoutMs = 3_000,
                maxRxBytes = 128,
                setupSteps = listOf(
                    DumlLabStep.Connect("connect"),
                    DumlLabStep.Read("observe eof", 1_000, 100, 128),
                    DumlLabStep.Read("must remain closed", 100, 20, 128)
                ),
                cycleSteps = emptyList(),
                cycleCount = 0,
                teardownSteps = emptyList()
            )
        )
        serverThread.join(2_000)

        assertEquals(1, acceptedConnections.get())
        assertEquals(1, result.connectionsOpened)
        assertEquals("failed", result.status)
        assertEquals("not_connected", result.terminalReason)
        assertEquals("eof", result.steps[1].status)
        assertEquals("failed", result.steps[2].status)
    }

    @Test
    fun `cancellation closes active socket and stops recipe promptly`() {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val serverThread = Thread {
            server.use {
                it.accept().use {
                    accepted.countDown()
                    releaseServer.await(2, TimeUnit.SECONDS)
                }
            }
        }
        serverThread.start()

        val cancellation = DumlLabCancellation()
        val result = AtomicReference<DumlLabExecutionResult?>()
        val engineThread = Thread {
            result.set(
                DumlLabEngine().execute(
                    DumlLabRecipe(
                        name = "cancel active read",
                        port = server.localPort,
                        connectTimeoutMs = 1_000,
                        totalTimeoutMs = 10_000,
                        maxRxBytes = 128,
                        setupSteps = listOf(DumlLabStep.Connect("connect")),
                        cycleSteps = listOf(
                            DumlLabStep.Read("blocking read", 5_000, 1_000, 128)
                        ),
                        cycleCount = 1,
                        teardownSteps = emptyList()
                    ),
                    cancellation
                )
            )
        }
        engineThread.start()
        assertTrue(accepted.await(1, TimeUnit.SECONDS))

        cancellation.cancel()
        engineThread.join(1_000)
        releaseServer.countDown()
        serverThread.join(1_000)

        assertTrue(!engineThread.isAlive)
        assertEquals("cancelled", result.get()?.status)
        assertEquals("cancelled", result.get()?.terminalReason)
    }

    @Test
    fun `result event preserves recipe bytes and bench safety marker`() {
        val raw = byteArrayOf(0x55, 0x01, 0x02)
        val recipe = DumlLabRecipe(
            name = "raw",
            port = 40009,
            connectTimeoutMs = 1_000,
            totalTimeoutMs = 2_000,
            maxRxBytes = 128,
            setupSteps = listOf(DumlLabStep.WriteRaw("tx", raw)),
            cycleSteps = emptyList(),
            cycleCount = 0,
            teardownSteps = emptyList()
        )
        val line = TelemetryDumlLabResultEvent(
            sessionId = "session",
            requestId = "request",
            recipe = recipe,
            result = DumlLabExecutionResult(
                status = "ok",
                terminalReason = "completed",
                message = "done",
                durationMs = 5,
                connectionsOpened = 1,
                txBytes = raw.size,
                rxBytes = 0,
                steps = listOf(
                    DumlLabStepResult(
                        index = 1,
                        phase = "setup",
                        cycle = null,
                        op = "write_raw",
                        label = "tx",
                        status = "ok",
                        durationMs = 1,
                        bytes = raw
                    )
                ),
                frames = emptyList(),
                parserErrors = emptyList()
            )
        ).toJsonLine()
        val json = JSONObject(line)

        assertEquals("DUML_LAB_RESULT", json.getString("type"))
        assertEquals("bench_only_bounded_loopback", json.getString("safety"))
        assertEquals(
            Base64.getEncoder().encodeToString(raw),
            json.getJSONObject("recipe").getJSONArray("setup")
                .getJSONObject(0).getString("bytes_b64")
        )
    }
}
