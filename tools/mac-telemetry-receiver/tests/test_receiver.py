from __future__ import annotations

import base64
import io
import json
import socket
import struct
import tempfile
import threading
import unittest
from pathlib import Path

from rc2_telemetry_receiver.duml import (
    DumlFrameParser,
    ParsedFrame,
    WrappedDumlFrameParser,
    build_test_frame,
    crc16,
    crc8,
)
from rc2_telemetry_receiver.pcap import iter_pcap_tcp_payloads
from rc2_telemetry_receiver.publish8902 import (
    PublishParseError,
    PublishRecord,
    Rc2PublishStreamParser,
)
from rc2_telemetry_receiver.receiver import (
    RelayControlHub,
    SessionWriter,
    error_event,
    extract_serial_candidate,
    handle_payload_stream,
    raw_chunk_event,
    replay_session,
)
from rc2_telemetry_receiver.telemetry import analyze_session, decode_candidate


class DumlParserTest(unittest.TestCase):
    def test_split_frame_extracts_once(self) -> None:
        parser = DumlFrameParser()
        frame = build_test_frame(sequence=99)
        self.assertEqual([], parser.feed(frame[:5]))
        results = parser.feed(frame[5:])
        self.assertEqual(1, len(results))
        parsed = results[0]
        self.assertIsInstance(parsed, ParsedFrame)
        assert isinstance(parsed, ParsedFrame)
        self.assertEqual(99, parsed.sequence)
        self.assertEqual(0x03, parsed.cmd_set)
        self.assertEqual(0x43, parsed.cmd_id)

    def test_crc_values_match_known_frame(self) -> None:
        frame = build_test_frame()
        self.assertEqual(frame[3], crc8(frame[:3]))
        self.assertEqual(frame[-2] | (frame[-1] << 8), crc16(frame[:-2]))

    def test_wrapped_40007_frame_is_extracted(self) -> None:
        frame = build_test_frame(sender=0xEE, receiver=0x82, cmd_set=0x51, cmd_id=0x14)
        wrapped = b"\x55\xcc\x30\x75" + len(frame).to_bytes(4, "little") + frame
        parser = WrappedDumlFrameParser()

        self.assertEqual([], parser.feed(wrapped[:6]))
        results = parser.feed(wrapped[6:])

        self.assertEqual(1, len(results))
        self.assertIsInstance(results[0], ParsedFrame)

    def test_truncated_wrapped_frame_is_reported_at_end_of_stream(self) -> None:
        frame = build_test_frame()
        wrapped = b"\x55\xcc\x30\x75" + len(frame).to_bytes(4, "little") + frame
        parser = WrappedDumlFrameParser()

        self.assertEqual([], parser.feed(wrapped[:-2]))
        results = parser.finish()

        self.assertEqual("truncated_frame", results[0].reason)


class Publish8902ParserTest(unittest.TestCase):
    def test_split_record_extracts_clock_and_marker(self) -> None:
        raw = _publish_record(0xF5, 71, 0x1FFFE)
        parser = Rc2PublishStreamParser()

        self.assertEqual([], parser.feed(raw[:6]))
        result = parser.feed(raw[6:])[0]

        self.assertIsInstance(result, PublishRecord)
        assert isinstance(result, PublishRecord)
        self.assertEqual(0xF5, result.marker)
        self.assertEqual(71, result.length)
        self.assertEqual(0x1FFFE, result.source_clock_ms)

    def test_invalid_length_resyncs(self) -> None:
        bad = b"\xf5\x64\x04\x00\x00\x00\x00\x00"
        results = Rc2PublishStreamParser().feed(
            bad + _publish_record(0xF8, 16, 22)
        )

        self.assertTrue(
            any(
                isinstance(result, PublishParseError)
                and result.reason == "publish_invalid_length"
                for result in results
            )
        )
        self.assertTrue(
            any(
                isinstance(result, PublishRecord)
                and result.source_clock_ms == 22
                for result in results
            )
        )


