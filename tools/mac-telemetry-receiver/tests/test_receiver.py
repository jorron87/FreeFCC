from __future__ import annotations

import base64
import io
import json
import socket
import struct
import tempfile
import threading
import unittest
from datetime import datetime, timezone
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
from rc2_telemetry_receiver.mqtt import (
    MqttConfig,
    MqttGeoreferencePublisher,
    parse_mqtt_url,
)
from rc2_telemetry_receiver.publish8902 import (
    PublishParseError,
    PublishRecord,
    Rc2PublishStreamParser,
)
from rc2_telemetry_receiver.receiver import (
    RelayControlHub,
    SessionWriter,
    _validate_loopback_control_bind,
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
    def test_outbound_keepalive_is_evidence_not_inbound_stream(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                raw_chunk_event(
                    session_id="keepalive-session",
                    seq=1,
                    source="bench_wrapped_keepalive",
                    direction="client_to_controller",
                    port=40007,
                    data=b"keepalive",
                )
            )

            assert writer.session_dir is not None
            self.assertFalse((writer.session_dir / "raw-stream.bin").exists())
            self.assertEqual("unknown", writer.stats.capture_state)
            self.assertEqual(
                b"keepalive",
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

    def test_replay_preserves_duml_frame_and_rebuilds_candidate(self) -> None:
        payload = bytearray(48)
        struct.pack_into("<dd", payload, 0, 0.185, 1.047)
        struct.pack_into("<h", payload, 16, 123)
        struct.pack_into("<hhh", payload, 24, 55, -22, 900)
        frame_event = {
            **_frame_event(cmd_set=0x03, cmd_id=0x43, payload=bytes(payload)),
            "session_id": "frame-replay",
            "elapsed_realtime_ns": 1_000_000_000,
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.ndjson"
            events = [
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "frame-replay",
                    "source_mode": "bench_wrapped_keepalive",
                },
                frame_event,
                {
                    "type": "TELEMETRY_TICK",
                    "session_id": "frame-replay",
                    "source_status": "active",
                    "elapsed_realtime_ns": 2_000_000_000,
                },
            ]
            source.write_text(
                "".join(json.dumps(event) + "\n" for event in events),
                encoding="utf-8",
            )

            writer = replay_session(source, root / "replayed")

            assert writer.session_dir is not None
            replayed = [
                json.loads(line)
                for line in (writer.session_dir / "session.ndjson").read_text().splitlines()
            ]
            stored_frame = next(event for event in replayed if event["type"] == "DUML_FRAME")
            sample = next(
                event for event in replayed if event["type"] == "GEOREFERENCE_SAMPLE"
            )
            self.assertEqual(frame_event["raw_frame_b64"], stored_frame["raw_frame_b64"])
            self.assertAlmostEqual(
                59.98868115019719,
                sample["position"]["lat_deg"],
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
            writer.handle_event(
                error_event(
                    session_id="gap-session",
                    reason="snapshot_gap",
                    detail="snapshot returned no source bytes",
                )
            )

            assert writer.session_dir is not None
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())
            self.assertEqual(2, summary["capture_gaps"])
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
        self.assertEqual(12.3, candidate["altitude"]["relative_takeoff_m"])
        self.assertEqual(12.3, candidate["raw"]["relative_height_m_candidate"])
        self.assertEqual("probable", candidate["quality"]["position"])
        self.assertEqual("probable", candidate["quality"]["attitude"])
        self.assertEqual("unknown", candidate["quality"]["altitude"])
        self.assertEqual("probable", candidate["quality"]["relative_altitude"])

    def test_georeference_sample_exposes_fresh_relative_height_then_clears_it(self) -> None:
        payload = bytearray(48)
        struct.pack_into("<dd", payload, 0, 0.185, 1.047)
        struct.pack_into("<h", payload, 16, 123)
        struct.pack_into("<hhh", payload, 24, 55, -22, 900)
        frame = {
            **_frame_event(cmd_set=0x03, cmd_id=0x43, payload=bytes(payload)),
            "elapsed_realtime_ns": 1_000_000_000,
        }

        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "relative-height",
                    "source_mode": "bench_wrapped_keepalive",
                }
            )
            writer.handle_event(
                {
                    "type": "SOURCE_STATUS",
                    "session_id": "relative-height",
                    "status": "active",
                    "elapsed_realtime_ns": 900_000_000,
                }
            )
            writer.handle_event(frame)
            writer.handle_event(
                {
                    "type": "TELEMETRY_TICK",
                    "session_id": "relative-height",
                    "source": "bench_wrapped_keepalive",
                    "source_status": "active",
                    "elapsed_realtime_ns": 2_000_000_000,
                }
            )
            writer.handle_event(
                {
                    "type": "TELEMETRY_TICK",
                    "session_id": "relative-height",
                    "source": "bench_wrapped_keepalive",
                    "source_status": "active",
                    "elapsed_realtime_ns": 4_000_000_000,
                }
            )

            assert writer.session_dir is not None
            events = [
                json.loads(line)
                for line in (writer.session_dir / "session.ndjson").read_text().splitlines()
            ]
            samples = [event for event in events if event["type"] == "GEOREFERENCE_SAMPLE"]

            self.assertEqual(12.3, samples[0]["altitude"]["relative_takeoff_m"])
            self.assertEqual("probable", samples[0]["quality"]["relative_altitude"])
            self.assertIsNone(samples[1]["altitude"]["relative_takeoff_m"])
            self.assertEqual("unavailable", samples[1]["quality"]["relative_altitude"])

    def test_parser_error_invalidates_fields_until_each_family_recovers(self) -> None:
        osd_payload = bytearray(48)
        struct.pack_into("<dd", osd_payload, 0, 0.185, 1.047)
        struct.pack_into("<h", osd_payload, 16, 123)
        struct.pack_into("<hhh", osd_payload, 24, 55, -22, 900)
        gimbal_payload = struct.pack("<hhh", -600, 10, 25) + bytes(6)

        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "parser-invalidation",
                    "source_mode": "bench_wrapped_keepalive",
                }
            )
            writer.handle_event(
                {
                    "type": "SOURCE_STATUS",
                    "session_id": "parser-invalidation",
                    "status": "active",
                    "elapsed_realtime_ns": 900_000_000,
                }
            )
            writer.handle_event(
                {
                    **_frame_event(
                        cmd_set=0x03,
                        cmd_id=0x43,
                        payload=bytes(osd_payload),
                    ),
                    "session_id": "parser-invalidation",
                    "elapsed_realtime_ns": 1_000_000_000,
                }
            )
            writer.handle_event(
                {
                    "type": "ERROR",
                    "session_id": "parser-invalidation",
                    "reason": "crc16_mismatch",
                    "detail": "test",
                }
            )
            writer.handle_event(
                {
                    **_frame_event(
                        cmd_set=0x04,
                        cmd_id=0x05,
                        payload=gimbal_payload,
                    ),
                    "session_id": "parser-invalidation",
                    "elapsed_realtime_ns": 1_500_000_000,
                }
            )
            writer.handle_event(
                {
                    "type": "TELEMETRY_TICK",
                    "session_id": "parser-invalidation",
                    "source_status": "active",
                    "elapsed_realtime_ns": 2_000_000_000,
                }
            )

            assert writer.session_dir is not None
            samples = [
                json.loads(line)
                for line in (writer.session_dir / "session.ndjson").read_text().splitlines()
                if '"type":"GEOREFERENCE_SAMPLE"' in line
            ]

        self.assertIsNone(samples[0]["position"]["lat_deg"])
        self.assertEqual("unavailable", samples[0]["quality"]["position"])
        self.assertEqual(-60.0, samples[0]["gimbal"]["pitch_deg"])
        self.assertEqual("candidate", samples[0]["quality"]["gimbal"])

    def test_relay_reconnect_invalidates_previous_dynamic_values(self) -> None:
        payload = bytearray(48)
        struct.pack_into("<dd", payload, 0, 0.185, 1.047)
        struct.pack_into("<hhh", payload, 24, 55, -22, 900)

        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            hello = {
                "type": "HELLO",
                "schema": "dji-rc2-telemetry/v2",
                "session_id": "relay-reconnect",
                "source_id": "neo2",
                "source_mode": "bench_wrapped_keepalive",
            }
            writer.handle_event(hello)
            writer.handle_event(
                {
                    "type": "SOURCE_STATUS",
                    "session_id": "relay-reconnect",
                    "status": "active",
                    "elapsed_realtime_ns": 900_000_000,
                }
            )
            writer.handle_event(
                {
                    **_frame_event(cmd_set=0x03, cmd_id=0x43, payload=bytes(payload)),
                    "session_id": "relay-reconnect",
                    "elapsed_realtime_ns": 1_000_000_000,
                }
            )
            writer.handle_event(hello)
            writer.handle_event(
                {
                    "type": "TELEMETRY_TICK",
                    "session_id": "relay-reconnect",
                    "source_status": "active",
                    "elapsed_realtime_ns": 2_000_000_000,
                }
            )

            assert writer.session_dir is not None
            sample = next(
                json.loads(line)
                for line in (writer.session_dir / "session.ndjson").read_text().splitlines()
                if '"type":"GEOREFERENCE_SAMPLE"' in line
            )

        self.assertIsNone(sample["position"]["lat_deg"])
        self.assertEqual("unavailable", sample["quality"]["position"])

    def test_queued_frame_before_reconnect_hello_stays_historical(self) -> None:
        payload = bytearray(48)
        struct.pack_into("<dd", payload, 0, 0.185, 1.047)
        struct.pack_into("<hhh", payload, 24, 55, -22, 900)

        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "queued-reconnect",
                    "source_mode": "bench_wrapped_keepalive",
                    "created_at": "2026-07-25T12:00:01.000Z",
                    "elapsed_realtime_ns": 1_000_000_000,
                }
            )
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "queued-reconnect",
                    "source_mode": "bench_wrapped_keepalive",
                    "created_at": "2026-07-25T12:00:10.000Z",
                    "elapsed_realtime_ns": 10_000_000_000,
                }
            )
            old_frame = {
                **_frame_event(cmd_set=0x03, cmd_id=0x43, payload=bytes(payload)),
                "session_id": "queued-reconnect",
                "wall_time_utc": "2026-07-25T12:00:09.000Z",
                "elapsed_realtime_ns": 9_000_000_000,
            }
            writer.handle_event(old_frame)
            writer.handle_event(
                {
                    "type": "TELEMETRY_TICK",
                    "session_id": "queued-reconnect",
                    "source_status": "active",
                    "elapsed_realtime_ns": 11_000_000_000,
                }
            )

            assert writer.session_dir is not None
            events = [
                json.loads(line)
                for line in (writer.session_dir / "session.ndjson").read_text().splitlines()
            ]
            stored_frame = next(event for event in events if event["type"] == "DUML_FRAME")
            sample = next(
                event for event in events if event["type"] == "GEOREFERENCE_SAMPLE"
            )
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())

        self.assertEqual(old_frame["raw_frame_b64"], stored_frame["raw_frame_b64"])
        self.assertEqual(1, summary["duml_frames"])
        self.assertIsNone(sample["position"]["lat_deg"])
        self.assertEqual("unavailable", sample["quality"]["position"])

    def test_initial_connection_accepts_current_session_queue_before_hello(self) -> None:
        payload = bytearray(48)
        struct.pack_into("<dd", payload, 0, 0.185, 1.047)
        struct.pack_into("<hhh", payload, 24, 55, -22, 900)

        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "initial-queue",
                    "source_mode": "bench_wrapped_keepalive",
                    "created_at": "2026-07-25T12:00:10.000Z",
                    "elapsed_realtime_ns": 10_000_000_000,
                }
            )
            writer.handle_event(
                {
                    **_frame_event(cmd_set=0x03, cmd_id=0x43, payload=bytes(payload)),
                    "session_id": "initial-queue",
                    "wall_time_utc": "2026-07-25T12:00:09.000Z",
                    "elapsed_realtime_ns": 9_000_000_000,
                }
            )
            writer.handle_event(
                {
                    "type": "TELEMETRY_TICK",
                    "session_id": "initial-queue",
                    "source_status": "active",
                    "elapsed_realtime_ns": 11_000_000_000,
                }
            )

            assert writer.session_dir is not None
            sample = next(
                json.loads(line)
                for line in (writer.session_dir / "session.ndjson").read_text().splitlines()
                if '"type":"GEOREFERENCE_SAMPLE"' in line
            )

        self.assertAlmostEqual(59.98868115019719, sample["position"]["lat_deg"])
        self.assertEqual("probable", sample["quality"]["position"])

    def test_keepalive_stats_are_recorded_without_raw_artifact_expansion(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "keepalive-stats",
                    "source_mode": "bench_wrapped_keepalive",
                }
            )
            writer.handle_event(
                {
                    "type": "STREAM_KEEPALIVE_STATS",
                    "session_id": "keepalive-stats",
                    "sent_count": 42,
                    "command_family": "00/01",
                }
            )

            assert writer.session_dir is not None
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())
            self.assertEqual(42, summary["stream_keepalives"])
            self.assertEqual([], list((writer.session_dir / "raw").iterdir()))

    def test_snapshot_stats_are_recorded_without_raw_artifact_expansion(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "snapshot-stats",
                    "source_mode": "bench_wrapped_snapshot_1hz",
                }
            )
            writer.handle_event(
                {
                    "type": "SNAPSHOT_STATS",
                    "session_id": "snapshot-stats",
                    "attempt_count": 3,
                    "success_count": 2,
                    "failure_count": 1,
                    "last_rx_bytes": 4116,
                    "last_frame_count": 92,
                }
            )

            assert writer.session_dir is not None
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())
            self.assertEqual(3, summary["snapshot_attempts"])
            self.assertEqual(2, summary["snapshot_successes"])
            self.assertEqual(1, summary["snapshot_failures"])
            self.assertEqual([], list((writer.session_dir / "raw").iterdir()))

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

    def test_sample_keeps_amsl_separate_from_newer_relative_height(self) -> None:
        gps_payload = bytearray(34)
        struct.pack_into("<iii", gps_payload, 0, 103_931_366, 591_011_495, 7_420)
        osd_payload = bytearray(48)
        struct.pack_into("<dd", osd_payload, 0, 0.181398395, 1.03150916)
        struct.pack_into("<h", osd_payload, 16, 123)
        struct.pack_into("<hhh", osd_payload, 24, 55, -22, 310)
        now = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace(
            "+00:00", "Z"
        )
        sink = _RecordingSink()

        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp), [sink])
            writer.handle_event(
                {
                    "type": "HELLO",
                    "schema": "dji-rc2-telemetry/v2",
                    "session_id": "combined-height",
                    "source_id": "neo2-rc2-H103",
                    "source_mode": "bench_wrapped_keepalive",
                    "created_at": now,
                    "elapsed_realtime_ns": 800_000_000,
                }
            )
            writer.handle_event(
                {
                    "type": "SOURCE_STATUS",
                    "session_id": "combined-height",
                    "status": "active",
                    "elapsed_realtime_ns": 900_000_000,
                }
            )
            writer.handle_event(
                {
                    **_frame_event(
                        cmd_set=0x03,
                        cmd_id=0x57,
                        payload=bytes(gps_payload),
                    ),
                    "session_id": "combined-height",
                    "elapsed_realtime_ns": 1_000_000_000,
                }
            )
            writer.handle_event(
                {
                    **_frame_event(
                        cmd_set=0x03,
                        cmd_id=0x43,
                        payload=bytes(osd_payload),
                    ),
                    "session_id": "combined-height",
                    "elapsed_realtime_ns": 1_100_000_000,
                }
            )
            writer.handle_event(
                {
                    "type": "TELEMETRY_TICK",
                    "session_id": "combined-height",
                    "source": "bench_wrapped_keepalive",
                    "source_status": "active",
                    "elapsed_realtime_ns": 2_000_000_000,
                    "wall_time_utc": now,
                }
            )

            assert writer.session_dir is not None
            stored = [
                json.loads(line)
                for line in (writer.session_dir / "session.ndjson").read_text().splitlines()
                if '"type":"GEOREFERENCE_SAMPLE"' in line
            ][0]
            summary = json.loads((writer.session_dir / "session-summary.json").read_text())

        self.assertEqual("neo2-rc2-H103", stored["source_id"])
        self.assertEqual(7.42, stored["altitude"]["amsl_m"])
        self.assertEqual(12.3, stored["altitude"]["relative_takeoff_m"])
        self.assertEqual(7.42, stored["position"]["alt_m"])
        self.assertEqual("mean_sea_level_geoid", stored["altitude"]["amsl_reference"])
        self.assertEqual("takeoff_relative", stored["altitude"]["relative_reference"])
        self.assertEqual("03/57", stored["raw"]["message_families"]["altitude"])
        self.assertEqual("03/43", stored["raw"]["message_families"]["position"])
        self.assertEqual(stored, sink.samples[0])
        self.assertEqual("neo2-rc2-H103", summary["georef"]["source_id"])

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
        record = bytearray(49)
        record[:23] = b"WA150\x001581F6ABCDEF1234\x00"
        struct.pack_into("<H", record, 23, 0x1234)
        record[25] = 7
        record[26:29] = b"\x01\x02\x03"
        struct.pack_into("<III", record, 29, 1_000, 2_000, 3_000)
        payload = b"\x01\x00" + bytes(record)
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
        self.assertIsNone(candidate["identity"]["serial_suffix"])
        self.assertEqual("WA150", candidate["identity"]["model_code"])
        self.assertEqual("candidate", candidate["quality"]["identity"])
        self.assertEqual(1, candidate["raw"]["neighbor_count"])
        self.assertEqual(0x1234, candidate["raw"]["neighbor_records"][0]["link_state_raw_u16"])
        self.assertEqual(
            [1_000, 2_000, 3_000],
            candidate["raw"]["neighbor_records"][0]["timestamp_age_raw_u32"],
        )

        wrong_family = {**event, "cmd_id": 0x13}
        self.assertIsNone(decode_candidate(wrong_family))

    def test_51_14_rejects_bad_record_length_and_identity_outside_identity_region(self) -> None:
        bad_length = _frame_event(
            cmd_set=0x51,
            cmd_id=0x14,
            payload=b"\x01\x00" + bytes(48),
            sender=0xEE,
            receiver=0x82,
        )
        self.assertIsNone(decode_candidate(bad_length))

        record = bytearray(49)
        record[23:39] = b"1581F6ABCDEF1234"
        outside_identity_region = _frame_event(
            cmd_set=0x51,
            cmd_id=0x14,
            payload=b"\x01\x00" + bytes(record),
            sender=0xEE,
            receiver=0x82,
        )
        self.assertIsNone(decode_candidate(outside_identity_region))

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

    def test_transport_age_prefers_android_monotonic_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            writer = SessionWriter(Path(tmp))
            hello_received = datetime(2026, 7, 25, 12, 0, 0, tzinfo=timezone.utc)
            writer._align_rc_clock(
                {
                    "created_at": "2026-07-25T12:00:00.000Z",
                    "elapsed_realtime_ns": 10_000_000_000,
                },
                hello_received,
            )

            sample = writer._build_georeference_sample(
                {
                    "type": "TELEMETRY_TICK",
                    "source_status": "active",
                    "elapsed_realtime_ns": 8_000_000_000,
                    "wall_time_utc": "2099-01-01T00:00:00.000Z",
                },
                hello_received.replace(second=1),
            )

        self.assertEqual(3_000, sample["clock"]["transport_age_ms"])
        self.assertEqual(
            "android_monotonic",
            sample["clock"]["transport_age_source"],
        )


