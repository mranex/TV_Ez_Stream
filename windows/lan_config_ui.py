"""Transport/pairing panel inside the existing Tk controller; no second backend."""
from pathlib import Path
import datetime
import tkinter as tk
from tkinter import ttk, messagebox
try:
    from .bridge_runtime import ipc_request
    from .lan_firewall import request_elevation, query, network_profiles
except ImportError:
    from bridge_runtime import ipc_request
    from lan_firewall import request_elevation, query, network_profiles


def show(parent,directory,submit,store,refresh_parent):
    window=tk.Toplevel(parent);window.title("AquaStream — Kết nối và ghép đôi");window.geometry("780x720")
    panel=ttk.Frame(window,padding=18);panel.pack(fill="both",expand=True)
    config=store.load()
    mode=tk.StringVar(value=config["transport_mode"]);name=tk.StringVar(value=config["server_name"])
    interface=tk.StringVar(value=config["lan_interface"]);discovery=tk.BooleanVar(value=config["discovery_enabled"])
    status=tk.StringVar(value="Đang đọc trạng thái…")
    ttk.Label(panel,text="Chế độ kết nối",font=("Segoe UI",16,"bold")).pack(anchor="w")
    for text,value in [("Direct LAN — Khuyên dùng","DIRECT_LAN"),("ADB Reverse — Legacy","ADB_REVERSE_LEGACY")]:
        ttk.Radiobutton(panel,text=text,variable=mode,value=value).pack(anchor="w",pady=4)
    ttk.Label(panel,text="Tên PC").pack(anchor="w");ttk.Entry(panel,textvariable=name).pack(fill="x")
    ttk.Label(panel,text="Network Interface (trống = tự chọn gateway LAN)").pack(anchor="w",pady=(8,0))
    interfaces=ttk.Combobox(panel,textvariable=interface,values=[""],state="readonly");interfaces.pack(fill="x")
    ttk.Checkbutton(panel,text="Quảng bá mDNS",variable=discovery).pack(anchor="w",pady=8)
    ttk.Label(panel,textvariable=status,wraplength=730).pack(anchor="w",pady=6)
    def update(result):
        if not window.winfo_exists():return
        lan=result.get("lan",{});interfaces["values"]=[""]+[r["name"] for r in lan.get("interfaces",[])]
        status.set(f'{result["config"]["transport_mode"]}  |  HTTP {lan.get("address","-")}:{result["config"]["bridge_port"]}\nServer ID: {lan.get("server_id","-")}\nmDNS: {lan.get("discovery",False)}  |  Đang stream: {lan.get("active_streams",0)}')
        devices.delete(*devices.get_children())
        for row in lan.get("devices",[]):
            devices.insert("", "end",iid=row["device_id"],values=(row["device_name"],row["device_id"],datetime.datetime.fromtimestamp(row["last_connected"]).strftime("%d/%m %H:%M")))
    def read():submit(lambda:ipc_request(directory,"status"),update,True)
    def save():
        candidate=dict(store.load(),transport_mode=mode.get(),server_name=name.get().strip(),lan_interface=interface.get(),discovery_enabled=discovery.get())
        if not messagebox.askyesno("Đổi kết nối","Listener có thể cần khởi động lại và làm gián đoạn video đang xem. Tiếp tục?",parent=window):return
        def work():
            try:return ipc_request(directory,"configure",config=candidate,allow_interrupt=True)
            except OSError:store.save(candidate);return None
        def saved(result):
            config.update(candidate)
            if result:update(result)
            refresh_parent()
        submit(work,saved,True)
    ttk.Button(panel,text="Lưu chế độ / tên / interface",command=save).pack(anchor="w",pady=6)
    ttk.Label(panel,text="Thiết bị đã ghép đôi",font=("Segoe UI",12,"bold")).pack(anchor="w",pady=(10,4))
    devices=ttk.Treeview(panel,columns=("name","id","last"),show="headings",height=4)
    for key,label,width in [("name","Thiết bị",170),("id","Device ID",310),("last","Lần kết nối",130)]:devices.heading(key,text=label);devices.column(key,width=width)
    devices.pack(fill="x")
    buttons=ttk.Frame(panel);buttons.pack(fill="x",pady=6)
    def pair():
        def display(result):
            if not window.winfo_exists():return
            pin=tk.Toplevel(window);pin.title("Ghép đôi thiết bị mới")
            label=ttk.Label(pin,text=result["pin"],font=("Segoe UI",36,"bold"),padding=25);label.pack()
            ttk.Label(pin,text="Nhập PIN trên TV. Hết hạn sau 2 phút.",padding=12).pack()
            pin.after(120000,lambda:label.configure(text="Đã hết hạn") if pin.winfo_exists() else None)
        submit(lambda:ipc_request(directory,"pair"),display,True)
    def revoke():
        selected=devices.selection()
        if selected and messagebox.askyesno("Thu hồi quyền","Thu hồi thiết bị đã chọn?",parent=window):
            submit(lambda:ipc_request(directory,"revoke",device_id=selected[0]),lambda _:read(),True)
    ttk.Button(buttons,text="Ghép đôi thiết bị mới",command=pair).pack(side="left")
    ttk.Button(buttons,text="Thu hồi quyền",command=revoke).pack(side="left",padx=6)
    ttk.Button(buttons,text="Làm mới",command=read).pack(side="left")
    firewall=tk.StringVar(value="Firewall: chưa kiểm tra")
    ttk.Label(panel,textvariable=firewall,wraplength=730).pack(anchor="w",pady=6)
    mdns=tk.BooleanVar(value=False)
    ttk.Checkbutton(panel,text="Thêm UDP 5353 chỉ khi mDNS bị firewall chặn",variable=mdns).pack(anchor="w")
    def rules(enable):
        text="Cho phép TCP AquaStream trên Private Network, LocalSubnet. Windows sẽ hỏi UAC. Không mở Public Network." if enable else "Xóa riêng các rule AquaStream do tài khoản này tạo. Không dừng backend."
        if messagebox.askyesno("Windows Firewall",text,parent=window):
            submit(lambda:request_elevation(config["bridge_port"],enable,mdns.get()),lambda _:firewall.set("Đã gửi yêu cầu UAC. Bấm Kiểm tra để xem kết quả thực tế."),True)
    controls=ttk.Frame(panel);controls.pack(anchor="w",pady=4)
    ttk.Button(controls,text="Cho phép qua Firewall…",command=lambda:rules(True)).pack(side="left")
    ttk.Button(controls,text="Xóa rule AquaStream…",command=lambda:rules(False)).pack(side="left",padx=4)
    ttk.Button(controls,text="Kiểm tra",command=lambda:submit(lambda:query()+"\n"+network_profiles(),firewall.set,True)).pack(side="left")
    ttk.Label(panel,text="HTTP LAN không mã hóa: PIN/token không chống nghe lén mạng. Chỉ dùng LAN đáng tin cậy. Không cần ADB trong Direct LAN.",wraplength=730).pack(anchor="w",pady=8)
    read()
