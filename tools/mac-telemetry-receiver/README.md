# RC2 Telemetry Receiver

Mac-side companion for the FreeFCC telemetry research mode.

Run the Android relay receiver:

```sh
cd tools/mac-telemetry-receiver
python -m rc2_telemetry_receiver --listen 0.0.0.0:8765 --out /Users/jorgen/Documents/RC/captures
```

The same process binds a control socket to Mac localhost only (`127.0.0.1:8766`).
In the Android Telemetry tab, select `40007 Wrapped` for the preferred passive
metadata source. `40009 Direct` remains available for the narrower controller
stream. Each app start opens one read-only DUML connection and does not
automatically reconnect it after EOF.
Request one decoded FC OSD candidate sample:

```sh
python -m rc2_telemetry_receiver --probe-fc-osd
```

Request one read-only `00/51 Get Serial Number` sample:

```sh
python -m rc2_telemetry_receiver --probe-aircraft-serial
```

This active `40009` request remains a legacy experiment. The preferred aircraft
identity path is passive `51/14` on wrapped `40007`; the receiver extracts a
candidate serial only from a CRC-valid frame on the observed `0xEE -> App`
route and records it in `session-summary.json`.

Send one general structured DUML request (sender, destination, command type,
command set, command ID):

```sh
python -m rc2_telemetry_receiver \
  --duml 0x82 0x03 0x40 0x03 0x43 \
  --duml-port 40009 \
  --read-window-ms 1500
```

Add `--payload-hex "01 02"` for request data or `--no-response` for a one-way
frame. Supported controller ports are `40007`, `40009`, and `8901`-`8904`.
The Android app builds CRCs, pins the selected port, logs the request/result,
retains bounded unmatched response frames, and sends no automatic retries.
When capture already holds the requested port, the command returns
`port_busy`; select another command port or explicitly stop capture first.
Remote DUML is a bench research interface. An external local script may
schedule one-shot requests, but the receiver does not start polling on its own.

`1.5.3-research.6` accepts a correlated DJI reply even when the RESPONSE bit is
not set, matching the behavior documented by `dji-firmware-tools`. CRC,
sequence, reverse routing and command set/ID must still match. This change is
unit-tested. Every result also includes a bounded diagnostics block showing
whether the exchange matched, timed out, reached EOF, or received valid but
unmatched DUML frames. Raw observed frames are retained as base64.

The earlier `40009` bench run proved LAN relay and stable DJI Fly coexistence on
the tested RC2, with 11,512 valid frames and no parser errors. Wrapped `40007`
on Neo 2 still requires its own physical gate: one explicit connection, zero
DJI Fly reconnects, and observed CRC-valid `03/43`, `03/44`, `04/05`, and
`51/14`. This does not promote any app socket to flight-safe.

Preferred passive path when an authorized RC2 shell can run `tcpdump`:

```sh
cd tools/mac-telemetry-receiver
python -m rc2_telemetry_receiver --adb-pcap --out /Users/jorgen/Documents/RC/captures
```

The tool writes `session.ndjson`, raw chunk files, and `session-summary.json`.
It does not upload artifacts or promote GPS/attitude fields to verified values.

Analyze a saved session and inventory georeference candidates:

```sh
python -m rc2_telemetry_receiver --analyze /path/to/session.ndjson
```

The analyzer implements candidate layouts for `03/43` FC OSD and `04/05`
gimbal position from the `o-gs/dji-firmware-tools` Wireshark dissectors. It also
retains `03/44` home-point state and frame-scoped `51/14` aircraft identity as
model/session-specific candidates. GPS coordinates and relative height remain
raw candidates; latitude, longitude, and absolute altitude stay null until
controlled correlation verifies them.
