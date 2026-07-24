from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path

from .mqtt import MqttConfig, MqttGeoreferencePublisher
from .receiver import (
    listen,
    request_remote_aircraft_serial,
    request_remote_duml,
    request_remote_gps_hmsl,
    request_remote_lab,
    request_remote_probe,
    replay_session,
)
from .telemetry import analyze_session


def main() -> None:
    parser = argparse.ArgumentParser(description="Receive DJI RC2 telemetry research streams")
    parser.add_argument("--listen", default="0.0.0.0:8765", help="TCP bind address for Android NDJSON relay")
    parser.add_argument("--control", default="127.0.0.1:8766", help="Mac-local control address for allowlisted probes")
    parser.add_argument("--out", default="captures", help="Output directory for session artifacts")
    parser.add_argument("--analyze", help="Analyze an existing session.ndjson and print candidate fields")
    parser.add_argument("--replay", help="Replay source events and regenerate derived metadata")
    parser.add_argument("--probe-fc-osd", action="store_true", help="Request one allowlisted 03/43 probe from the connected RC2")
    parser.add_argument("--probe-gps-hmsl", action="store_true", help="Request one read-only 03/57 GPS hMSL sample")
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
    parser.add_argument(
        "--lab",
        help="Run a bounded DUML Lab v1 JSON recipe through the connected RC2",
    )
    parser.add_argument(
        "--mqtt-url",
        default=os.getenv("RC2_MQTT_URL", ""),
        help="Optional mqtt:// or mqtts:// broker; disabled when empty",
    )
    parser.add_argument(
        "--mqtt-topic-prefix",
        default=os.getenv("RC2_MQTT_TOPIC_PREFIX", "nordlys/rc2"),
        help="Topic prefix for live georeference samples",
    )
    parser.add_argument(
        "--mqtt-client-id",
        default=os.getenv("RC2_MQTT_CLIENT_ID", ""),
        help="Stable MQTT receiver client ID",
    )
    parser.add_argument(
        "--mqtt-username",
        default=os.getenv("RC2_MQTT_USERNAME"),
        help="MQTT username; password is read from RC2_MQTT_PASSWORD",
    )
    parser.add_argument(
        "--mqtt-ca-file",
        default=os.getenv("RC2_MQTT_CA_FILE", ""),
        help="Optional CA file for mqtts://",
    )
    parser.add_argument(
        "--mqtt-max-sample-age-ms",
        type=int,
        default=int(os.getenv("RC2_MQTT_MAX_SAMPLE_AGE_MS", "2500")),
        help="Drop older samples instead of queuing stale metadata",
    )
    args = parser.parse_args()

    out_root = Path(args.out).expanduser().resolve()
    if args.lab:
        recipe_path = Path(args.lab).expanduser().resolve()
        if recipe_path.stat().st_size > 1_000_000:
            parser.error("DUML Lab recipe exceeds 1 MB")
        recipe = json.loads(recipe_path.read_text(encoding="utf-8"))
        if not isinstance(recipe, dict):
            parser.error("DUML Lab recipe root must be a JSON object")
        print(json.dumps(request_remote_lab(args.control, recipe), indent=2))
    elif args.duml:
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
    elif args.probe_gps_hmsl:
        print(json.dumps(request_remote_gps_hmsl(args.control, args.duml_port), indent=2))
    elif args.probe_aircraft_serial:
        print(json.dumps(request_remote_aircraft_serial(args.control), indent=2))
    elif args.probe_fc_osd:
        print(json.dumps(request_remote_probe(args.control), indent=2))
    elif args.replay:
        writer = replay_session(Path(args.replay).expanduser().resolve(), out_root)
        print(writer.session_dir)
    elif args.analyze:
        print(json.dumps(analyze_session(Path(args.analyze).expanduser().resolve()), indent=2))
    else:
        publisher = None
        if args.mqtt_url:
            publisher = MqttGeoreferencePublisher(
                MqttConfig(
                    url=args.mqtt_url,
                    topic_prefix=args.mqtt_topic_prefix,
                    client_id=args.mqtt_client_id,
                    username=args.mqtt_username,
                    password=os.getenv("RC2_MQTT_PASSWORD"),
                    ca_file=Path(args.mqtt_ca_file).expanduser().resolve()
                    if args.mqtt_ca_file
                    else None,
                    max_sample_age_ms=args.mqtt_max_sample_age_ms,
                )
            )
            publisher.start()
        try:
            listen(
                args.listen,
                out_root,
                args.control,
                [publisher] if publisher is not None else (),
            )
        finally:
            if publisher is not None:
                publisher.close()


if __name__ == "__main__":
    main()
