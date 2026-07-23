from __future__ import annotations

import base64
import binascii
import json
import re
import socket
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from .duml import ParseError, ParsedFrame, WrappedDumlFrameParser
from .telemetry import decode_candidate


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
    candidate_events: int = 0
    attitude: dict[str, float | None] = field(
        default_factory=lambda: {"roll_deg": None, "pitch_deg": None, "yaw_deg": None}
    )
    gimbal: dict[str, float | None] = field(
        default_factory=lambda: {"roll_deg": None, "pitch_deg": None, "yaw_deg": None}
    )
    attitude_quality: str = "unknown"
    gimbal_quality: str = "unknown"
    raw_candidate: dict[str, Any] = field(default_factory=lambda: {"message_family": None})
    probe_results: int = 0
    last_probe: dict[str, Any] = field(default_factory=dict)
    duml_results: int = 0
    last_duml: dict[str, Any] = field(default_factory=dict)
    aircraft_identity: dict[str, Any] = field(
        default_factory=lambda: {"aircraft_serial": None, "serial_suffix": None, "model_code": None}
    )
    identity_quality: str = "unknown"


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
        candidate = decode_candidate(event)
        if candidate is not None:
            self._append_event(candidate)
            self._update_stats(candidate)
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
                self._clear_candidates()
            else:
                self.stats.parser_errors += 1
                self.stats.last_error = reason
        elif event_type == "TELEMETRY_CANDIDATE":
            quality = event.get("quality", {})
            if isinstance(quality, dict) and "unavailable" in quality.values():
                self.stats.capture_state = "unavailable"
                self._clear_candidates()
            else:
                self.stats.candidate_events += 1
                family = str(event.get("raw", {}).get("message_family") or "")
                if family == "03/43":
                    self.stats.attitude = dict(event.get("attitude", self.stats.attitude))
                    self.stats.attitude_quality = str(quality.get("attitude", "candidate"))
                elif family == "04/05":
                    self.stats.gimbal = dict(event.get("gimbal", self.stats.gimbal))
                    self.stats.gimbal_quality = str(quality.get("gimbal", "candidate"))
                elif family == "51/14":
                    self.stats.aircraft_identity = dict(event.get("identity", self.stats.aircraft_identity))
                    self.stats.identity_quality = str(quality.get("identity", "candidate"))
                self.stats.raw_candidate = dict(event.get("raw", self.stats.raw_candidate))
        elif event_type == "PROBE_RESULT":
            self.stats.probe_results += 1
            self.stats.last_probe = dict(event)
            if event.get("status") == "ok":
                self.stats.capture_state = "active_probe"
                self.stats.last_error = ""
                self.stats.candidate_events += 1
                self.stats.attitude = dict(event.get("attitude", self.stats.attitude))
                quality = event.get("quality", {})
                self.stats.attitude_quality = str(quality.get("attitude", "candidate"))
                self.stats.raw_candidate = dict(event.get("raw", self.stats.raw_candidate))
        elif event_type == "DUML_RESULT":
            self.stats.duml_results += 1
            self.stats.last_duml = dict(event)

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
            "candidate_events": self.stats.candidate_events,
            "probe_results": self.stats.probe_results,
            "last_probe": self.stats.last_probe,
            "duml_results": self.stats.duml_results,
            "last_duml": self.stats.last_duml,
            "aircraft_identity": self.stats.aircraft_identity,
            "identity_quality": self.stats.identity_quality,
            "bytes": self.stats.bytes,
            "last_error": self.stats.last_error,
            "command_pairs": dict(sorted(self.stats.command_pairs.items())),
            "georef": {
                "source_id": self.session_id,
                "captured_at": utc_now(),
                "position": {"lat_deg": None, "lon_deg": None, "alt_m": None},
                "attitude": self.stats.attitude,
                "gimbal": self.stats.gimbal,
                "quality": {
                    "position": "unavailable" if self.stats.capture_state == "unavailable" else "unknown",
                    "attitude": "unavailable" if self.stats.capture_state == "unavailable" else self.stats.attitude_quality,
                    "gimbal": "unavailable" if self.stats.capture_state == "unavailable" else self.stats.gimbal_quality,
                },
                "raw": self.stats.raw_candidate,
            },
        }
        summary_path = self.session_dir / "session-summary.json"
        temporary_path = self.session_dir / ".session-summary.json.tmp"
        temporary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
        temporary_path.replace(summary_path)

    def _clear_candidates(self) -> None:
        self.stats.attitude = {"roll_deg": None, "pitch_deg": None, "yaw_deg": None}
        self.stats.gimbal = {"roll_deg": None, "pitch_deg": None, "yaw_deg": None}
        self.stats.attitude_quality = "unknown"
        self.stats.gimbal_quality = "unknown"
        self.stats.raw_candidate = {"message_family": None}
        self.stats.aircraft_identity = {
            "aircraft_serial": None,
            "serial_suffix": None,
            "model_code": None,
        }
        self.stats.identity_quality = "unknown"