class RelayControlHubTest(unittest.TestCase):
    def test_control_bind_rejects_non_loopback_address(self) -> None:
        _validate_loopback_control_bind("127.0.0.1:8766")
        _validate_loopback_control_bind("::1:8766")
        with self.assertRaisesRegex(ValueError, "loopback"):
            _validate_loopback_control_bind("0.0.0.0:8766")

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

    def test_duml_lab_recipe_is_forwarded_and_correlated(self) -> None:
        app_side, mac_side = socket.socketpair()
        hub = RelayControlHub()
        hub.attach(app_side)
        received: dict[str, object] = {}
        recipe = {
            "schema": "duml-lab/v1",
            "name": "unit",
            "port": 49123,
            "setup": [{"op": "connect"}],
            "cycle": [],
            "cycle_count": 0,
            "teardown": [{"op": "close"}],
        }

        def respond() -> None:
            with mac_side.makefile("r", encoding="utf-8") as reader:
                command = json.loads(reader.readline())
                received.update(command)
                hub.handle_event(
                    {
                        "type": "DUML_LAB_RESULT",
                        "request_id": command["request_id"],
                        "status": "ok",
                        "recipe": command["recipe"],
                    }
                )

        thread = threading.Thread(target=respond)
        thread.start()
        try:
            result = hub.request(
                {"type": "DUML_LAB_REQUEST", "recipe": recipe},
                timeout=1.0,
            )
        finally:
            thread.join(timeout=1.0)
            app_side.close()
            mac_side.close()

        self.assertEqual("DUML_LAB_REQUEST", received["type"])
        self.assertEqual(recipe, received["recipe"])
        self.assertEqual("ok", result["status"])


