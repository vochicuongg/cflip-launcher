# CFlip 2.2 — Giao diện app chính mới (25/10/2026)

## Decompile đã làm (bản 149, static-only)

Giao diện app chính của 9 Flip là Flutter, nên không có layout XML
native để đọc. Những gì đọc được từ chính APK:

- `assets/flutter_assets/assets/translations/{en,vi}.json`: 570 khóa
  chuỗi, nhóm thành các màn: Home (tips, setup wizard, widget launcher
  status, quick launcher status, swipe indicator, battery indicator,
  album-art wallpaper, hidden apps, permission status/manager, premium,
  privacy/terms/contact, app version/update, social), Welcome,
  Get Started (notification / quick launcher / widget launcher, ready
  dialog), Paywall, New Features (tape, header custom, header pages,
  search, compact).
- Channel native `nine_cover/*` trong DEX: app_info, app_navigation,
  app_update, assistive_touch_icon, battery_indicator,
  cover_notification_banner, cover_recents, cover_rotate (+events),
  cover_runtime (+events), cover_widget, external_links,
  get_started_state, hidden_apps, mail — tức mỗi thẻ trên Home đều có
  1 cầu nối native tương ứng.
- Ảnh 4 Cường gửi trước đó: màn tùy chỉnh trong app chính ghi cùng
  state `m8` với panel Control Center trên cover.

**Không sao chép sang CFlip:** không lấy chữ, icon, ảnh, màu hay bố
cục nguyên bản của 9 Flip; không có thẻ premium/paywall (CFlip không
có premium); không có mục social (Reddit/X/Threads/Instagram) vì CFlip
là app cá nhân.

## Giao diện mới cho CFlip (MainScreen, màn hình lớn)

Dùng design system sẵn có của CFlip (thẻ kính, accent mint #7FE3C0,
chữ trắng trên nền đen), cấu trúc học theo Home của app mẫu nhưng viết
lại hoàn toàn:

1. **Hero:** logo C, tên, phiên bản lấy từ PackageManager (không ghi
   cứng), dòng "không paywall".
2. **Thẻ Trạng thái:** quyền đã cấp x/5, dịch vụ cover đang chạy hay
   chưa (kiểm tra thật qua ActivityManager), màn phụ đã thấy hay chưa,
   danh sách màn hình phát hiện. Nút "Làm mới" vì quay lại từ Settings
   hệ thống không chắc recompose — trạng thái không đoán.
3. **Lưới tính năng 2 cột, mỗi thẻ mở đúng tab cover:**
   Cover → Home, Ứng dụng → Apps, Thông báo → Notifs (kèm số thông báo
   mới thật), Nhạc → Media, Điều khiển nhanh → Quick, Cá nhân hóa →
   Style. CoverApp thêm tham số `initialTab`.
4. **Sắp xếp & hiển thị nhanh:** chọn A-Z / Mới mở / Mới cài, bật/tắt
   Yêu thích lên trước, Đồng hồ 24h, Hiện nhãn — ghi thẳng vào
   SettingsStore mà cover đang đọc, nên đổi ở đây là cover đổi theo.
5. **Quyền:** 5 hàng, mỗi hàng có chấm trạng thái thật + nút mở đúng
   trang Settings hệ thống (thông báo, Accessibility, Overlay, Usage
   Access, sửa cài đặt hệ thống).
6. **Dịch vụ cover:** bắt đầu/dừng foreground service, hiện
   Presentation lên màn phụ, hướng dẫn widget MultiStar.
7. **Phản chiếu & bong bóng:** nhúng nguyên MirrorControls và
   BubbleControls đã có (không viết lại, không trùng logic).

## Tính năng mới duy nhất đi kèm UI này: sắp xếp app

- `CoverSettings.sortMode`: 0 = A-Z, 1 = Mới mở, 2 = Mới cài.
- Mới mở: lấy thứ tự từ RecentAppsRepository (UsageStats + lịch sử mở
  trong CFlip), app chưa từng mở xếp sau theo A-Z.
- Mới cài: `AppRepository.installTimes()` lấy `firstInstallTime` từ
  PackageManager, app không lấy được thời gian thì xếp cuối theo A-Z.
- Yêu thích lên trước (nếu bật) vẫn là khóa chính, giữ nguyên hành vi cũ.
- Chọn sắp xếp ở cả màn hình chính mới và tab Cá nhân hóa trên cover.

## Chưa kiểm chứng (giống 2.1.1)

Build chưa chạy trên VM này (Gradle local hỏng do Java loopback).
Build bằng GitHub Actions "Build Debug APK" (Run workflow) hoặc
`gradlew.bat assembleDebug` trên máy Windows của Cường, rồi cài và
chạy checklist TESTING.md. Kiểm tra riêng trên màn hình lớn: bấm từng
thẻ tính năng có mở đúng tab cover không, đổi sắp xếp ở màn hình chính
có đổi lưới app trên cover không, và nút "Làm mới" có cập nhật đúng
số quyền sau khi cấp quyền xong.
