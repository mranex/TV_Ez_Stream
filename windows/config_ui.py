"""Small Tk controller. All IPC, scheduling and subprocess work stays off Tk's thread."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import ctypes
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import sys
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

try:
    from .bridge_config import ConfigStore, DEFAULTS, StartupTask, executable_command, validate
    from .bridge_runtime import InstanceLock, ipc_request, pipe_name
except ImportError:
    from bridge_config import ConfigStore, DEFAULTS, StartupTask, executable_command, validate
    from bridge_runtime import InstanceLock, ipc_request, pipe_name

FIELDS = [("target_ip", "Địa chỉ IP TV"), ("adb_port", "Cổng ADB"), ("bridge_port", "Cổng HTTP Bridge"),
          ("connected_check_interval_seconds", "Kiểm tra khi kết nối (giây)"),
          ("offline_check_interval_seconds", "Kiểm tra khi không truy cập được (giây)"),
          ("recovery_retry_interval_seconds", "Thử khôi phục nhanh (giây)"),
          ("tcp_probe_timeout_seconds", "Timeout TCP (giây)"), ("adb_command_timeout_seconds", "Timeout ADB (giây)"),
          ("adb_path", "Đường dẫn adb.exe (để trống = tự tìm)")]


def run_gui(directory, args):
    lock = InstanceLock("AquaStreamGUI-" + pipe_name(directory).rsplit("\\", 1)[-1])
    if not lock.acquired:
        user = ctypes.windll.user32
        user.FindWindowW.restype = ctypes.c_void_p
        user.FindWindowW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p]
        user.ShowWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
        user.SetForegroundWindow.argtypes = [ctypes.c_void_p]
        window = user.FindWindowW(None, "AquaStream Bridge")
        if window:
            user.ShowWindow(window, 9)
            user.SetForegroundWindow(window)
        lock.close()
        return 0
    root = tk.Tk()
    root.title("AquaStream Bridge")
    root.geometry("750x815")
    root.minsize(710, 770)
    root.configure(bg="#202123")
    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure("TFrame", background="#202123")
    style.configure("TLabel", background="#202123", foreground="#ececec", font=("Segoe UI", 10))
    style.configure("TCheckbutton", background="#202123", foreground="#ececec", font=("Segoe UI", 10))
    style.map("TCheckbutton", background=[("active", "#343638")])
    style.configure("TButton", font=("Segoe UI", 10), padding=7)
    panel = ttk.Frame(root, padding=22)
    panel.pack(fill="both", expand=True)
    ttk.Label(panel, text="AquaStream Bridge", font=("Segoe UI", 20, "bold")).grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, 12))
    panel.columnconfigure(1, weight=1)
    status = tk.StringVar(value="Đang đọc trạng thái backend…")
    ttk.Label(panel, textvariable=status, justify="left", wraplength=690).grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 14))
    values = {key: tk.StringVar() for key, _ in FIELDS}
    field_widgets = {}
    startup, watchdog = tk.BooleanVar(), tk.BooleanVar()
    row = 2
    for key, label in FIELDS:
        ttk.Label(panel, text=label).grid(row=row, column=0, sticky="w", padx=(0, 12), pady=5)
        entry = ttk.Entry(panel, textvariable=values[key], width=24)
        field_widgets[key] = entry
        entry.grid(row=row, column=1, columnspan=2 if key != "adb_path" else 1, sticky="ew", pady=5)
        if key == "adb_path":
            def choose_adb():
                path = filedialog.askopenfilename(parent=root, title="Chọn adb.exe", filetypes=[("ADB", "adb.exe"), ("Executable", "*.exe")])
                if path:
                    values["adb_path"].set(path)
            ttk.Button(panel, text="Chọn…", command=choose_adb).grid(row=row, column=2, padx=(5, 0))
        row += 1
    ttk.Checkbutton(panel, text="Khởi động AquaStream Bridge cùng Windows", variable=startup).grid(row=row, column=0, columnspan=3, sticky="w", pady=(12, 4))
    ttk.Checkbutton(panel, text="Bật Connection Watchdog", variable=watchdog).grid(row=row+1, column=0, columnspan=3, sticky="w", pady=4)
    startup_label = tk.StringVar(value="Task Scheduler: đang kiểm tra…")
    ttk.Label(panel, textvariable=startup_label).grid(row=row+2, column=0, columnspan=3, sticky="w", pady=(2, 10))
    notice = tk.StringVar(value="Đóng cửa sổ này vẫn giữ backend chạy nền.")
    buttons = ttk.Frame(panel)
    buttons.grid(row=row+3, column=0, columnspan=3, sticky="ew", pady=8)
    ttk.Label(panel, textvariable=notice, wraplength=690).grid(row=row+4, column=0, columnspan=3, sticky="w", pady=8)
    ttk.Label(panel, text="TCP latency là thời gian mở kết nối, không phải độ trễ streaming.\nKhông thể kết luận TV đang ngủ từ trạng thái cổng ADB.", wraplength=690).grid(row=row+5, column=0, columnspan=3, sticky="w", pady=8)

    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="gui-control")
    replies = queue.Queue()
    state = dict(busy=False, running=False, initialized=False, closed=False, task_known=False)
    controls = []
    store = ConfigStore(directory)
    def transport_panel():
        try:
            from .lan_config_ui import show
        except ImportError:
            from lan_config_ui import show
        def refresh_parent():
            state["initialized"] = False
            submit(read_status,render)
        show(root,directory,submit,store,refresh_parent)
    ttk.Button(panel,text="Chế độ LAN / Ghép đôi…",command=transport_panel).grid(row=0,column=2,sticky="e")

    def display_config(config, actual_startup=None):
        for key in values:
            values[key].set(str(config[key]))
            field_widgets[key].configure(state="normal" if config["transport_mode"]=="ADB_REVERSE_LEGACY" or key=="bridge_port" else "disabled")
        watchdog.set(config["watchdog_enabled"])
        startup.set(config["start_with_windows"] if actual_startup is None else actual_startup)

    def stamp(value):
        if not value:
            return "—"
        import datetime
        return datetime.datetime.fromtimestamp(value).strftime("%d/%m %H:%M:%S")

    def render(result):
        if result.get("running") is False:
            state["running"] = False
            status.set("FileBridge: Stopped\nAndroid TV: —     |     ADB Reverse: Unknown\nNhấn Khởi động Backend để chạy dịch vụ nền.")
            backend_button.configure(text="Khởi động Backend")
            if not state["initialized"]:
                display_config(result["config"], result.get("startup_enabled"))
        else:
            state["running"] = True
            c = result["connection"]
            status.set(f'FileBridge: {result["file_bridge"]}   |   Android TV: {c["state"]}   |   Reverse: {c["reverse"]}\n'
                       f'Target: {result["config"]["target_ip"]}:{result["config"]["adb_port"]}   |   HTTP: {result.get("lan",{}).get("address","127.0.0.1")}:{result["config"]["bridge_port"]}\n'
                       f'Kiểm tra: {stamp(c["last_check"])}   |   Thành công: {stamp(c["last_success"])}\n'
                       f'TCP connection latency: {c["tcp_latency_ms"] if c["tcp_latency_ms"] is not None else "—"} ms   |   Lượt tới: {c["next_check_in_seconds"]} s\n{c["detail"]}')
            backend_button.configure(text="Dừng Backend")
            args.log_dir = Path(result["log_directory"])
            if not state["initialized"]:
                display_config(result["config"], result.get("startup_enabled"))
        actual = result.get("startup_enabled")
        state["task_known"] = actual is not None
        startup_label.set("Task Scheduler: " + ("Đã bật" if actual else "Đã tắt") if actual is not None else "Task Scheduler: không xác minh được — " + str(result.get("startup_error", "")))
        state["initialized"] = True

    def read_status():
        try:
            return ipc_request(directory, "status")
        except (OSError, EOFError, FileNotFoundError):
            config = store.load()
            try:
                actual, error = StartupTask(directory, args.log_dir).enabled(), None
            except Exception as failure:
                actual, error = None, str(failure)
            return dict(running=False, config=config, startup_enabled=actual, startup_error=error)

    def submit(work, callback=None, interactive=False):
        if state["busy"] or state["closed"]:
            return
        state["busy"] = True
        for button in controls:
            button.configure(state="disabled")
        def run():
            try:
                replies.put((callback, work(), None, interactive))
            except Exception as error:
                replies.put((callback, None, error, interactive))
        executor.submit(run)

    def collect():
        try:
            callback, result, error, interactive = replies.get_nowait()
        except queue.Empty:
            pass
        else:
            state["busy"] = False
            for button in controls:
                button.configure(state="normal")
            if error:
                notice.set(str(error))
                if interactive:
                    messagebox.showerror("AquaStream Bridge", str(error), parent=root)
            elif callback:
                callback(result)
        if not state["closed"]:
            root.after(100, collect)

    def save():
        try:
            candidate = store.load()
            for key in values:
                text = values[key].get().strip()
                candidate[key] = text if key in ("target_ip", "adb_path") else float(text) if "timeout" in key else int(text)
            candidate.update(start_with_windows=startup.get(), watchdog_enabled=watchdog.get())
            candidate = validate(candidate)
        except (ValueError, TypeError) as error:
            messagebox.showerror("Cấu hình không hợp lệ", str(error), parent=root)
            return
        running = state["running"]
        def work():
            if running:
                return ipc_request(directory, "configure", config=candidate)
            old = store.load()
            with socket.socket() as probe:
                try:
                    probe.bind(("127.0.0.1", candidate["bridge_port"]))
                except OSError:
                    raise RuntimeError("Cổng HTTP đang được tiến trình khác sử dụng; cấu hình cũ được giữ lại.") from None
            task = StartupTask(directory, args.log_dir)
            changed = candidate["start_with_windows"] != old["start_with_windows"]
            if changed:
                task.set_enabled(candidate["start_with_windows"])
            try:
                store.save(candidate)
            except Exception:
                if changed:
                    task.set_enabled(old["start_with_windows"])
                raise
            return read_status()
        def saved(result):
            state["initialized"] = False
            render(result)
            notice.set("Đã lưu cấu hình." + (" Android hiện dùng cổng 8765; giữ cổng mặc định để xem trên TV." if candidate["bridge_port"] != 8765 else ""))
        submit(work, saved, True)

    def action(name):
        if not state["running"]:
            notice.set("Backend chưa chạy.")
            return
        submit(lambda: ipc_request(directory, name), lambda _: notice.set("Đã gửi yêu cầu " + ("khôi phục kết nối." if name == "recover" else "kiểm tra.")), True)

    def backend():
        if state["running"]:
            submit(lambda: ipc_request(directory, "shutdown"), lambda _: notice.set("Đang dừng backend…"), True)
            return
        def start():
            executable, command = executable_command()
            command += ["--data-dir", str(directory), "--log-dir", str(args.log_dir)]
            subprocess.Popen([executable, *command], cwd=str(Path(executable).parent),
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), close_fds=True)
        submit(start, lambda _: notice.set("Đang khởi động backend…"), True)

    def logs():
        Path(args.log_dir).mkdir(parents=True, exist_ok=True)
        os.startfile(str(args.log_dir))

    for text, command in [("Lưu cấu hình", save), ("Kiểm tra ngay", lambda: action("check")),
                          ("Kết nối lại", lambda: action("recover")), ("Mở nhật ký", logs)]:
        button = ttk.Button(buttons, text=text, command=command)
        button.pack(side="left", padx=(0, 7))
        controls.append(button)
    backend_button = ttk.Button(panel, text="Khởi động Backend", command=backend)
    backend_button.grid(row=row+6, column=0, columnspan=3, sticky="w", pady=10)
    controls.append(backend_button)

    def poll():
        submit(read_status, render)
        if not state["closed"]:
            root.after(2500, poll)

    def close():
        state["closed"] = True
        executor.shutdown(wait=False, cancel_futures=True)
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    try:
        collect()
        poll()
        root.mainloop()
    finally:
        lock.close()
    return 0
