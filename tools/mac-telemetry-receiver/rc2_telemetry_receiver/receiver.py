from __future__ import annotations

import base64
import binascii
import json
import socket
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from .duml import DumlFrameParser, ParseError, ParsedFrame


SCHEMA = "dji-rc2-telemetry/v1"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def crc32_hex(data: bytes) -> str:
    return f"{binascii.crc32(data) & 0xFFFFFFFF:08x}"


def raw_chunk_event(
    *,
    session_id: str,
    seq: int,
    source: str,
    direction: str,
    port: int,
    data: bytes,
) -> dict[str, Any]:
    return {
        "type": "RAW_CHUNK",
        "schema": SCHEMA,
        "session_id": session_id,
        "seq": seq,
        "wall_time_utc": utc_now(),
        "elapsed_realtime_ns": 0,
        "source": source,
        "direction": direction,
        "port": port,
        "bytes_b64": base64.b64encode(data).decode("ascii"),
        "crc32": crc32_hex(data),
    }


def duml_frame_event(
    *,
    session_id: str,
    raw_seq: int,
    source: str,
    direction: str,
    port: int,
    frame: ParsedFrame,
) -> dict[str, Any]:
    return {
        "type": "DUML_FRAME",
        "schema": SCHEMA,
        "session_id": session_id,
        "raw_seq": raw_seq,
        "wall_time_utc": utc_now(),
        "source": source,
        "direction": direction,
        "port": port,
        "sender": frame.sender,
        "receiver": frame.receiver,
        "seq": frame.sequence,
        "cmd_type": frame.cmd_type,
        "cmd_set": frame.cmd_set,
        "cmd_id": frame.cmd_id,
        "payload_length": frame.payload_length,
        "validation_status": frame.validation_status,
        "raw_frame_b64": base64.b64encode(frame.raw).decode("ascii"),
        "quality": "unknown",
    }


def error_event(*, session_id: str, reason: str, detail: str) -> dict[str, Any]:
    return {
        "type": "ERROR",
        "schema": SCHEMA,
        "session_id": session_id,
        "wall_time_utc": utc_now(),
        "reason": reason,
        "detail": detail,
    }


@dataclass
class SessionStats:
    raw_chunks: int = 0
    frames: int = 0
    parser_errors: int = 0
    capture_gaps: int = 0
    bytes: int = 0
    last_error: str = ""
    capture_state: str = "unknown"
    command_pairs: dict[str, int] = field(default_factory=dict)