class RelayControlHub:
    """Correlates local, allowlisted probe requests with the active RC2 socket."""

    def __init__(self) -> None:
        self._condition = threading.Condition()
        self._connection: socket.socket | None = None
        self._results: dict[str, dict[str, Any]] = {}

    def attach(self, connection: socket.socket) -> None:
        with self._condition:
            self._connection = connection
            self._condition.notify_all()

    def detach(self, connection: socket.socket) -> None:
        with self._condition:
            if self._connection is connection:
                self._connection = None
            self._condition.notify_all()

    def handle_event(self, event: dict[str, Any]) -> None:
        if event.get("type") not in ("PROBE_RESULT", "DUML_RESULT"):
            return
        request_id = str(event.get("request_id") or "")
        with self._condition:
            self._results[request_id] = event
            self._condition.notify_all()

    def request(self, command: dict[str, Any], timeout: float = 6.0) -> dict[str, Any]:
        request_id = str(uuid.uuid4())
        command = {**command, "schema": SCHEMA, "request_id": request_id}
        with self._condition:
            connection = self._connection
            if connection is None:
                return {"status": "unavailable", "message": "No RC2 relay is connected"}
            try:
                connection.sendall((json.dumps(command, separators=(",", ":")) + "\n").encode("utf-8"))
            except OSError as exc:
                return {"status": "send_error", "message": str(exc)}

            deadline = time.monotonic() + timeout
            while request_id not in self._results:
                if self._connection is not connection:
                    return {"status": "disconnected", "request_id": request_id}
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return {"status": "timeout", "request_id": request_id}
                self._condition.wait(remaining)
            return self._results.pop(request_id)

    def request_probe(self, timeout: float = 6.0) -> dict[str, Any]:
        return self.request(
            {"type": "PROBE_REQUEST", "probe": "fc_osd_03_43_once"},
            timeout=timeout,
        )


def listen(bind: str, out_root: Path, control_bind: str = "127.0.0.1:8766") -> None:
    host, port_text = bind.rsplit(":", 1)
    port = int(port_text)
    out_root.mkdir(parents=True, exist_ok=True)
    writers: dict[str, SessionWriter] = {}
    control_hub = RelayControlHub()
    threading.Thread(
        target=_serve_control,
        args=(control_bind, control_hub),
        daemon=True,
        name="rc2-probe-control",
    ).start()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((host, port))
        server.listen(1)
        print(f"listening on {host}:{port}", flush=True)
        while True:
            conn, addr = server.accept()
            print(f"client connected from {addr[0]}:{addr[1]}", flush=True)
            control_hub.attach(conn)
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
                    control_hub.handle_event(event)
                    _print_status(writer.stats)
            control_hub.detach(conn)
            print("client disconnected", flush=True)


