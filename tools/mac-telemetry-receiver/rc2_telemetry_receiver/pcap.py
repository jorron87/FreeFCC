from __future__ import annotations

import io
import struct
from collections.abc import Iterator


LINKTYPE_NULL = 0
LINKTYPE_ETHERNET = 1
LINKTYPE_RAW = 101
LINKTYPE_LINUX_SLL = 113
LINKTYPE_LINUX_SLL2 = 276


def iter_pcap_tcp_payloads(stream: io.BufferedReader, port: int = 40009) -> Iterator[bytes]:
    header = stream.read(24)
    if len(header) < 24:
        return
    magic = header[:4]
    if magic == b"\xd4\xc3\xb2\xa1":
        endian = "<"
    elif magic == b"\xa1\xb2\xc3\xd4":
        endian = ">"
    elif magic == b"\x4d\x3c\xb2\xa1":
        endian = "<"
    elif magic == b"\xa1\xb2\x3c\x4d":
        endian = ">"
    else:
        raise ValueError("unsupported pcap magic")

    _version_major, _version_minor, _thiszone, _sigfigs, _snaplen, network = struct.unpack(
        f"{endian}HHiiii", header[4:]
    )

    while True:
        record_header = stream.read(16)
        if not record_header:
            break
        if len(record_header) < 16:
            raise ValueError("truncated pcap record header")
        _ts_sec, _ts_usec, incl_len, _orig_len = struct.unpack(f"{endian}IIII", record_header)
        packet = stream.read(incl_len)
        if len(packet) < incl_len:
            raise ValueError("truncated pcap packet")
        payload = tcp_payload(packet, network, port)
        if payload:
            yield payload


def tcp_payload(packet: bytes, linktype: int, port: int) -> bytes | None:
    ip_packet = _ip_packet(packet, linktype)
    if not ip_packet or len(ip_packet) < 20:
        return None

    version = ip_packet[0] >> 4
    if version == 4:
        ihl = (ip_packet[0] & 0x0F) * 4
        if len(ip_packet) < ihl + 20 or ip_packet[9] != 6:
            return None
        total_length = int.from_bytes(ip_packet[2:4], "big")
        tcp = ip_packet[ihl:total_length]
    elif version == 6:
        if len(ip_packet) < 40 or ip_packet[6] != 6:
            return None
        payload_length = int.from_bytes(ip_packet[4:6], "big")
        tcp = ip_packet[40 : 40 + payload_length]
    else:
        return None

    if len(tcp) < 20:
        return None
    src_port = int.from_bytes(tcp[0:2], "big")
    dst_port = int.from_bytes(tcp[2:4], "big")
    if src_port != port and dst_port != port:
        return None
    data_offset = (tcp[12] >> 4) * 4
    if len(tcp) < data_offset:
        return None
    payload = tcp[data_offset:]
    return payload or None


def _ip_packet(packet: bytes, linktype: int) -> bytes | None:
    if linktype == LINKTYPE_RAW:
        return packet
    if linktype == LINKTYPE_ETHERNET:
        if len(packet) < 14:
            return None
        eth_type = int.from_bytes(packet[12:14], "big")
        if eth_type not in (0x0800, 0x86DD):
            return None
        return packet[14:]
    if linktype == LINKTYPE_NULL:
        return packet[4:] if len(packet) >= 4 else None
    if linktype == LINKTYPE_LINUX_SLL:
        if len(packet) < 16:
            return None
        proto = int.from_bytes(packet[14:16], "big")
        if proto not in (0x0800, 0x86DD):
            return None
        return packet[16:]
    if linktype == LINKTYPE_LINUX_SLL2:
        if len(packet) < 20:
            return None
        proto = int.from_bytes(packet[0:2], "big")
        if proto not in (0x0800, 0x86DD):
            return None
        return packet[20:]
    return None
