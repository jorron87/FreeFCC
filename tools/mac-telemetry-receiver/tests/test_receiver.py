from __future__ import annotations

import base64
import io
import json
import struct
import tempfile
import unittest
from pathlib import Path

from rc2_telemetry_receiver.duml import DumlFrameParser, ParsedFrame, build_test_frame, crc16, crc8
from rc2_telemetry_receiver.pcap import iter_pcap_tcp_payloads
from rc2_telemetry_receiver.receiver import SessionWriter, error_event, handle_payload_stream


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
