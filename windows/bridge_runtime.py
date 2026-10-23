"""Background runtime and local JSON Named Pipe control channel."""
from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import hashlib
import hmac
import http.client
import json
import logging
from logging.handlers import RotatingFileHandler
from multiprocessing.connection import Client
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time
try:
    from .lan_transport import AccessStore, Advertisement, interfaces, select_address
except ImportError:
    from lan_transport import AccessStore, Advertisement, interfaces, select_address

try:
    from .bridge_config import ConfigStore, StartupTask, atomic_json, config_directory, log_directory, validate, user_tag
    from .connection_manager import ConnectionManager
except ImportError:
    from bridge_config import ConfigStore, StartupTask, atomic_json, config_directory, log_directory, validate, user_tag
    from connection_manager import ConnectionManager

LOG = logging.getLogger("aquastream")


def pipe_name(directory):
    suffix = hashlib.sha256(str(Path(directory).resolve()).casefold().encode()).hexdigest()[:16]
    return rf"\\.\pipe\AquaStream-{user_tag()}-{suffix}"


def local_listener(address):
    # stdlib PipeListener otherwise permits SMB remote pipe clients. Explicitly
    # reject those at the OS boundary; all payloads are bounded JSON, never pickle.
    import _winapi
    from multiprocessing.connection import BUFSIZE, PipeListener
    class LocalPipeListener(PipeListener):
        def _new_handle(self, first=False):
            flags = _winapi.PIPE_ACCESS_DUPLEX | _winapi.FILE_FLAG_OVERLAPPED
            if first:
                flags |= _winapi.FILE_FLAG_FIRST_PIPE_INSTANCE
            return _winapi.CreateNamedPipe(self._address, flags,
                _winapi.PIPE_TYPE_MESSAGE | _winapi.PIPE_READMODE_MESSAGE | _winapi.PIPE_WAIT | 0x8,
                _winapi.PIPE_UNLIMITED_INSTANCES, BUFSIZE, BUFSIZE,
                _winapi.NMPWAIT_WAIT_FOREVER, _winapi.NULL)
    return LocalPipeListener(address)


class InstanceLock:
    def __init__(self, name):
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
        kernel.CreateMutexW.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel = kernel
        self.handle = kernel.CreateMutexW(None, False, "Global\\" + name)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        self.acquired = ctypes.get_last_error() != 183

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


def ipc_request(directory, action, **values):
    token = (Path(directory) / "control.key").read_text(encoding="ascii").strip()
    with Client(pipe_name(directory), family="AF_PIPE") as client:
        client.send_bytes(json.dumps(dict(token=token, action=action, **values), allow_nan=False).encode())
        if not client.poll(90):
            raise TimeoutError("Backend did not reply")
        result = json.loads(client.recv_bytes(65536))
    if not result.get("ok"):
        raise RuntimeError(result.get("error", "Backend request failed"))
    return result["result"]


