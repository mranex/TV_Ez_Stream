"""Shared file API: authenticated Direct LAN or loopback-only ADB Reverse."""
from __future__ import annotations

import base64
import ctypes
from ctypes import wintypes
import hashlib
import heapq
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import mimetypes
import os
from pathlib import Path
import re
import secrets
import stat
import sqlite3
import threading
import time
from urllib.parse import parse_qs, urlsplit

try:
    from .progress_store import ProgressStore, volume_keys
except ImportError:
    from progress_store import ProgressStore, volume_keys

LOG = logging.getLogger("aquastream")
VIDEO = {".mp4", ".m4v", ".mkv", ".webm", ".mov", ".ts", ".avi"}
PORT = 8765
CHUNK = 64 * 1024
NAME_PARTS = re.compile(r"([0-9]+)")


def natural_name_key(name: str) -> tuple:
    """Compare digit runs numerically without parsing unbounded integers."""
    parts = []
    for part in NAME_PARTS.split(name.casefold()):
        if part and part[0] in "0123456789":
            number = part.lstrip("0") or "0"
            parts.append((1, len(number), number))
        else:
            parts.append((0, part))
    return tuple(parts)


class ApiError(Exception):
    def __init__(self, status: int, message: str):
        self.status, self.message = status, message


def native_volume(root: str) -> tuple[str, str]:
    label = ctypes.create_unicode_buffer(261)
    serial = wintypes.DWORD()
    ok = ctypes.windll.kernel32.GetVolumeInformationW(
        str(root), label, len(label), ctypes.byref(serial), None, None, None, 0)
    return (label.value, str(serial.value)) if ok else ("", "unavailable")


class DriveRegistry:
    """Re-enumerates on every request; fixed USB disks and ordinary data disks included."""
    def __init__(self, roots: list[str] | None = None):
        self.roots = [Path(p).absolute() for p in roots] if roots else None

    def current(self) -> dict[str, dict]:
        result = {}
        if self.roots:
            candidates = [(p, "test", p.name or str(p)) for p in self.roots]
        elif os.name == "nt":
            system = os.environ.get("SystemDrive", "C:").upper()
            mask = ctypes.windll.kernel32.GetLogicalDrives()
            candidates = []
            for n in range(26):
                if not mask & (1 << n):
                    continue
                root = Path(f"{chr(65+n)}:\\")
                kind = ctypes.windll.kernel32.GetDriveTypeW(str(root))
                if str(root)[:2].upper() == system or kind not in (2, 3):
                    continue
                label, serial = native_volume(str(root))
                if serial == "unavailable":
                    continue
                candidates.append((root, serial, f"{root.drive}  {label}".rstrip()))
        else:
            candidates = []
        for root, identity, label in candidates:
            try:
                st = root.lstat()
                if self.is_link(st) or not stat.S_ISDIR(st.st_mode):
                    continue
                identity = f"{identity}:{st.st_dev}:{st.st_ino}"
                key = hashlib.sha256(f"{root}:{identity}".encode()).hexdigest()[:24]
                result[key] = {"root": root, "identity": identity, "name": label}
            except OSError:
                continue
        return result

    @staticmethod
    def is_link(st: os.stat_result) -> bool:
        return stat.S_ISLNK(st.st_mode) or bool(getattr(st, "st_file_attributes", 0) & 0x400)


