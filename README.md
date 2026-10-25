# CFlip Launcher

**Cover launcher cá nhân của Võ Chí Cường cho Galaxy Z Flip5.**
Chữ **C** là Cường — app này là bản tự viết (clean-room), mọi tính năng đều
mở sẵn, **không paywall, không premium**: tính năng nào làm được là dùng luôn.

> v1.0.0 — hoàn thiện toàn bộ tính năng theo roadmap. Project tiền thân: MyFlip Launcher v0.1 (scaffold).
> **v2.0.0 (22/10/2026)** — thiết kế lại toàn bộ giao diện: design system riêng (thẻ kính, accent mint `#7FE3C0`, thanh điều hướng pill nổi), Home hero có chỉ báo pin; kèm Hidden Apps (v1.3). Lộ trình: `FEATURES-V2.md`.
> **v2.1.1 (25/10/2026)** — rà lỗi 12 lỗi (mở app, đèn flash, độ sáng, seek nhạc, số cột, hình nền, đồng hồ cover, xoay khi phản chiếu, widget, bong bóng, nút Back). Chi tiết: `BUGFIX-2.1.1.md`.
> **v2.2.0 (25/10/2026)** — giao diện app chính mới trên màn hình lớn: thẻ trạng thái quyền/dịch vụ, lưới tính năng mở đúng tab cover, chỉnh nhanh sắp xếp A-Z / Mới mở / Mới cài. Chi tiết: `UI-2.2.md`.

## Đối chiếu tính năng với cover launcher mẫu

| Tính năng | CFlip v1.0 |
|---|---|
| Mở app từ cover, app drawer đầy đủ + tìm kiếm | ✅ Tab Apps, tìm theo tên |
| Favorite apps | ✅ Giữ (long-press) để ghim/bỏ ghim, hiện ở tab Home + Presentation |
| Recent apps / chuyển app nhanh | ✅ UsageStats (cấp Usage Access) + lịch sử mở app trong CFlip làm fallback |
| Thông báo + thao tác | ✅ Mở, xóa, bấm nút action, **trả lời trực tiếp (RemoteInput)** |
| Media controls | ✅ Play/Pause, Next/Prev, **seek**, hiện tên bài/nghệ sĩ, cập nhật mỗi giây |
| Cover gestures | ✅ Vuốt ngang đổi tab trong CFlip + tab bar gọn cho màn hình nhỏ |
| Quick controls | ✅ Đèn flash, âm lượng, độ sáng, panel Wi-Fi/Internet/NFC, Bluetooth, Settings |
| System actions | ✅ Khóa máy, Recents, mở khay thông báo, Quick Settings (qua Accessibility) |
| Cá nhân hóa | ✅ Số cột lưới (Auto/3–6), cỡ icon, ẩn/hiện nhãn, màu nền, **hình nền cover tự chọn**, đồng hồ 24h, favorites first |
| Ẩn app (Hidden Apps) | ✅ **v1.3** — giữ app để mở menu → Ẩn app này; app ẩn biến mất khỏi mọi bề mặt (lọc trong AppRepository), quản lý/hiện lại ở tab Style. Lộ trình tính năng v2 đầy đủ: `FEATURES-V2.md` |
| Hiển thị lên màn hình phụ thật | ✅ `CoverPresentation` + foreground service tự show khi có display phụ, tự ẩn khi mất |
| Đường chính thống Samsung | ✅ Widget trong giao diện cover của CFlip |
| Không root / không ADB / không Shizuku khi dùng | ✅ Chỉ cấp quyền Android bình thường trong Settings |

## Cài lên Flip5 thật (cách test chính — quyết định của Cường 17/10/2026)

1. Mở project bằng **Android Studio** (Open → thư mục `cflip-launcher`), chờ Gradle Sync.
2. Cắm Flip5, bật Developer Options → USB Debugging (hoặc Wireless Debugging).
3. Run ▶ — app cài thẳng vào máy.
4. Mở **CFlip Launcher** → cấp đủ quyền theo thứ tự trong app:
   Notification Access → Accessibility → Overlay → Usage Access → Modify system settings.
