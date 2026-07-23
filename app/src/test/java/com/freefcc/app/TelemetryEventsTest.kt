package com.freefcc.app

import org.junit.Assert.assertEquals
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
            controllerIdentity = ControllerIdentity("RC123H103", "Build.getSerial")
        ).toJsonLine()

        assertTrue(line.contains("\"source_mode\":\"bench_wrapped_socket\""))
        assertTrue(line.contains("\"source_port\":40007"))
        assertTrue(line.contains("\"controller_serial\":\"RC123H103\""))
        assertTrue(line.contains("\"controller_serial_source\":\"Build.getSerial\""))
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
    fun `hello records same socket primer policy`() {
        val line = TelemetryHelloEvent(
            sessionId = "session-3",
            config = TelemetryConfig(
                host = "192.168.5.99",
                port = 8765,
                sourceId = "neo2",
                capturePort = 40007,
                primerEnabled = true
            ),
            appVersion = "test",
            controllerModel = "rc331"
        ).toJsonLine()

        assertTrue(line.contains("\"source_mode\":\"bench_wrapped_primed\""))
        assertTrue(line.contains("\"source_policy\":\"same_socket_1hz_03_44_no_reconnect\""))
        assertTrue(line.contains("\"primer_enabled\":true"))
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