class Backend:
    def __init__(self, store, args):
        self.store, self.args = store, args
        self.config = store.load()
        if args.adb:
            self.config = validate(dict(self.config, adb_path=args.adb))
        if args.no_adb:
            self.config["watchdog_enabled"] = False
        self.shutdown_event = threading.Event()
        self.operation_lock = threading.RLock()
        self.server = None
        self.http_thread = None
        self.manager = None
        self.access = AccessStore(store.directory)
        self.advertisement = Advertisement()
        self.network_interfaces = []
        self.network_thread = None
        self.task = StartupTask(store.directory, args.log_dir)
        self.task_status, self.task_error, self.task_checked = None, None, 0

    def storage_path(self):
        # Keep the existing SQLite database and its schema untouched. Remember its
        # location in AppData so moving the executable does not lose watch history.
        metadata = self.store.directory / "storage.json"
        if metadata.exists():
            return Path(json.loads(metadata.read_text(encoding="utf-8"))["progress_database"])
        home = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent.parent / ".runtime"
        legacy = home / "config" / "progress.sqlite3"
        database = legacy if legacy.exists() else self.store.directory / "progress.sqlite3"
        atomic_json(metadata, {"progress_database": str(database.resolve())})
        return database

    def make_server(self, port, config=None, address=None):
        try:
            from .bridge import BridgeServer
        except ImportError:
            from bridge import BridgeServer
        config = config or self.config
        address = address or select_address(config, self.network_interfaces)
        return BridgeServer((address, port), self.files, self.progress, access=self.access,
                            mode=config["transport_mode"], server_name=config["server_name"])

    def serve(self, server):
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .2}, name="file-bridge", daemon=True)
        thread.start()
        return thread

    def check_http(self, port, address="127.0.0.1"):
        connection = http.client.HTTPConnection(address, port, timeout=2)
        try:
            connection.request("GET", "/health")
            result = connection.getresponse()
            if result.status != 200 or json.loads(result.read())["status"] != "ok":
                raise RuntimeError("FileBridge readiness check failed")
        finally:
            connection.close()

    def start(self):
        try:
            from .bridge import Files, DriveRegistry
            from .progress_store import ProgressStore
        except ImportError:
            from bridge import Files, DriveRegistry
            from progress_store import ProgressStore
        signing = self.store.directory / "file-signing.json"
        if not signing.exists(): atomic_json(signing, {"key":secrets.token_hex(32)})
        self.files = Files(DriveRegistry(self.args.allow_root), bytes.fromhex(json.loads(signing.read_text(encoding="utf-8"))["key"]))
        self.progress = ProgressStore(self.storage_path())
        if self.config["transport_mode"] == "DIRECT_LAN":
            try: self.network_interfaces = interfaces()
            except Exception: LOG.warning("Network interface enumeration unavailable")
        self.server = self.make_server(self.config["bridge_port"])
        self.http_thread = self.serve(self.server)
        self.check_http(self.config["bridge_port"], self.server.server_address[0])
        LOG.info("FileBridge started on %s:%s (%s)", *self.server.server_address, self.config["transport_mode"])
        self.store.save(self.config)
        self.setup_transport()
        self.network_thread = threading.Thread(target=self.watch_network, name="lan-network", daemon=True)
        self.network_thread.start()

    def setup_transport(self):
        if self.config["transport_mode"] == "ADB_REVERSE_LEGACY":
            self.advertisement.close()
            if self.manager: self.manager.update(self.config)
            else:
                self.manager = ConnectionManager(self.config, self.on_connection_status)
                self.manager.start()
        else:
            if self.manager:
                self.manager.stop(); self.manager.join(65); self.manager = None
            self.advertisement.update(self.config, self.server.server_address[0], self.access.server_id)

    def watch_network(self):
        while not self.shutdown_event.wait(30):
            try:
                if self.config["transport_mode"] != "DIRECT_LAN": continue
                available = interfaces()
                with self.operation_lock:
                    self.network_interfaces = available
                    if self.config["transport_mode"] == "DIRECT_LAN":
                        address = select_address(self.config, available)
                        if address != self.server.server_address[0]:
                            self.replace_listener(self.config, address)
                            LOG.info("LAN address changed; stable server identity retained")
                        self.advertisement.update(self.config, self.server.server_address[0], self.access.server_id)
            except Exception:
                LOG.warning("LAN network refresh failed; keeping previous listener")

    def replace_listener(self, config, address=None):
        address = address or select_address(config, self.network_interfaces)
        previous, previous_thread = self.server, self.http_thread
        if previous.server_address == (address, config["bridge_port"]):
            previous.mode = config["transport_mode"]
            previous.server_name = config["server_name"]
            return
        same_port = previous.server_port == config["bridge_port"]
        if same_port:
            previous.shutdown(); previous.server_close(); previous_thread.join(2)
        try:
            replacement = self.make_server(config["bridge_port"], config, address)
            thread = self.serve(replacement)
            self.check_http(config["bridge_port"], address)
        except Exception:
            if 'replacement' in locals(): replacement.shutdown(); replacement.server_close()
            if same_port:
                self.server = self.make_server(self.config["bridge_port"], self.config, previous.server_address[0])
                self.http_thread = self.serve(self.server)
            raise
        self.server, self.http_thread = replacement, thread
        if not same_port:
            previous.shutdown(); previous.server_close(); previous_thread.join(2)

    def on_connection_status(self, status):
        if self.server: self.server.adb_status = "connected" if status["state"] == "CONNECTED" else status["state"].lower()

    def startup_status(self):
        if time.monotonic() - self.task_checked > 60:
            try:
                self.task_status, self.task_error = self.task.enabled(), None
            except (OSError, subprocess.TimeoutExpired, RuntimeError, ValueError) as error:
                self.task_status, self.task_error = None, str(error)
            self.task_checked = time.monotonic()
        return self.task_status

    def status(self):
        return dict(file_bridge="Running" if self.http_thread and self.http_thread.is_alive() else "Error",
                    connection=self.manager.snapshot() if self.manager else dict(state="DIRECT_LAN", reverse="Not used", enabled=False,
                        last_check=None,last_success=None,tcp_latency_ms=None,next_check_in_seconds=None,detail="Pair on the PC to authorize LAN access"), config=dict(self.config),
                    lan=dict(server_id=self.access.server_id, address=self.server.server_address[0],
                        discovery=bool(self.advertisement.zc), discovery_error=self.advertisement.error,
                        interfaces=self.network_interfaces, devices=self.access.devices(), active_streams=self.server.active_streams),
                    startup_enabled=self.startup_status(), startup_error=self.task_error,
                    progress_database=self.progress.path, config_directory=str(self.store.directory), pid=os.getpid(),
                    log_directory=str(self.args.log_dir))

    def apply(self, value, allow_interrupt=False):
        candidate = validate(value)
        with self.operation_lock:
            old = self.config
            replacement = None
            replacement_thread = None
            changed_task = False
            try:
                listener_changed = any(candidate[k] != old[k] for k in ("bridge_port", "transport_mode", "lan_interface"))
                if listener_changed:
                    if candidate["transport_mode"] == "DIRECT_LAN": self.network_interfaces = interfaces()
                    if self.server.active_streams and not allow_interrupt:
                        raise ValueError("A video is streaming. Confirm the connection change in ConfigUI first.")
                    self.replace_listener(candidate)
                    replacement = self.server
                task_state = self.startup_status()
                if (candidate["start_with_windows"] != old["start_with_windows"] or
                        (task_state is not None and candidate["start_with_windows"] != task_state)):
                    self.task.set_enabled(candidate["start_with_windows"])
                    changed_task = True
                self.store.save(candidate)
            except Exception:
                if replacement:
                    self.replace_listener(old)
                if changed_task:
                    try:
                        self.task.set_enabled(old["start_with_windows"])
                    except Exception:
                        LOG.exception("Could not restore previous startup task")
                raise
            self.config = candidate
            self.server.server_name = candidate["server_name"]
            self.setup_transport()
            self.task_checked = 0
            LOG.info("Configuration changed: %s", ", ".join(k for k in candidate if candidate[k] != old[k]) or "unchanged")
            return self.status()

    def control(self, request):
        action = request.get("action")
        if action == "status":
            return self.status()
        if action == "configure":
            return self.apply(request["config"], request.get("allow_interrupt") is True)
        if action == "pair":
            if self.config["transport_mode"] != "DIRECT_LAN": raise ValueError("Select Direct LAN first")
            return self.access.open_pairing()
        if action == "devices": return self.access.devices()
        if action == "revoke":
            self.access.revoke(request["device_id"])
            return {"revoked": True}
        if action in ("check", "recover"):
            if self.manager: self.manager.request(recovery=action == "recover")
            return {"queued": True}
        if action == "shutdown":
            LOG.info("Manual backend shutdown requested")
            self.shutdown_event.set()
            return {"stopping": True}
        raise ValueError("Unknown control operation")

    def close(self):
        self.shutdown_event.set()
        if self.network_thread: self.network_thread.join(12)
        self.advertisement.close()
        if self.manager:
            self.manager.stop()
            if self.manager.ident is not None:
                self.manager.join(65)  # at most one bounded ADB command is still in progress
        if self.server:
            if self.http_thread and self.http_thread.is_alive():
                self.server.shutdown()
            self.server.server_close()
            if self.http_thread:
                self.http_thread.join(2)
        if self.server and self.server.active_streams == 0: self.access.close()
        LOG.info("Application shutdown")


