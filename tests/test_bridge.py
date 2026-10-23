import concurrent.futures
import http.client
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from urllib.parse import urlencode

from windows.bridge import ApiError, BridgeServer, DriveRegistry, Files, byte_range


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "日本語 tiếng Việt").mkdir()
        self.video = self.root / "日本語 tiếng Việt" / "phim 日本.mp4"
        self.payload = bytes(range(256)) * 1000
        self.video.write_bytes(self.payload)
        (self.root / "hidden.txt").write_text("private")
        self.files = Files(DriveRegistry([str(self.root)]))
        drive = next(iter(self.files.registry.current()))
        self.root_id = self.files.token(drive)
        self.file_id = self.files.token(drive, "日本語 tiếng Việt/phim 日本.mp4")
        self.server = BridgeServer(("127.0.0.1", 0), self.files)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, path, method="GET", headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        conn.request(method, path, headers=headers or {})
        response = conn.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        conn.close()
        return result

    def test_range_and_head(self):
        path = f"/stream/{self.file_id}"
        status, headers, data = self.request(path)
        self.assertEqual((status, data), (200, self.payload))
        self.assertEqual(headers["Content-Length"], str(len(data)))
        for value, expected in [("bytes=0-1", self.payload[:2]), ("bytes=100000-100009", self.payload[100000:100010]),
                                ("bytes=-7", self.payload[-7:]), ("bytes=255990-", self.payload[-10:]),
                                ("bytes=255999-9999999", self.payload[-1:])]:
            status, headers, data = self.request(path, headers={"Range": value})
            self.assertEqual((status, data), (206, expected))
            self.assertEqual(int(headers["Content-Length"]), len(expected))
            self.assertIn("Content-Range", headers)
        for value in ["bytes=999999-", "bytes=2-1", "bytes=-0", "bytes=-", "bytes=0-1,3-4", "bananas", "bytes=a-b"]:
            status, headers, data = self.request(path, headers={"Range": value})
            self.assertEqual(status, 416)
            self.assertEqual(headers["Content-Range"], f"bytes */{len(self.payload)}")
            self.assertEqual(data, b"")
        status, headers, data = self.request(path, "HEAD", {"Range": "bytes=5-9"})
        self.assertEqual((status, headers["Content-Length"], data), (206, "5", b""))
        self.assertEqual(self.request(path, "HEAD")[0], 200)

    def test_large_sparse_file(self):
        size = 5 * 1024**3 + 17
        path = self.root / "large.mp4"
        with path.open("wb") as file:
            if os.name == "nt":
                import ctypes
                import msvcrt
                returned = ctypes.c_ulong()
                control = ctypes.WinDLL("kernel32").DeviceIoControl
                control.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong,
                                    ctypes.c_void_p, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_void_p]
                self.assertTrue(control(msvcrt.get_osfhandle(file.fileno()), 0x900C4, None, 0, None, 0, ctypes.byref(returned), None))
            file.seek(size - 4)
            file.write(b"END!")
        drive = next(iter(self.files.registry.current()))
        endpoint = "/stream/" + self.files.token(drive, path.name)
        status, headers, data = self.request(endpoint, headers={"Range": f"bytes={size-4}-"})
        self.assertEqual((status, data), (206, b"END!"))
        self.assertEqual(headers["Content-Range"], f"bytes {size-4}-{size-1}/{size}")
        status, headers, _ = self.request(endpoint, "HEAD")
        self.assertEqual(headers["Content-Length"], str(size))
        self.assertEqual(status, 200)

    def test_unicode_pagination_and_readonly(self):
        status, _, body = self.request("/browse?" + urlencode({"id": self.root_id, "limit": 1}))
        result = json.loads(body)
        self.assertEqual(status, 200)
        self.assertEqual(result["entries"][0]["name"], "日本語 tiếng Việt")
        self.assertTrue(result["entries"][0]["directory"])
        self.assertIsNone(result["parent_id"])
        self.assertEqual(self.request("/drives", "POST")[0], 403)
        self.assertEqual(self.request("/browse?id=../../Windows")[0], 403)
        drive = next(iter(self.files.registry.current()))
        for relative in ["../secret.mp4", "/etc/passwd", "C:/Windows", "a\\b", "x:stream", "a./b"]:
            with self.assertRaises(ApiError):
                self.files.resolve(self.files.token(drive, relative))
        self.assertEqual(self.request("/stream/" + self.files.token(drive, "hidden.txt"))[0], 403)
        self.assertEqual(self.request("/drives", headers={"Host": "evil.example"})[0], 403)
        self.assertEqual(self.request("/drives", headers={"Origin": "https://evil.example"})[0], 403)

    def test_natural_browse_order_across_pages(self):
        folder = self.root / "natural"
        folder.mkdir()
        for name in ["Folder 10", "Folder 2", "Folder 1"]:
            (folder / name).mkdir()
        for name in ["Tập 11.mkv", "Tập 2.mkv", "Tập 10.mkv", "Tập 1.mkv",
                     "S01E10.mp4", "S01E02.mp4", "S02E01.mp4", "Tập 02.mkv"]:
            (folder / name).touch()
        drive = next(iter(self.files.registry.current()))
        token = self.files.token(drive, "natural")
        names = []
        offset = 0
        while True:
            status, _, body = self.request("/browse?" + urlencode({"id": token, "offset": offset, "limit": 2}))
            self.assertEqual(status, 200)
            page = json.loads(body)
            names.extend(entry["name"] for entry in page["entries"])
            offset = page["next_offset"]
            if offset is None:
                break
        self.assertEqual(names, ["Folder 1", "Folder 2", "Folder 10", "S01E02.mp4", "S01E10.mp4",
                                 "S02E01.mp4", "Tập 1.mkv", "Tập 02.mkv", "Tập 2.mkv", "Tập 10.mkv", "Tập 11.mkv"])

    def test_drive_removal_and_reattach(self):
        original = self.files.registry.current
        self.files.registry.current = lambda: {}
        self.assertEqual(self.request("/stream/" + self.file_id)[0], 410)
        self.assertEqual(json.loads(self.request("/drives")[2])["drives"], [])
        self.files.registry.current = original
        self.assertEqual(self.request("/stream/" + self.file_id, "HEAD")[0], 200)

    def test_parallel_seeks(self):
        def fetch(n):
            start = n * 1000
            status, _, data = self.request("/stream/" + self.file_id, headers={"Range": f"bytes={start}-{start+99}"})
            return status == 206 and data == self.payload[start:start+100]
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            self.assertTrue(all(pool.map(fetch, range(30))))

    def test_junction_blocked(self):
        with tempfile.TemporaryDirectory() as outside:
            (Path(outside) / "secret.mp4").write_bytes(b"secret")
            link = self.root / "escape"
            if os.name == "nt":
                # Native cmd used only for mklink; no recursive move/delete through another shell.
                result = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), outside], capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
            else:
                link.symlink_to(outside, target_is_directory=True)
            try:
                drive = next(iter(self.files.registry.current()))
                self.assertEqual(self.request("/stream/" + self.files.token(drive, "escape/secret.mp4"))[0], 403)
                listing = self.files.browse(self.root_id)
                self.assertNotIn("escape", [e["name"] for e in listing["entries"]])
            finally:
                if os.name == "nt":
                    os.rmdir(link)
                else:
                    link.unlink()

    def test_empty_video(self):
        (self.root / "empty.mp4").touch()
        drive = next(iter(self.files.registry.current()))
        endpoint = "/stream/" + self.files.token(drive, "empty.mp4")
        self.assertEqual(self.request(endpoint), (200, self.request(endpoint)[1], b""))
        self.assertEqual(self.request(endpoint, headers={"Range": "bytes=0-"})[0], 416)


if __name__ == "__main__":
    unittest.main()
