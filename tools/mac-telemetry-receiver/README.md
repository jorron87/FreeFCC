# RC2 Telemetry Receiver

Mac-side companion for the FreeFCC telemetry research mode.

Run the Android relay receiver:

```sh
cd tools/mac-telemetry-receiver
python -m rc2_telemetry_receiver --listen 0.0.0.0:8765 --out /Users/jorgen/Documents/RC/captures
```

The same process binds a control socket to Mac localhost only (`127.0.0.1:8766`).
Request one decoded FC OSD candidate sample:

```sh
python -m rc2_telemetry_receiver --probe-fc-osd
```

Send one general structured DUML request (sender, destination, command type,
command set, command ID):

```sh
python -m rc2_telemetry_receiver --duml 0x82 0x03 0x40 0x03 0x43 --read-window-ms 1500
```

Add `--payload-hex "01 02"` for request data or `--no-response` for a one-way
frame. The Android app builds CRCs, logs the request/result, pauses passive
capture around the command, and sends no automatic retries. Remote DUML is a
bench research interface; an external script may schedule one-shot requests,
but the receiver does not start polling on its own.

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
gimbal position from the `o-gs/dji-firmware-tools` Wireshark dissectors. GPS
coordinates and relative height remain raw candidates; latitude, longitude,
and absolute altitude stay null until controlled correlation verifies them.