class MqttPublisherTest(unittest.TestCase):
    def test_url_defaults_and_tls_are_explicit(self) -> None:
        plain = parse_mqtt_url("mqtt://broker.local")
        secure = parse_mqtt_url("mqtts://user:secret@broker.local")

        self.assertEqual(1883, plain.port)
        self.assertFalse(plain.tls)
        self.assertEqual(8883, secure.port)
        self.assertTrue(secure.tls)
        self.assertEqual("user", secure.username)

    def test_live_sample_uses_stable_topic_and_stale_samples_are_dropped(self) -> None:
        client = _FakeMqttClient()
        mqtt_module = _FakeMqttModule()
        publisher = MqttGeoreferencePublisher(
            MqttConfig(
                url="mqtt://broker.local",
                topic_prefix="nordlys/rc2",
                client_id="mac receiver",
            ),
            mqtt_module=mqtt_module,
            client=client,
        )
        publisher._on_connect(client, None, None, 0, None)
        sample = {
            "type": "GEOREFERENCE_SAMPLE",
            "schema": "dji-rc2-telemetry/v2",
            "source_id": "neo2 rc/H103",
            "clock": {"transport_age_ms": 20},
            "position": {"lat_deg": 59.1, "lon_deg": 10.3},
        }

        self.assertTrue(publisher.publish(sample))
        topic, payload, qos, retain = next(
            item for item in client.published if item[0].endswith("/georeference")
        )
        self.assertEqual("nordlys/rc2/neo2_rc_H103/georeference", topic)
        self.assertEqual(sample, json.loads(payload))
        self.assertEqual(0, qos)
        self.assertFalse(retain)

        stale = {**sample, "clock": {"transport_age_ms": 3_000}}
        self.assertFalse(publisher.publish(stale))
        self.assertEqual(1, publisher.stats.published)
        self.assertEqual(1, publisher.stats.dropped_stale)

        publisher._on_disconnect(client, None, None, 0, None)
        self.assertFalse(publisher.publish(sample))
        self.assertEqual(1, publisher.stats.dropped_disconnected)


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