class Files:
    def __init__(self, registry: DriveRegistry, secret=None):
        self.registry = registry
        self.secret = secret or secrets.token_bytes(32)

    def token(self, drive: str, relative: str = "") -> str:
        data = json.dumps([drive, relative], ensure_ascii=False, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(data + hmac.digest(self.secret, data, "sha256")).decode().rstrip("=")

    def resolve(self, token: str) -> tuple[Path, str, str, Path]:
        if not token or len(token) > 16384:
            raise ApiError(403, "ID đường dẫn không hợp lệ. Refresh danh sách ổ đĩa.")
        try:
            raw = base64.b64decode(token + "=" * (-len(token) % 4), altchars=b"-_", validate=True)
            data, signature = raw[:-32], raw[-32:]
            if not hmac.compare_digest(signature, hmac.digest(self.secret, data, "sha256")):
                raise ValueError()
            drive, rel = json.loads(data)
            if not isinstance(drive, str) or not isinstance(rel, str):
                raise ValueError()
        except (ValueError, TypeError, UnicodeError):
            raise ApiError(403, "ID đường dẫn không hợp lệ. Refresh danh sách ổ đĩa.") from None
        roots = self.registry.current()
        if drive not in roots:
            raise ApiError(410, "Ổ đĩa đã bị rút hoặc thay đổi. Refresh danh sách ổ đĩa.")
        # No alternate data streams, rooted paths, dot segments or Windows aliases.
        parts = rel.split("/") if rel else []
        if any(not p or p in (".", "..") or any(c in p for c in '\\:\x00') or p.endswith((".", " ")) for p in parts):
            raise ApiError(403, "Đường dẫn bị từ chối.")
        root = roots[drive]["root"]
        path = root
        for part in [None] + parts:
            if part is not None:
                path = path / part
            try:
                if self.registry.is_link(path.lstat()):
                    raise ApiError(403, "Không truy cập symlink hoặc junction.")
            except FileNotFoundError:
                raise ApiError(404, "File hoặc thư mục không còn tồn tại.") from None
        return path, drive, rel, root

    def opened(self, token: str):
        path, _, _, root = self.resolve(token)
        if path.suffix.lower() not in VIDEO:
            raise ApiError(403, "Chỉ cho phép đọc file video.")
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0))
        try:
            if os.name == "nt":
                import msvcrt
                kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                final = kernel.GetFinalPathNameByHandleW
                final.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD]
                final.restype = wintypes.DWORD
                buf = ctypes.create_unicode_buffer(32768)
                count = final(msvcrt.get_osfhandle(fd), buf, len(buf), 0)
                if not count or count >= len(buf):
                    raise ApiError(403, "Không xác minh được file.")
                actual = Path(buf.value.removeprefix("\\\\?\\"))
            else:
                actual = Path(os.readlink(f"/proc/self/fd/{fd}"))
            if not actual.is_relative_to(root.resolve()):
                raise ApiError(403, "File nằm ngoài ổ đĩa cho phép.")
            # Validate again after open and compare the opened file against current path.
            self.resolve(token)
            if not os.path.samestat(os.fstat(fd), path.stat()) or not stat.S_ISREG(os.fstat(fd).st_mode):
                raise ApiError(403, "File đã thay đổi trong lúc mở.")
            return os.fdopen(fd, "rb"), path
        except BaseException:
            os.close(fd)
            raise

    def progress_identity(self, token: str):
        path, drive, rel, root = self.resolve(token)
        opened, verified = self.opened(token)
        with opened:
            st = os.fstat(opened.fileno())
            volume, fallback = volume_keys(root, explicit=bool(self.registry.roots))
        return ProgressStore.identity(volume, fallback, rel, st.st_size, st.st_mtime_ns, str(st.st_ino)), self.token(drive, rel.rpartition('/')[0]), path.name

    def drives(self):
        return {"drives": [{"id": self.token(key), "name": d["name"]} for key, d in self.registry.current().items()]}

    def browse(self, token: str, offset: int = 0, limit: int = 200):
        path, drive, rel, _ = self.resolve(token)
        if not path.is_dir():
            raise ApiError(400, "Đây không phải thư mục.")
        if offset < 0 or offset > 100000 or limit < 1 or limit > 500:
            raise ApiError(400, "Trang thư mục không hợp lệ.")
        def entries():
            with os.scandir(path) as scan:
                for e in scan:
                    try:
                        if self.registry.is_link(e.stat(follow_symlinks=False)):
                            continue
                        directory = e.is_dir(follow_symlinks=False)
                        if not directory and (not e.is_file(follow_symlinks=False) or Path(e.name).suffix.lower() not in VIDEO):
                            continue
                        relative = f"{rel}/{e.name}" if rel else e.name
                        yield {"id": self.token(drive, relative), "name": e.name, "directory": directory,
                               "size": None if directory else e.stat(follow_symlinks=False).st_size}
                    except OSError:
                        continue
        items = heapq.nsmallest(offset + limit + 1, entries(), key=lambda e: (not e["directory"], natural_name_key(e["name"]), e["name"]))
        return {"id": token, "name": path.name or str(path),
                "parent_id": self.token(drive, rel.rpartition("/")[0]) if rel else None,
                "entries": items[offset:offset + limit],
                "next_offset": offset + limit if len(items) > offset + limit else None}


