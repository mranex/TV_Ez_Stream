import http.client
import json
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from windows.lan_transport import AccessStore, PairingError, select_address
from windows.bridge import BridgeServer, DriveRegistry, Files
from windows.bridge_config import ConfigStore, DEFAULTS, atomic_json


class PairingTests(unittest.TestCase):
    def test_expiry_attempt_limits_revocation_and_persistence(self):
        with tempfile.TemporaryDirectory() as temp:
            now=[100.0];store=AccessStore(temp,lambda:now[0]);identity=store.server_id
            client=str(uuid.uuid4())
            body=dict(pin="000000",device_id=client,device_name="Mi Stick")
            with self.assertRaises(PairingError):store.pair(body,"one")
            session=store.open_pairing();now[0]+=121;body["pin"]=session["pin"]
            with self.assertRaises(PairingError):store.pair(body,"one")
            session=store.open_pairing()
            for _ in range(5):
                with self.assertRaises(PairingError):store.pair(dict(body,pin="not-a-pin"),"bad")
            with self.assertRaises(PairingError):store.pair(dict(body,pin=session["pin"]),"good")
            session=store.open_pairing();token=store.pair(dict(body,pin=session["pin"]),"good")["credential"]
            self.assertTrue(store.authorize("Bearer "+token));self.assertFalse(store.authorize("Bearer wrong"))
            self.assertNotIn(token,(Path(temp)/"devices.sqlite3").read_bytes().decode("latin1"))
            store.close();store=AccessStore(temp);self.assertEqual(store.server_id,identity)
            self.assertTrue(store.authorize("Bearer "+token));store.revoke(client)
            self.assertFalse(store.authorize("Bearer "+token));store.close()

    def test_configuration_migration_and_interface_selection(self):
        with tempfile.TemporaryDirectory()as temp:
            store=ConfigStore(temp);self.assertEqual(store.load()["transport_mode"],"DIRECT_LAN")
            atomic_json(store.path,{"target_ip":"192.168.1.32"})
            self.assertEqual(store.load()["transport_mode"],"ADB_REVERSE_LEGACY")
        self.assertEqual(select_address(dict(DEFAULTS,transport_mode="ADB_REVERSE_LEGACY"),[]),"127.0.0.1")
        self.assertEqual(select_address(DEFAULTS,[dict(name="Wi-Fi",ip="192.168.1.10")]),"192.168.1.10")

    def test_authenticated_range_head_status_and_token_revoke(self):
        with tempfile.TemporaryDirectory()as temp:
            root=Path(temp);media=root/"EP01.mp4";media.write_bytes(bytes(range(256))*128)
            access=AccessStore(root/"auth");files=Files(DriveRegistry([str(root)]))
            drive=next(iter(files.registry.current()));token=files.token(drive,"EP01.mp4")
            server=BridgeServer(("127.0.0.1",0),files,access=access,mode="DIRECT_LAN",server_name="Living Room PC")
            worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
            def request(path,method="GET",headers=None,body=None):
                c=http.client.HTTPConnection(*server.server_address,timeout=3)
                c.request(method,path,body=body,headers=headers or {});r=c.getresponse();result=(r.status,dict(r.getheaders()),r.read());c.close();return result
            try:
                self.assertEqual(request("/identity")[0],200)
                self.assertEqual(json.loads(request("/identity")[2])["name"],"Living Room PC")
                for path in ("/drives","/browse?id=invalid",f"/stream/{token}",f"/progress/{token}","/session"):
                    self.assertEqual(request(path)[0],401)
                pin=access.open_pairing()["pin"];device=str(uuid.uuid4())
                code,_,data=request("/pair","POST",body=json.dumps(dict(pin=pin,device_id=device,device_name="TV")))
                self.assertEqual(code,200);credential=json.loads(data)["credential"]
                headers={"Authorization":"Bearer "+credential,"Range":"bytes=9-17"}
                code,h,data=request(f"/stream/{token}",headers=headers)
                self.assertEqual((code,data),(206,media.read_bytes()[9:18]));self.assertEqual(h["Content-Range"],"bytes 9-17/32768")
                self.assertEqual(request(f"/stream/{token}","HEAD",headers=headers)[2],b"")
                self.assertEqual(request(f"/stream/{token}",headers=dict(headers,Range="bytes=999999-"))[0],416)
                self.assertEqual(request("/drives",headers=headers)[0],200)
                access.revoke(device);self.assertEqual(request(f"/stream/{token}",headers=headers)[0],401)
            finally:server.shutdown();server.server_close();worker.join();access.close()


if __name__=="__main__":unittest.main()
