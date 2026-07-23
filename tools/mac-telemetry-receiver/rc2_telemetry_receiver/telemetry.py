from __future__ import annotations

import base64
import collections
import json
import math
import re
import struct
from pathlib import Path
from typing import Any


LAYOUT_REFERENCE = "o-gs/dji-firmware-tools@1956922"
NEO2_CORRELATION = "Neo 2 + RC2 operator correlation 2026-07-23"


def decode_candidate(event: dict[str, Any]) -> dict[str, Any] | None:
    common = {
        "type": "TELEMETRY_CANDIDATE",
        "schema": event.get("schema", "dji-rc2-telemetry/v1"),
        "session_id": event.get("session_id", ""),
        "source_id": event.get("source", "rc2"),
        "captured_at": event.get("wall_time_utc") or event.get("captured_at", ""),
        "elapsed_realtime_ns": int(event.get("elapsed_realtime_ns", 0) or 0),
    }
    if event.get("type") == "DUML_RESULT":
        request = event.get("request", {})
        response = event.get("response", {})
        if (
            event.get("status") == "ok"
            and int(request.get("cmd_set", -1)) == 0x03
            and int(request.get("cmd_id", -1)) == 0x57
            and response.get("payload_b64")
        ):
            return _decode_gps_glns(base64.b64decode(response["payload_b64"]), common)
        return None

    if event.get("type") != "DUML_FRAME":
        return None

    payload = _payload(event)
    cmd_set = int(event.get("cmd_set", -1))
    cmd_id = int(event.get("cmd_id", -1))

    if cmd_set == 0x03 and cmd_id == 0x43 and len(payload) >= 30:
        longitude_raw, latitude_raw = struct.unpack_from("<dd", payload, 0)
        relative_height_raw = struct.unpack_from("<h", payload, 16)[0]
        pitch_raw, roll_raw, yaw_raw = struct.unpack_from("<hhh", payload, 24)
        longitude_deg = _radians_candidate(longitude_raw, math.pi)
        latitude_deg = _radians_candidate(latitude_raw, math.pi / 2)
        position_quality = "probable" if latitude_deg is not None and longitude_deg is not None else "unknown"
        return {
            **common,
            "position": {"lat_deg": latitude_deg, "lon_deg": longitude_deg, "alt_m": None},
            "attitude": {
                "roll_deg": roll_raw * 0.1,
                "pitch_deg": pitch_raw * 0.1,
                "yaw_deg": yaw_raw * 0.1,
            },
            "heading": {
                "aircraft_deg": yaw_raw * 0.1,
                "reference": "aircraft_yaw_reference_unknown",
            },
            "gimbal": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
            "quality": {
                "position": position_quality,
                "attitude": "probable",
                "heading": "probable",
                "altitude": "unknown",
                "gimbal": "unknown",
            },
            "raw": {
                "message_family": "03/43",
                "layout_reference": LAYOUT_REFERENCE,
                "verification_scope": NEO2_CORRELATION,
                "longitude_raw_f64": _finite_or_none(longitude_raw),
                "latitude_raw_f64": _finite_or_none(latitude_raw),
                "longitude_if_radians_deg": longitude_deg,
                "latitude_if_radians_deg": latitude_deg,
                "relative_height_raw_i16": relative_height_raw,
                "relative_height_m_candidate": relative_height_raw * 0.1,
                "relative_height_reference": "takeoff_relative_not_amsl",
            },
        }

    if cmd_set == 0x03 and cmd_id == 0x57 and len(payload) >= 12:
        return _decode_gps_glns(payload, common)

    if cmd_set == 0x04 and cmd_id == 0x05 and len(payload) >= 6:
        pitch_raw, roll_raw, yaw_raw = struct.unpack_from("<hhh", payload, 0)
        return {
            **common,
            "position": {"lat_deg": None, "lon_deg": None, "alt_m": None},
            "attitude": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
            "gimbal": {
                "roll_deg": roll_raw * 0.1,
                "pitch_deg": pitch_raw * 0.1,
                "yaw_deg": yaw_raw * 0.1,
            },
            "quality": {"position": "unknown", "attitude": "unknown", "gimbal": "candidate"},
            "raw": {"message_family": "04/05", "layout_reference": LAYOUT_REFERENCE},
        }

    if cmd_set == 0x03 and cmd_id == 0x44 and len(payload) >= 22:
        home_longitude_raw, home_latitude_raw = struct.unpack_from("<dd", payload, 0)
        home_altitude_raw = struct.unpack_from("<f", payload, 16)[0]
        home_state = struct.unpack_from("<H", payload, 20)[0]
        return {
            **common,
            "position": {"lat_deg": None, "lon_deg": None, "alt_m": None},
            "home": {
                "lat_deg": _radians_candidate(home_latitude_raw, math.pi / 2),
                "lon_deg": _radians_candidate(home_longitude_raw, math.pi),
                "alt_m": None,
            },
            "attitude": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
            "heading": {"aircraft_deg": None, "reference": None},
            "gimbal": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
            "quality": {
                "position": "unknown",
                "home": "candidate",
                "attitude": "unknown",
                "heading": "unknown",
                "altitude": "rejected",
                "gimbal": "unknown",
            },
            "raw": {
                "message_family": "03/44",
                "layout_reference": LAYOUT_REFERENCE,
                "home_state_raw_u16": home_state,
                "home_point_recorded_candidate": bool(home_state & 0x01),
                "home_altitude_raw_f32": _finite_or_none(home_altitude_raw),
                "home_altitude_classification": "pressure_or_fused_datum_not_geodetic",
            },
        }

    if (
        cmd_set == 0x51
        and cmd_id == 0x14
        and int(event.get("sender", -1)) == 0xEE
        and (int(event.get("receiver", -1)) & 0x1F) == 0x02
        and event.get("validation_status") == "valid"
    ):
        identity = _identity_candidates(payload)
        if any(identity.values()):
            return {
                **common,
                "position": {"lat_deg": None, "lon_deg": None, "alt_m": None},
                "attitude": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
                "gimbal": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
                "identity": identity,
                "quality": {
                    "position": "unknown",
                    "attitude": "unknown",
                    "gimbal": "unknown",
                    "identity": "candidate",
                },
                "raw": {
                    "message_family": "51/14",
                    "layout_reference": "SkylabFCCfree live map, frame-scoped extraction",
                },
            }

    return None