def byte_range(value: str | None, size: int) -> tuple[int, int, bool]:
    if value is None:
        return 0, size - 1, False
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    if not match or size == 0 or not any(match.groups()):
        raise ApiError(416, "Range không hợp lệ.")
    start, end = match.groups()
    try:
        if start:
            first, last = int(start), int(end) if end else size - 1
            if first >= size or last < first:
                raise ValueError()
            return first, min(last, size - 1), True
        suffix = int(end)
        if suffix <= 0:
            raise ValueError()
        return max(0, size - suffix), size - 1, True
    except ValueError:
        raise ApiError(416, "Range không thỏa mãn.") from None


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, address, files: Files, progress: ProgressStore | None = None, *, access=None, mode="ADB_REVERSE_LEGACY", server_name="AquaStream"):
        if mode == "DIRECT_LAN" and access is None:
            raise ValueError("Direct LAN requires authentication")
        self.access, self.mode, self.server_name = access, mode, server_name
        self.stream_lock = threading.Lock()
        self.active_streams = 0
        self.files = files
        self.progress = progress
        self.slots = threading.BoundedSemaphore(12)
        super().__init__(address, Handler)
        # HTTPServer.server_bind replaces server_name with the DNS hostname.
        # Restore the configured public name after binding the listener.
        self.server_name = server_name

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            request.settimeout(20)
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    server: BridgeServer
    # HTTP/1.0 closes promptly when the player abandons a seek request.
    server_version = "AquaStream/1.0"

    def log_message(self, fmt, *args):
        # Do not log full path tokens.
        pass

    def json(self, status: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        try:
            if self.headers.get("Host", "") not in {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}", f"{self.server.server_address[0]}:{self.server.server_port}"}:
                raise ApiError(403, "Host bị từ chối.")
            if self.headers.get("Origin"):
                raise ApiError(403, "Không nhận truy cập từ trang web.")
            request = urlsplit(self.path)
            query = parse_qs(request.query)
            if request.path == "/identity":
                self.json(200, {"server_id": self.server.access.server_id if self.server.access else None,
                    "name": self.server.server_name, "protocol_version": 1, "mode": self.server.mode})
                return
            if request.path != "/health" and self.server.mode == "DIRECT_LAN" and not self.server.access.authorize(self.headers.get("Authorization")):
                self.json(401, {"error": "Pair this TV on the PC, or check both transport modes"})
                return
            if request.path == "/health":
                self.json(200, {"status": "ok", "version": "1.1", "progress": self.server.progress is not None, "adb": getattr(self.server, "adb_status", "disabled")})
            elif request.path == "/drives":
                self.json(200, self.server.files.drives())
            elif request.path == "/session":
                self.json(200, {"server_id": self.server.access.server_id if self.server.access else None})
            elif request.path == "/browse":
                try:
                    offset = int(query.get("offset", ["0"])[0])
                    limit = int(query.get("limit", ["200"])[0])
                except ValueError:
                    raise ApiError(400, "Trang thư mục không hợp lệ.") from None
                self.json(200, self.server.files.browse(query.get("id", [""])[0], offset, limit))
            elif request.path.startswith("/progress/"):
                self.progress_request(request.path[len('/progress/'):])
            elif request.path.startswith("/stream/"):
                self.stream(request.path[len("/stream/"):])
            else:
                raise ApiError(404, "API không tồn tại.")
        except ApiError as e:
            self.json(e.status, {"error": e.message})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except sqlite3.Error:
            self.json(503, {"error": "Progress database temporarily unavailable"})
        except PermissionError:
            self.json(403, {"error": "Windows từ chối đọc thư mục hoặc file."})
        except OSError:
            self.json(410, {"error": "Không đọc được ổ đĩa. Có thể ổ đã bị rút."})

    def do_PUT(self):
        if not urlsplit(self.path).path.startswith('/progress/'):
            self.json(405, {'error': 'Only progress can be updated'}); return
        self.do_GET()

    def do_POST(self):
        try:
            if self.server.mode != "DIRECT_LAN" or urlsplit(self.path).path != "/pair" or self.headers.get("Origin"):
                self.json(403, {"error": "Pairing is available only in Direct LAN"}); return
            if self.headers.get("Host", "") not in {f"{self.server.server_address[0]}:{self.server.server_port}", f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}:
                self.json(403, {"error": "Invalid host"}); return
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 2048: raise ValueError()
            self.connection.settimeout(4)
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, dict): raise ValueError()
            self.json(200, self.server.access.pair(payload, self.client_address[0]))
        except (ValueError, TypeError, UnicodeError):
            self.json(400, {"error": "Invalid pairing request"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except Exception as error:
            if hasattr(error, "status") and hasattr(error, "message"): self.json(error.status, {"error": error.message})
            else: self.json(503, {"error": "Pairing unavailable"})

    def do_DELETE(self):
        self.do_PUT()

    def progress_request(self, token: str):
        store = self.server.progress
        if store is None:
            raise ApiError(503, 'Progress storage is not enabled')
        identity, parent, name = self.server.files.progress_identity(token)
        if self.command == 'PUT':
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 4096: raise ValueError()
                payload = json.loads(self.rfile.read(length))
                if not isinstance(payload, dict): raise ValueError()
                progress = store.put(identity, payload)
            except (ValueError, TypeError, UnicodeError):
                raise ApiError(400, 'Invalid progress body') from None
        elif self.command == 'DELETE':
            store.delete(identity); progress = None
        else:
            progress = store.get(identity)
        self.json(200, {'progress': progress, 'parent_id': parent, 'name': name})

    def stream(self, token: str):
        file, path = self.server.files.opened(token)
        with file:
            size = os.fstat(file.fileno()).st_size
            try:
                start, end, partial = byte_range(self.headers.get("Range"), size)
            except ApiError:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.send_header("Accept-Ranges", "bytes")
                self.end_headers()
                return
            length = max(0, end - start + 1)
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if self.command == "HEAD":
                return
            file.seek(start)
            # Once headers were sent an unplug must close the connection, never append JSON.
            try:
                checked = 0.0
                with self.server.stream_lock: self.server.active_streams += 1
                while length:
                    if self.server.mode == "DIRECT_LAN" and time.monotonic()-checked >= 1:
                        if not self.server.access.authorize(self.headers.get("Authorization")): break
                        checked = time.monotonic()
                    block = file.read(min(CHUNK, length))
                    if not block:
                        break
                    self.wfile.write(block)
                    length -= len(block)
            except OSError:
                self.close_connection = True
            finally:
                with self.server.stream_lock: self.server.active_streams -= 1


def main():
    try:
        from .bridge_runtime import main as runtime_main
    except ImportError:
        from bridge_runtime import main as runtime_main
    try:
        return runtime_main()
    except Exception:
        LOG.exception("Application startup failed")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
