from __future__ import annotations

from dataclasses import dataclass


MIN_FRAME_BYTES = 13
MAX_FRAME_BYTES = 1023
MAX_BUFFER_BYTES = 4096


@dataclass(frozen=True)
class ParsedFrame:
    raw: bytes
    sender: int
    receiver: int
    sequence: int
    cmd_type: int
    cmd_set: int
    cmd_id: int
    payload_length: int
    validation_status: str = "valid"


@dataclass(frozen=True)
class ParseError:
    reason: str
    byte_offset: int
    detail: str = ""


def crc8(data: bytes) -> int:
    c = 0x77
    for byte in data:
        c ^= byte
        for _ in range(8):
            if c & 0x01:
                c = (c >> 1) ^ 0x8C
            else:
                c >>= 1
    return c & 0xFF


def crc16(data: bytes) -> int:
    c = 0x3692
    for byte in data:
        c ^= byte
        for _ in range(8):
            if c & 0x0001:
                c = (c >> 1) ^ 0x8408
            else:
                c >>= 1
    return c & 0xFFFF


def build_test_frame(
    *,
    sender: int = 0x03,
    receiver: int = 0x43,
    sequence: int = 42,
    cmd_type: int = 0,
    cmd_set: int = 0x03,
    cmd_id: int = 0x43,
    payload: bytes = b"\x01\x02\x03\x04",
) -> bytes:
    total_length = len(payload) + MIN_FRAME_BYTES
    out = bytearray(total_length)
    out[0] = 0x55
    out[1] = total_length & 0xFF
    out[2] = ((total_length >> 8) & 0x03) | 0x04
    out[3] = crc8(bytes(out[:3]))
    out[4] = sender & 0xFF
    out[5] = receiver & 0xFF
    out[6] = sequence & 0xFF
    out[7] = (sequence >> 8) & 0xFF
    out[8] = cmd_type & 0xFF
    out[9] = cmd_set & 0xFF
    out[10] = cmd_id & 0xFF
    out[11 : 11 + len(payload)] = payload
    crc = crc16(bytes(out[:-2]))
    out[-2] = crc & 0xFF
    out[-1] = (crc >> 8) & 0xFF
    return bytes(out)


class DumlFrameParser:
    def __init__(self) -> None:
        self._buffer = bytearray()
        self._consumed = 0

    def feed(self, chunk: bytes) -> list[ParsedFrame | ParseError]:
        if not chunk:
            return []
        self._buffer.extend(chunk)
        results: list[ParsedFrame | ParseError] = []

        while self._buffer:
            try:
                magic_index = self._buffer.index(0x55)
            except ValueError:
                self._consumed += len(self._buffer)
                self._buffer.clear()
                break

            if magic_index:
                del self._buffer[:magic_index]
                self._consumed += magic_index

            if len(self._buffer) < MIN_FRAME_BYTES:
                break

            total_length = self._buffer[1] | ((self._buffer[2] & 0x03) << 8)
            if total_length < MIN_FRAME_BYTES or total_length > MAX_FRAME_BYTES:
                results.append(ParseError("invalid_length", self._consumed, f"length={total_length}"))
                del self._buffer[0]
                self._consumed += 1
                continue

            if len(self._buffer) < total_length:
                break

            raw = bytes(self._buffer[:total_length])
            if crc8(raw[:3]) != raw[3]:
                results.append(ParseError("crc8_mismatch", self._consumed, f"length={total_length}"))
                del self._buffer[0]
                self._consumed += 1
                continue

            expected_crc16 = crc16(raw[:-2])
            actual_crc16 = raw[-2] | (raw[-1] << 8)
            if expected_crc16 != actual_crc16:
                results.append(ParseError("crc16_mismatch", self._consumed, f"length={total_length}"))
                del self._buffer[0]
                self._consumed += 1
                continue

            results.append(
                ParsedFrame(
                    raw=raw,
                    sender=raw[4],
                    receiver=raw[5],
                    sequence=raw[6] | (raw[7] << 8),
                    cmd_type=raw[8],
                    cmd_set=raw[9],
                    cmd_id=raw[10],
                    payload_length=total_length - MIN_FRAME_BYTES,
                )
            )
            del self._buffer[:total_length]
            self._consumed += total_length

        if len(self._buffer) > MAX_BUFFER_BYTES:
            drop = len(self._buffer) - MAX_BUFFER_BYTES
            del self._buffer[:drop]
            self._consumed += drop
            results.append(ParseError("buffer_overflow", self._consumed, f"dropped={drop}"))

        return results
