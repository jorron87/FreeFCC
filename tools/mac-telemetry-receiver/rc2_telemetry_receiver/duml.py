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

    def finish(self) -> list[ParsedFrame | ParseError]:
        if not self._buffer:
            return []
        pending = len(self._buffer)
        offset = self._consumed
        self._buffer.clear()
        self._consumed += pending
        return [ParseError("truncated_frame", offset, f"pending={pending}")]


class WrappedDumlFrameParser:
    """Parses direct DUML and the 40007 55cc3075 + u32 length envelope."""

    OUTER_MAGIC = b"\x55\xcc\x30\x75"
    OUTER_HEADER_BYTES = 8
    MAX_BUFFER_BYTES = 16 * 1024

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
                marker = self._buffer.index(0x55)
            except ValueError:
                self._consumed += len(self._buffer)
                self._buffer.clear()
                break
            if marker:
                self._drop(marker)
            if len(self._buffer) < 4:
                break

            if self._buffer.startswith(self.OUTER_MAGIC):
                if len(self._buffer) < self.OUTER_HEADER_BYTES:
                    break
                inner_length = int.from_bytes(self._buffer[4:8], "little")
                if not MIN_FRAME_BYTES <= inner_length <= MAX_FRAME_BYTES:
                    results.append(ParseError("wrapped_invalid_length", self._consumed, f"inner_length={inner_length}"))
                    self._drop(1)
                    continue
                outer_length = self.OUTER_HEADER_BYTES + inner_length
                if len(self._buffer) < outer_length:
                    break
                inner = bytes(self._buffer[self.OUTER_HEADER_BYTES:outer_length])
                results.append(self._validate_single(inner, wrapped=True))
                self._drop(outer_length)
                continue

            if len(self._buffer) < MIN_FRAME_BYTES:
                break
            frame_length = self._buffer[1] | ((self._buffer[2] & 0x03) << 8)
            if not MIN_FRAME_BYTES <= frame_length <= MAX_FRAME_BYTES:
                results.append(ParseError("invalid_length", self._consumed, f"length={frame_length}"))
                self._drop(1)
                continue
            if len(self._buffer) < frame_length:
                break
            result = self._validate_single(bytes(self._buffer[:frame_length]), wrapped=False)
            results.append(result)
            self._drop(frame_length if isinstance(result, ParsedFrame) else 1)

        if len(self._buffer) > self.MAX_BUFFER_BYTES:
            dropped = len(self._buffer) - self.MAX_BUFFER_BYTES
            self._drop(dropped)
            results.append(ParseError("buffer_overflow", self._consumed, f"dropped={dropped}"))
        return results

    def finish(self) -> list[ParsedFrame | ParseError]:
        if not self._buffer:
            return []
        pending = len(self._buffer)
        offset = self._consumed
        self._buffer.clear()
        self._consumed += pending
        return [ParseError("truncated_frame", offset, f"pending={pending}")]

    def _validate_single(self, raw: bytes, *, wrapped: bool) -> ParsedFrame | ParseError:
        parsed = DumlFrameParser().feed(raw)
        if len(parsed) == 1 and isinstance(parsed[0], ParsedFrame):
            return parsed[0]
        error = next((item for item in parsed if isinstance(item, ParseError)), None)
        prefix = "wrapped_" if wrapped else ""
        return ParseError(
            prefix + (error.reason if error else "invalid_frame"),
            self._consumed,
            error.detail if error else "",
        )

    def _drop(self, count: int) -> None:
        del self._buffer[:count]
        self._consumed += count
