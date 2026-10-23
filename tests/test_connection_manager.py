import copy
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from windows.bridge_config import ConfigStore, DEFAULTS, StartupTask, validate
from windows.connection_manager import ConnectionManager


def result(out="", code=0, err=""):
    return subprocess.CompletedProcess([], code, out, err)


class WatchdogTest(unittest.TestCase):
    def manager(self, responses=(), probe=None):
        runner = Mock(side_effect=list(responses))
        manager = ConnectionManager(DEFAULTS, probe=probe or Mock(return_value=1.25), runner=runner)
        manager.adb_path = lambda config: "adb.exe"
        return manager, runner

    def check(self, manager, manual=None):
        manager.cycle(copy.copy(manager.config), manager.generation, manual)

    def test_unreachable_never_calls_adb(self):
        m, run = self.manager(probe=Mock(side_effect=TimeoutError()))
        self.check(m)
        self.assertEqual(m.snapshot()["state"], "UNREACHABLE")
        self.assertEqual(m.interval(DEFAULTS), 300)
        run.assert_not_called()

    def test_healthy_does_nothing_even_manual_recovery(self):
        m, run = self.manager([result("device\n"), result("UsbFfs tcp:8765 tcp:8765\n")]*2)
        self.check(m)
        self.check(m, "recovery")
        self.assertEqual(m.snapshot()["state"], "CONNECTED")
        self.assertEqual([x.args[0][3:] for x in run.call_args_list],
                         [["get-state"], ["reverse", "--list"]]*2)
        for call in run.call_args_list:
            self.assertEqual(call.args[0][1:3], ["-s", "192.168.1.32:5555"])
            self.assertEqual(call.kwargs["timeout"], 5)
            self.assertTrue(call.kwargs["capture_output"])

    def test_missing_or_wrong_mapping_restored_without_connect(self):
        m, run = self.manager([result("device"), result("UsbFfs tcp:8765 tcp:9999\nUsbFfs tcp:9998 tcp:9998"),
                               result(), result("UsbFfs tcp:8765 tcp:8765\nUsbFfs tcp:9998 tcp:9998")])
        self.check(m)
        self.assertEqual(m.status["state"], "CONNECTED")
        self.assertEqual([x.args[0][3:] for x in run.call_args_list],
                         [["get-state"], ["reverse", "--list"], ["reverse", "tcp:8765", "tcp:8765"], ["reverse", "--list"]])

    def test_reconnect_specific_target_then_verify_mapping(self):
        m, run = self.manager([result(code=1), result(code=1), result("connected"), result("device"),
                               result(), result(), result("UsbFfs tcp:8765 tcp:8765")])
        self.check(m)
        self.assertEqual(m.status["state"], "CONNECTED")
        self.assertEqual(run.call_args_list[2].args[0], ["adb.exe", "connect", "192.168.1.32:5555"])

    def test_timeout_backoff_bounded(self):
        m, run = self.manager()
        run.side_effect = subprocess.TimeoutExpired("adb", 5)
        for _ in range(4):
            self.check(m)
        self.assertEqual(m.failures, 4)
        self.assertEqual(m.interval(DEFAULTS), 300)
        self.assertEqual(run.call_count, 4)

    def test_connect_success_without_transport_is_not_connected(self):
        m, _ = self.manager([result(code=1), result(code=1), result("connected"), result("offline", 1)])
        self.check(m)
        self.assertEqual(m.status["state"], "RECOVERING")

    def test_missing_adb_not_http_failure(self):
        m, run = self.manager()
        m.adb_path = lambda config: None
        self.check(m)
        self.assertEqual(m.status["state"], "ERROR")
        run.assert_not_called()

    def test_generation_prevents_old_recovery_commands(self):
        m, run = self.manager()
        generation = m.generation
        m.update(dict(DEFAULTS, target_ip="192.168.1.33"))
        m.cycle(dict(DEFAULTS), generation)
        run.assert_not_called()
        self.assertEqual(m.status["state"], "AVAILABLE")

    def test_failed_probe_preserves_healthy_transport(self):
        m, run = self.manager([result("device"), result("device"), result("UsbFfs tcp:8765 tcp:8765")],
                               probe=Mock(side_effect=TimeoutError()))
        m.status["state"] = "CONNECTED"
        self.check(m)
        self.assertEqual(m.status["state"], "CONNECTED")
        self.assertFalse(any("connect" in call.args[0] for call in run.call_args_list))

    def test_disabled_worker_manual_request_and_shutdown(self):
        m, run = self.manager([result("device"), result("UsbFfs tcp:8765 tcp:8765")])
        m.update(dict(DEFAULTS, watchdog_enabled=False))
        m.start()
        run.assert_not_called()
        m.request()
        deadline = time.monotonic()+2
        with m.condition:
            while m.status["state"] != "CONNECTED" and time.monotonic() < deadline:
                m.condition.wait(.02)
        m.stop()
        m.join(2)
        self.assertFalse(m.is_alive())
        self.assertEqual(run.call_count, 2)


class ConfigurationTest(unittest.TestCase):
    def test_missing_saved_adb_path_does_not_block_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            config = dict(DEFAULTS, adb_path=str(Path(directory) / "removed" / "adb.exe"))
            store = ConfigStore(directory)
            store.save(config)
            self.assertEqual(store.load(), config)

    def test_validation_atomic_persistence(self):
        with tempfile.TemporaryDirectory() as directory:
            store = ConfigStore(directory)
            store.save(DEFAULTS)
            for change in [dict(target_ip="host; reboot"), dict(adb_port=0), dict(bridge_port=65536),
                           dict(connected_check_interval_seconds=0), dict(tcp_probe_timeout_seconds=float("nan")),
                           dict(watchdog_enabled="yes")]:
                with self.assertRaises(ValueError):
                    store.save(dict(DEFAULTS, **change))
                self.assertEqual(store.load(), DEFAULTS)
            self.assertEqual(list(Path(directory).glob("*.tmp")), [])

    def test_task_is_per_user_least_privilege_without_password(self):
        task = StartupTask()
        captured = []
        def execute(*args):
            if "/XML" in args and "/Create" in args:
                xml = Path(args[args.index("/XML")+1]).read_text(encoding="utf-16")
                captured.append(xml)
            return result()
        with patch.object(task, "run", side_effect=execute):
            task.set_enabled(True)
        xml = captured[0]
        self.assertIn("InteractiveToken", xml)
        self.assertIn("LeastPrivilege", xml)
        self.assertIn("LogonTrigger", xml)
        self.assertIn("--background", xml)
        self.assertNotIn("SYSTEM", xml)


if __name__ == "__main__":
    unittest.main()
