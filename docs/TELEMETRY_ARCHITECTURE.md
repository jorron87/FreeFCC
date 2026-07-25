# RC2 Telemetry Architecture

## Goal

Produce one current georeference sample per second for later video alignment:

- aircraft latitude and longitude;
- AMSL altitude when `03/57 hMSL` is physically confirmed;
- takeoff-relative height as a separate field;
- aircraft roll, pitch, yaw and heading;
- gimbal roll, pitch and yaw;
- aircraft and controller identity;
- explicit quality, age and raw DUML provenance for every field.

This is parallel metadata. Video muxing is outside the current release.

## Runtime boundary

```text
RC2 / FreeFCC research service
  -> one controller-local capture socket, or control-only DUML Lab
  -> raw chunks + CRC-valid DUML over TCP NDJSON
  -> Mac receiver and session artifacts
  -> one normalized GEOREFERENCE_SAMPLE per second
  -> optional MQTT output
  -> Nordlys / Helilink video correlation
```

The Android service owns capture only. It has no MQTT dependency, broker
credential, cloud upload, decoder backlog or production retry policy.

The Mac receiver owns:

- raw artifact persistence and replay;
- candidate decoding and confidence;
- field freshness and stale-value nulling;
- Android monotonic to Mac receive-time alignment, with RC wall-time fallback;
- normalized output and optional MQTT.

## Controller source policy

`40007 1Hz` is the current bench source. It:

1. opens a new `127.0.0.1:40007` socket for each sample window;
2. sends exactly one wrapped `02 -> 06`, `00/01` Version Inquiry;
3. reads for at most 500 ms and stops after 100 ms idle;
4. validates wrapped DUML and closes the socket;
5. paces the next bounded connection at one hertz;
6. marks empty, malformed or failed snapshots unavailable before retrying.

This is active bench behavior, not a flight-safe claim. The older periodic
`03/44` primer and same-socket `00/01` keepalive are retired because a second
write reset the tested RC2 socket.

## DUML Lab boundary

`Lab only` runs the foreground Android relay without holding a controller port.
The Mac control listener is loopback-only and forwards one `duml-lab/v1`
recipe at a time over the existing RC2 relay connection. Android rejects
arbitrary recipes unless `Lab only` was explicitly selected before relay start.

A recipe owns only `127.0.0.1` plus its selected port. Its phases can explicitly
connect, write direct/wrapped DUML or raw bytes, read, sleep and close. It may
repeat a cycle on one socket or explicitly close/connect per cycle. There is no
automatic reconnect, hidden polling or semantic promotion of received bytes.

Android enforces:

- global hardware and per-port leases;
- active-capture port rejection;
- 30 seconds total runtime;
- 512 expanded steps;
- 64 KiB total TX and 256 KiB total RX;
- 4 KiB per raw write and valid DUML frame length;
- immediate recipe cancellation and socket close when the relay stops;
- exact raw/result persistence as `DUML_LAB_RESULT`.

This is the fast bench discovery path. Candidate decoding and the one-second
`GEOREFERENCE_SAMPLE` contract remain separate, conservative boundaries.

## One-second envelope

`GEOREFERENCE_SAMPLE` contains:

- `source_id`, `session_id`, `sample_seq`;
- RC `captured_at`, Mac `received_at`, Android `elapsed_realtime_ns`;
- `clock.transport_age_ms`, its monotonic/wall source, and wall offset;
- `position`, `altitude`, `attitude`, `heading`, `gimbal`, `identity`;
- per-field `quality` and `age_ms`;
- `raw.message_families` identifying each field's DUML source.

AMSL and relative height are intentionally independent:

```json
{
  "altitude": {
    "amsl_m": null,
    "relative_takeoff_m": 0.0,
    "amsl_reference": null,
    "relative_reference": "takeoff_relative"
  },
  "quality": {
    "altitude": "unknown",
    "relative_altitude": "probable"
  }
}
```

No last-known dynamic value survives a capture gap, inactive source, parser
failure or field-specific freshness deadline.

## Field confidence

| Field | DUML family | Current state |
|---|---|---|
| Latitude/longitude | `03/43` | Probable for the correlated Neo 2 session |
| Aircraft attitude/yaw | `03/43` | Probable for the correlated Neo 2 session |
| Takeoff-relative height | `03/43` | Probable, never labeled AMSL |
| Gimbal pitch/attitude | `04/05` | Candidate; -90 degree correlation observed |
| AMSL/geoid height | `03/57` | Candidate decoder only; not captured live yet |
| `03/44` home altitude | `03/44` | Rejected as geodetic altitude |
| Aircraft serial | `51/14` | Operator-confirmed on the observed route; RM510 record layout enforced |
| Controller serial | Android system property | Operator-confirmed suffix `H103` |

`51/14` is decoded as the statically documented RM510 neighbor list:
`count`, one reserved byte, then exactly `count` records of 49 bytes. Only the
23-byte identity region in each record is searched for serial/model text.
The following link-state, mode and timestamp/age words are preserved as raw
diagnostics and are not assigned operational semantics by this project.

## Skylab follow-up

SkylabFCCfree commits through `v1.5.49` were reviewed on 2026-07-25.

- Adopted: the RM510 `51/14` record boundary and identity-region offsets.
- Not adopted: its inbound LAN server; control remains outbound RC2-to-Mac
  with a Mac loopback-only command listener.
- Not adopted: periodic FCC country checks and writes. They add controller
  traffic but no georeference data.
- Not adopted: sharing one notification ID between app and FCC services.
  Telemetry keeps its own ongoing foreground notification so Android can
  accurately show whether metadata is active, silent, gapped or unavailable.

Skylab's newly documented `51/0C` composite link-state report is retained as a
future diagnostic lead, not treated as position, heading, altitude or gimbal
metadata.

## MQTT policy

MQTT is disabled unless `RC2_MQTT_URL` or `--mqtt-url` is provided.

- Data topic: `nordlys/rc2/{source_id}/georeference`
- Data QoS: 0
- Data retain: false
- Status topic: `nordlys/rc2/receiver/{client_id}/status`
- Status QoS: 1, retained, with Last Will offline
- Raw DUML: never published
- Offline samples: dropped
- Missing clock alignment: dropped
- Default maximum transport age: 2.5 seconds

The session NDJSON remains the source of truth. Replay regenerates artifacts
but does not publish MQTT.

## Remaining physical gate

Install `1.5.3-research.11`, keep propellers off and DJI Fly in the foreground,
then run `40007 1Hz` for at least ten minutes.

Acceptance requires:

1. zero DJI Fly reconnects, control-link warnings or pairing changes;
2. one uninterrupted app session with one bounded connection per snapshot;
3. repeated CRC-valid `03/43` and `04/05` frames;
4. fresh one-second samples with nulls after deliberate source loss;
5. heading and gimbal pitch following controlled movements;
6. `03/57` treated as unknown unless a valid frame is captured and correlated;
7. no MQTT backlog burst after broker or LAN interruption.

The RC and drone do not need to be online for unit tests, replay, APK builds or
MQTT contract tests. They are required only for this physical acceptance gate.
