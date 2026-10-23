# AquaStream

Phát video từ ổ đĩa Windows trên Android TV, không chép phim sang TV, không lập thư viện và không transcode. Phiên bản mã nguồn: **0.7.0**.

- **Direct LAN:** tìm máy tính trong mạng, ghép đôi bằng PIN, duyệt thư mục và phát trực tiếp.
- **ADB Reverse / Legacy:** kết nối qua ADB, có watchdog kiểm tra và khôi phục kết nối.
- Hỗ trợ HTTP Range, tua bằng remote, ghi nhớ vị trí xem, tốc độ phát và tự chuyển tập.
- Android dùng Kotlin Views và Media3 ExoPlayer. Windows dùng Python, Tkinter và SQLite.

## Sử dụng Direct LAN

1. Chạy Windows Bridge, khởi động backend và chọn chế độ Direct LAN.
2. Kiểm tra địa chỉ LAN được chọn. Nếu Windows chặn kết nối, dùng chức năng Firewall trong GUI cho mạng Private.
3. Trên TV mở phần máy tính trong mạng, chọn PC. Trên PC tạo PIN ghép đôi rồi nhập PIN trên TV.
4. Chọn ổ đĩa, thư mục và video. Có thể nhập địa chỉ máy tính thủ công nếu mạng không hỗ trợ discovery.

Direct LAN không cần ADB. HTTP hiện **không mã hóa**; chỉ sử dụng trong mạng tin cậy. Ghép đôi và bearer token kiểm soát truy cập, không mã hóa dữ liệu truyền.

## Chạy Windows Bridge từ source

Cần Windows và Python 3.12 có Tcl/Tk (bản cài Python tiêu chuẩn cho Windows). Chạy các lệnh từ thư mục gốc repository:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r windows/requirements.txt
python windows/bridge.py
```

Chạy nền bằng `python windows/bridge.py --background`. Mở lại GUI để quản lý, dừng backend hoặc bật khởi động cùng Windows. Đóng GUI không dừng backend.

Cấu hình mặc định nằm tại `%APPDATA%\AquaStream`, log tại `%LOCALAPPDATA%\AquaStream\logs`. Giữ dữ liệu cấu hình, nhận dạng máy chủ, ghép đôi và database khi cập nhật. Cài đặt cũ có thể trỏ database xem tiếp tới vị trí cũ qua `storage.json`.

## Build EXE

Trong môi trường Python ở trên:

```powershell
python -m pip install -r windows/requirements-build.txt
powershell -ExecutionPolicy Bypass -File windows/build.ps1
```

Kết quả: `dist/windows/AquaStreamBridge.exe`, một EXE có GUI và chế độ chạy nền. Máy chạy EXE không cần cài Python. Dependency được cài qua pip; repository không chứa thư viện vendor.

ADB chỉ cần cho Legacy. Script build tự tìm ADB trong PATH hoặc nhận `-AdbPath`; nếu tìm thấy sẽ chép ADB và các tệp đi kèm vào bản phân phối.

## Build Android APK

Cần JDK 17 hoặc 21, Android SDK Platform 35 và Build Tools 35.0.0. Đặt `ANDROID_HOME` tới SDK; script mặc định thử `%LOCALAPPDATA%\Android\Sdk`.

```powershell
powershell -ExecutionPolicy Bypass -File android/build.ps1
```

Kết quả: `dist/android/AquaStream-debug.apk`. Gradle Wrapper nhỏ được giữ trong repository; Gradle 8.9 và dependency được tải khi build lần đầu. Không kèm SDK, cache hoặc bản Gradle đã tải.

Có thể mở thư mục `android/` bằng Android Studio. Build hiện tạo APK debug dùng để sideload; repository không chứa khóa ký riêng. Cài đè một bản đã có trên TV đòi hỏi cùng khóa ký.

Nếu muốn cài qua ADB sau khi build:

```powershell
powershell -ExecutionPolicy Bypass -File android/build.ps1 -Install -DeviceSerial "YOUR_ADB_SERIAL"
```

## Legacy / ADB Reverse

Chọn Legacy ở cả Windows và TV, cấu hình IP/port ADB đúng với TV trong GUI Windows và dùng cùng HTTP port ở hai phía (mặc định 8765). Bridge thiết lập và kiểm tra reverse mapping. IP mặc định trong cấu hình là giá trị cũ của môi trường phát triển; cần đổi cho thiết bị của bạn. Ở Legacy, HTTP chỉ lắng nghe trên loopback.

## Kiểm thử

```powershell
python -m unittest discover -s tests -p "test_*.py" -v
.\android\gradlew.bat --project-dir android testDebugUnitTest
```

Bản source gọn giữ các bài Python kiểm tra bridge, Range, progress, watchdog và LAN, cùng Android JVM unit tests. Không kèm video mẫu, kết quả test cũ hoặc các harness điều khiển TV dành riêng cho máy phát triển. Debug renderer phục vụ hook seek tùy chọn vẫn được giữ; không có trong release.

## Phạm vi

Tập trung vào MP4 H.264/H.265; khả năng phát container, audio codec, profile và độ phân giải phụ thuộc phần cứng TV. Direct LAN hiện dùng IPv4. Không có transcoding hoặc dịch vụ chia sẻ qua Internet.

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `android/` | Ứng dụng TV, tài nguyên, unit tests và cấu hình Gradle |
| `windows/` | Bridge, GUI, cấu hình, watchdog, LAN và script build |
| `tests/` | Python tests không cần bộ video thử nghiệm |
| `dist/` | Kết quả build, được bỏ qua bởi Git |
