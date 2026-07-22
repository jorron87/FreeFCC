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

from rc2_telemetry_receiver.duml import DumlFrameParser, ParsedFrame, build_test_frame, crc16, crc8
from rc2_telemetry_receiver.pcap import iter_pcap_tcp_payloads
from rc2_telemetry_receiver.receiver import RelayControlHub, SessionWriter, error_event, handle_payload_stream
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


class SessionWriterTest(unittest.TestCase):
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

    def test_fc_osd_candidate_keeps_position_null(self) -> None:
        payload = bytearray(48)
        struct.pack_into("<dd", payload, 0, 0.185, 1.047)
        struct.pack_into("<h", payload, 16, 123)
        struct.pack_into("<hhh", payload, 24, 55, -22, 900)
        event = _frame_event(cmd_set=0x03, cmd_id=0x43, payload=bytes(payload))

        candidate = decode_candidate(event)

        assert candidate is not None
        self.assertIsNone(candidate["position"]["lat_deg"])
        self.assertEqual(5.5, candidate["attitude"]["pitch_deg"])
        self.assertEqual(-2.2, candidate["attitude"]["roll_deg"])
        self.assertEqual(90.0, candidate["attitude"]["yaw_deg"])
        self.assertEqual(12.3, candidate["raw"]["relative_height_m_candidate"])
        self.assertEqual("candidate", candidate["quality"]["attitude"])

    def test_gimbal_candidate_uses_tenth_degree_layout(self) -> None:
        payload = struct.pack("<hhh", -600, 10, 25) + bytes(6)
        candidate = decode_candidate(_frame_event(cmd_set=0x04, cmd_id=0x05, payload=payload))

        assert candidate is not None
        self.assertEqual(-60.0, candidate["gimbal"]["pitch_deg"])
        self.assertEqual(1.0, candidate["gimbal"]["roll_deg"])
        self.assertEqual(2.5, candidate["gimbal"]["yaw_deg"])
        self.assertEqual("candidate", candidate["quality"]["gimbal"])

    def test_analyzer_does_not_classify_rc_channels_as_camera_pitch(self) -> None:
        rc_payload = b"\x00" + struct.pack("<8H", 256, 0, 1024, 1024, 1024, 1024, 1684, 1024)
        with tempfile.TemporaryDirectory() as tmp:
            session = Path(tmp) / "session.ndjson"
            session.write_text(json.dumps(_frame_event(cmd_set=0x06, cmd_id=0xAE, payload=rc_payload)) + "\n")

            report = analyze_session(session)

            self.assertEqual(["03/43", "04/05"], report["missing_georeference_families"])
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


def _frame_event(*, cmd_set: int, cmd_id: int, payload: bytes) -> dict[str, object]:
    frame = build_test_frame(cmd_set=cmd_set, cmd_id=cmd_id, payload=payload)
    return {
        "type": "DUML_FRAME",
        "schema": "dji-rc2-telemetry/v1",
        "session_id": "candidate-session",
        "wall_time_utc": "2026-07-22T21:00:00.000Z",
        "source": "unit",
        "cmd_set": cmd_set,
        "cmd_id": cmd_id,
        "raw_frame_b64": base64.b64encode(frame).decode("ascii"),
    }


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
