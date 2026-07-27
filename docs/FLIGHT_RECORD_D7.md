# `03/D7` Flight Record Research

## Status

`03/D7` is confirmed as a live flight-controller record transport on the
tested Neo 2 + RC2 combination. It is not yet a decoded metadata source.

The evidence boundary is:

- confirmed: outer DUML frames and nested flight-record framing;
- confirmed: nested header CRC-8 and record CRC-16;
- observed: modern record types associated with controller, IMU, battery,
  sensor and ESC data;
- candidate: independently encrypted or block-compressed 16-byte payloads;
- unknown: payload key/codec and current Neo 2 field layouts;
- not promoted: position, altitude, attitude or gimbal values from `03/D7`.

## Captured structure

Every observed `03/D7` payload starts with:

```text
01 02 00
```

DJI's decompiled `DataFlycGetPushFlightRecord` model identifies byte 1 value
`02` as the `Write` RPC command and treats bytes after offset 3 as the
compressed flight-record package. The three-byte prefix remains opaque in our
wire schema.

Bytes after the prefix are concatenated record packets:

```text
55
length_and_protocol_version  u16le
header_crc8                  u8
entry_type                   u16le
sequence_or_clock            u32le
encoded_payload              bytes
record_crc16                 u16le
```

The length occupies the low ten bits. CRC algorithms are the same DJI Packet
`0x55` CRC-8/CRC-16 profiles already used by the DUML parser.

## Neo 2 evidence

The bounded `40007` passive reconnect capture on 2026-07-25 produced:

- 59 outer `03/D7` frames;
- 518 nested records;
- 52,680 validated nested-record bytes;
- zero nested CRC or framing errors;
- constant outer payload prefix `010200`;
- 29 record types;
- strictly increasing record sequence/clock values across all 518 records.

High-interest observed types include:

| Type | Count | Reference name |
|---|---:|---|
| `0x03E8` | 21 | Controller |
| `0x06AE` | 6 | Battery Info |
| `0x0800` | 20 | IMU Atti 0 |
| `0x0801` | 77 | IMU Atti 1 |
| `0x08A0` | 9 | Atti Mini 0 |
| `0x2765` | 5 | Navigation/sensor debug |
| `0x276A` | 79 | ESC Data |

The names are references from the Mavic flight-record dissector in
`o-gs/dji-firmware-tools`, not verified Neo 2 layouts.

All 518 encoded payloads are multiples of 16 bytes. Across 2,904 blocks, 795
are repeated instances. Some later blocks stay byte-identical while earlier
blocks in the same record type change. This is strong evidence for independent
block encryption, or an equivalent block-reset transform, rather than the
legacy one-byte sequence XOR alone.

The record sequence/clock increased from `458781870` to `487657254` with no
reversal in capture order. This supports a live rolling stream rather than
random historical pages and gives us a future timing anchor. Its tick scale is
not yet verified.

The legacy XOR transform is retained as a research candidate but does not
produce position or attitude matching the simultaneous, physically correlated
`03/43` data. It must not feed `TELEMETRY_CANDIDATE` or
`GEOREFERENCE_SAMPLE`.

## Offline analysis

Analyze a retained session and export validated nested packets:

```sh
cd tools/mac-telemetry-receiver
python -m rc2_telemetry_receiver.flightrecord_analyze \
  /path/to/session.ndjson \
  --request-id REQUEST_ID \
  --out-records /path/to/flight-record.bin
```

Add `--json` for a machine-readable report. The exported stream preserves
capture order and each nested packet byte-for-byte, including its CRC.

The reference capture artifact is:

```text
bytes   52680
sha256  b7eef984cebab323c97138bed74bbc09ae3c546965d0690a427e07debabe8dc6
```

## Transport findings

A single passive `40007` connection is data-rich but the RC2 server closes it
after about two seconds. Reconnecting repeatedly captures fresh records but
caused brief DJI Fly telemetry loss in the main camera view.

Two bounded `03/D7 AppRequest` attempts over `40009`, using the modern app
route and the legacy indexed route, received unrelated valid frames but no
matching reply. DJI Fly remained visually stable. These attempts do not prove
that a second, non-disruptive D7 subscription is available on `40009`.

No field use should depend on repeated `40007` connections until DJI Fly
coexistence is solved.

## Next decoder gate

The best next route is to recover the Neo 2 flight-record payload key/transform
from the flight-controller firmware or a DJI client-side key exchange. The
available WA341 Linux userspace corpus does not contain the flight-controller
MCU implementation, and the corresponding firmware modules remain protected
by unavailable DJI image keys.

Once plaintext is independently validated, prioritize:

1. `0x0800`/`0x0801` for position, fused altitude and attitude;
2. a GNSS record carrying geodetic or mean-sea-level height;
3. timestamp/clock conversion for capture-time alignment;
4. a separate gimbal source if FC records do not contain camera attitude.

One-second-old data is acceptable for the intended proof of concept, but
staleness must remain explicit and no value may survive gaps or failed
decryption.