class SessionWriterTest(unittest.TestCase):
    def test_outbound_primer_is_evidence_not_inbound_stream(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                raw_chunk_event(
                    session_id="primed-session",
                    seq=1,
                    source="bench_wrapped_primed",
                    direction="client_to_controller",
                    port=40007,
                    data=b"primer",
                )
            )

            assert writer.session_dir is not None
            self.assertFalse((writer.session_dir / "raw-stream.bin").exists())
            self.assertEqual("unknown", writer.stats.capture_state)
            self.assertEqual(
                b"primer",
                next((writer.session_dir / "raw").glob("*client_to_controller.bin")).read_bytes(),
            )

    def test_8902_raw_stream_produces_records_readiness_and_one_hz_sample(self) -> None:
        record = bytearray(_publish_record(0xF5, 71, 1234))
        struct.pack_into("<I", record, 48, 0x00815100)
        record[52] = 18
        record[55] = 5
        record[61:65] = b"\x0f\x8a\x20\x7f"
        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "publish",
                    "source_mode": "rc2_publish_8902",
                }
            )
            writer.handle_event(
                {
                    "type": "SOURCE_STATUS",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "publish",
                    "status": "active",
                    "elapsed_realtime_ns": 1_000_000_000,
                }
            )
            raw_event = {
                **_raw_event(bytes(record), port=8902),
                "session_id": "publish",
                "source": "rc2_publish_8902",
                "elapsed_realtime_ns": 1_100_000_000,
            }
            writer.handle_event(raw_event)
            writer.handle_event(
                {
                    "type": "TELEMETRY_TICK",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "publish",
                    "source": "rc2_publish_8902",
                    "source_status": "active",
                    "source_clock_ms": 1234,
                    "elapsed_realtime_ns": 2_000_000_000,
                    "wall_time_utc": "2026-07-23T22:00:00.000Z",
                }
            )

            assert writer.session_dir is not None
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())
            events = [
                json.loads(line)
                for line in (writer.session_dir / "session.ndjson").read_text().splitlines()
            ]

            self.assertEqual(1, summary["stream_records"])
            self.assertEqual(1, summary["georeference_samples"])
            self.assertEqual(18, summary["georef"]["gnss_readiness"]["satellites"])
            self.assertTrue(any(event["type"] == "GEOREFERENCE_SAMPLE" for event in events))
            self.assertEqual(bytes(record), (writer.session_dir / "raw-stream.bin").read_bytes())

    def test_replay_preserves_publish_stream_bytes(self) -> None:
        record = _publish_record(0xF6, 32, 44)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.ndjson"
            events = [
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "replay",
                    "source_mode": "rc2_publish_8902",
                },
                {
                    **_raw_event(record, port=8902),
                    "session_id": "replay",
                    "source": "rc2_publish_8902",
                },
            ]
            source.write_text(
                "".join(json.dumps(event) + "\n" for event in events),
                encoding="utf-8",
            )

            writer = replay_session(source, root / "replayed")

            assert writer.session_dir is not None
            self.assertEqual(
                record,
                (writer.session_dir / "raw-stream.bin").read_bytes(),
            )

    def test_payload_stream_writes_ndjson_summary_and_raw_bytes(self) -> None:
        frame = build_test_frame(payload=b"\x10\x20")
        with tempfile.TemporaryDirectory() as tmp:
            writer = handle_payload_stream(
                payloads=[frame],
                out_root=Path(tmp),
                session_id="test-session",
                source="unit",
            )
            assert writer.session_dir is not None
            ndjson = writer.session_dir / "session.ndjson"
            lines = [json.loads(line) for line in ndjson.read_text().splitlines()]
            self.assertEqual("HELLO", lines[0]["type"])
            self.assertTrue(any(line["type"] == "RAW_CHUNK" for line in lines))
            self.assertTrue(any(line["type"] == "DUML_FRAME" for line in lines))
            raw_line = next(line for line in lines if line["type"] == "RAW_CHUNK")
            self.assertEqual(frame, base64.b64decode(raw_line["bytes_b64"]))
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())
            self.assertEqual(1, summary["raw_chunks"])
            self.assertEqual(1, summary["duml_frames"])
            self.assertIsNone(summary["georef"]["position"]["lat_deg"])

    def test_capture_gap_is_not_counted_as_parser_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v1",
                    "session_id": "gap-session",
                }
            )
            writer.handle_event(
                error_event(
                    session_id="gap-session",
                    reason="capture_gap",
                    detail="socket closed; reconnecting",
                )
            )

            assert writer.session_dir is not None
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())
            self.assertEqual(1, summary["capture_gaps"])
            self.assertEqual(0, summary["parser_errors"])
            self.assertEqual("unavailable", summary["capture_state"])
            self.assertEqual("unavailable", summary["georef"]["quality"]["position"])

    def test_fc_osd_candidate_exposes_operator_correlated_position_and_heading(self) -> None:
        payload = bytearray(48)
        struct.pack_into("<dd", payload, 0, 0.185, 1.047)
        struct.pack_into("<h", payload, 16, 123)
        struct.pack_into("<hhh", payload, 24, 55, -22, 900)
        event = _frame_event(cmd_set=0x03, cmd_id=0x43, payload=bytes(payload))

        candidate = decode_candidate(event)

        assert candidate is not None
        self.assertAlmostEqual(59.98868115019719, candidate["position"]["lat_deg"])
        self.assertAlmostEqual(10.599719210771538, candidate["position"]["lon_deg"])
        self.assertIsNone(candidate["position"]["alt_m"])
        self.assertEqual(5.5, candidate["attitude"]["pitch_deg"])
        self.assertEqual(-2.2, candidate["attitude"]["roll_deg"])
        self.assertEqual(90.0, candidate["attitude"]["yaw_deg"])
        self.assertEqual(90.0, candidate["heading"]["aircraft_deg"])
        self.assertEqual(12.3, candidate["raw"]["relative_height_m_candidate"])
        self.assertEqual("probable", candidate["quality"]["position"])
        self.assertEqual("probable", candidate["quality"]["attitude"])
        self.assertEqual("unknown", candidate["quality"]["altitude"])

    def test_gps_glns_candidate_decodes_hmsl_millimetres(self) -> None:
        payload = bytearray(34)
        struct.pack_into("<iii", payload, 0, 103_931_366, 591_011_495, 7_420)
        struct.pack_into("<ffff", payload, 12, 0.0, 0.0, 0.0, 0.8)
        struct.pack_into("<HH", payload, 28, 18, 99)
        payload[33] = 1

        candidate = decode_candidate(_frame_event(cmd_set=0x03, cmd_id=0x57, payload=bytes(payload)))

        assert candidate is not None
        self.assertAlmostEqual(59.1011495, candidate["position"]["lat_deg"])
        self.assertAlmostEqual(10.3931366, candidate["position"]["lon_deg"])
        self.assertAlmostEqual(7.42, candidate["position"]["alt_m"])
        self.assertEqual("mean_sea_level_geoid", candidate["altitude"]["reference"])
        self.assertEqual("candidate", candidate["quality"]["altitude"])
        self.assertEqual(18, candidate["raw"]["satellites"])
        self.assertTrue(candidate["raw"]["home_point_recorded_candidate"])

    def test_gps_glns_duml_result_uses_the_same_decoder(self) -> None:
        payload = struct.pack("<iii", 103_931_366, 591_011_495, 7_420)
        event = {
            "type": "DUML_RESULT",
            "schema": "dji-rc2-telemetry/v1",
            "session_id": "gps-result",
            "status": "ok",
            "request": {"cmd_set": 0x03, "cmd_id": 0x57},
            "response": {"payload_b64": base64.b64encode(payload).decode("ascii")},
        }

        candidate = decode_candidate(event)

        assert candidate is not None
        self.assertAlmostEqual(7.42, candidate["position"]["alt_m"])
        self.assertEqual("candidate", candidate["quality"]["altitude"])

    def test_gimbal_candidate_uses_tenth_degree_layout(self) -> None:
        payload = struct.pack("<hhh", -600, 10, 25) + bytes(6)
        candidate = decode_candidate(_frame_event(cmd_set=0x04, cmd_id=0x05, payload=payload))

        assert candidate is not None
        self.assertEqual(-60.0, candidate["gimbal"]["pitch_deg"])
        self.assertEqual(1.0, candidate["gimbal"]["roll_deg"])
        self.assertEqual(2.5, candidate["gimbal"]["yaw_deg"])
        self.assertEqual("candidate", candidate["quality"]["gimbal"])

    def test_aircraft_serial_is_only_extracted_from_valid_51_14_route(self) -> None:
        payload = b"\x00WA150\x001581F6ABCDEF1234\x00FA1234567890ABCD\x00"
        event = _frame_event(
            cmd_set=0x51,
            cmd_id=0x14,
            payload=payload,
            sender=0xEE,
            receiver=0x82,
        )

        candidate = decode_candidate(event)

        assert candidate is not None
        self.assertEqual("1581F6ABCDEF1234", candidate["identity"]["aircraft_serial"])
        self.assertEqual("FA1234567890ABCD", candidate["identity"]["serial_suffix"])
        self.assertEqual("WA150", candidate["identity"]["model_code"])
        self.assertEqual("candidate", candidate["quality"]["identity"])

        wrong_family = {**event, "cmd_id": 0x13}
        self.assertIsNone(decode_candidate(wrong_family))

    def test_home_point_state_remains_candidate_and_position_null(self) -> None:
        payload = bytearray(102)
        struct.pack_into("<ddf", payload, 0, 0.1814, 1.0315, 323.843)
        struct.pack_into("<H", payload, 20, 0x47)

        candidate = decode_candidate(_frame_event(cmd_set=0x03, cmd_id=0x44, payload=bytes(payload)))

        assert candidate is not None
        self.assertIsNone(candidate["position"]["lat_deg"])
        self.assertTrue(candidate["raw"]["home_point_recorded_candidate"])
        self.assertEqual("rejected", candidate["quality"]["altitude"])
        self.assertIsNone(candidate["home"]["alt_m"])
        self.assertAlmostEqual(323.843, candidate["raw"]["home_altitude_raw_f32"], places=3)

    def test_analyzer_does_not_classify_rc_channels_as_camera_pitch(self) -> None:
        rc_payload = b"\x00" + struct.pack("<8H", 256, 0, 1024, 1024, 1024, 1024, 1684, 1024)
        with tempfile.TemporaryDirectory() as tmp:
            session = Path(tmp) / "session.ndjson"
            session.write_text(json.dumps(_frame_event(cmd_set=0x06, cmd_id=0xAE, payload=rc_payload)) + "\n")

            report = analyze_session(session)

            self.assertEqual(["03/43", "03/44", "03/57", "04/05"], report["missing_georeference_families"])
            self.assertEqual({}, report["candidate_frames"])
            self.assertEqual(
                "candidate_rc_channels_not_camera_attitude",
                report["rc_input_candidate_06_AE"]["classification"],
            )
            self.assertEqual(1684, report["rc_input_candidate_06_AE"]["ranges"][6]["max"])

    def test_probe_result_updates_summary_as_candidate_only(self) -> None:
        event = {
            "type": "PROBE_RESULT",
            "schema": "dji-rc2-telemetry/v1",
            "session_id": "probe-session",
            "request_id": "request-1",
            "status": "ok",
            "position": {"lat_deg": None, "lon_deg": None, "alt_m": None},
            "attitude": {"roll_deg": 1.0, "pitch_deg": -4.5, "yaw_deg": 90.0},
            "quality": {"position": "unknown", "attitude": "candidate", "gimbal": "unknown"},
            "raw": {"message_family": "03/43"},
        }
        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "TELEMETRY_CANDIDATE",
                    "schema": "dji-rc2-telemetry/v1",
                    "session_id": "probe-session",
                    "quality": {"position": "unavailable", "attitude": "unavailable", "gimbal": "unavailable"},
                    "raw": {"message_family": None},
                }
            )
            writer.handle_event(event)
            assert writer.session_dir is not None
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())

            self.assertEqual(1, summary["probe_results"])
            self.assertEqual("request-1", summary["last_probe"]["request_id"])
            self.assertEqual("active_probe", summary["capture_state"])
            self.assertIsNone(summary["georef"]["position"]["lat_deg"])
            self.assertEqual(-4.5, summary["georef"]["attitude"]["pitch_deg"])
            self.assertEqual("candidate", summary["georef"]["quality"]["attitude"])


