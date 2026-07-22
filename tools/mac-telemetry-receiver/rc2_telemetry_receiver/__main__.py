from __future__ import annotations

import argparse
from pathlib import Path

from .pcap import adb_pcap
from .receiver import listen


def main() -> None:
    parser = argparse.ArgumentParser(description="Receive DJI RC2 telemetry research streams")
    parser.add_argument("--listen", default="0.0.0.0:8765", help="TCP bind address for Android NDJSON relay")
    parser.add_argument("--out", default="captures", help="Output directory for session artifacts")
    parser.add_argument("--adb-pcap", action="store_true", help="Run adb exec-out tcpdump and decode passive lo:40009 PCAP")
    parser.add_argument("--adb", default="adb", help="adb executable for --adb-pcap")
    args = parser.parse_args()

    out_root = Path(args.out).expanduser().resolve()
    if args.adb_pcap:
        adb_pcap(out_root=out_root, adb=args.adb)
    else:
        listen(args.listen, out_root)


if __name__ == "__main__":
    main()
