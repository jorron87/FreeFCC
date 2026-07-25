from __future__ import annotations

from dataclasses import dataclass

from .duml import crc16, crc8


MIN_RECORD_BYTES = 12
MAX_RECORD_BYTES = 1023
D7_CHUNK_HEADER_BYTES = 3


@dataclass(frozen=True)
class FlightRecord:
    raw: bytes
    protocol_version: int
    entry_type: int
    sequence: int
    encoded_payload: bytes
    legacy_xor_payload: bytes
    validation_status: str = "valid"


@dataclass(frozen=True)
class FlightRecordError:
    reason: str
    byte_offset: int
    detail: str = ""


@dataclass(frozen=True)
class D7FlightRecordChunk:
    header: bytes
    records: tuple[FlightRecord, ...]
    errors: tuple[FlightRecordError, ...]
    trailing_bytes: bytes


def parse_d7_payload(payload: bytes) -> D7FlightRecordChunk:
    """Parse the opaque three-byte 03/D7 prefix and its nested record stream."""
    if len(payload) < D7_CHUNK_HEADER_BYTES:
        return D7FlightRecordChunk(
            header=payload,
            records=(),
            errors=(FlightRecordError("d7_header_truncated", 0, f"length={len(payload)}"),),
            trailing_bytes=b"",
        )

    header = payload[:D7_CHUNK_HEADER_BYTES]
    stream = payload[D7_CHUNK_HEADER_BYTES:]
    records: list[FlightRecord] = []
    errors: list[FlightRecordError] = []
    offset = 0

    while offset < len(stream):
        remaining = len(stream) - offset
        if remaining < MIN_RECORD_BYTES:
            errors.append(
                FlightRecordError("record_truncated", offset, f"remaining={remaining}")
            )
            break

        if stream[offset] != 0x55:
            next_magic = stream.find(b"\x55", offset + 1)
            skipped = remaining if next_magic < 0 else next_magic - offset
            errors.append(
                FlightRecordError("record_magic_missing", offset, f"skipped={skipped}")
            )
            if next_magic < 0:
                offset = len(stream)
                break
            offset = next_magic
            continue

        encoded_length = int.from_bytes(stream[offset + 1 : offset + 3], "little")
        record_length = encoded_length & 0x03FF
        protocol_version = (encoded_length & 0xFC00) >> 10
        if not MIN_RECORD_BYTES <= record_length <= MAX_RECORD_BYTES:
            errors.append(
                FlightRecordError(
                    "record_invalid_length",
                    offset,
                    f"length={record_length}",
                )
            )
            offset += 1
            continue
        if remaining < record_length:
            errors.append(
                FlightRecordError(
                    "record_truncated",
                    offset,
                    f"expected={record_length} remaining={remaining}",
                )
            )
            break

        raw = stream[offset : offset + record_length]
        if crc8(raw[:3]) != raw[3]:
            errors.append(
                FlightRecordError("record_crc8_mismatch", offset, f"length={record_length}")
            )
            offset += 1
            continue

        expected_crc16 = crc16(raw[:-2])
        actual_crc16 = int.from_bytes(raw[-2:], "little")
        if expected_crc16 != actual_crc16:
            errors.append(
                FlightRecordError(
                    "record_crc16_mismatch",
                    offset,
                    f"expected={expected_crc16:04x} actual={actual_crc16:04x}",
                )
            )
            offset += 1
            continue

        entry_type = int.from_bytes(raw[4:6], "little")
        sequence = int.from_bytes(raw[6:10], "little")
        encoded_payload = raw[10:-2]
        xor_key = sequence & 0xFF
        legacy_xor_payload = bytes(byte ^ xor_key for byte in encoded_payload)
        records.append(
            FlightRecord(
                raw=raw,
                protocol_version=protocol_version,
                entry_type=entry_type,
                sequence=sequence,
                encoded_payload=encoded_payload,
                legacy_xor_payload=legacy_xor_payload,
            )
        )
        offset += record_length

    return D7FlightRecordChunk(
        header=header,
        records=tuple(records),
        errors=tuple(errors),
        trailing_bytes=stream[offset:],
    )