def run_backend(store, args):
    lock = InstanceLock("AquaStreamBackend-" + pipe_name(store.directory).rsplit("\\", 1)[-1])
    if not lock.acquired:
        lock.close()
        LOG.info("Backend already running; duplicate start ignored")
        return 0
    backend = None
    listener = None
    control_thread = None
    try:
        key_path = store.directory / "control.key"
        store.directory.mkdir(parents=True, exist_ok=True)
        if not key_path.exists():
            key_path.write_text(secrets.token_hex(32), encoding="ascii")
        token = key_path.read_text(encoding="ascii").strip()
        backend = Backend(store, args)
        backend.start()
        listener = local_listener(pipe_name(store.directory))

        def controls():
            while not backend.shutdown_event.is_set():
                try:
                    with listener.accept() as connection:
                        if not connection.poll(3):
                            continue
                        request = json.loads(connection.recv_bytes(32768))
                        if not isinstance(request, dict) or not isinstance(request.get("token"), str) or not hmac.compare_digest(request["token"], token):
                            connection.send_bytes(b'{"ok": false, "error": "Unauthorized"}')
                            continue
                        try:
                            reply = dict(ok=True, result=backend.control(request))
                        except Exception as error:
                            LOG.warning("Control request failed: %s", error)
                            reply = dict(ok=False, error=str(error))
                        connection.send_bytes(json.dumps(reply, allow_nan=False).encode())
                except (OSError, EOFError, ValueError, TypeError):
                    if not backend.shutdown_event.is_set():
                        LOG.warning("Invalid or interrupted local control request")

        control_thread = threading.Thread(target=controls, name="local-control", daemon=True)
        control_thread.start()
        LOG.info("Background runtime ready")
        backend.shutdown_event.wait()
        return 0
    except KeyboardInterrupt:
        return 0
    except Exception:
        LOG.exception("Backend startup/runtime error")
        return 1
    finally:
        if backend:
            backend.shutdown_event.set()
            backend.close()
        if listener:
            # Wake a pending accept so the worker can observe shutdown and exit.
            if control_thread and control_thread.is_alive():
                try:
                    with Client(pipe_name(store.directory), family="AF_PIPE") as wake:
                        wake.send_bytes(b"{}")
                except OSError:
                    pass
                control_thread.join(4)
            listener.close()
        lock.close()


