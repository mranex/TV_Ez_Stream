"""One serialized, conservative ADB worker; independent of the HTTP server."""
from __future__ import annotations

import logging
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import threading
import time

LOG = logging.getLogger("aquastream.connection")


class ConnectionManager(threading.Thread):
    def __init__(self, config, on_status=None, probe=None, runner=None, clock=time.monotonic):
        super().__init__(name="connection-watchdog", daemon=True)
        self.config = dict(config)
        self.on_status = on_status or (lambda status: None)
        self.probe = probe or self.tcp_probe
        self.runner = runner or subprocess.run
        self.clock = clock
        self.condition = threading.Condition(threading.RLock())
        self.stopping = False
        self.pending = None
        self.generation = 0
        self.failures = 0
        self.due = clock()
        self.status = dict(state="AVAILABLE", reverse="Unknown", last_check=None,
                           last_success=None, tcp_latency_ms=None, detail="Waiting for first check")

    @staticmethod
    def tcp_probe(config):
        started = time.monotonic()
        with socket.create_connection((config["target_ip"], config["adb_port"]),
                                      timeout=config["tcp_probe_timeout_seconds"]):
            return round((time.monotonic() - started) * 1000, 2)

    @staticmethod
    def adb_path(config):
        if config.get("adb_path"):
            return config["adb_path"]
        home = Path(sys.executable if getattr(sys, "frozen", False) else __file__).parent
        beside = home / "platform-tools" / "adb.exe"
        return str(beside) if beside.is_file() else shutil.which("adb")

    def snapshot(self):
        with self.condition:
            return dict(self.status, enabled=self.config["watchdog_enabled"],
                        next_check_in_seconds=round(max(0, self.due-self.clock()), 1)
                        if self.config["watchdog_enabled"] else None)

    def update(self, config):
        with self.condition:
            old = self.config
            self.config = dict(config)
            self.generation += 1
            target_changed = any(old.get(k) != config.get(k) for k in ("target_ip", "adb_port", "bridge_port", "adb_path"))
            if target_changed:
                self.failures = 0
                self.status.update(state="AVAILABLE", reverse="Unknown", tcp_latency_ms=None,
                                   last_success=None, detail="Target configuration changed")
            immediate = target_changed or (config["watchdog_enabled"] and not old["watchdog_enabled"])
            self.due = self.clock() if immediate else self.clock() + self.interval(config)
            self.condition.notify_all()

    def request(self, recovery=False):
        with self.condition:
            self.pending = "recovery" if recovery or self.pending == "recovery" else "check"
            self.condition.notify_all()

    def stop(self):
        with self.condition:
            self.stopping = True
            self.condition.notify_all()

    def publish(self, generation, **values):
        with self.condition:
            if generation != self.generation or self.stopping:
                return False
            previous = self.status["state"]
            self.status.update(values)
            if previous != self.status["state"]:
                LOG.info("State: %s (%s)", self.status["state"], self.status["detail"])
            self.on_status(dict(self.status))
            self.condition.notify_all()
            return True

    def interval(self, config):
        state = self.status["state"]
        if state == "CONNECTED":
            return config["connected_check_interval_seconds"]
        if state == "RECOVERING" and self.failures <= 3:
            return config["recovery_retry_interval_seconds"]
        return config["offline_check_interval_seconds"]

    def command(self, config, generation, *args, connection=False):
        # Config changes and shutdown invalidate the remainder of an old operation.
        with self.condition:
            if generation != self.generation or self.stopping:
                raise InterruptedError("Operation superseded")
            adb = self.adb_path(config)
            if not adb:
                raise FileNotFoundError("adb.exe missing; select its path in Settings")
            serial = f'{config["target_ip"]}:{config["adb_port"]}'
            command = [adb, *args] if connection else [adb, "-s", serial, *args]
        result = self.runner(command, capture_output=True, text=True, encoding="utf-8", errors="replace",
                             timeout=config["adb_command_timeout_seconds"],
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            LOG.warning("ADB %s failed (%s): %s", args[0], result.returncode,
                        (result.stderr or result.stdout or "No diagnostic")[:500].strip())
        return result

    def cycle(self, config, generation, manual=None):
        if manual:
            LOG.info("Manual %s requested", manual)
        self.publish(generation, last_check=time.time(), tcp_latency_ms=None)
        try:
            latency = self.probe(config)
        except OSError as probe_error:
            # A failed/slow fresh socket must not reset an already healthy ADB transport.
            if self.status["state"] == "CONNECTED":
                try:
                    current = self.command(config, generation, "get-state")
                    if current.returncode == 0 and current.stdout.strip() == "device":
                        latency = None
                    else:
                        raise OSError("Transport also unavailable")
                except (OSError, subprocess.TimeoutExpired, InterruptedError):
                    self.failures = 0
                    self.publish(generation, state="UNREACHABLE", reverse="Unknown",
                                 detail=f"ADB port unreachable ({probe_error}); device power state unknown")
                    return
            else:
                self.failures = 0
                self.publish(generation, state="UNREACHABLE", reverse="Unknown",
                             detail=f"ADB port unreachable ({probe_error}); device power state unknown")
                return
        if not self.publish(generation, tcp_latency_ms=latency):
            return
        try:
            if self.status["state"] != "CONNECTED":
                self.publish(generation, state="AVAILABLE", detail="ADB port reachable; verifying transport")
            result = self.command(config, generation, "get-state")
            online = result.returncode == 0 and result.stdout.strip() == "device"
            if not online:
                # Confirm a transient transport failure before touching it.
                result = self.command(config, generation, "get-state")
                online = result.returncode == 0 and result.stdout.strip() == "device"
            if not online:
                self.publish(generation, state="RECOVERING", reverse="Unknown", detail="Restoring target transport")
                serial = f'{config["target_ip"]}:{config["adb_port"]}'
                LOG.info("%s ADB connect %s", "Manual" if manual else "Automatic", serial)
                result = self.command(config, generation, "connect", serial, connection=True)
                if result.returncode:
                    raise RuntimeError("ADB connect failed")
                result = self.command(config, generation, "get-state")
                if result.returncode or result.stdout.strip() != "device":
                    message = (result.stderr or result.stdout).strip()
                    if "unauthorized" in message.lower():
                        raise PermissionError("Accept the ADB authorization prompt on the TV")
                    raise RuntimeError("ADB transport not ready after connect")
            port = f'tcp:{config["bridge_port"]}'
            result = self.command(config, generation, "reverse", "--list")
            if result.returncode:
                raise RuntimeError("Cannot verify Reverse mapping")
            mapped = any(len(parts := line.split()) >= 2 and parts[-2:] == [port, port]
                         for line in result.stdout.splitlines())
            if not mapped:
                self.publish(generation, state="RECOVERING", reverse="Missing", detail="Restoring AquaStream Reverse mapping")
                result = self.command(config, generation, "reverse", port, port)
                if result.returncode:
                    raise RuntimeError("Reverse restoration failed")
                result = self.command(config, generation, "reverse", "--list")
                if result.returncode or not any(line.split()[-2:] == [port, port] for line in result.stdout.splitlines()):
                    raise RuntimeError("Reverse mapping not confirmed")
                LOG.info("Reverse mapping restored: %s -> %s", port, port)
            self.failures = 0
            self.publish(generation, state="CONNECTED", reverse="Active", last_success=time.time(),
                         detail="ADB transport and Reverse verified (not an end-to-end streaming test)")
        except InterruptedError:
            return
        except (FileNotFoundError, PermissionError) as error:
            self.failures += 1
            self.publish(generation, state="ERROR", reverse="Unknown", detail=str(error))
        except (OSError, subprocess.TimeoutExpired, RuntimeError) as error:
            self.failures += 1
            self.publish(generation, state="RECOVERING", reverse="Unknown", detail=str(error))
            LOG.warning("Recovery attempt %d: %s", self.failures, error)

    def run(self):
        LOG.info("Watchdog worker started")
        while True:
            with self.condition:
                while not self.stopping:
                    if self.pending or (self.config["watchdog_enabled"] and self.clock() >= self.due):
                        break
                    wait = max(0, self.due-self.clock()) if self.config["watchdog_enabled"] else None
                    self.condition.wait(wait)
                if self.stopping:
                    break
                config, generation, manual = dict(self.config), self.generation, self.pending
                self.pending = None
            try:
                self.cycle(config, generation, manual)
            except Exception:
                LOG.exception("Unexpected watchdog error; backing off")
                self.publish(generation, state="ERROR", reverse="Unknown", detail="Unexpected error; see log")
            with self.condition:
                if generation == self.generation:
                    self.due = self.clock() + self.interval(config)
        LOG.info("Watchdog worker stopped")
