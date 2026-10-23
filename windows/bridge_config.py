"""Per-user configuration and user-logon scheduled task; no elevation."""
from __future__ import annotations

import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import socket
from xml.sax.saxutils import escape

DEFAULTS = dict(target_ip="192.168.1.32", adb_port=5555, bridge_port=8765,
                offline_check_interval_seconds=300, connected_check_interval_seconds=30,
                recovery_retry_interval_seconds=5, tcp_probe_timeout_seconds=2,
                adb_command_timeout_seconds=5, start_with_windows=False, watchdog_enabled=True,
                adb_path="", transport_mode="DIRECT_LAN", lan_interface="",
                server_name=socket.gethostname(), discovery_enabled=True)


def validate(value):
    if not isinstance(value, dict) or set(value)-set(DEFAULTS):
        raise ValueError("Unknown configuration fields")
    config = dict(DEFAULTS, **value)
    if config["transport_mode"] not in ("DIRECT_LAN", "ADB_REVERSE_LEGACY"):
        raise ValueError("Invalid transport mode")
    for key in ("lan_interface", "server_name"):
        if not isinstance(config[key], str) or len(config[key]) > 80 or any(ord(c)<32 for c in config[key]):
            raise ValueError(f"Invalid {key}")
    if not config["server_name"].strip():
        raise ValueError("Enter a server name")
    try:
        ipaddress.IPv4Address(config["target_ip"])
    except (ValueError, TypeError, ipaddress.AddressValueError):
        raise ValueError("Target IP must be a valid IPv4 address") from None
    for key in ("adb_port", "bridge_port"):
        if type(config[key]) is not int or not 1 <= config[key] <= 65535:
            raise ValueError(f"{key}: enter a port from 1 to 65535")
    for key in ("offline_check_interval_seconds", "connected_check_interval_seconds", "recovery_retry_interval_seconds"):
        if type(config[key]) is not int or not 1 <= config[key] <= 86400:
            raise ValueError(f"{key}: enter whole seconds from 1 to 86400")
    for key in ("tcp_probe_timeout_seconds", "adb_command_timeout_seconds"):
        if type(config[key]) not in (int, float) or not 0.1 <= config[key] <= 60:
            raise ValueError(f"{key}: enter seconds from 0.1 to 60")
    for key in ("start_with_windows", "watchdog_enabled", "discovery_enabled"):
        if type(config[key]) is not bool:
            raise ValueError(f"{key}: expected true/false")
    if not isinstance(config["adb_path"], str):
        raise ValueError("Invalid ADB path")
    # A previously selected executable can be removed after saving. That is a
    # watchdog ERROR, never a reason to prevent FileBridge from starting.
    if config["adb_path"] and (not Path(config["adb_path"]).is_absolute() or Path(config["adb_path"]).suffix.lower() != ".exe"):
        raise ValueError("Enter an absolute path to adb.exe")
    return config


def user_tag():
    identity = os.environ.get("USERPROFILE", getpass.getuser()).casefold()
    return hashlib.sha256(identity.encode()).hexdigest()[:16]


def config_directory():
    return Path(os.environ.get("APPDATA", Path.home())) / "AquaStream"


def log_directory():
    return Path(os.environ.get("LOCALAPPDATA", Path.home())) / "AquaStream" / "logs"


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name+".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(value, output, ensure_ascii=False, indent=2, allow_nan=False)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class ConfigStore:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.path = self.directory / "config.json"

    def load(self):
        if self.path.exists():
            old = json.loads(self.path.read_text(encoding="utf-8-sig"))
            if "transport_mode" not in old:
                old["transport_mode"] = "ADB_REVERSE_LEGACY"
            return validate(old)
        return dict(DEFAULTS)

    def save(self, config):
        atomic_json(self.path, validate(config))


def executable_command():
    if getattr(sys, "frozen", False):
        return sys.executable, ["--background"]
    python = Path(sys.executable)
    hidden = python.with_name("pythonw.exe")
    return str(hidden if hidden.exists() else python), [str(Path(__file__).with_name("bridge.py")), "--background"]


class StartupTask:
    name = "AquaStreamBridge-" + user_tag()

    def __init__(self, directory=None, logs=None):
        self.directory, self.logs = directory, logs

    def run(self, *args):
        result = subprocess.run(["schtasks.exe", *args], capture_output=True, text=True,
                                timeout=10, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return result

    def enabled(self):
        result = self.run("/Query", "/TN", self.name, "/XML")
        if result.returncode:
            # Distinguish a missing task from an inaccessible/unavailable scheduler.
            if self.run("/Query", "/FO", "CSV", "/NH").returncode:
                raise RuntimeError("Cannot query Windows Task Scheduler")
            return False
        import xml.etree.ElementTree as ET
        root = ET.fromstring(result.stdout.lstrip("\ufeff"))
        enabled = root.find("{*}Settings/{*}Enabled")
        return enabled is None or enabled.text != "false"

    def set_enabled(self, enabled):
        if not enabled:
            if self.enabled():
                result = self.run("/Delete", "/TN", self.name, "/F")
                if result.returncode:
                    raise RuntimeError((result.stderr or result.stdout).strip())
            return
        executable, arguments = executable_command()
        if self.directory and Path(self.directory).resolve() != config_directory().resolve():
            arguments += ["--data-dir", str(Path(self.directory).resolve())]
        if self.logs and Path(self.logs).resolve() != log_directory().resolve():
            arguments += ["--log-dir", str(Path(self.logs).resolve())]
        user = os.environ.get("USERDOMAIN", "") + "\\" + getpass.getuser()
        xml = f'''<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
 <RegistrationInfo><Description>AquaStream per-user background bridge</Description></RegistrationInfo>
 <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>{escape(user)}</UserId></LogonTrigger></Triggers>
 <Principals><Principal id="CurrentUser"><UserId>{escape(user)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
 <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><StartWhenAvailable>true</StartWhenAvailable><ExecutionTimeLimit>PT0S</ExecutionTimeLimit><Enabled>true</Enabled><RestartOnFailure><Interval>PT1M</Interval><Count>3</Count></RestartOnFailure></Settings>
 <Actions Context="CurrentUser"><Exec><Command>{escape(executable)}</Command><Arguments>{escape(subprocess.list2cmdline(arguments))}</Arguments><WorkingDirectory>{escape(str(Path(executable).parent))}</WorkingDirectory></Exec></Actions>
</Task>'''
        fd, path = tempfile.mkstemp(suffix=".xml")
        try:
            with os.fdopen(fd, "w", encoding="utf-16") as output:
                output.write(xml)
            result = self.run("/Create", "/TN", self.name, "/XML", path, "/F")
            if result.returncode:
                raise RuntimeError((result.stderr or result.stdout).strip())
        finally:
            os.unlink(path)
