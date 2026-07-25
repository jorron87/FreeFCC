from __future__ import annotations

import argparse
import base64
import collections
import hashlib
import json
from pathlib import Path
from typing import Any

from .flightrecord import FlightRecord, parse_d7_payload


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect nested flight records in 03/D7 DUML frames")
    parser.add_argument("session", type=Path, help="session.ndjson containing DUML_LAB_RESULT events")
    parser.add_argument("--request-id", help="limit analysis to one DUML Lab request")
    parser.add_argument("--out-records", type=Path, help="write validated nested records byte-for-byte")
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON")
    args = parser.parse_args()

    report = analyze_d7_session(
        args.session,
        request_id=args.request_id,
        record_output=args.out_records,
    )
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
        return

    print(f"D7 frames: {report['d7_frames']}")
    print(f"Nested records: {report['nested_records']}")
    print(f"Validation errors: {report['validation_errors']}")
    print("D7 headers:")
    for header, count in report["d7_headers"].items():
        print(f"  {header}: {count}")
    print("Record types:")
    for entry_type, details in report["record_types"].items():
        print(
            f"  {entry_type}: count={details['count']} "
            f"payload_lengths={details['payload_lengths']} "
            f"sequence={details['sequence_min']}..{details['sequence_max']}"
        )
    encoding = report["payload_encoding"]
    print(
        "Payload encoding: "
        f"classification={encoding['classification']} "
        f"block_aligned={encoding['all_payloads_16_byte_aligned']} "
        f"repeated_blocks={encoding['repeated_block_instances']}"
    )
    sequence = report["sequence_clock"]
    print(
        "Sequence/clock: "
        f"strictly_increasing={sequence['strictly_increasing']} "
        f"first={sequence['first']} last={sequence['last']}"
    )
    if report["record_output"]:
        print(
            f"Record stream: {report['record_output']} "
            f"bytes={report['record_output_bytes']} "
            f"sha256={report['record_output_sha256']}"
        )


def analyze_d7_session(
    path: Path,
    *,
    request_id: str | None = None,
    record_output: Path | None = None,
) -> dict[str, Any]:
    headers: collections.Counter[str] = collections.Counter()
    record_types: dict[int, list[FlightRecord]] = collections.defaultdict(list)
    ordered_records: list[FlightRecord] = []
    errors: collections.Counter[str] = collections.Counter()
    d7_frames = 0

    with path.open(encoding="utf-8") as handle:
        for line in handle:
            event = json.loads(line)
            if event.get("type") != "DUML_LAB_RESULT":
                continue
            if request_id and event.get("request_id") != request_id:
                continue
            for frame in event.get("frames", []):
                if int(frame.get("cmd_set", -1)) != 0x03 or int(frame.get("cmd_id", -1)) != 0xD7:
                    continue
                raw = base64.b64decode(frame["raw_frame_b64"])
                if len(raw) < 13:
                    errors["outer_frame_truncated"] += 1
                    continue
                d7_frames += 1
                chunk = parse_d7_payload(raw[11:-2])
                headers[chunk.header.hex()] += 1
                for error in chunk.errors:
                    errors[error.reason] += 1
                for record in chunk.records:
                    record_types[record.entry_type].append(record)
                    ordered_records.append(record)

    records = ordered_records
    block_counts: collections.Counter[bytes] = collections.Counter(
        block
        for record in records
        for block in _blocks(record.encoded_payload)
    )
    repeated_blocks = {
        block.hex(): count
        for block, count in block_counts.most_common(10)
        if count > 1
    }
    all_block_aligned = bool(records) and all(
        len(record.encoded_payload) % 16 == 0 for record in records
    )
    output_sha256 = None
    output_bytes = 0
    if record_output is not None:
        record_output.parent.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256()
        with record_output.open("wb") as output:
            for record in records:
                output.write(record.raw)
                digest.update(record.raw)
                output_bytes += len(record.raw)
        output_sha256 = digest.hexdigest()

    return {
        "session": str(path),
        "request_id": request_id,
        "d7_frames": d7_frames,
        "nested_records": sum(len(records) for records in record_types.values()),
        "validation_errors": dict(sorted(errors.items())),
        "d7_headers": dict(sorted(headers.items())),
        "record_types": {
            f"0x{entry_type:04X}": {
                "count": len(records),
                "payload_lengths": sorted({len(record.encoded_payload) for record in records}),
                "sequence_min": min(record.sequence for record in records),
                "sequence_max": max(record.sequence for record in records),
            }
            for entry_type, records in sorted(record_types.items())
        },
        "payload_encoding": {
            "classification": (
                "block_cipher_or_block_compression_candidate"
                if all_block_aligned
                else "unknown"
            ),
            "all_payloads_16_byte_aligned": all_block_aligned,
            "unique_blocks": len(block_counts),
            "total_blocks": sum(block_counts.values()),
            "repeated_block_instances": sum(count - 1 for count in block_counts.values() if count > 1),
            "most_common_repeated_blocks": repeated_blocks,
            "legacy_xor_status": "not_promoted_no_neo2_plaintext_validation",
        },
        "sequence_clock": {
            "strictly_increasing": all(
                later.sequence > earlier.sequence
                for earlier, later in zip(records, records[1:])
            ),
            "first": records[0].sequence if records else None,
            "last": records[-1].sequence if records else None,
            "scale_hz": None,
            "classification": "monotonic_clock_candidate",
        },
        "record_output": str(record_output) if record_output else None,
        "record_output_bytes": output_bytes,
        "record_output_sha256": output_sha256,
    }


def _blocks(payload: bytes) -> list[bytes]:
    return [
        payload[offset : offset + 16]
        for offset in range(0, len(payload), 16)
        if len(payload[offset : offset + 16]) == 16
    ]


if __name__ == "__main__":
    main()
