import http.client
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from windows.bridge import BridgeServer, Files, DriveRegistry
from windows.progress_store import ProgressStore

class ProgressTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.base=Path(self.temp.name)
        self.media=self.base/'usb'; self.media.mkdir(); self.video=self.media/'Tập 09.mp4'; self.video.write_bytes(b'test-video')
        self.db=self.base/'config'/'progress.sqlite3'
        self.files=Files(DriveRegistry([str(self.media)])); self.store=ProgressStore(self.db)
        self.token=self.files.token(next(iter(self.files.registry.current())),self.video.name)
        self.server=BridgeServer(('127.0.0.1',0),self.files,self.store)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True); self.thread.start()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();self.temp.cleanup()
    def request(self,method='GET',body=None,headers=None):
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        data=json.dumps(body).encode() if body is not None else None
        c.request(method,'/progress/'+self.token,body=data,headers=headers or {})
        r=c.getresponse(); code=r.status; payload=json.loads(r.read());c.close();return code,payload
    def test_save_reopen_and_token_restart(self):
        self.assertIsNone(self.request()[1]['progress'])
        self.assertEqual(self.request('PUT',dict(position_ms=120000,duration_ms=360000,completed=False))[0],200)
        self.server.progress=ProgressStore(self.db)
        self.server.files=Files(DriveRegistry([str(self.media)]))
        self.token=self.server.files.token(next(iter(self.server.files.registry.current())),self.video.name)
        self.assertEqual(self.request()[1]['progress']['position_ms'],120000)
        self.assertTrue(self.request()[1]['parent_id'])
    def test_transient_zero_unknown_duration_and_explicit_reset(self):
        self.request('PUT',dict(position_ms=120000,duration_ms=360000))
        self.request('PUT',dict(position_ms=0,duration_ms=0))
        self.assertEqual(self.request()[1]['progress']['position_ms'],120000)
        self.request('PUT',dict(position_ms=130000,duration_ms=0))
        self.assertEqual(self.request()[1]['progress']['duration_ms'],360000)
        self.request('PUT',dict(position_ms=0,duration_ms=0,reset=True))
        self.assertEqual(self.request()[1]['progress']['position_ms'],0)
    def test_modified_or_replaced_file_does_not_resume(self):
        self.request('PUT',dict(position_ms=120000,duration_ms=360000))
        old=self.video.stat(); os.utime(self.video,ns=(old.st_atime_ns,old.st_mtime_ns+1000000000))
        self.assertIsNone(self.request()[1]['progress'])
        identity=self.files.progress_identity(self.token)[0]
        self.store.put(identity,dict(position_ms=90000,duration_ms=300000))
        old=self.video.stat(); replacement=self.media/'temp.mp4';replacement.write_bytes(b'new--video')
        os.utime(replacement,ns=(old.st_atime_ns,old.st_mtime_ns)); os.replace(replacement,self.video)
        self.assertIsNone(self.request()[1]['progress'])
    def test_volume_letter_is_not_identity_and_guid_fallback(self):
        a=ProgressStore.identity('guid:disk-a','serial:one','Anime/Ep01.mp4',123,456,'file-1')
        self.store.put(a,dict(position_ms=120000,duration_ms=300000))
        b=ProgressStore.identity('guid:changed-mount','serial:one','anime/ep01.mp4',123,456,'file-1')
        self.assertEqual(self.store.get(b)['position_ms'],120000)
        other=ProgressStore.identity('guid:other','serial:two','anime/ep01.mp4',123,456,'file-1')
        self.assertIsNone(self.store.get(other))
        self.store.put(b,dict(position_ms=121000,duration_ms=300000))
        self.assertEqual(ProgressStore(self.db).get(b)['position_ms'],121000)
    def test_security_validation_and_completion(self):
        self.assertEqual(self.request('PUT',dict(position_ms=-1,duration_ms=300))[0],400)
        self.assertEqual(self.request('PUT',dict(position_ms=True,duration_ms=300))[0],400)
        self.assertEqual(self.request('PUT',dict(position_ms=20,duration_ms=300),{'Origin':'https://example.com'})[0],403)
        self.assertEqual(self.request('PUT',dict(position_ms=20,duration_ms=300),{'Host':'evil.test'})[0],403)
        self.request('PUT',dict(position_ms=250,duration_ms=300,completed=True))
        self.assertEqual(self.request()[1]['progress']['position_ms'],300)
        self.assertTrue(self.request()[1]['progress']['completed'])
        self.assertEqual(self.request('DELETE')[0],200)
        self.assertTrue(self.video.exists());self.assertIsNone(self.request()[1]['progress'])
    def test_disconnected_volume_is_non_destructive(self):
        self.request('PUT',dict(position_ms=120000,duration_ms=300000))
        self.server.files.registry.roots=[self.base/'absent']
        self.assertEqual(self.request()[0],410)
        self.server.files.registry.roots=[self.media]
        self.assertEqual(self.request()[1]['progress']['position_ms'],120000)

if __name__=='__main__': unittest.main()
