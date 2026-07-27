from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Iterator


MARKERS = {0xF5, 0xF6, 0xF8}
HEADER_SIZE = 8
MAX_RECORD_LENGTH = 65_535
MAX_PENDING_BYTES = 256 * 1024


@dataclass(frozen=True)
class PublishRecord:
    marker: int
    length: int
    source_clock_ms: int
    raw: bytes


@dataclass(frozen=True)
class PublishParseError:
    reason: str
    detail: str


class Rc2PublishStreamParser:
    def __init__(self) -> None:
        self._pending = bytearray()

    def feed(self, data: bytes) -> list[PublishRecord | PublishParseError]:
        if not data:
            return []
        self._pending.extend(data)
        results: list[PublishRecord | PublishParseError] = []

        if len(self._pending) > MAX_PENDING_BYTES:
            results.append(
                PublishParseError(
                    "publish_buffer_overflow",
                    f"pending={len(self._pending)} max={MAX_PENDING_BYTES}",
                )
            )
            self._pending = self._pending[-(HEADER_SIZE - 1) :]

        while len(self._pending) >= 2:
            marker_offset = self._find_marker()
            if marker_offset < 0:
                self._pending = self._pending[-1:] if self._pending[-1] in MARKERS else bytearray()
                break
            if marker_offset:
                results.append(PublishParseError("publish_resync", f"discarded={marker_offset}"))
                del self._pending[:marker_offset]
            if len(self._pending) < HEADER_SIZE:
                break

            length = struct.unpack_from("<H", self._pending, 2)[0]
            if length < HEADER_SIZE or length > MAX_RECORD_LENGTH:
                results.append(
                    PublishParseError(
                        "publish_invalid_length",
                        f"marker={self._pending[0]:02X}64 length={length}",
                    )
                )
                del self._pending[0]
                continue
            if len(self._pending) < length:
                break

            raw = bytes(self._pending[:length])
            del self._pending[:length]
            results.append(
                PublishRecord(
                    marker=raw[0],
                    length=length,
                    source_clock_ms=struct.unpack_from("<I", raw, 4)[0],
                    raw=raw,
                )
            )
        return results

    def finish(self) -> list[PublishParseError]:
        if not self._pending:
            return []
        error = PublishParseError("publish_truncated_record", f"remaining={len(self._pending)}")
        self._pending.clear()
        return [error]

    def _find_marker(self) -> int:
        for index in range(len(self._pending) - 1):
            if self._pending[index] in MARKERS and self._pending[index + 1] == 0x64:
                return index
        return -1


def decode_gnss_readiness(
    record: PublishRecord,
    *,
    session_id: str,
    source_id: str,
    captured_at: str,
    elapsed_realtime_ns: int,
) -> dict[str, object] | None:
    if (
        record.marker != 0xF5
        or record.length != 71
        or record.raw[61:65] != b"\x0f\x8a\x20\x7f"
    ):
        return None

    gps_state = struct.unpack_from("<I", record.raw, 48)[0]
    satellites = record.raw[52]
    gps_level = record.raw[55] & 0x0F
    gps_used = bool(gps_state & 0x0001_0000)
    return {
        "type": "TELEMETRY_CANDIDATE",
        "schema": "dji-rc2-telemetry/v2",
        "session_id": session_id,
        "source_id": source_id,
        "captured_at": captured_at,
        "elapsed_realtime_ns": elapsed_realtime_ns,
        "position": {"lat_deg": None, "lon_deg": None, "alt_m": None},
        "attitude": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
        "heading": {"aircraft_deg": None, "reference": None},
        "gimbal": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
        "quality": {
            "position": "unknown",
            "attitude": "unknown",
            "heading": "unknown",
            "altitude": "unknown",
            "gimbal": "unknown",
            "gnss_readiness": "candidate",
        },
        "raw": {
            "message_family": "8902/F5-71",
            "layout_reference": "SkylabFCCfree RC2 port map",
            "source_clock_ms": record.source_clock_ms,
            "gps_state_raw_u32": gps_state,
            "satellites": satellites,
            "gps_used_candidate": gps_used,
            "gps_level_candidate": gps_level,
        },
    }


def iter_records(chunks: Iterator[bytes]) -> Iterator[PublishRecord | PublishParseError]:
    parser = Rc2PublishStreamParser()
    for chunk in chunks:
        yield from parser.feed(chunk)
    yield from parser.finish()
