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
from .publish8902 import (
    PublishParseError,
    PublishRecord,
    Rc2PublishStreamParser,
    decode_gnss_readiness,
)
from .telemetry import decode_candidate


SCHEMA = "dji-rc2-telemetry/v2"


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
    stream_records: int = 0
    stream_record_families: dict[str, int] = field(default_factory=dict)
    georeference_samples: int = 0
    parser_errors: int = 0
    capture_gaps: int = 0
    bytes: int = 0
    last_error: str = ""
    capture_state: str = "unknown"
    source_status: str = "unknown"
    source_clock_ms: int | None = None
    latest_elapsed_realtime_ns: int = 0
    command_pairs: dict[str, int] = field(default_factory=dict)
    candidate_events: int = 0
    position: dict[str, float | None] = field(
        default_factory=lambda: {"lat_deg": None, "lon_deg": None, "alt_m": None}
    )
    attitude: dict[str, float | None] = field(
        default_factory=lambda: {"roll_deg": None, "pitch_deg": None, "yaw_deg": None}
    )
    gimbal: dict[str, float | None] = field(
        default_factory=lambda: {"roll_deg": None, "pitch_deg": None, "yaw_deg": None}
    )
    position_quality: str = "unknown"
    attitude_quality: str = "unknown"
    gimbal_quality: str = "unknown"
    altitude_quality: str = "unknown"
    raw_candidate: dict[str, Any] = field(default_factory=lambda: {"message_family": None})
    probe_results: int = 0
    last_probe: dict[str, Any] = field(default_factory=dict)
    duml_results: int = 0
    last_duml: dict[str, Any] = field(default_factory=dict)
    aircraft_identity: dict[str, Any] = field(
        default_factory=lambda: {"aircraft_serial": None, "serial_suffix": None, "model_code": None}
    )
    controller_identity: dict[str, Any] = field(
        default_factory=lambda: {"controller_serial": None, "serial_source": None}
    )
    identity_quality: str = "unknown"
    field_updated_ns: dict[str, int] = field(default_factory=dict)
    gnss_readiness: dict[str, Any] = field(default_factory=dict)