class RelayControlHubTest(unittest.TestCase):
    def test_stale_disconnect_does_not_detach_newer_connection(self) -> None:
        stale_app, stale_mac = socket.socketpair()
        current_app, current_mac = socket.socketpair()
        hub = RelayControlHub()
        hub.attach(stale_app)
        hub.attach(current_app)
        hub.detach(stale_app)
        received: dict[str, object] = {}

        def respond() -> None:
            with current_mac.makefile("r", encoding="utf-8") as reader:
                command = json.loads(reader.readline())
                received.update(command)
                hub.handle_event(
                    {
                        "type": "PROBE_RESULT",
                        "request_id": command["request_id"],
                        "status": "ok",
                    }
                )

        thread = threading.Thread(target=respond)
        thread.start()
        try:
            result = hub.request_probe(timeout=1.0)
        finally:
            thread.join(timeout=1.0)
            stale_app.close()
            stale_mac.close()
            current_app.close()
            current_mac.close()

        self.assertEqual("PROBE_REQUEST", received["type"])
        self.assertEqual("ok", result["status"])

    def test_allowlisted_probe_is_correlated_with_result(self) -> None:
        app_side, mac_side = socket.socketpair()
        hub = RelayControlHub()
        hub.attach(app_side)
        received: dict[str, object] = {}

        def respond() -> None:
            with mac_side.makefile("r", encoding="utf-8") as reader:
                command = json.loads(reader.readline())
                received.update(command)
                hub.handle_event(
                    {
                        "type": "PROBE_RESULT",
                        "request_id": command["request_id"],
                        "status": "ok",
                    }
                )

        thread = threading.Thread(target=respond)
        thread.start()
        try:
            result = hub.request_probe(timeout=1.0)
        finally:
            thread.join(timeout=1.0)
            app_side.close()
            mac_side.close()

        self.assertEqual("PROBE_REQUEST", received["type"])
        self.assertEqual("fc_osd_03_43_once", received["probe"])
        self.assertEqual("ok", result["status"])

    def test_serial_candidate_prefers_full_aircraft_serial(self) -> None:
        payload = b"\x00WA123\x001581F6ABCDEF1234\x00"
        self.assertEqual("1581F6ABCDEF1234", extract_serial_candidate(payload))

    def test_missing_serial_candidate_stays_unknown(self) -> None:
        self.assertIsNone(extract_serial_candidate(b"\x00\x01\x02"))

    def test_general_duml_request_is_forwarded_without_shape_changes(self) -> None:
        app_side, mac_side = socket.socketpair()
        hub = RelayControlHub()
        hub.attach(app_side)
        received: dict[str, object] = {}

        def respond() -> None:
            with mac_side.makefile("r", encoding="utf-8") as reader:
                command = json.loads(reader.readline())
                received.update(command)
                hub.handle_event(
                    {
                        "type": "DUML_RESULT",
                        "request_id": command["request_id"],
                        "status": "ok",
                    }
                )

        thread = threading.Thread(target=respond)
        thread.start()
        try:
            result = hub.request(
                {
                    "type": "DUML_REQUEST",
                    "sender": 0x82,
                    "destination": 3,
                    "cmd_type": 0x40,
                    "cmd_set": 3,
                    "cmd_id": 0x43,
                    "payload_b64": "",
                    "expect_response": True,
                    "read_window_ms": 1500,
                    "port": 40009,
                },
                timeout=1.0,
            )
        finally:
            thread.join(timeout=1.0)
            app_side.close()
            mac_side.close()

        self.assertEqual("DUML_REQUEST", received["type"])
        self.assertEqual(0x82, received["sender"])
        self.assertEqual(0x43, received["cmd_id"])
        self.assertEqual("ok", result["status"])


