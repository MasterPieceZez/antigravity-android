#!/usr/bin/env python3
"""Antigravity local bridge server (Python 3 stdlib only, binds 127.0.0.1:4401)."""

import hmac
import json
import os
import re
import secrets
import socket
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HOST = "127.0.0.1"
PORT = 4401
HUB_PORT = 4400

HOME = os.environ.get("HOME", "/data/data/com.termux/files/home")
PREFIX = os.environ.get("PREFIX", "/data/data/com.termux/files/usr")
BRIDGE_DIR = os.path.join(HOME, ".agy_bridge")
AGY_BIN = os.path.join(HOME, ".local", "bin", "agy")
AGY_LOG = os.path.join(BRIDGE_DIR, "agy.log")
NOTIFY_FILE = os.path.join(BRIDGE_DIR, "pending_notify.json")
TOKEN_FILE = os.path.join(BRIDGE_DIR, "bridge.token")

HUB_URL_RE = re.compile(r"https?://(?:localhost|127\.0\.0\.1):4400/#t=[A-Za-z0-9._-]+")


def load_or_create_token() -> str:
    os.makedirs(BRIDGE_DIR, exist_ok=True)
    if os.path.isfile(TOKEN_FILE):
        try:
            tok = open(TOKEN_FILE, "r", encoding="utf-8").read().strip()
            if len(tok) >= 32:
                return tok
        except OSError:
            pass
    tok = secrets.token_hex(32)
    fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(tok)
    return tok


BRIDGE_TOKEN = load_or_create_token()


def is_port_open(host: str, port: int, timeout: float = 0.35) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def find_hub_url() -> str | None:
    if not os.path.isfile(AGY_LOG):
        return None
    try:
        with open(AGY_LOG, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
        matches = HUB_URL_RE.findall(content)
        if matches:
            return matches[-1].replace("127.0.0.1:4400", "localhost:4400")
    except OSError:
        pass
    return None


def build_env() -> dict[str, str]:
    env = os.environ.copy()
    env["PREFIX"] = PREFIX
    env["HOME"] = HOME
    env["PATH"] = f"{HOME}/.local/bin:{PREFIX}/bin:{env.get('PATH', '')}"
    env["LD_LIBRARY_PATH"] = f"{PREFIX}/lib"
    env["TMPDIR"] = os.path.join(PREFIX, "tmp")
    env["AGY_ENABLE_HUB"] = "1"
    env["AGY_CLI_DISABLE_AUTO_UPDATE"] = "1"
    return env


def restart_agy(wait_seconds: float = 10.0) -> str | None:
    env = build_env()
    subprocess.run(["pkill", "-f", "agy --hub"], env=env, check=False)
    time.sleep(0.5)

    os.makedirs(BRIDGE_DIR, exist_ok=True)
    with open(AGY_LOG, "w", encoding="utf-8") as f:
        f.write("")

    if not os.path.isfile(AGY_BIN):
        return None

    try:
        os.chmod(AGY_BIN, 0o755)
    except OSError:
        pass

    with open(AGY_LOG, "ab") as log_file:
        subprocess.Popen(
            [AGY_BIN, "--hub", f"--hub-port={HUB_PORT}"],
            stdout=log_file,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            env=env,
            start_new_session=True,
        )

    deadline = time.time() + wait_seconds
    while time.time() < deadline:
        url = find_hub_url()
        if url and is_port_open("127.0.0.1", HUB_PORT):
            return url
        time.sleep(0.35)
    return find_hub_url()


class BridgeHandler(BaseHTTPRequestHandler):
    def log_message(self, format: str, *args) -> None:
        return

    def send_json(self, status: int, payload: dict) -> None:
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def check_authorized(self) -> bool:
        origin = self.headers.get("Origin")
        if origin:
            host = (urlparse(origin).hostname or "").lower()
            if host not in ("localhost", "127.0.0.1"):
                self.send_json(403, {"ok": False, "error": "forbidden_origin"})
                return False
        supplied = (self.headers.get("X-Bridge-Token") or "").strip()
        if not supplied or not hmac.compare_digest(supplied, BRIDGE_TOKEN):
            self.send_json(403, {"ok": False, "error": "unauthorized"})
            return False
        return True

    def do_GET(self) -> None:
        if not self.check_authorized():
            return
        if self.path.split("?")[0] == "/health":
            agy_installed = os.path.isfile(AGY_BIN)
            hub_url = find_hub_url()
            hub_ready = bool(hub_url and is_port_open("127.0.0.1", HUB_PORT))
            self.send_json(
                200,
                {
                    "bridge": True,
                    "agy_installed": agy_installed,
                    "hub_ready": hub_ready,
                    "hub_url": hub_url,
                    "auto_update_disabled": True,
                },
            )
            return
        self.send_json(404, {"ok": False, "error": "not_found"})

    def do_POST(self) -> None:
        if not self.check_authorized():
            return
        route = self.path.split("?")[0]
        if route == "/restart-agy":
            url = restart_agy(wait_seconds=10.0)
            self.send_json(
                200 if url else 503,
                {"ok": bool(url), "hub_url": url},
            )
            return

        if route == "/notify":
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            raw = self.rfile.read(min(max(length, 0), 32768))
            try:
                data = json.loads(raw.decode("utf-8")) if raw else {}
            except Exception:
                data = {}
            payload = {
                "title": str(data.get("title") or "Antigravity")[:120],
                "body": str(data.get("body") or "Agent task finished")[:500],
                "ts": time.time(),
            }
            os.makedirs(BRIDGE_DIR, exist_ok=True)
            tmp = NOTIFY_FILE + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(payload, f)
            os.replace(tmp, NOTIFY_FILE)
            self.send_json(200, {"ok": True})
            return

        self.send_json(404, {"ok": False, "error": "not_found"})


class ReusableThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> None:
    server = ReusableThreadingHTTPServer((HOST, PORT), BridgeHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