class _RecordingSink:
    def __init__(self) -> None:
        self.samples: list[dict[str, object]] = []

    def publish(self, sample: dict[str, object]) -> bool:
        self.samples.append(sample)
        return True


class _FakePublishInfo:
    def __init__(self, rc: int = 0) -> None:
        self.rc = rc

    def wait_for_publish(self, timeout: float) -> None:
        del timeout


class _FakeMqttClient:
    def __init__(self) -> None:
        self.published: list[tuple[str, str, int, bool]] = []
        self.on_connect = None
        self.on_disconnect = None

    def username_pw_set(self, username: str, password: str | None) -> None:
        del username, password

    def tls_set(self, ca_certs: str | None = None) -> None:
        del ca_certs

    def max_queued_messages_set(self, count: int) -> None:
        del count

    def max_inflight_messages_set(self, count: int) -> None:
        del count

    def reconnect_delay_set(self, min_delay: int, max_delay: int) -> None:
        del min_delay, max_delay

    def will_set(self, topic: str, payload: str, qos: int, retain: bool) -> None:
        del topic, payload, qos, retain

    def publish(
        self,
        topic: str,
        payload: str,
        qos: int,
        retain: bool,
    ) -> _FakePublishInfo:
        self.published.append((topic, payload, qos, retain))
        return _FakePublishInfo()

    def connect_async(self, host: str, port: int, keepalive: int) -> None:
        del host, port, keepalive

    def loop_start(self) -> None:
        pass

    def loop_stop(self) -> None:
        pass

    def disconnect(self) -> None:
        pass


class _FakeMqttModule:
    MQTT_ERR_SUCCESS = 0
    MQTTv311 = 4

    class CallbackAPIVersion:
        VERSION2 = 2

    def Client(self, *args: object, **kwargs: object) -> _FakeMqttClient:
        del args, kwargs
        return _FakeMqttClient()


if __name__ == "__main__":
    unittest.main()
