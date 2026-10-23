"""Explicitly consented, per-user-named Private/LocalSubnet rules. Never change other rules."""
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
try:
    from .bridge_config import user_tag
except ImportError:
    from bridge_config import user_tag

PREFIX = "AquaStream-LAN-" + user_tag()


def query():
    script = f"$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.UTF8Encoding]::new(); @(Get-NetFirewallRule -ErrorAction Stop | Where-Object Name -Like '{PREFIX}-*' | Select-Object Name,Enabled,Profile) | ConvertTo-Json -Compress"
    p = subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command",script],capture_output=True,timeout=10,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    if p.returncode: return "Cannot query Windows Firewall"
    data=json.loads(p.stdout.decode("utf-8-sig")) if p.stdout.strip() else []
    if isinstance(data,dict):data=[data]
    return "; ".join(f'{r["Name"]}: {r["Enabled"]} / {r["Profile"]}' for r in data) or "No AquaStream rule; existing Windows policy may already allow access"


def configure(port, enable, mdns=False):
    port=int(port)
    if not 1<=port<=65535:raise ValueError("Invalid port")
    # Paths are escaped as PS single-quoted literals; no external command/text interpolation.
    program=sys.executable.replace("'","''")
    name=PREFIX+"-TCP"
    script=f"$ErrorActionPreference='Stop'; Get-NetFirewallRule -Name '{name}' -ErrorAction SilentlyContinue | Remove-NetFirewallRule; "
    if enable:
        script+=f"New-NetFirewallRule -Name '{name}' -DisplayName 'AquaStream Direct LAN' -Direction Inbound -Action Allow -Protocol TCP -LocalPort {port} -Profile Private -RemoteAddress LocalSubnet -Program '{program}' | Out-Null; "
    if mdns or not enable:
        script+=f"Get-NetFirewallRule -Name '{PREFIX}-mDNS' -ErrorAction SilentlyContinue | Remove-NetFirewallRule; "
        if enable and mdns:
            script+=f"New-NetFirewallRule -Name '{PREFIX}-mDNS' -DisplayName 'AquaStream mDNS' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5353 -Profile Private -RemoteAddress LocalSubnet -Program '{program}' | Out-Null; "
    p=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command",script],capture_output=True,timeout=20,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    if p.returncode:raise RuntimeError("Windows Firewall operation failed")


def request_elevation(port, enable, mdns=False):
    if getattr(sys,"frozen",False): executable=sys.executable;args=[]
    else: executable=sys.executable;args=[str(Path(__file__).with_name("bridge.py"))]
    args += ["--firewall", "enable" if enable else "disable", "--firewall-port", str(int(port))]
    if mdns:args += ["--firewall-mdns"]
    # This function is invoked ONLY after ConfigUI's explicit confirmation dialog.
    result=ctypes.windll.shell32.ShellExecuteW(None,"runas",executable,subprocess.list2cmdline(args),None,0)
    if result<=32:raise RuntimeError("UAC cancelled or elevation failed")


def network_profiles():
    p=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-Command","$ErrorActionPreference='Stop'; Get-NetConnectionProfile -ErrorAction Stop | Select-Object InterfaceAlias,NetworkCategory | ConvertTo-Json -Compress"],capture_output=True,timeout=10,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    return p.stdout.decode("utf-8-sig").strip() if p.returncode==0 else "Unknown network profile"
