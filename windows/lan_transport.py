"""LAN identity, deliberately opened pairing sessions, hashed client credentials and DNS-SD.

HTTP credentials are visible to network observers: this is local access control, not TLS.
No PIN/token/path is logged or advertised. No persistent plaintext bearer is stored on PC.
"""
from __future__ import annotations
import hashlib
import hmac
import ipaddress
import json
import logging
import os
from pathlib import Path
import secrets
import socket
import sqlite3
import subprocess
import threading
import time
import uuid

try:
    from .bridge_config import atomic_json
except ImportError:
    from bridge_config import atomic_json

LOG = logging.getLogger("aquastream.lan")
SERVICE = "_aquastream._tcp.local."


class PairingError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


class AccessStore:
    def __init__(self, directory, clock=time.monotonic):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        identity = self.directory / "server.json"
        if not identity.exists():
            atomic_json(identity, {"server_id": str(uuid.uuid4())})
        self.server_id = str(uuid.UUID(json.loads(identity.read_text(encoding="utf-8"))["server_id"]))
        self.lock = threading.RLock()
        self.clock = clock
        self.pin = None
        self.expires = 0
        self.failures = 0
        self.attempts = {}
        self.db = sqlite3.connect(self.directory / "devices.sqlite3", check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, digest TEXT NOT NULL, last_connected REAL NOT NULL)")
        self.db.commit()

    def open_pairing(self):
        with self.lock:
            self.pin = f"{secrets.randbelow(1000000):06d}"
            self.expires = self.clock() + 120
            self.failures = 0
            return {"pin": self.pin, "expires_in": 120}

    def pair(self, payload, address):
        with self.lock:
            now = self.clock()
            self.attempts = {k:v for k,v in self.attempts.items() if now-v[0]<120}
            start, count = self.attempts.get(address, (now, 0))
            if count >= 8 or (address not in self.attempts and len(self.attempts)>=256):
                raise PairingError(429, "Too many pairing attempts; wait two minutes")
            self.attempts[address] = (start, count+1)
            if not self.pin or now >= self.expires or self.failures >= 5:
                raise PairingError(403, "Open a new pairing session on the PC")
            pin = payload.get("pin", "")
            if not isinstance(pin, str) or not hmac.compare_digest(pin, self.pin):
                self.failures += 1
                raise PairingError(403, "Incorrect PIN")
            try:
                device = str(uuid.UUID(payload.get("device_id", "")))
            except (ValueError, TypeError, AttributeError):
                raise PairingError(400, "Invalid device ID") from None
            name = payload.get("device_name", "Android TV")
            if not isinstance(name, str) or not 1 <= len(name) <= 80 or any(ord(c)<32 for c in name):
                raise PairingError(400, "Invalid device name")
            token = secrets.token_urlsafe(32)
            self.db.execute("INSERT OR REPLACE INTO devices VALUES (?,?,?,?)", (device, name, hashlib.sha256(token.encode()).hexdigest(), time.time()))
            self.db.commit()
            self.pin = None  # One successful device per explicitly opened session.
            return {"server_id": self.server_id, "device_id": device, "credential": token}

    def authorize(self, authorization):
        if not isinstance(authorization, str) or not authorization.startswith("Bearer ") or len(authorization)>256:
            return False
        digest = hashlib.sha256(authorization[7:].encode()).hexdigest()
        with self.lock:
            row = self.db.execute("SELECT id,last_connected FROM devices WHERE digest=?", (digest,)).fetchone()
            if row is None:
                return False
            if time.time()-row[1] >= 30:
                self.db.execute("UPDATE devices SET last_connected=? WHERE id=?", (time.time(), row[0]))
                self.db.commit()
            return True

    def devices(self):
        with self.lock:
            return [dict(device_id=r[0], device_name=r[1], last_connected=r[2]) for r in self.db.execute("SELECT id,name,last_connected FROM devices ORDER BY name")]

    def revoke(self, device):
        with self.lock:
            self.db.execute("DELETE FROM devices WHERE id=?", (device,))
            self.db.commit()

    def close(self):
        with self.lock:
            self.db.close()


def interfaces():
    """IPv4 interfaces only; never advertise a wildcard/public/loopback address."""
    preferred = None
    try:
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as route:
            route.connect(("192.0.2.1",9))  # Unicast default-route selection only; NO packet sent.
            preferred = route.getsockname()[0]
    except OSError: pass
    try:
        import ifaddr
        result = [dict(name=a.nice_name,ip=ip.ip,default=ip.ip==preferred) for a in ifaddr.get_adapters() for ip in a.ips if isinstance(ip.ip,str)]
    except ImportError:
        result = [dict(name="LAN",ip=x,default=x==preferred) for x in socket.gethostbyname_ex(socket.gethostname())[2]]
    def suitable(row):
        address = ipaddress.ip_address(row["ip"])
        return address.version == 4 and address.is_private and not (address.is_loopback or address.is_link_local or address.is_unspecified or address.is_multicast)
    def order(row):
        virtual=any(s in row["name"].casefold() for s in ("virtual","vmware","hyper-v","vpn","wintun","loopback"))
        return (virtual,not row.get("default"))
    return sorted([r for r in result if suitable(r)], key=order)


def select_address(config, available=None):
    if config["transport_mode"] == "ADB_REVERSE_LEGACY": return "127.0.0.1"
    available = interfaces() if available is None else available
    selected = [r for r in available if not config["lan_interface"] or r["name"] == config["lan_interface"]]
    return selected[0]["ip"] if selected else "127.0.0.1"  # Wait for network; auth still required.


class Advertisement:
    def __init__(self):
        self.zc = None
        self.info = None
        self.error = None
        self.key = None

    def update(self, config, address, server_id):
        key = (config["transport_mode"], address, config["bridge_port"], config["server_name"], config["discovery_enabled"])
        if key == self.key and not self.error: return
        self.close()
        self.key = key
        if key[0] != "DIRECT_LAN" or not key[4] or address == "127.0.0.1": return
        try:
            from zeroconf import Zeroconf, ServiceInfo, IPVersion
            self.zc = Zeroconf(interfaces=[address], ip_version=IPVersion.V4Only)
            # Stable suffix avoids same-hostname collisions; TXT contains public identity only.
            label = ("AquaStream - " + config["server_name"].replace(".","·")).encode("utf-8")[:50].decode("utf-8",errors="ignore") + "-" + server_id[:8]
            self.info = ServiceInfo(SERVICE, label+"."+SERVICE, addresses=[socket.inet_aton(address)], port=config["bridge_port"],
                properties={"server_id":server_id, "version":"1", "name":config["server_name"].encode("utf-8")[:160].decode("utf-8",errors="ignore")}, server="aquastream-"+server_id+".local.")
            self.zc.register_service(self.info, allow_name_change=True)
            self.error = None
        except Exception as error:
            self.error = str(error)
            LOG.warning("LAN advertisement unavailable: %s", type(error).__name__)
            if self.zc: self.zc.close()
            self.zc = None

    def close(self):
        if self.zc:
            if self.info: self.zc.unregister_service(self.info)
            self.zc.close()
        self.zc = self.info = None
        self.key = None
        self.error = None