def main():
    parser = argparse.ArgumentParser(description="AquaStream Bridge")
    parser.add_argument("--background", action="store_true")
    parser.add_argument("--firewall", choices=["enable","disable"])
    parser.add_argument("--firewall-port", type=int, default=8765)
    parser.add_argument("--firewall-mdns", action="store_true")
    parser.add_argument("--adb")
    parser.add_argument("--no-adb", action="store_true")
    parser.add_argument("--allow-root", action="append")
    parser.add_argument("--data-dir", help="Override per-user config directory (development)")
    parser.add_argument("--log-dir", help="Override rotating log directory (development)")
    parser.add_argument("--control", choices=["status", "check", "recover", "shutdown"])
    args = parser.parse_args()
    if args.firewall:
        try:
            from .lan_firewall import configure
        except ImportError:
            from lan_firewall import configure
        configure(args.firewall_port,args.firewall=="enable",args.firewall_mdns)
        return 0
    directory = Path(args.data_dir) if args.data_dir else config_directory()
    args.log_dir = Path(args.log_dir) if args.log_dir else log_directory()
    if args.control:
        try:
            result = ipc_request(directory, args.control)
        except (OSError, EOFError, RuntimeError, TimeoutError):
            return 1
        if sys.stdout:
            print(json.dumps(result, ensure_ascii=False))
        return 0
    if not args.background:
        args.log_dir.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(args.log_dir / "gui.log", maxBytes=500_000, backupCount=2, encoding="utf-8")
        logging.basicConfig(level=logging.INFO, handlers=[handler], format="%(asctime)s %(levelname)s %(message)s")
        try:
            from .config_ui import run_gui
        except ImportError:
            from config_ui import run_gui
        return run_gui(directory, args)
    args.log_dir.mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(args.log_dir / "bridge.log", maxBytes=1_000_000, backupCount=3, encoding="utf-8")
    logging.basicConfig(level=logging.INFO, handlers=[handler], format="%(asctime)s %(levelname)s %(message)s")
    if os.name == "nt":
        ctypes.windll.kernel32.SetErrorMode(0x0001 | 0x8000)
    LOG.info("Application startup (background)")
    return run_backend(ConfigStore(directory), args)
