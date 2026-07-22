from __future__ import annotations

import argparse
import json
from pathlib import Path

from .pcap import adb_pcap
from .receiver import listen
from .telemetry import analyze_session


def main() -> None:
    parser = argparse.ArgumentParser(description="Receive DJI RC2 telemetry research streams")
    parser.add_argument("--listen", default="0.0.0.0:8765", help="TCP bind address for Android NDJSON relay")
    parser.add_argument("--out", default="captures", help="Output directory for session artifacts")
    parser.add_argument("--adb-pcap", action="store_true", help="Run adb exec-out tcpdump and decode passive lo:40009 PCAP")
    parser.add_argument("--adb", default="adb", help="adb executable for --adb-pcap")
    parser.add_argument("--analyze", help="Analyze an existing session.ndjson and print candidate fields")
    args = parser.parse_args()

    out_root = Path(args.out).expanduser().resolve()
    if args.analyze:
        print(json.dumps(analyze_session(Path(args.analyze).expanduser().resolve()), indent=2))
    elif args.adb_pcap:
        adb_pcap(out_root=out_root, adb=args.adb)
    else:
        listen(args.listen, out_root)


if __name__ == "__main__":
    main()
