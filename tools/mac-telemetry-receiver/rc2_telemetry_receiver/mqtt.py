from __future__ import annotations

import json
import re
import socket
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse


SCHEMA = "dji-rc2-telemetry/v2"


@dataclass(frozen=True)
class MqttEndpoint:
    host: str
    port: int
    tls: bool
    username: str | None
    password: str | None


@dataclass(frozen=True)
class MqttConfig:
    url: str
    topic_prefix: str = "nordlys/rc2"
    client_id: str = ""
    username: str | None = None
    password: str | None = None
    ca_file: Path | None = None
    max_sample_age_ms: int = 2_500


@dataclass
class MqttStats:
    published: int = 0
    dropped_disconnected: int = 0
    dropped_stale: int = 0
    dropped_unclocked: int = 0
    publish_errors: int = 0


def parse_mqtt_url(url: str) -> MqttEndpoint:
    parsed = urlparse(url)
    if parsed.scheme not in {"mqtt", "mqtts"}:
        raise ValueError("MQTT URL must use mqtt:// or mqtts://")
    if not parsed.hostname:
        raise ValueError("MQTT URL must include a hostname")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise ValueError("Use --mqtt-topic-prefix for topics; MQTT URL cannot include a path")
    tls = parsed.scheme == "mqtts"
    return MqttEndpoint(
        host=parsed.hostname,
        port=parsed.port or (8883 if tls else 1883),
        tls=tls,
        username=unquote(parsed.username) if parsed.username else None,
        password=unquote(parsed.password) if parsed.password else None,
    )


def topic_component(value: object) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", str(value or "").strip())
    return cleaned.strip("._-")[:128] or "unknown"


class MqttGeoreferencePublisher:
    """Best-effort live output; disconnected or stale samples are never queued."""

    def __init__(
        self,
        config: MqttConfig,
        *,
        mqtt_module: Any | None = None,
        client: Any | None = None,
    ) -> None:
        if config.max_sample_age_ms <= 0:
            raise ValueError("max_sample_age_ms must be positive")
        self.config = config
        self.endpoint = parse_mqtt_url(config.url)
        self.topic_prefix = config.topic_prefix.strip().strip("/")
        if not self.topic_prefix:
            raise ValueError("MQTT topic prefix cannot be empty")
        self.client_id = topic_component(
            config.client_id or f"freefcc-{socket.gethostname()}"
        )
        self.stats = MqttStats()
        self._lock = threading.Lock()
        self._connected = False
        self._started = False
        self._mqtt = mqtt_module or self._load_mqtt()
        self._client = client or self._new_client()
        self._configure_client()

    @staticmethod
    def _load_mqtt() -> Any:
        try:
            import paho.mqtt.client as mqtt
        except ImportError as exc:
            raise RuntimeError(
                "MQTT output requires paho-mqtt; install requirements-mqtt.txt"
            ) from exc
        return mqtt

    def _new_client(self) -> Any:
        return self._mqtt.Client(
            self._mqtt.CallbackAPIVersion.VERSION2,
            client_id=self.client_id,
            clean_session=True,
            protocol=self._mqtt.MQTTv311,
        )

    def _configure_client(self) -> None:
        username = self.config.username or self.endpoint.username
        password = self.config.password
        if password is None:
            password = self.endpoint.password
        if username:
            self._client.username_pw_set(username, password)
        if self.endpoint.tls:
            ca_file = str(self.config.ca_file) if self.config.ca_file else None
            self._client.tls_set(ca_certs=ca_file)
        self._client.max_queued_messages_set(1)
        self._client.max_inflight_messages_set(1)
        self._client.reconnect_delay_set(min_delay=1, max_delay=10)
        self._client.on_connect = self._on_connect
        self._client.on_disconnect = self._on_disconnect
        self._client.will_set(
            self.status_topic,
            payload=self._status_payload("offline"),
            qos=1,
            retain=True,
        )

    @property
    def status_topic(self) -> str:
        return f"{self.topic_prefix}/receiver/{self.client_id}/status"

    @property
    def connected(self) -> bool:
        with self._lock:
            return self._connected

    def start(self) -> None:
        if self._started:
            return
        self._started = True
        self._client.connect_async(
            self.endpoint.host,
            self.endpoint.port,
            keepalive=30,
        )
        self._client.loop_start()
        print(
            f"mqtt connecting to {self.endpoint.host}:{self.endpoint.port} "
            f"as {self.client_id}",
            file=sys.stderr,
            flush=True,
        )

    def publish(self, sample: dict[str, Any]) -> bool:
        if sample.get("type") != "GEOREFERENCE_SAMPLE":
            return False
        transport_age_ms = sample.get("clock", {}).get("transport_age_ms")
        if not isinstance(transport_age_ms, (int, float)):
            with self._lock:
                self.stats.dropped_unclocked += 1
            return False
        if transport_age_ms > self.config.max_sample_age_ms:
            with self._lock:
                self.stats.dropped_stale += 1
            return False
        if not self.connected:
            with self._lock:
                self.stats.dropped_disconnected += 1
            return False

        topic = (
            f"{self.topic_prefix}/{topic_component(sample.get('source_id'))}"
            "/georeference"
        )
        payload = json.dumps(sample, separators=(",", ":"), allow_nan=False)
        info = self._client.publish(topic, payload=payload, qos=0, retain=False)
        if info.rc != self._mqtt.MQTT_ERR_SUCCESS:
            with self._lock:
                self.stats.publish_errors += 1
            return False
        with self._lock:
            self.stats.published += 1
        return True

    def close(self) -> None:
        if not self._started:
            return
        if self.connected:
            info = self._client.publish(
                self.status_topic,
                payload=self._status_payload("offline"),
                qos=1,
                retain=True,
            )
            try:
                info.wait_for_publish(timeout=1.0)
            except (RuntimeError, ValueError):
                pass
        self._client.disconnect()
        self._client.loop_stop()
        with self._lock:
            self._connected = False
        self._started = False

    def _on_connect(
        self,
        client: Any,
        _userdata: Any,
        _flags: Any,
        reason_code: Any,
        _properties: Any,
    ) -> None:
        connected = reason_code == 0
        with self._lock:
            self._connected = connected
        if connected:
            client.publish(
                self.status_topic,
                payload=self._status_payload("online"),
                qos=1,
                retain=True,
            )
            print("mqtt connected", file=sys.stderr, flush=True)
        else:
            print(
                f"mqtt connection rejected: {reason_code}",
                file=sys.stderr,
                flush=True,
            )

    def _on_disconnect(
        self,
        _client: Any,
        _userdata: Any,
        _disconnect_flags: Any,
        reason_code: Any,
        _properties: Any,
    ) -> None:
        with self._lock:
            self._connected = False
        if self._started:
            print(
                f"mqtt disconnected: {reason_code}",
                file=sys.stderr,
                flush=True,
            )

    def _status_payload(self, status: str) -> str:
        return json.dumps(
            {
                "type": "RECEIVER_STATUS",
                "schema": SCHEMA,
                "receiver_id": self.client_id,
                "status": status,
            },
            separators=(",", ":"),
        )
