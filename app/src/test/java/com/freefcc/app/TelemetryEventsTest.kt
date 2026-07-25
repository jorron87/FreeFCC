package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryEventsTest {

    @Test
    fun `raw chunk json preserves bytes as base64 and crc32`() {
        val event = RawChunkEvent(
            sessionId = "session-1",
            seq = 7,
            elapsedRealtimeNs = 123,
            source = "bench_active_socket",
            direction = "controller_to_client",
            port = 40009,
            bytes = byteArrayOf(0x55, 0x0d, 0x04, 0x01)
        )

        val line = event.toJsonLine()

        assertTrue(line.contains("\"type\":\"RAW_CHUNK\""))
        assertTrue(line.contains("\"schema\":\"dji-rc2-telemetry/v2\""))
        assertTrue(line.contains("\"bytes_b64\":\"VQ0EAQ==\""))
        assertTrue(line.contains("\"crc32\":\"${crc32Hex(byteArrayOf(0x55, 0x0d, 0x04, 0x01))}\""))
    }

    @Test
    fun `candidate event leaves position unknown`() {
        val line = TelemetryCandidateEvent(
            sessionId = "session-1",
            sourceId = "rc2-bench",
            messageFamily = "03/43",
            quality = "candidate"
        ).toJsonLine()

        assertTrue(line.contains("\"lat_deg\":null"))
        assertTrue(line.contains("\"lon_deg\":null"))
        assertTrue(line.contains("\"position\":\"unknown\""))
        assertTrue(line.contains("\"attitude\":\"candidate\""))
    }

    @Test
    fun `unavailable event clears every georeference field`() {
        val line = TelemetryUnavailableEvent(
            sessionId = "session-1",
            sourceId = "rc2-bench",
            reason = "capture gap"
        ).toJsonLine()

        assertTrue(line.contains("\"lat_deg\":null"))
        assertTrue(line.contains("\"position\":\"unavailable\""))
        assertTrue(line.contains("\"attitude\":\"unavailable\""))
        assertTrue(line.contains("\"gimbal\":\"unavailable\""))
        assertTrue(line.contains("\"unavailable_reason\":\"capture gap\""))
    }

    @Test
    fun `crc32 uses lower-case eight character hex`() {
        assertEquals("b63cfbcd", crc32Hex(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `hello records wrapped source port`() {
        val line = TelemetryHelloEvent(
            sessionId = "session-1",
            config = TelemetryConfig(
                host = "192.168.5.99",
                port = 8765,
                sourceId = "neo2",
                capturePort = 40007
            ),
            appVersion = "test",
            controllerModel = "rc331",
            controllerIdentity = ControllerIdentity("RC123H103", "Build.getSerial"),
            elapsedRealtimeNs = 123_000_000
        ).toJsonLine()

        assertTrue(line.contains("\"source_mode\":\"bench_wrapped_socket\""))
        assertTrue(line.contains("\"source_port\":40007"))
        assertTrue(line.contains("\"controller_serial\":\"RC123H103\""))
        assertTrue(line.contains("\"controller_serial_source\":\"Build.getSerial\""))
        assertTrue(line.contains("\"elapsed_realtime_ns\":123000000"))
    }

    @Test
    fun `hello records persistent passive 8902 policy`() {
        val line = TelemetryHelloEvent(
            sessionId = "session-2",
            config = TelemetryConfig(
                host = "192.168.5.99",
                port = 8765,
                sourceId = "neo2"
            ),
            appVersion = "test",
            controllerModel = "rc331"
        ).toJsonLine()

        assertTrue(line.contains("\"schema\":\"dji-rc2-telemetry/v2\""))
        assertTrue(line.contains("\"source_mode\":\"rc2_publish_8902\""))
        assertTrue(line.contains("\"source_port\":8902"))
        assertTrue(line.contains("\"source_policy\":\"persistent_read_only\""))
        assertTrue(line.contains("\"sample_interval_ms\":1000"))
    }

    @Test
    fun `hello records one command per connection snapshot policy`() {
        val line = TelemetryHelloEvent(
            sessionId = "session-3",
            config = TelemetryConfig(
                host = "192.168.5.99",
                port = 8765,
                sourceId = "neo2",
                capturePort = 40007,
                streamKeepaliveEnabled = true
            ),
            appVersion = "test",
            controllerModel = "rc331"
        ).toJsonLine()

        assertTrue(line.contains("\"source_mode\":\"bench_wrapped_snapshot_1hz\""))
        assertTrue(line.contains("\"source_policy\":\"one_00_01_per_connection_1hz\""))
        assertTrue(line.contains("\"stream_keepalive_enabled\":true"))
        assertTrue(line.contains("\"snapshot_mode_enabled\":true"))
    }

    @Test
    fun `hello records explicitly armed control only lab policy`() {
        val line = TelemetryHelloEvent(
            sessionId = "session-lab",
            config = TelemetryConfig(
                host = "192.168.5.99",
                port = 8765,
                sourceId = "neo2",
                capturePort = CONTROL_ONLY_PORT
            ),
            appVersion = "test",
            controllerModel = "rc331"
        ).toJsonLine()

        assertTrue(line.contains("\"source_mode\":\"duml_lab_control_only\""))
        assertTrue(line.contains("\"source_port\":0"))
        assertTrue(line.contains("\"source_policy\":\"remote_bounded_recipes_no_capture\""))
    }

    @Test
    fun `hello records read only DJI Fly flight log policy`() {
        val line = TelemetryHelloEvent(
            sessionId = "session-log",
            config = TelemetryConfig(
                host = "192.168.5.99",
                port = 8765,
                sourceId = "neo2",
                capturePort = FLIGHT_LOG_SOURCE_PORT
            ),
            appVersion = "test",
            controllerModel = "rc331"
        ).toJsonLine()

        assertTrue(line.contains("\"source_mode\":\"dji_fly_flight_log_tail\""))
        assertTrue(line.contains("\"source_port\":-1"))
        assertTrue(line.contains("\"source_policy\":\"read_only_growing_file_offset_chunks\""))
    }

    @Test
    fun `flight log chunk preserves file offset and bytes`() {
        val line = FlightLogChunkEvent(
            sessionId = "session-log",
            seq = 3,
            elapsedRealtimeNs = 9,
            logName = "FlightRecord_test.txt",
            offset = 1024,
            fileSize = 1030,
            newFile = false,
            bytes = byteArrayOf(1, 2, 3)
        ).toJsonLine()

        assertTrue(line.contains("\"type\":\"FLIGHT_LOG_CHUNK\""))
        assertTrue(line.contains("\"offset\":1024"))
        assertTrue(line.contains("\"file_size\":1030"))
        assertTrue(line.contains("\"bytes_b64\":\"AQID\""))
    }

    @Test
    fun `snapshot stats preserve command and connection evidence`() {
        val line = SnapshotStatsEvent(
            sessionId = "session-4",
            elapsedRealtimeNs = 5_000_000_000,
            source = "bench_wrapped_snapshot_1hz",
            port = 40007,
            attemptCount = 17,
            successCount = 16,
            failureCount = 1,
            rxBytes = 4116,
            frameCount = 92,
            intervalMs = 1000
        ).toJsonLine()

        assertTrue(line.contains("\"type\":\"SNAPSHOT_STATS\""))
        assertTrue(line.contains("\"attempt_count\":17"))
        assertTrue(line.contains("\"success_count\":16"))
        assertTrue(line.contains("\"connection_policy\":\"one_command_per_connection\""))
        assertTrue(line.contains("\"command_family\":\"00/01\""))
        assertTrue(line.contains("\"route\":\"02>06\""))
    }

    @Test
    fun `DJI Fly UI snapshot preserves bounded labels without telemetry promotion`() {
        val line = DjiFlyUiSnapshotEvent(
            sessionId = "session-ui",
            snapshot = DjiFlyUiSnapshot(
                sequence = 3,
                capturedAtUtc = "2026-07-25T20:00:00Z",
                elapsedRealtimeNs = 4_000_000_000,
                eventType = "TYPE_WINDOW_CONTENT_CHANGED",
                labels = listOf("H 12.3 m", "Gimbal -90"),
                visitedNodeCount = 42,
                truncated = false
            )
        ).toJsonLine()

        assertTrue(line.contains("\"type\":\"DJI_FLY_UI_SNAPSHOT\""))
        assertTrue(line.contains("\"captured_at\":\"2026-07-25T20:00:00Z\""))
        assertTrue(line.contains("\"labels\":[\"H 12.3 m\",\"Gimbal -90\"]"))
        assertTrue(line.contains("\"quality\":\"ui_observation\""))
        assertTrue(line.contains("\"semantics\":\"unparsed\""))
        assertTrue(!line.contains("\"position\""))
    }

    @Test
    fun `telemetry tick preserves source freshness`() {
        val line = TelemetryTickEvent(
            sessionId = "session-2",
            source = "rc2_publish_8902",
            port = 8902,
            elapsedRealtimeNs = 8_000_000_000,
            sourceStatus = "active",
            lastByteAgeMs = 12,
            sourceClockMs = 77
        ).toJsonLine()

        assertTrue(line.contains("\"type\":\"TELEMETRY_TICK\""))
        assertTrue(line.contains("\"last_byte_age_ms\":12"))
        assertTrue(line.contains("\"source_clock_ms\":77"))
    }

    @Test
    fun `relay serialization freezes capture wall time before reconnect backlog`() {
        val event = TelemetryTickEvent(
            sessionId = "session-queue",
            source = "bench_wrapped_snapshot_1hz",
            port = 40007,
            elapsedRealtimeNs = 9_000_000_000,
            sourceStatus = "active",
            lastByteAgeMs = 4,
            sourceClockMs = null
        )

        val serialized = serializeTelemetryEvent(event)
        Thread.sleep(20)
        val laterSerialization = event.toJsonLine()

        assertNotEquals(laterSerialization, serialized.line)
        assertEquals(
            serialized.line.toByteArray(Charsets.UTF_8).size.toLong() + 1L,
            serialized.bytes
        )
    }

    @Test
    fun `probe result preserves request correlation and candidate quality`() {
        val payload = ByteArray(30)
        val result = TelemetryProbeDecoder.decode(payload)
        val line = TelemetryProbeResultEvent(
            sessionId = "session-1",
            requestId = "request-7",
            status = "ok",
            message = "candidate",
            payload = payload,
            result = result,
            exchange = DumlExchangeResult(
                terminalReason = "matched",
                observations = listOf(DumlResponseObservation(byteArrayOf(0x55), "matched"))
            )
        ).toJsonLine()

        assertTrue(line.contains("\"type\":\"PROBE_RESULT\""))
        assertTrue(line.contains("\"request_id\":\"request-7\""))
        assertTrue(line.contains("\"probe\":\"fc_osd_03_43_once\""))
        assertTrue(line.contains("\"position\":{\"lat_deg\":null,\"lon_deg\":null"))
        assertTrue(line.contains("\"attitude\":\"candidate\""))
        assertTrue(line.contains("\"terminal_reason\":\"matched\""))
        assertTrue(line.contains("\"validation\":\"matched\""))
    }

    @Test
    fun `rejected general command remains a duml result`() {
        val line = TelemetryDumlRejectedEvent(
            sessionId = "session-1",
            requestId = "request-8",
            message = "invalid"
        ).toJsonLine()

        assertTrue(line.contains("\"type\":\"DUML_RESULT\""))
        assertTrue(line.contains("\"request_id\":\"request-8\""))
        assertTrue(line.contains("\"status\":\"rejected\""))
        assertTrue(!line.contains("fc_osd_03_43_once"))
    }
}