class SessionWriter:
    def __init__(self, out_root: Path) -> None:
        self.out_root = out_root
        self.session_id = "pending"
        self.session_dir: Path | None = None
        self.ndjson_path: Path | None = None
        self.raw_dir: Path | None = None
        self.raw_stream_path: Path | None = None
        self.stats = SessionStats()
        self.source_mode = ""
        self.publish_parser = Rc2PublishStreamParser()
        self.last_gnss_signature: tuple[object, ...] | None = None

    def handle_event(self, event: dict[str, Any]) -> None:
        if event.get("type") == "HELLO":
            self._start_session(str(event.get("session_id") or "unknown"))
            self.source_mode = str(event.get("source_mode") or "")
        elif self.session_dir is None:
            self._start_session(str(event.get("session_id") or "manual"))

        self._append_event(event)
        self._update_stats(event)
        if event.get("type") == "RAW_CHUNK" and self._is_publish_stream(event):
            self._handle_publish_chunk(event)
        candidate = decode_candidate(event)
        if candidate is not None:
            self._append_event(candidate)
            self._update_stats(candidate)
        if event.get("type") == "TELEMETRY_TICK":
            sample = self._build_georeference_sample(event)
            self._append_event(sample)
            self._update_stats(sample)
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
        self.raw_stream_path = self.session_dir / "raw-stream.bin"

    def _append_event(self, event: dict[str, Any]) -> None:
        assert self.ndjson_path is not None
        with self.ndjson_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, separators=(",", ":")) + "\n")

        if event.get("type") == "RAW_CHUNK":
            seq = int(event.get("seq", 0))
            data = base64.b64decode(str(event.get("bytes_b64", "")))
            assert self.raw_dir is not None
            (self.raw_dir / f"raw-{seq:08d}.bin").write_bytes(data)
            assert self.raw_stream_path is not None
            with self.raw_stream_path.open("ab") as stream:
                stream.write(data)

    def _is_publish_stream(self, event: dict[str, Any]) -> bool:
        return (
            int(event.get("port", 0) or 0) == 8902
            or str(event.get("source") or self.source_mode) == "rc2_publish_8902"
        )

    def _handle_publish_chunk(self, event: dict[str, Any]) -> None:
        data = base64.b64decode(str(event.get("bytes_b64", "")))
        elapsed_ns = int(event.get("elapsed_realtime_ns", 0) or 0)
        captured_at = str(event.get("wall_time_utc") or utc_now())
        source_id = str(event.get("source") or self.source_mode or "rc2_publish_8902")
        for result in self.publish_parser.feed(data):
            if isinstance(result, PublishParseError):
                self.stats.parser_errors += 1
                self.stats.last_error = result.reason
                continue
            assert isinstance(result, PublishRecord)
            self.stats.stream_records += 1
            family = f"{result.marker:02X}/len{result.length}"
            self.stats.stream_record_families[family] = (
                self.stats.stream_record_families.get(family, 0) + 1
            )
            self.stats.source_clock_ms = result.source_clock_ms
            candidate = decode_gnss_readiness(
                result,
                session_id=self.session_id,
                source_id=source_id,
                captured_at=captured_at,
                elapsed_realtime_ns=elapsed_ns,
            )
            if candidate is not None:
                raw = candidate.get("raw", {})
                signature = (
                    raw.get("satellites"),
                    raw.get("gps_used_candidate"),
                    raw.get("gps_level_candidate"),
                    raw.get("gps_state_raw_u32"),
                )
                if signature == self.last_gnss_signature:
                    continue
                self.last_gnss_signature = signature
                self._append_event(candidate)
                self._update_stats(candidate)

    def _update_stats(self, event: dict[str, Any]) -> None:
        event_type = event.get("type")
        if event_type == "HELLO":
            self.stats.controller_identity = {
                "controller_serial": event.get("controller_serial"),
                "serial_source": event.get("controller_serial_source"),
            }
        elif event_type == "RAW_CHUNK":
            self.stats.raw_chunks += 1
            self.stats.bytes += len(base64.b64decode(str(event.get("bytes_b64", ""))))
            self.stats.capture_state = "active"
            if self.stats.last_error.startswith("capture_gap"):
                self.stats.last_error = ""
            self.stats.latest_elapsed_realtime_ns = int(
                event.get("elapsed_realtime_ns", self.stats.latest_elapsed_realtime_ns) or 0
            )
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
        elif event_type == "SOURCE_STATUS":
            status = str(event.get("status") or "unknown")
            self.stats.source_status = status
            self.stats.latest_elapsed_realtime_ns = int(
                event.get("elapsed_realtime_ns", self.stats.latest_elapsed_realtime_ns) or 0
            )
            if status == "active":
                self.stats.capture_state = "active"
                self.stats.last_error = ""
            elif status in {"gap", "eof", "unavailable"}:
                self.stats.capture_state = "unavailable"
                self.stats.last_error = str(event.get("detail") or status)
                self._clear_candidates()
            elif status == "open_silent":
                self.stats.capture_state = "open_silent"
        elif event_type == "STREAM_RECORD_STATS":
            source_clock = int(event.get("source_clock_ms", -1) or -1)
            self.stats.source_clock_ms = source_clock if source_clock >= 0 else None
        elif event_type == "TELEMETRY_TICK":
            self.stats.latest_elapsed_realtime_ns = int(
                event.get("elapsed_realtime_ns", self.stats.latest_elapsed_realtime_ns) or 0
            )
            self.stats.source_status = str(
                event.get("source_status") or self.stats.source_status
            )
            source_clock = event.get("source_clock_ms")
            if source_clock is not None:
                self.stats.source_clock_ms = int(source_clock)
        elif event_type == "TELEMETRY_CANDIDATE":
            quality = event.get("quality", {})
            if isinstance(quality, dict) and "unavailable" in quality.values():
                self.stats.capture_state = "unavailable"
                self._clear_candidates()
            else:
                self.stats.candidate_events += 1
                family = str(event.get("raw", {}).get("message_family") or "")
                elapsed_ns = int(
                    event.get("elapsed_realtime_ns", self.stats.latest_elapsed_realtime_ns) or 0
                )
                if family == "03/43":
                    self.stats.position = dict(event.get("position", self.stats.position))
                    self.stats.attitude = dict(event.get("attitude", self.stats.attitude))
                    self.stats.position_quality = str(quality.get("position", "probable"))
                    self.stats.attitude_quality = str(quality.get("attitude", "candidate"))
                    self.stats.altitude_quality = str(quality.get("altitude", "unknown"))
                    self.stats.field_updated_ns.update(
                        {"position": elapsed_ns, "attitude": elapsed_ns, "heading": elapsed_ns}
                    )
                elif family == "03/57":
                    self.stats.position = dict(event.get("position", self.stats.position))
                    self.stats.position_quality = str(quality.get("position", "candidate"))
                    self.stats.altitude_quality = str(quality.get("altitude", "candidate"))
                    self.stats.field_updated_ns.update(
                        {"position": elapsed_ns, "altitude": elapsed_ns}
                    )
                elif family == "04/05":
                    self.stats.gimbal = dict(event.get("gimbal", self.stats.gimbal))
                    self.stats.gimbal_quality = str(quality.get("gimbal", "candidate"))
                    self.stats.field_updated_ns["gimbal"] = elapsed_ns
                elif family == "51/14":
                    self.stats.aircraft_identity = dict(event.get("identity", self.stats.aircraft_identity))
                    self.stats.identity_quality = str(quality.get("identity", "candidate"))
                    self.stats.field_updated_ns["identity"] = elapsed_ns
                elif family == "8902/F5-71":
                    self.stats.gnss_readiness = dict(event.get("raw", {}))
                self.stats.raw_candidate = dict(event.get("raw", self.stats.raw_candidate))
        elif event_type == "GEOREFERENCE_SAMPLE":
            self.stats.georeference_samples += 1
            self.stats.position = dict(event.get("position", self.stats.position))
            self.stats.attitude = dict(event.get("attitude", self.stats.attitude))
            self.stats.gimbal = dict(event.get("gimbal", self.stats.gimbal))
            quality = event.get("quality", {})
            self.stats.position_quality = str(quality.get("position", "unknown"))
            self.stats.attitude_quality = str(quality.get("attitude", "unknown"))
            self.stats.gimbal_quality = str(quality.get("gimbal", "unknown"))
            self.stats.altitude_quality = str(quality.get("altitude", "unknown"))
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

    def _build_georeference_sample(self, tick: dict[str, Any]) -> dict[str, Any]:
        now_ns = int(tick.get("elapsed_realtime_ns", 0) or 0)
        source_status = str(tick.get("source_status") or self.stats.source_status)

        def freshness(field: str, base_quality: str, max_age_ms: int) -> tuple[str, int | None]:
            updated_ns = self.stats.field_updated_ns.get(field)
            age_ms = None if updated_ns is None or now_ns <= 0 else max(0, (now_ns - updated_ns) // 1_000_000)
            if source_status != "active":
                return "unavailable", age_ms
            if updated_ns is None:
                return "unknown", None
            if age_ms is not None and age_ms > max_age_ms:
                return "unavailable", age_ms
            return base_quality, age_ms

        position_quality, position_age = freshness("position", self.stats.position_quality, 2_500)
        altitude_quality, altitude_age = freshness("altitude", self.stats.altitude_quality, 2_500)
        attitude_quality, attitude_age = freshness("attitude", self.stats.attitude_quality, 1_500)
        heading_quality, heading_age = freshness("heading", self.stats.attitude_quality, 1_500)
        gimbal_quality, gimbal_age = freshness("gimbal", self.stats.gimbal_quality, 1_500)

        def dynamic_value(value: dict[str, Any], quality: str) -> dict[str, Any]:
            if quality == "unavailable":
                return {key: None for key in value}
            return dict(value)

        position = dynamic_value(self.stats.position, position_quality)
        attitude = dynamic_value(self.stats.attitude, attitude_quality)
        gimbal = dynamic_value(self.stats.gimbal, gimbal_quality)
        heading_value = attitude.get("yaw_deg") if heading_quality != "unavailable" else None
        return {
            "type": "GEOREFERENCE_SAMPLE",
            "schema": SCHEMA,
            "session_id": self.session_id,
            "source_id": str(tick.get("source") or self.source_mode or "rc2"),
            "captured_at": str(tick.get("wall_time_utc") or utc_now()),
            "elapsed_realtime_ns": now_ns,
            "source_clock_ms": tick.get("source_clock_ms", self.stats.source_clock_ms),
            "source_status": source_status,
            "position": position,
            "altitude": {
                "value_m": position.get("alt_m"),
                "reference": (
                    "mean_sea_level_geoid"
                    if self.stats.raw_candidate.get("message_family") == "03/57"
                    else None
                ),
            },
            "attitude": attitude,
            "heading": {
                "aircraft_deg": heading_value,
                "reference": (
                    "aircraft_yaw_reference_unknown" if heading_value is not None else None
                ),
            },
            "gimbal": gimbal,
            "identity": {
                **self.stats.aircraft_identity,
                **self.stats.controller_identity,
            },
            "quality": {
                "position": position_quality,
                "altitude": altitude_quality,
                "attitude": attitude_quality,
                "heading": heading_quality,
                "gimbal": gimbal_quality,
                "identity": self.stats.identity_quality,
            },
            "age_ms": {
                "position": position_age,
                "altitude": altitude_age,
                "attitude": attitude_age,
                "heading": heading_age,
                "gimbal": gimbal_age,
            },
            "raw": {
                "message_family": self.stats.raw_candidate.get("message_family"),
                "gnss_readiness": self.stats.gnss_readiness,
            },
        }

    def _write_summary(self) -> None:
        assert self.session_dir is not None
        summary = {
            "schema": SCHEMA,
            "session_id": self.session_id,
            "updated_at": utc_now(),
            "raw_chunks": self.stats.raw_chunks,
            "duml_frames": self.stats.frames,
            "stream_records": self.stats.stream_records,
            "stream_record_families": dict(sorted(self.stats.stream_record_families.items())),
            "georeference_samples": self.stats.georeference_samples,
            "parser_errors": self.stats.parser_errors,
            "capture_gaps": self.stats.capture_gaps,
            "capture_state": self.stats.capture_state,
            "source_status": self.stats.source_status,
            "source_clock_ms": self.stats.source_clock_ms,
            "candidate_events": self.stats.candidate_events,
            "probe_results": self.stats.probe_results,
            "last_probe": self.stats.last_probe,
            "duml_results": self.stats.duml_results,
            "last_duml": self.stats.last_duml,
            "aircraft_identity": self.stats.aircraft_identity,
            "controller_identity": self.stats.controller_identity,
            "identity_quality": self.stats.identity_quality,
            "bytes": self.stats.bytes,
            "last_error": self.stats.last_error,
            "command_pairs": dict(sorted(self.stats.command_pairs.items())),
            "georef": {
                "source_id": self.session_id,
                "captured_at": utc_now(),
                "position": self.stats.position,
                "attitude": self.stats.attitude,
                "heading": {
                    "aircraft_deg": self.stats.attitude.get("yaw_deg"),
                    "reference": "aircraft_yaw_reference_unknown",
                },
                "gimbal": self.stats.gimbal,
                "quality": {
                    "position": "unavailable" if self.stats.capture_state == "unavailable" else self.stats.position_quality,
                    "attitude": "unavailable" if self.stats.capture_state == "unavailable" else self.stats.attitude_quality,
                    "heading": "unavailable" if self.stats.capture_state == "unavailable" else self.stats.attitude_quality,
                    "altitude": "unavailable" if self.stats.capture_state == "unavailable" else self.stats.altitude_quality,
                    "gimbal": "unavailable" if self.stats.capture_state == "unavailable" else self.stats.gimbal_quality,
                },
                "raw": self.stats.raw_candidate,
                "gnss_readiness": self.stats.gnss_readiness,
            },
        }
        summary_path = self.session_dir / "session-summary.json"
        temporary_path = self.session_dir / ".session-summary.json.tmp"
        temporary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
        temporary_path.replace(summary_path)

    def _clear_candidates(self) -> None:
        self.stats.position = {"lat_deg": None, "lon_deg": None, "alt_m": None}
        self.stats.attitude = {"roll_deg": None, "pitch_deg": None, "yaw_deg": None}
        self.stats.gimbal = {"roll_deg": None, "pitch_deg": None, "yaw_deg": None}
        self.stats.position_quality = "unknown"
        self.stats.attitude_quality = "unknown"
        self.stats.gimbal_quality = "unknown"
        self.stats.altitude_quality = "unknown"
        self.stats.raw_candidate = {"message_family": None}
        self.stats.aircraft_identity = {
            "aircraft_serial": None,
            "serial_suffix": None,
            "model_code": None,
        }
        self.stats.identity_quality = "unknown"
        self.stats.field_updated_ns.clear()
        self.stats.gnss_readiness = {}


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
            try:
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
            except OSError as exc:
                print(f"client socket closed: {exc}", flush=True)
            finally:
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


def request_remote_gps_hmsl(control_bind: str, port: int = 40009) -> dict[str, Any]:
    result = request_remote_duml(
        control_bind,
        {
            "sender": 0x82,
            "destination": 0x03,
            "cmd_type": 0x40,
            "cmd_set": 0x03,
            "cmd_id": 0x57,
            "payload_b64": "",
            "expect_response": True,
            "read_window_ms": 1500,
            "port": port,
        },
    )
    candidate = decode_candidate(result)
    if candidate is not None:
        result["telemetry_candidate"] = candidate
    return result


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


def replay_session(path: Path, out_root: Path) -> SessionWriter:
    """Replays source events while regenerating all derived 8902 candidates and samples."""
    writer = SessionWriter(out_root)
    source_event_types = {
        "HELLO",
        "RAW_CHUNK",
        "SOURCE_STATUS",
        "TELEMETRY_TICK",
        "STREAM_RECORD_STATS",
        "ERROR",
    }
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            event = json.loads(line)
            if event.get("type") in source_event_types:
                writer.handle_event(event)
    return writer


def _print_status(stats: SessionStats) -> None:
    print(
        f"\rsource={stats.source_status} raw={stats.raw_chunks} "
        f"records={stats.stream_records} frames={stats.frames} "
        f"samples={stats.georeference_samples} errors={stats.parser_errors} "
        f"bytes={stats.bytes}",
        end="",
        file=sys.stderr,
        flush=True,
    )