class PcapParserTest(unittest.TestCase):
    def test_ipv4_tcp_payload_is_extracted_from_raw_pcap(self) -> None:
        payload = build_test_frame()
        tcp = _tcp_packet(src_port=40009, dst_port=50000, payload=payload)
        ip = _ipv4_packet(tcp)
        pcap = _pcap_raw([ip])
        extracted = list(iter_pcap_tcp_payloads(io.BufferedReader(io.BytesIO(pcap)), port=40009))
        self.assertEqual([payload], extracted)


def _tcp_packet(src_port: int, dst_port: int, payload: bytes) -> bytes:
    header = bytearray(20)
    header[0:2] = src_port.to_bytes(2, "big")
    header[2:4] = dst_port.to_bytes(2, "big")
    header[12] = 5 << 4
    return bytes(header) + payload


def _frame_event(
    *,
    cmd_set: int,
    cmd_id: int,
    payload: bytes,
    sender: int = 0x03,
    receiver: int = 0x43,
) -> dict[str, object]:
    frame = build_test_frame(
        sender=sender,
        receiver=receiver,
        cmd_set=cmd_set,
        cmd_id=cmd_id,
        payload=payload,
    )
    return {
        "type": "DUML_FRAME",
        "schema": "dji-rc2-telemetry/v1",
        "session_id": "candidate-session",
        "wall_time_utc": "2026-07-22T21:00:00.000Z",
        "source": "unit",
        "sender": sender,
        "receiver": receiver,
        "cmd_set": cmd_set,
        "cmd_id": cmd_id,
        "validation_status": "valid",
        "raw_frame_b64": base64.b64encode(frame).decode("ascii"),
    }


