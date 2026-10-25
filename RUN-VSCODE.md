# Chạy CFlip Launcher bằng VS Code (Windows)

CFlip là app **Kotlin Android thuần** — không dùng Flutter (`flutter run` sẽ báo
"No pubspec.yaml" là đúng). Trong VS Code, quy trình là: **build bằng Gradle
Wrapper ngay trong Terminal → cài + mở bằng adb**.

Project đã kèm sẵn: `gradlew.bat`, `gradle/wrapper/`, `local.properties`
(đã trỏ vào SDK ở `C:\Users\Cuong VO\AppData\Local\Android\Sdk` — nếu máy bạn
để SDK chỗ khác, sửa dòng `sdk.dir` trong file đó), và `.vscode/tasks.json`.

## Bước 0 — mở đúng thư mục
VS Code → File → Open Folder → chọn thư mục `cflip-launcher` **trong cùng**
(thư mục chứa `settings.gradle.kts` và `gradlew.bat`).

## Bước 1 — kiểm tra Java (trong Terminal của VS Code: Ctrl+`)

```powershell
java -version
```

- Nếu hiện version 17 trở lên: qua bước 2.
- Nếu báo không tìm thấy, mà máy có Android Studio, chạy 2 dòng này trong
  chính terminal đó (có tác dụng cho phiên terminal hiện tại):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

- Nếu không có Android Studio: cài JDK 17 bằng
  `winget install Microsoft.OpenJDK.17`, đóng/mở lại VS Code, kiểm tra lại.

## Bước 2 — kiểm tra điện thoại

Cắm Flip5, bật USB Debugging, chấp nhận RSA trên máy, rồi:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

Thấy 1 dòng device (không phải `unauthorized`) là được.

## Bước 3 — build + cài + mở app (3 lệnh)

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.cuong.cflip/.MainActivity
```

Lần đầu `gradlew` sẽ tải Gradle 8.7 (~120 MB) + thư viện — cần mạng, vài phút.
Các lần sau nhanh hơn nhiều. APK nằm ở `app\build\outputs\apk\debug\app-debug.apk`.

Hoặc dùng task có sẵn: `Ctrl+Shift+P` → **Tasks: Run Task** →
*CFlip: Build Debug APK* / *CFlip: Install on phone* / *CFlip: Launch app* /
*CFlip: Logcat (cover tags)*.

## Xem log khi test cover

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s CFlipFG CFlipNotifListener CFlipA11y AppLauncher
```

## Giới hạn của VS Code với project này (nói thẳng)
- Code, build, cài, logcat: VS Code làm tốt.
- **Compose Preview** (soi UI cover 720×748 không cần chạy máy) và trình quản lý
  máy ảo chỉ có trong Android Studio. Bạn đã chọn test trên máy thật trước nên
  điểm này ít ảnh hưởng: cứ `installDebug` thẳng lên Flip5 mà thử.
