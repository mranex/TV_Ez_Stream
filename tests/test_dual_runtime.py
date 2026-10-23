import argparse
import http.client
import json
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch
from windows.bridge_config import ConfigStore,DEFAULTS
from windows.bridge_runtime import Backend


class DualRuntimeTests(unittest.TestCase):
    def test_mode_transitions_preserve_progress_identity_and_single_listener(self):
        with tempfile.TemporaryDirectory()as temp:
            directory=Path(temp);media=directory/'media';media.mkdir();(media/'EP01.mp4').write_bytes(b'video')
            with socket.socket()as free:free.bind(('127.0.0.1',0));port=free.getsockname()[1]
            store=ConfigStore(directory)
            config=dict(DEFAULTS,transport_mode='ADB_REVERSE_LEGACY',watchdog_enabled=False,discovery_enabled=False,bridge_port=port)
            store.save(config)
            args=argparse.Namespace(adb=None,no_adb=False,allow_root=[str(media)],log_dir=directory/'logs')
            with patch('windows.bridge_runtime.StartupTask.enabled',return_value=False),patch('windows.bridge_runtime.interfaces',return_value=[dict(name='test',ip='127.0.0.1')]):
                backend=Backend(store,args);backend.start()
                def request(path,method='GET',data=None,credential=None):
                    c=http.client.HTTPConnection('127.0.0.1',port,timeout=3)
                    c.request(method,path,body=json.dumps(data)if data is not None else None,headers={'Authorization':'Bearer '+credential}if credential else {})
                    r=c.getresponse();body=r.read();c.close();return r.status,json.loads(body)if body else None
                try:
                    original=backend.server;identity=backend.access.server_id;database=backend.progress.path
                    self.assertEqual(request('/drives')[0],200)
                    direct=dict(config,transport_mode='DIRECT_LAN');backend.apply(direct)
                    self.assertIs(backend.server,original);self.assertIsNone(backend.manager)
                    self.assertEqual(request('/drives')[0],401)
                    import uuid
                    pin=backend.control({'action':'pair'})['pin']
                    status,body=request('/pair','POST',dict(pin=pin,device_id=str(uuid.uuid4()),device_name='TV'))
                    self.assertEqual(status,200);credential=body['credential']
                    root=request('/drives',credential=credential)[1]['drives'][0]['id']
                    file=request('/browse?id='+root,credential=credential)[1]['entries'][0]['id']
                    self.assertEqual(request('/progress/'+file,'PUT',dict(position_ms=20000,duration_ms=100000),credential)[0],200)
                    backend.apply(config)
                    self.assertIsNotNone(backend.manager);self.assertEqual(request('/progress/'+file)[1]['progress']['position_ms'],20000)
                    backend.apply(direct)
                    self.assertIsNone(backend.manager);self.assertEqual(backend.access.server_id,identity);self.assertEqual(backend.progress.path,database)
                    self.assertEqual(request('/progress/'+file,credential=credential)[1]['progress']['position_ms'],20000)
                    self.assertEqual(request('/drives')[0],401)
                finally:backend.close()


if __name__=='__main__':unittest.main()
