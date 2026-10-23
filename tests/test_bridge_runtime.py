"""Real Windows HTTP/mutex/Named Pipe/Tk integration; no device commands."""
import argparse
import http.client
import json
from multiprocessing.connection import Client
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from windows.bridge_config import ConfigStore, DEFAULTS, atomic_json
from windows.bridge_runtime import ipc_request, pipe_name, run_backend


@unittest.skipUnless(os.name == "nt", "Windows integration")
class RuntimeTest(unittest.TestCase):
    def test_control_single_instance_port_rollback_and_gui_lifetime(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with socket.socket() as available:
                available.bind(("127.0.0.1", 0))
                port = available.getsockname()[1]
            store = ConfigStore(directory)
            config = dict(DEFAULTS, bridge_port=port, watchdog_enabled=False, transport_mode="ADB_REVERSE_LEGACY")
            store.save(config)
            atomic_json(directory / "storage.json", {"progress_database": str(directory / "fixture.sqlite3")})
            media = directory / "media"
            media.mkdir()
            (media / "EP01.mp4").write_bytes(b"AquaStream test")
            args = argparse.Namespace(adb=None, no_adb=False, allow_root=[str(media)], log_dir=directory / "logs")
            errors = []
            def backend():
                try:
                    run_backend(store, args)
                except Exception as error:
                    errors.append(error)
            with patch("windows.bridge_runtime.StartupTask.enabled", return_value=False):
                thread = threading.Thread(target=backend, daemon=True)
                thread.start()
                try:
                    deadline = time.monotonic()+5
                    while True:
                        try:
                            first = ipc_request(directory, "status")
                            break
                        except (OSError, FileNotFoundError):
                            if time.monotonic() >= deadline:
                                raise
                            threading.Event().wait(.03)
                    self.assertEqual(first["file_bridge"], "Running")
                    self.assertFalse(first["connection"]["enabled"])
                    self.assertEqual(run_backend(store, args), 0)
                    self.assertEqual(ipc_request(directory, "status")["pid"], first["pid"])
                    with Client(pipe_name(directory), family="AF_PIPE") as connection:
                        connection.send_bytes(b'{"token":"wrong", "action":"shutdown"}')
                        self.assertTrue(connection.poll(2))
                        self.assertFalse(json.loads(connection.recv_bytes())["ok"])
                    with socket.socket() as occupied:
                        occupied.bind(("127.0.0.1", 0))
                        occupied.listen()
                        rejected = dict(config, bridge_port=occupied.getsockname()[1])
                        with self.assertRaises(RuntimeError):
                            ipc_request(directory, "configure", config=rejected)
                    self.assertEqual(store.load(), config)
                    changed = dict(config, target_ip="192.0.2.17", offline_check_interval_seconds=123)
                    current = ipc_request(directory, "configure", config=changed)
                    self.assertEqual(current["config"], changed)
                    self.assertEqual(current["pid"], first["pid"])
                    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
                    connection.request("GET", "/drives")
                    response = connection.getresponse()
                    self.assertEqual(response.status, 200)
                    self.assertEqual(len(json.loads(response.read())["drives"]), 1)
                    connection.close()
                    import tkinter as tk
                    from windows.config_ui import run_gui
                    original = tk.Tk.mainloop
                    def bounded_mainloop(root, *a, **kw):
                        root.after(900, lambda: root.tk.call(root.protocol("WM_DELETE_WINDOW")))
                        return original(root, *a, **kw)
                    with patch.object(tk.Tk, "mainloop", bounded_mainloop):
                        self.assertEqual(run_gui(directory, args), 0)
                    self.assertEqual(ipc_request(directory, "status")["pid"], first["pid"])
                finally:
                    try:
                        ipc_request(directory, "shutdown")
                    finally:
                        thread.join(5)
                self.assertFalse(thread.is_alive())
                self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