5. Bấm **Start foreground service**, rồi **Mở CFlip ngay trên màn hình chính**
   để dùng thử toàn bộ 6 tab mà chưa cần gập máy.
6. Khi sẵn sàng test cover thật: gập máy lại. Xem Logcat tag `CFlipFG`:
   service có nhận được display phụ không, Presentation có bị hệ thống chặn không.
   Kết quả bước này quyết định hướng dùng chính: Presentation hoặc overlay của CFlip.
   Checklist đầy đủ trong `TESTING.md`.

Build APK bằng GitHub Actions (không cần Studio): đẩy project lên GitHub →
Actions → **Build Debug APK** → Run workflow → tải artifact `cflip-debug-apk` về cài.
(Lưu ý quen thuộc: workflow do API/commit đẩy lên sẽ không tự chạy — bạn tự bấm Run workflow.)

Lệnh local sau khi Studio đã sync 1 lần:

```bat
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Build APK release (cài được qua adb)

Project chưa kèm keystore thật và không bao giờ commit keystore/mật khẩu.
Làm 1 lần trên máy của cậu, rồi build release như bình thường:

```bat
cd /d D:\cflip-launcher
"C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\keytool.exe" -genkeypair -v -keystore cflip-release.keystore -alias cflip -keyalg RSA -keysize 2048 -validity 10000
copy keystore.properties.example keystore.properties
```

Sửa `keystore.properties` bằng mật khẩu của cậu (file này đã nằm trong
`.gitignore`, không commit). Sau đó:

```bat
gradlew.bat assembleRelease
adb connect 192.168.150.102:43239
adb -s 192.168.150.102:43239 install -r app\build\outputs\apk\release\app-release.apk
adb -s 192.168.150.102:43239 shell am start -n com.cuong.cflip/.MainActivity
```

Nếu chưa có `keystore.properties`, bản release vẫn build được nhưng không
ký nên `adb install` sẽ báo lỗi chứng chỉ — lúc đó dùng bản debug ở trên
để test nhanh (debug tự ký sẵn, tính năng giống hệt vì release cũng tắt
minify).

## Giới hạn đã biết (nói thẳng)

- Samsung/One UI **có thể chặn** app bên thứ ba tự hiển thị Presentation lên Flex Window.
  CFlip xử lý bằng cách bắt lỗi, ghi log, và vẫn còn đường overlay của CFlip.
  Đây là giới hạn của nền tảng, không phải tính năng bị khóa sau paywall.
- Một số toggle hệ thống (Wi-Fi/Bluetooth) không cho app thường tự bật/tắt —
  CFlip mở **panel chính thức** của Android/Settings thay thế, đúng cách Google cho phép.
- Độ sáng hệ thống cần quyền "Modify system settings" cấp riêng từng app.

## Cấu trúc

```
app/src/main/java/com/cuong/cflip/
├── MainActivity.kt / CFlipApp.kt
├── ui/       CoverApp (6 tab), MainScreen, CoverLauncherScreen, NotificationsScreen,
│             MediaScreen, QuickControlsScreen, SettingsScreen, AppIcon, CoverPreviews
├── data/     AppRepository, FavoritesStore, SettingsStore, LaunchHistoryStore,
│             RecentAppsRepository, RecentSorter
├── display/  DisplayRepository, DisplayPicker, AppLauncher
├── cover/    CoverPresentation (UI trên màn hình phụ)
├── service/  CoverForegroundService, MyNotificationListenerService,
│             NotificationModels, MyAccessibilityService
├── media/    MediaControllerRepository, MediaModels
├── control/  QuickControls (flashlight/volume/brightness/panels)
└── widget/   CoverWidgetProvider (widget màn hình chính)
```