def _raw_event(payload: bytes, *, port: int) -> dict[str, object]:
    return {
        "type": "RAW_CHUNK",
        "schema": "dji-rc2-telemetry/v2",
        "session_id": "raw-session",
        "seq": 1,
        "wall_time_utc": "2026-07-23T22:00:00.000Z",
        "elapsed_realtime_ns": 0,
        "source": "unit",
        "direction": "controller_to_client",
        "port": port,
        "bytes_b64": base64.b64encode(payload).decode("ascii"),
        "crc32": "00000000",
    }


def _publish_record(marker: int, length: int, source_clock_ms: int) -> bytes:
    record = bytearray(length)
    record[0] = marker
    record[1] = 0x64
    struct.pack_into("<H", record, 2, length)
    struct.pack_into("<I", record, 4, source_clock_ms)
    return bytes(record)


def _ipv4_packet(tcp: bytes) -> bytes:
    total_length = 20 + len(tcp)
    header = bytearray(20)
    header[0] = 0x45
    header[2:4] = total_length.to_bytes(2, "big")
    header[8] = 64
    header[9] = 6
    header[12:16] = b"\x7f\x00\x00\x01"
    header[16:20] = b"\x7f\x00\x00\x01"
    return bytes(header) + tcp


def _pcap_raw(packets: list[bytes]) -> bytes:
    out = bytearray()
    out += struct.pack("<IHHiiii", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 101)
    for packet in packets:
        out += struct.pack("<IIII", 0, 0, len(packet), len(packet))
        out += packet
    return bytes(out)


if __name__ == "__main__":
    unittest.main()
