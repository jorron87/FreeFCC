from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
from typing import Any, Iterable

DEFAULT_API_KEY_ENV = "DJI_FLIGHTLOG_APP_KEY"
SCHEMA = "dji-flightlog-oracle/v1"


def _number_or_none(value: Any, *, minimum: float, maximum: float) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(number) or not minimum <= number <= maximum:
        return None
    return number


def _coordinate_or_none(value: Any, *, latitude: bool) -> float | None:
    limit = 90.0 if latitude else 180.0
    number = _number_or_none(value, minimum=-limit, maximum=limit)
    return None if number == 0.0 else number


def build_oracle_rows(
    parsed: dict[str, Any],
    *,
    session_id: str,
    interval_s: float = 1.0,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    last_bucket: int | None = None
    details = parsed.get("details") or {}
    summary = parsed.get("summary") or {}

    for frame_index, frame in enumerate(parsed.get("frames") or []):
        osd = frame.get("osd") or {}
        fly_time_s = _number_or_none(
            osd.get("flyTime"), minimum=0.0, maximum=7 * 24 * 60 * 60
        )
        if fly_time_s is None:
            continue

        if interval_s > 0:
            bucket = math.floor(fly_time_s / interval_s)
            if bucket == last_bucket:
                continue
            last_bucket = bucket

        home = frame.get("home") or {}
        gimbal = frame.get("gimbal") or {}
        custom = frame.get("custom") or {}
        latitude = _coordinate_or_none(osd.get("latitude"), latitude=True)
        longitude = _coordinate_or_none(osd.get("longitude"), latitude=False)
        gps_valid = bool(osd.get("isGpdUsed"))
        home_altitude = _number_or_none(
            home.get("altitude"), minimum=-1000.0, maximum=20_000.0
        )
        altitude_candidate = _number_or_none(
            osd.get("altitude"), minimum=-1000.0, maximum=20_000.0
        )

        rows.append(
            {
                "type": "TELEMETRY_ORACLE",
                "schema": SCHEMA,
                "source_id": summary.get("aircraftSn")
                or details.get("aircraftSn")
                or "unknown",
                "session_id": session_id,
                "captured_at": custom.get("dateTime"),
                "source_time_s": fly_time_s,
                "position": {
                    "latitude": latitude,
                    "longitude": longitude,
                    "height_takeoff_m": _number_or_none(
                        osd.get("height"), minimum=-1000.0, maximum=20_000.0
                    ),
                    "home_altitude_candidate_m": home_altitude,
                    "altitude_candidate_msl_m": altitude_candidate,
                },
                "attitude": {
                    "pitch_deg": _number_or_none(
                        osd.get("pitch"), minimum=-180.0, maximum=180.0
                    ),
                    "roll_deg": _number_or_none(
                        osd.get("roll"), minimum=-180.0, maximum=180.0
                    ),
                    "yaw_deg": _number_or_none(
                        osd.get("yaw"), minimum=-360.0, maximum=360.0
                    ),
                },
                "gimbal": {
                    "pitch_deg": _number_or_none(
                        gimbal.get("pitch"), minimum=-180.0, maximum=180.0
                    ),
                    "roll_deg": _number_or_none(
                        gimbal.get("roll"), minimum=-180.0, maximum=180.0
                    ),
                    "yaw_deg": _number_or_none(
                        gimbal.get("yaw"), minimum=-360.0, maximum=360.0
                    ),
                },
                "quality": {
                    "position": "candidate" if gps_valid and latitude is not None else "unknown",
                    "attitude": "candidate",
                    "gimbal": "candidate",
                    "height": "candidate"
                    if home_altitude is not None and altitude_candidate is not None
                    else "unknown",
                    "gps_valid": gps_valid,
                    "gps_satellites": osd.get("gpsNum"),
                    "gps_level": osd.get("gpsLevel"),
                    "height_reference": "home_plus_takeoff_relative_candidate"
                    if home_altitude is not None
                    else "unknown",
                },
                "raw": {
                    "message_family": "dji_fly_flight_record",
                    "frame_index": frame_index,
                    "parser_version": parsed.get("version"),
                },
            }
        )

    return rows


def _write_ndjson(path: Path, events: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for event in events:
            handle.write(json.dumps(event, separators=(",", ":"), sort_keys=True))
            handle.write("\n")


def _load_parser() -> Any:
    try:
        from dji_flightlog_parser import parse_file
    except ImportError as exc:
        raise SystemExit(
            "Missing optional dependency. Install with "
            "'python3 -m pip install -r requirements-flightlog.txt'."
        ) from exc
    return parse_file


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Decode a DJI Fly flight log into a one-Hz research oracle."
    )
    parser.add_argument("flight_log", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--interval-seconds", type=float, default=1.0)
    parser.add_argument("--api-key-env", default=DEFAULT_API_KEY_ENV)
    parser.add_argument(
        "--allow-dji-keychain-api",
        action="store_true",
        help="Explicitly allow sending encrypted keychain material to DJI OpenAPI.",
    )
    args = parser.parse_args()

    if not args.allow_dji_keychain_api:
        parser.error("--allow-dji-keychain-api is required for this research-only cloud call")
    if args.interval_seconds < 0:
        parser.error("--interval-seconds must be zero or positive")

    api_key = os.environ.get(args.api_key_env)
    if not api_key:
        parser.error(f"missing API key in environment variable {args.api_key_env}")

    flight_log = args.flight_log.expanduser().resolve()
    raw = flight_log.read_bytes()
    session_id = hashlib.sha256(raw).hexdigest()[:24]
    output = args.out or flight_log.with_suffix(".oracle.ndjson")
    summary_output = args.summary or flight_log.with_suffix(".oracle-summary.json")

    parse_file = _load_parser()
    parsed = parse_file(flight_log, api_key=api_key, use_cache=True)
    rows = build_oracle_rows(
        parsed,
        session_id=session_id,
        interval_s=args.interval_seconds,
    )
    hello = {
        "type": "ORACLE_HELLO",
        "schema": SCHEMA,
        "session_id": session_id,
        "source_file_sha256": hashlib.sha256(raw).hexdigest(),
        "parser_version": parsed.get("version"),
        "frames_decoded": len(parsed.get("frames") or []),
        "rows_written": len(rows),
        "cloud_keychain_lookup": True,
    }
    _write_ndjson(output, [hello, *rows])

    summary = {
        **hello,
        "output": str(output.resolve()),
        "details": parsed.get("details") or {},
        "flight_summary": parsed.get("summary") or {},
        "quality_note": (
            "Flight-log values are a research oracle. Home altitude plus relative "
            "height remains candidate until independently checked against AMSL."
        ),
    }
    summary_output.parent.mkdir(parents=True, exist_ok=True)
    summary_output.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
