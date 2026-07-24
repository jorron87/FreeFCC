# DUML Lab v1

DUML Lab is a bench-only command runner carried over the existing FreeFCC
telemetry relay. Its purpose is to change controller-local commands, ports and
socket strategies from the Mac without publishing another APK.

## Start

1. Start the Mac receiver on `8765`.
2. In the app, select `Telemetry -> Lab only`.
3. Start Relay and wait for `DUML Lab ready`.
4. Run a recipe from the Mac:

```sh
cd tools/mac-telemetry-receiver
python -m rc2_telemetry_receiver \
  --lab recipes/40007-version-keepalive.json
```

The control listener remains on `127.0.0.1:8766`. It cannot be exposed on LAN.

## Recipe

```json
{
  "schema": "duml-lab/v1",
  "name": "example",
  "port": 40009,
  "connect_timeout_ms": 2000,
  "total_timeout_ms": 5000,
  "max_rx_bytes": 65536,
  "setup": [
    {"op": "connect"},
    {
      "op": "write_duml",
      "sender": 130,
      "destination": 3,
      "cmd_type": 64,
      "cmd_set": 3,
      "cmd_id": 87,
      "payload_b64": "",
      "wire": "direct"
    }
  ],
  "cycle": [
    {
      "op": "read",
      "duration_ms": 1500,
      "idle_timeout_ms": 250,
      "max_bytes": 65536
    }
  ],
  "cycle_count": 1,
  "teardown": [
    {"op": "close"}
  ]
}
```

`setup` and `teardown` execute once. `cycle` executes `cycle_count` times while
preserving socket state. Put `close` and `connect` inside the cycle to test
one-connection-per-cycle behavior.

## Operations

| Operation | Fields | Behavior |
|---|---|---|
| `connect` | optional `label` | Opens one socket to `127.0.0.1:<port>` |
| `close` | optional `label` | Closes the current socket |
| `write_duml` | route, command, `payload_b64`, `wire` | Builds fresh DUML sequence and CRCs |
| `write_raw` | `bytes_b64` | Sends exact bytes without interpretation |
| `read` | `duration_ms`, `idle_timeout_ms`, `max_bytes` | Reads until idle, duration, EOF or limit |
| `sleep` | `duration_ms` | Bounded pause without I/O |

`wire` is `direct` or `wrapped_40007`. A read parses both formats and retains
the exact received bytes regardless of whether a valid frame is found.

## Hard limits

- target host fixed to `127.0.0.1`;
- port `1..65535`;
- one recipe in flight;
- active capture port rejected;
- 30 seconds total;
- 512 expanded steps and 200 cycles;
- 64 KiB total TX;
- 256 KiB total RX;
- 4 KiB per raw write;
- no implicit retry or reconnect.
- stopping the relay cancels the recipe and closes its active socket.

Every response is a `DUML_LAB_RESULT` containing the normalized recipe, each
expanded step, exact TX/RX bytes, validated DUML frames, parser errors and the
terminal reason. Frame and parser-error diagnostics are capped while exact
step TX/RX bytes remain intact; dropped diagnostic counts are reported. A
clean transport or CRC-valid frame is evidence only; it does not verify that a
payload field is GPS, AMSL height, heading or gimbal attitude.