class SessionWriter:
    def __init__(self, out_root: Path) -> None:
        self.out_root = out_root
        self.session_id = "pending"
        self.session_dir: Path | None = None
        self.ndjson_path: Path | None = None
        self.raw_dir: Path | None = None
        self.stats = SessionStats()

    def handle_event(self, event: dict[str, Any]) -> None:
        if event.get("type") == "HELLO":
            self._start_session(str(event.get("session_id") or "unknown"))
        elif self.session_dir is None:
            self._start_session(str(event.get("session_id") or "manual"))

        self._append_event(event)
        self._update_stats(event)
        self._write_summary()

    def _start_session(self, session_id: str) -> None:
        if self.session_dir is not None:
            return
        self.session_id = session_id
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        safe_session = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in session_id)[:64]
        self.session_dir = self.out_root / f"{stamp}-{safe_session}"
        self.raw_dir = self.session_dir / "raw"
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.ndjson_path = self.session_dir / "session.ndjson"

    def _append_event(self, event: dict[str, Any]) -> None:
        assert self.ndjson_path is not None
        with self.ndjson_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, separators=(",", ":")) + "\n")

        if event.get("type") == "RAW_CHUNK":
            seq = int(event.get("seq", 0))
            data = base64.b64decode(str(event.get("bytes_b64", "")))
            assert self.raw_dir is not None
            (self.raw_dir / f"raw-{seq:08d}.bin").write_bytes(data)

    def _update_stats(self, event: dict[str, Any]) -> None:
        event_type = event.get("type")
        if event_type == "RAW_CHUNK":
            self.stats.raw_chunks += 1
            self.stats.bytes += len(base64.b64decode(str(event.get("bytes_b64", ""))))
            self.stats.capture_state = "active"
            if self.stats.last_error.startswith("capture_gap"):
                self.stats.last_error = ""
        elif event_type == "DUML_FRAME":
            self.stats.frames += 1
            pair = f"{int(event.get('cmd_set', 0)):02X}/{int(event.get('cmd_id', 0)):02X}"
            self.stats.command_pairs[pair] = self.stats.command_pairs.get(pair, 0) + 1
        elif event_type == "ERROR":
            reason = str(event.get("reason", ""))
            if reason == "capture_gap":
                self.stats.capture_gaps += 1
                self.stats.capture_state = "unavailable"
                self.stats.last_error = f"capture_gap: {event.get('detail', '')}"
            else:
                self.stats.parser_errors += 1
                self.stats.last_error = reason
        elif event_type == "TELEMETRY_CANDIDATE":
            quality = event.get("quality", {})
            if isinstance(quality, dict) and "unavailable" in quality.values():
                self.stats.capture_state = "unavailable"

    def _write_summary(self) -> None:
        assert self.session_dir is not None
        summary = {
            "schema": SCHEMA,
            "session_id": self.session_id,
            "updated_at": utc_now(),
            "raw_chunks": self.stats.raw_chunks,
            "duml_frames": self.stats.frames,
            "parser_errors": self.stats.parser_errors,
            "capture_gaps": self.stats.capture_gaps,
            "capture_state": self.stats.capture_state,
            "bytes": self.stats.bytes,
            "last_error": self.stats.last_error,
            "command_pairs": dict(sorted(self.stats.command_pairs.items())),
            "georef": {
                "source_id": self.session_id,
                "captured_at": utc_now(),
                "position": {"lat_deg": None, "lon_deg": None, "alt_m": None},
                "attitude": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
                "gimbal": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
                "quality": {
                    "position": "unavailable" if self.stats.capture_state == "unavailable" else "unknown",
                    "attitude": "unavailable" if self.stats.capture_state == "unavailable" else "unknown",
                },
                "raw": {"message_family": None},
            },
        }
        (self.session_dir / "session-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")


def listen(bind: str, out_root: Path) -> None:
    host, port_text = bind.rsplit(":", 1)
    port = int(port_text)
    out_root.mkdir(parents=True, exist_ok=True)
    writers: dict[str, SessionWriter] = {}

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((host, port))
        server.listen(1)
        print(f"listening on {host}:{port}", flush=True)
        while True:
            conn, addr = server.accept()
            print(f"client connected from {addr[0]}:{addr[1]}", flush=True)
            with conn, conn.makefile("r", encoding="utf-8", newline="\n") as handle:
                for line in handle:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError as exc:
                        event = error_event(session_id="invalid-json", reason="json_decode", detail=str(exc))
                    session_id = str(event.get("session_id") or "unknown")
                    writer = writers.get(session_id)
                    if writer is None:
                        writer = SessionWriter(out_root)
                        writers[session_id] = writer
                    writer.handle_event(event)
                    _print_status(writer.stats)
            print("client disconnected", flush=True)


def handle_payload_stream(*, payloads: Iterable[bytes], out_root: Path, session_id: str, source: str) -> SessionWriter:
    writer = SessionWriter(out_root)
    writer.handle_event(
        {
            "type": "HELLO",
            "schema": SCHEMA,
            "session_id": session_id,
            "app_version": "mac-adb-pcap",
            "source_mode": source,
            "source_id": source,
            "controller_model": "DJI RC2",
            "created_at": utc_now(),
        }
    )
    parser = DumlFrameParser()
    for seq, payload in enumerate(payloads, start=1):
        writer.handle_event(raw_chunk_event(session_id=session_id, seq=seq, source=source, direction="pcap_payload", port=40009, data=payload))
        for result in parser.feed(payload):
            if isinstance(result, ParsedFrame):
                writer.handle_event(duml_frame_event(session_id=session_id, raw_seq=seq, source=source, direction="pcap_payload", port=40009, frame=result))
            elif isinstance(result, ParseError):
                writer.handle_event(error_event(session_id=session_id, reason=result.reason, detail=result.detail))
        _print_status(writer.stats)
    return writer


def _print_status(stats: SessionStats) -> None:
    print(
        f"\rraw={stats.raw_chunks} frames={stats.frames} errors={stats.parser_errors} bytes={stats.bytes}",
        end="",
        file=sys.stderr,
        flush=True,
    )
