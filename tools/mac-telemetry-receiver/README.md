# RC2 Telemetry Receiver

Mac-side companion for the FreeFCC telemetry research mode.

Run the Android relay receiver:

```sh
cd tools/mac-telemetry-receiver
python -m rc2_telemetry_receiver --listen 0.0.0.0:8765 --out /Users/jorgen/Documents/RC/captures
```

Preferred passive path when an authorized RC2 shell can run `tcpdump`:

```sh
cd tools/mac-telemetry-receiver
python -m rc2_telemetry_receiver --adb-pcap --out /Users/jorgen/Documents/RC/captures
```

The tool writes `session.ndjson`, raw chunk files, and `session-summary.json`.
It does not upload artifacts or promote GPS/attitude fields to verified values.
