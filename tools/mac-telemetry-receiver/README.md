# RC2 Telemetry Receiver

Mac-side companion for the FreeFCC telemetry research mode.

Run the Android relay receiver:

```sh
cd tools/mac-telemetry-receiver
python -m rc2_telemetry_receiver --listen 0.0.0.0:8765 --out /Users/jorgen/Documents/RC/captures
```

The same process binds a control socket to Mac localhost only (`127.0.0.1:8766`).
Non-loopback control binds are rejected because this interface can transmit
arbitrary research bytes to controller-local ports.
On the tested Neo 2 firmware, `8902 Passive` accepted a socket but remained
silent. `40007 Snapshot` returned useful wrapped DUML for about two seconds.
`40007 Keepalive` is the current active bench source: it opens one socket and
sends an idle-triggered wrapped `02 -> 06`, `00/01` Version Inquiry on that
same socket. It never reconnects automatically after EOF or write failure.

The receiver stores chunks plus `raw-stream.bin`, validates wrapped DUML, and
writes one `GEOREFERENCE_SAMPLE` for every one-second Android tick. Each sample
has stable `source_id`, session/sample sequence, RC capture time, Mac receive
time, Android monotonic time, field-level message provenance, and explicit
quality/age. Stale or unavailable values are null rather than reused.

AMSL and takeoff-relative altitude are separate:

- `altitude.amsl_m` comes only from candidate `03/57 hMSL`;
- `altitude.relative_takeoff_m` comes from probable Neo 2 `03/43`;
- `03/44` home altitude is rejected as an AMSL source.

## DUML Lab recipes

Select `Lab only` in the Android Telemetry tab and start the relay. This keeps
the foreground service and Mac connection alive without opening a DJI capture
port. Then run a recipe:

```sh
python -m rc2_telemetry_receiver \
  --lab recipes/40007-version-keepalive.json
```

The installed APK accepts new commands and socket strategies from JSON, so
changing a sender, destination, command family, payload, port or cadence does
not require another Android build. A `duml-lab/v1` recipe supports:

- `setup`, repeated `cycle`, and `teardown` phases;
- `connect` and `close` for explicit socket lifecycle;
- `write_duml` with `direct` or `wrapped_40007` wire encoding;
- `write_raw` for exact base64 bytes;
- bounded `read` and `sleep` steps.

The engine always targets `127.0.0.1`. It never reconnects implicitly and is
limited to one in-flight recipe, 30 seconds, 512 expanded steps, 64 KiB TX and
256 KiB RX. The result includes exact step bytes, CRC-valid frames and parser
errors. A port held by another hardware operation is rejected.

Included strategies:

```text
recipes/40007-version-keepalive.json
recipes/40009-single-duml.json
recipes/8902-passive-observe.json
```

Edit a copy of a recipe for each experiment and retain its returned
`DUML_LAB_RESULT` with the corresponding physical observations. The full
contract is in [`../../docs/DUML_LAB.md`](../../docs/DUML_LAB.md).

Request one decoded FC OSD candidate sample:

```sh
python -m rc2_telemetry_receiver --probe-fc-osd
```

Request one read-only `03/57 GPS GLNS Info` hMSL sample:

```sh
python -m rc2_telemetry_receiver --probe-gps-hmsl
```

This command is one-shot and has no retry. The tested Neo 2 did not return
`03/57` on `40009`, so the expected useful path is a future frame in a wrapped
`40007` capture window.

Request one read-only `00/51 Get Serial Number` sample:

```sh
python -m rc2_telemetry_receiver --probe-aircraft-serial
```

This active `40009` request remains a legacy experiment. The preferred aircraft
identity path is passive `51/14` on wrapped `40007`; the receiver extracts a
candidate serial only from a CRC-valid frame on the observed `0xEE -> App`
route and records it in `session-summary.json`. It also requires the RM510
payload to be exactly `2 + 49*N` bytes and searches only each 23-byte identity
region. Raw link-state/mode/timestamp fields remain diagnostics.

Send one general structured DUML request (sender, destination, command type,
command set, command ID):