def _serve_control(bind: str, hub: RelayControlHub) -> None:
    host, port_text = bind.rsplit(":", 1)
    port = int(port_text)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((host, port))
        server.listen(4)
        print(f"probe control on {host}:{port}", flush=True)
        while True:
            conn, _ = server.accept()
            with conn, conn.makefile("r", encoding="utf-8", newline="\n") as reader:
                try:
                    request = json.loads(reader.readline())
                    if request.get("probe") == "fc_osd_03_43_once":
                        response = hub.request_probe()
                    elif request.get("type") == "DUML_REQUEST":
                        response = hub.request(request)
                    else:
                        response = {"status": "rejected", "message": "Unknown local control request"}
                except Exception as exc:
                    response = {"status": "error", "message": str(exc)}
                conn.sendall((json.dumps(response, separators=(",", ":")) + "\n").encode("utf-8"))


def request_remote_probe(control_bind: str) -> dict[str, Any]:
    return send_control_request(control_bind, {"probe": "fc_osd_03_43_once"})


def request_remote_duml(control_bind: str, command: dict[str, Any]) -> dict[str, Any]:
    return send_control_request(control_bind, {"type": "DUML_REQUEST", **command})

def request_remote_aircraft_serial(control_bind: str) -> dict[str, Any]:
    result = request_remote_duml(
        control_bind,
        {
            "sender": 0x82,
            "destination": 0x03,
            "cmd_type": 0x40,
            "cmd_set": 0x00,
            "cmd_id": 0x51,
            "payload_b64": "",
            "expect_response": True,
            "read_window_ms": 2000,
            "port": 40009,
        },
    )
    payload_b64 = result.get("response", {}).get("payload_b64")
    payload = base64.b64decode(payload_b64) if payload_b64 else b""
    result["serial_candidate"] = extract_serial_candidate(payload)
    result["serial_quality"] = "candidate" if result["serial_candidate"] else "unknown"
    return result


def extract_serial_candidate(payload: bytes) -> str | None:
    candidates = [
        match.group().decode("ascii")
        for match in re.finditer(rb"[A-Z0-9]{6,32}", payload.upper())
    ]
    if not candidates:
        return None
    return next((value for value in candidates if value.startswith("1581")), max(candidates, key=len))


def send_control_request(control_bind: str, request: dict[str, Any]) -> dict[str, Any]:
    host, port_text = control_bind.rsplit(":", 1)
    with socket.create_connection((host, int(port_text)), timeout=8.0) as conn:
        conn.sendall((json.dumps(request, separators=(",", ":")) + "\n").encode("utf-8"))
        with conn.makefile("r", encoding="utf-8", newline="\n") as reader:
            line = reader.readline()
            if not line:
                return {"status": "disconnected", "message": "Control socket closed without a response"}
            return json.loads(line)


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
    parser = WrappedDumlFrameParser()
    for seq, payload in enumerate(payloads, start=1):
        writer.handle_event(raw_chunk_event(session_id=session_id, seq=seq, source=source, direction="pcap_payload", port=40009, data=payload))
        for result in parser.feed(payload):
            if isinstance(result, ParsedFrame):
                writer.handle_event(duml_frame_event(session_id=session_id, raw_seq=seq, source=source, direction="pcap_payload", port=40009, frame=result))
            elif isinstance(result, ParseError):
                writer.handle_event(error_event(session_id=session_id, reason=result.reason, detail=result.detail))
        _print_status(writer.stats)
    for result in parser.finish():
        if isinstance(result, ParseError):
            writer.handle_event(error_event(session_id=session_id, reason=result.reason, detail=result.detail))
    return writer


def _print_status(stats: SessionStats) -> None:
    print(
        f"\rraw={stats.raw_chunks} frames={stats.frames} errors={stats.parser_errors} bytes={stats.bytes}",
        end="",
        file=sys.stderr,
        flush=True,
    )