def analyze_session(path: Path) -> dict[str, Any]:
    families: collections.Counter[str] = collections.Counter()
    candidates: collections.Counter[str] = collections.Counter()
    latest_candidates: dict[str, dict[str, Any]] = {}
    rc_channels: list[list[int]] = []

    with path.open(encoding="utf-8") as handle:
        for line in handle:
            event = json.loads(line)
            if event.get("type") != "DUML_FRAME":
                continue
            family = f"{int(event.get('cmd_set', 0)):02X}/{int(event.get('cmd_id', 0)):02X}"
            families[family] += 1
            candidate = decode_candidate(event)
            if candidate is not None:
                candidates[family] += 1
                latest_candidates[family] = candidate
            if family == "06/AE":
                payload = _payload(event)
                if len(payload) == 17:
                    rc_channels.append(list(struct.unpack("<8H", payload[1:])))

    rc_ranges = None
    if rc_channels:
        rc_ranges = [
            {"channel": i, "min": min(row[i] for row in rc_channels), "max": max(row[i] for row in rc_channels)}
            for i in range(8)
        ]

    return {
        "session": str(path),
        "frame_families": dict(sorted(families.items())),
        "candidate_frames": dict(sorted(candidates.items())),
        "latest_candidates": latest_candidates,
        "missing_georeference_families": [
            family for family in ("03/43", "03/44", "03/57", "04/05") if not families[family]
        ],
        "missing_identity_families": [family for family in ("51/14",) if not families[family]],
        "rc_input_candidate_06_AE": {
            "classification": "candidate_rc_channels_not_camera_attitude",
            "ranges": rc_ranges,
        },
    }


def _payload(event: dict[str, Any]) -> bytes:
    raw = base64.b64decode(str(event.get("raw_frame_b64", "")))
    return raw[11:-2] if len(raw) >= 13 else b""


def _decode_gps_glns(payload: bytes, common: dict[str, Any]) -> dict[str, Any] | None:
    if len(payload) < 12:
        return None
    longitude_raw, latitude_raw, hmsl_raw = struct.unpack_from("<iii", payload, 0)
    longitude_deg = longitude_raw / 10_000_000
    latitude_deg = latitude_raw / 10_000_000
    hmsl_m = hmsl_raw * 0.001
    coordinates_valid = -180 <= longitude_deg <= 180 and -90 <= latitude_deg <= 90
    altitude_valid = -1_000 <= hmsl_m <= 20_000
    return {
        **common,
        "position": {
            "lat_deg": latitude_deg if coordinates_valid else None,
            "lon_deg": longitude_deg if coordinates_valid else None,
            "alt_m": hmsl_m if altitude_valid else None,
        },
        "altitude": {
            "value_m": hmsl_m if altitude_valid else None,
            "reference": "mean_sea_level_geoid",
            "source": "gnss_hmsl",
        },
        "attitude": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
        "heading": {"aircraft_deg": None, "reference": None},
        "gimbal": {"roll_deg": None, "pitch_deg": None, "yaw_deg": None},
        "quality": {
            "position": "candidate" if coordinates_valid else "unknown",
            "attitude": "unknown",
            "heading": "unknown",
            "altitude": "candidate" if altitude_valid else "unknown",
            "gimbal": "unknown",
        },
        "raw": {
            "message_family": "03/57",
            "layout_reference": LAYOUT_REFERENCE,
            "longitude_raw_i32": longitude_raw,
            "latitude_raw_i32": latitude_raw,
            "hmsl_raw_i32_mm": hmsl_raw,
            "satellites": struct.unpack_from("<H", payload, 28)[0] if len(payload) >= 30 else None,
            "sequence": struct.unpack_from("<H", payload, 30)[0] if len(payload) >= 32 else None,
            "home_point_recorded_candidate": payload[33] != 0 if len(payload) >= 34 else None,
        },
    }


def _finite_or_none(value: float) -> float | None:
    return value if math.isfinite(value) else None


def _radians_candidate(value: float, bound: float) -> float | None:
    if not math.isfinite(value) or abs(value) > bound:
        return None
    return math.degrees(value)


def _identity_candidates(payload: bytes) -> dict[str, str | None]:
    upper = payload.upper()

    def match(pattern: bytes) -> str | None:
        found = re.search(pattern, upper)
        return found.group().decode("ascii") if found else None

    return {
        "aircraft_serial": match(rb"(?<![A-Z0-9])1581[A-Z0-9]{12,18}(?![A-Z0-9])"),
        "serial_suffix": match(rb"(?<![A-Z0-9])FA[A-Z0-9]{14}(?![A-Z0-9])"),
        "model_code": match(rb"(?<![A-Z0-9])W[AM][0-9]{3}(?![A-Z0-9])"),
    }
