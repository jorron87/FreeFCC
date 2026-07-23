from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path

from .pcap import adb_pcap
from .receiver import listen, request_remote_aircraft_serial, request_remote_duml, request_remote_probe
from .telemetry import analyze_session


def main() -> None:
    parser = argparse.ArgumentParser(description="Receive DJI RC2 telemetry research streams")
    parser.add_argument("--listen", default="0.0.0.0:8765", help="TCP bind address for Android NDJSON relay")
    parser.add_argument("--control", default="127.0.0.1:8766", help="Mac-local control address for allowlisted probes")
    parser.add_argument("--out", default="captures", help="Output directory for session artifacts")
    parser.add_argument("--adb-pcap", action="store_true", help="Run adb exec-out tcpdump and decode passive lo:40009 PCAP")
    parser.add_argument("--adb", default="adb", help="adb executable for --adb-pcap")
    parser.add_argument("--analyze", help="Analyze an existing session.ndjson and print candidate fields")
    parser.add_argument("--probe-fc-osd", action="store_true", help="Request one allowlisted 03/43 probe from the connected RC2")
    parser.add_argument("--probe-aircraft-serial", action="store_true", help="Request one read-only 00/51 aircraft serial sample")
    parser.add_argument(
        "--duml",
        nargs=5,
        metavar=("SENDER", "DEST", "CMD_TYPE", "CMD_SET", "CMD_ID"),
        help="Send one structured DUML frame; integer fields accept decimal or 0x hex",
    )
    parser.add_argument("--payload-hex", default="", help="DUML request payload as hex")
    parser.add_argument("--no-response", action="store_true", help="Do not wait for a matching DUML response")
    parser.add_argument("--read-window-ms", type=int, default=1000, help="Bounded response window, 20..5000 ms")
    parser.add_argument("--duml-port", type=int, default=40009, help="Controller DUML proxy port")
    args = parser.parse_args()

    out_root = Path(args.out).expanduser().resolve()
    if args.duml:
        sender, destination, cmd_type, cmd_set, cmd_id = (int(value, 0) for value in args.duml)
        payload = bytes.fromhex(args.payload_hex.replace(" ", ""))
        print(
            json.dumps(
                request_remote_duml(
                    args.control,
                    {
                        "sender": sender,
                        "destination": destination,
                        "cmd_type": cmd_type,
                        "cmd_set": cmd_set,
                        "cmd_id": cmd_id,
                        "payload_b64": base64.b64encode(payload).decode("ascii"),
                        "expect_response": not args.no_response,
                        "read_window_ms": args.read_window_ms,
                        "port": args.duml_port,
                    },
                ),
                indent=2,
            )
        )
    elif args.probe_aircraft_serial:
        print(json.dumps(request_remote_aircraft_serial(args.control), indent=2))
    elif args.probe_fc_osd:
        print(json.dumps(request_remote_probe(args.control), indent=2))
    elif args.analyze:
        print(json.dumps(analyze_session(Path(args.analyze).expanduser().resolve()), indent=2))
    elif args.adb_pcap:
        adb_pcap(out_root=out_root, adb=args.adb)
    else:
        listen(args.listen, out_root, args.control)


if __name__ == "__main__":
    main()
