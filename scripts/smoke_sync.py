#!/usr/bin/env python3
"""Two real HTTP/WebSocket clients against an explicitly disposable test server."""
import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import socket
import ssl
import struct
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Client:
    def __init__(self, base_url, token, device):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.device = device

    def headers(self):
        return {"Authorization": "Bearer " + self.token, "Content-Type": "application/json",
                "X-Device-Id": self.device,
                "X-Device-Name": urllib.parse.quote(self.device + " 검증", safe="")}

    def request(self, path, payload=None, method=None, expected=200):
        request = urllib.request.Request(
            self.base_url + path, headers=self.headers(),
            data=json.dumps(payload).encode() if payload is not None else None,
            method=method or ("POST" if payload is not None else "GET"),
        )
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        body = json.loads(response.read())
        assert response.code == expected, (path, response.code, body)
        return body

    def context(self, revision):
        return {"commandId": str(uuid.uuid4()), "expectedRevision": revision,
                "deviceId": self.device, "deviceName": self.device + " 검증"}

    def command(self, action, revision, **values):
        return {"context": self.context(revision), "action": action, **values}


class WebSocketObserver:
    def __init__(self, client):
        url = urllib.parse.urlsplit(client.base_url)
        port = url.port or (443 if url.scheme == "https" else 80)
        self.socket = socket.create_connection((url.hostname, port), timeout=8)
        if url.scheme == "https":
            self.socket = ssl.create_default_context().wrap_socket(self.socket, server_hostname=url.hostname)
        key = base64.b64encode(os.urandom(16)).decode()
        path = url.path.rstrip("/") + "/v1/events"
        headers = client.headers() | {"Host": url.netloc, "Upgrade": "websocket", "Connection": "Upgrade",
                                      "Sec-WebSocket-Key": key, "Sec-WebSocket-Version": "13"}
        self.socket.sendall((f"GET {path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in headers.items()) + "\r\n").encode())
        self.buffer = b""
        while b"\r\n\r\n" not in self.buffer:
            self.buffer += self.socket.recv(4096)
        head, self.buffer = self.buffer.split(b"\r\n\r\n", 1)
        assert b" 101 " in head.split(b"\r\n")[0], head
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        assert accept.lower() in head.lower()

    def exact(self, count):
        while len(self.buffer) < count:
            chunk = self.socket.recv(4096)
            if not chunk:
                raise RuntimeError("WebSocket closed before expected state")
            self.buffer += chunk
        result, self.buffer = self.buffer[:count], self.buffer[count:]
        return result

    def until(self, predicate):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            first, second = self.exact(2)
            opcode, length = first & 15, second & 127
            if length == 126:
                length = struct.unpack("!H", self.exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self.exact(8))[0]
            assert not second & 128, "server frames must not be masked"
            payload = self.exact(length)
            if opcode == 1:
                state = json.loads(payload)
                if predicate(state):
                    return state
            elif opcode == 8:
                raise RuntimeError("WebSocket closed")
        raise AssertionError("Expected state was not delivered")

    def close(self):
        self.socket.close()


def validate_target(base_url, config_path):
    """Fail closed even under python -O, before any credential is loaded."""
    try:
        url = urllib.parse.urlsplit(base_url)
        allowed = (
            url.scheme == "http" and url.hostname == "127.0.0.1" and url.port == 8788
            and url.username is None and url.password is None
            and url.path in {"", "/"} and not url.query and not url.fragment
        )
    except ValueError:
        allowed = False
    if not allowed:
        raise ValueError("Use only the dedicated disposable smoke server at http://127.0.0.1:8788")
    if Path(config_path).resolve().name == "production.json":
        raise ValueError("Production credentials must never be used for mutation smoke tests")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8788")
    parser.add_argument("--config", type=Path, default=Path(__file__).resolve().parents[1] / ".local/dev.json")
    args = parser.parse_args()
    # This script writes test records. Production URLs are deliberately rejected.
    validate_target(args.base_url, args.config)
    cfg = json.loads(args.config.read_text())
    mac = Client(args.base_url, cfg["STINT_AUTH_TOKEN"], "smoke-mac")
    android = Client(args.base_url, cfg["STINT_AUTH_TOKEN"], "smoke-android")
    checks = []
    bad = Client(args.base_url, "wrong-token", "unauthorized")
    bad.request("/v1/snapshot", expected=401)
    checks.append("unauthorized_rejected")
    initial = mac.request("/v1/snapshot")
    if initial["timer"]["status"] != "IDLE":
        initial = mac.request("/v1/timer/commands", mac.command("FINISH", initial["revision"]))
    subject_id = "smoke-" + uuid.uuid4().hex[:12]
    subject = {"id": subject_id, "name": "동기화 검증", "colorHex": "46735D", "focusSeconds": 5,
               "shortBreakSeconds": 2, "longBreakSeconds": 3, "longBreakEvery": 2, "archived": False}
    state = mac.request("/v1/subjects", {"context": mac.context(initial["revision"]), "subject": subject})
    left, right = WebSocketObserver(mac), WebSocketObserver(android)
    try:
        left.until(lambda s: s["revision"] >= state["revision"])
        right.until(lambda s: s["revision"] >= state["revision"])
        checks.append("both_websockets_authenticated")
        state = mac.request("/v1/timer/commands", mac.command("START", state["revision"], subjectId=subject_id, mode="COUNTDOWN", focusSeconds=5))
        timer_id = state["timer"]["id"]
        remote = right.until(lambda s: s["revision"] >= state["revision"])
        assert remote["timer"]["id"] == timer_id and remote["timer"]["status"] == "RUNNING"
        checks.append("mac_start_reaches_android")
        start_revision = state["revision"]
        time.sleep(1.2)
        state = android.request("/v1/timer/commands", android.command("PAUSE", state["revision"]))
        remote = left.until(lambda s: s["revision"] >= state["revision"])
        assert remote["timer"]["status"] == "PAUSED" and remote["timer"]["accumulatedMillis"] >= 1000
        time.sleep(.3)
        assert mac.request("/v1/snapshot")["timer"]["accumulatedMillis"] == state["timer"]["accumulatedMillis"]
        checks.append("android_pause_reaches_mac_and_stays_paused")
        conflict = mac.request("/v1/timer/commands", mac.command("RESUME", start_revision), expected=409)
        assert conflict["snapshot"]["revision"] == state["revision"]
        checks.append("stale_revision_rejected")
        commands = [(mac, mac.command("RESUME", state["revision"])),
                    (android, android.command("RESUME", state["revision"]))]
        def race(item):
            client, command = item
            request = urllib.request.Request(client.base_url + "/v1/timer/commands", data=json.dumps(command).encode(), headers=client.headers())
            try:
                response = urllib.request.urlopen(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            return response.code, json.loads(response.read()), client, command
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(race, commands))
        assert sorted(x[0] for x in results) == [200, 409], [x[0] for x in results]
        _, state, winner, successful_command = next(x for x in results if x[0] == 200)
        checks.append("concurrent_resume_has_one_winner")
        duplicate = winner.request("/v1/timer/commands", successful_command)
        assert duplicate["revision"] == state["revision"]
        checks.append("same_command_id_is_idempotent")
        time.sleep(4.1)
        state = mac.request("/v1/snapshot")
        records = [s for s in state["sessions"] if s["id"] == timer_id]
        assert len(records) == 1 and records[0]["durationSeconds"] == 5, records
        checks.append("natural_completion_is_exactly_once")
        state = mac.request("/v1/sessions/" + timer_id + "/memo",
                            {"context": mac.context(state["revision"]), "memo": "두 기기의 기록과 회고 동기화 확인"}, "PUT")
        remote = right.until(lambda s: s["revision"] >= state["revision"])
        assert next(s for s in remote["sessions"] if s["id"] == timer_id)["memo"] == "두 기기의 기록과 회고 동기화 확인"
        checks.append("memo_syncs_to_other_device")
        time.sleep(2.1)
        state = mac.request("/v1/snapshot")
        assert state["timer"]["status"] == "IDLE" and state["timer"]["phase"] == "FOCUS"
        assert len([s for s in state["sessions"] if s["id"] == timer_id]) == 1
        checks.append("break_finishes_without_phantom_focus_records")
        state = mac.request("/v1/timer/commands", mac.command("START", state["revision"], subjectId=subject_id, mode="STOPWATCH"))
        stopwatch_id = state["timer"]["id"]
        time.sleep(1.1)
        state = android.request("/v1/timer/commands", android.command("FINISH", state["revision"]))
        assert len([s for s in state["sessions"] if s["id"] == stopwatch_id and s["durationSeconds"] >= 1]) == 1
        checks.append("stopwatch_finish_saves_actual_time")
        report = {"passed": len(checks), "checks": checks, "revision": state["revision"],
                  "persisted_session_id": timer_id, "expected_memo": "두 기기의 기록과 회고 동기화 확인"}
        report_path = args.config.parent / "smoke-report.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps(report, ensure_ascii=False))
    finally:
        left.close()
        right.close()


if __name__ == "__main__":
    main()