```sh
python -m rc2_telemetry_receiver \
  --duml 0x82 0x03 0x40 0x03 0x43 \
  --duml-port 40009 \
  --read-window-ms 1500
```

Add `--payload-hex "01 02"` for request data or `--no-response` for a one-way
frame. This older one-shot interface supports `40007`, `40009`, and
`8901`-`8904`; DUML Lab supports any bounded localhost TCP port.
The Android app builds CRCs, pins the selected port, logs the request/result,
retains bounded unmatched response frames, and sends no automatic retries.
When capture already holds the requested port, the command returns
`port_busy`; select another command port or explicitly stop capture first.
Remote DUML is a bench research interface. An external local script may
schedule one-shot requests, but the receiver does not start polling on its own.

`1.5.3-research.10` accepts a correlated DJI reply even when the RESPONSE bit is
not set, matching the behavior documented by `dji-firmware-tools`. CRC,
sequence, reverse routing and command set/ID must still match. This change is
unit-tested. Every result also includes a bounded diagnostics block showing
whether the exchange matched, timed out, reached EOF, or received valid but
unmatched DUML frames. Raw observed frames are retained as base64.

The earlier `40009` bench run proved LAN relay and stable DJI Fly coexistence on
the tested RC2, with 11,512 valid frames and no parser errors. A direct LAN
check of the `research.10` keepalive pattern held `40007` open for 3.011 seconds,
received 4,116 bytes and 92 CRC-valid frames while sending 40 keepalives. That
short check did not contain `03/43` or `04/05`; continuous georeference-family
delivery and DJI Fly coexistence remain physical gates. No ADB path is required
or used by the current workflow.

The tool writes `session.ndjson`, raw chunk files, and `session-summary.json`.
It does not upload artifacts or promote GPS/attitude fields to verified values.

## Optional MQTT output

MQTT runs only on the Mac normalization boundary and is disabled by default.
Install its optional dependency:

```sh
python3 -m pip install -r requirements-mqtt.txt
```

Then add a local or production-selected broker:

```sh
export RC2_MQTT_URL='mqtt://broker.local:1883'
export RC2_MQTT_USERNAME='rc2-publisher'
export RC2_MQTT_PASSWORD='set-outside-shell-history'
python -m rc2_telemetry_receiver \
  --listen 0.0.0.0:8765 \
  --out /Users/jorgen/Documents/RC/captures
```

Live samples use:

```text
nordlys/rc2/{source_id}/georeference
```

They are QoS 0 and non-retained. The receiver publishes retained online/offline
status under `nordlys/rc2/receiver/{client_id}/status`. It does not queue
georeference samples while disconnected, and it drops samples whose
RC-to-Mac transport age exceeds 2.5 seconds. Raw DUML is never published.
`mqtts://` uses TLS; credentials and CA settings can be provided through
`RC2_MQTT_*` environment variables.

Replay source events and regenerate 8902 candidates and samples:

```sh
python -m rc2_telemetry_receiver \
  --replay /path/to/session.ndjson \
  --out /Users/jorgen/Documents/RC/replayed
```

Replay writes artifacts only and never publishes MQTT.

Analyze a saved session and inventory georeference candidates:

```sh
python -m rc2_telemetry_receiver --analyze /path/to/session.ndjson
```

The analyzer implements layouts for `03/43` FC OSD, `03/57 GPS GLNS Info`, and
`04/05` gimbal position from the `o-gs/dji-firmware-tools` Wireshark
dissectors. Neo 2 latitude/longitude and yaw are `probable` after controlled
operator correlation. `03/57` decodes signed longitude/latitude at `1e-7`
degrees and hMSL millimetres, but remains `candidate` until observed on this
firmware. `03/44` home altitude is diagnostic only: `323.843 m` disagreed with
the approximately `7 m AMSL` site and is rejected for georeferencing.
Frame-scoped `51/14` supplies aircraft identity, while `HELLO` now carries the
RC Android system serial and the property used to obtain it. The `51/14`
decoder follows the RM510 neighbor-record layout documented by
SkylabFCCfree commit `fb37257`; malformed counts and serial-like text outside
the identity region are rejected.
