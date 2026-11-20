# CFlip Launcher

**Cover launcher cá nhân của Võ Chí Cường cho Galaxy Z Flip5.**
Chữ **C** là Cường — app này là bản tự viết (clean-room), mọi tính năng đều
mở sẵn, **không paywall, không premium**: tính năng nào làm được là dùng luôn.

> v1.0.0 — hoàn thiện toàn bộ tính năng theo roadmap. Project tiền thân: MyFlip Launcher v0.1 (scaffold).
> **v2.0.0 (22/10/2026)** — thiết kế lại toàn bộ giao diện: design system riêng (thẻ kính, accent mint `#7FE3C0`, thanh điều hướng pill nổi), Home hero có chỉ báo pin; kèm Hidden Apps (v1.3). Lộ trình: `FEATURES-V2.md`.
> **v2.1.1 (25/10/2026)** — rà lỗi 12 lỗi (mở app, đèn flash, độ sáng, seek nhạc, số cột, hình nền, đồng hồ cover, xoay khi phản chiếu, widget, bong bóng, nút Back). Chi tiết: `BUGFIX-2.1.1.md`.
> **v2.2.0 (25/10/2026)** — giao diện app chính mới trên màn hình lớn: thẻ trạng thái quyền/dịch vụ, lưới tính năng mở đúng tab cover, chỉnh nhanh sắp xếp A-Z / Mới mở / Mới cài. Chi tiết: `UI-2.2.md`.
> **vc242 (14/11/2026)** — làm lại giao diện theo skill UI/UX Pro Max: motion tokens + press scale dùng chung (`ui/Motion.kt`), lưới Apps vào staggered, badge số crossfade, pill nav đổi màu/icon/label có animation, kết quả tìm kiếm + hàng trả lời mở ra mềm, nút media crossfade play/pause; logo mới (điện thoại gập, nét mint) + design system ở `design-system/cflip/MASTER.md`.
> **vc244 (14/11/2026)** — làm lại giao diện trong app theo skill UI/UX Pro Max: màn hình chính có hiệu ứng mở app (hero + từng mục hiện lần lượt, chỉ chạy 1 lần mỗi lần mở app, tắt animation hệ thống thì hiện ngay), đảo ứng dụng mới (`ui/AppDrawerScreen.kt`: tìm kiếm không dấu + sắp xếp A-Z / Mới mở / Mới cài, lưới 4 cột, badge số, giữ lâu mở menu ghim/ẩn), nút cấp quyền + hàng quyền có press scale; luật màn hình lớn ở `design-system/cflip/pages/main-app.md`.
> **vc245 (14/11/2026)** — đảo Widget + đảo cảnh báo + đảo PANEL theo skill UI/UX Pro Max: đảo Widget mới (`ui/WidgetDrawerScreen.kt`: bật Lưới ứng dụng trong panel + 4 widget Samsung dạng thông tin), đảo cảnh báo mới (`ui/NotificationDrawerScreen.kt`: hàng gọn, chấm mint + chữ đậm cho chưa đọc, trả lời/xóa), đảo PANEL (`ui/QuickControlsScreen.kt`: tile 64dp, press scale + rung, tile cần Accessibility mờ khi chưa bật); hub thống nhất card Island + target chạm ≥48dp; cover gọn lại (mật độ mới ở `design-system/cflip/pages/cover.md`); widget RemoteViews nhuộm mint (`#F5F7F6`/`#9AA8A1`, viền mint, nhấn sáng mint).
> **vc246 (14/11/2026)** — thiết kế lại sâu 4 widget theo 9Flip + skill UI/UX Pro Max: Nhạc thành băng cassette (cửa sổ băng 2 cuộn + thanh tiến độ + nút phát mint 56dp, trạng thái ĐANG PHÁT/TẠM DỪNG bằng chữ) + thêm widget Nhạc (nhỏ) 352x160dp dùng chung code MusicWidgetProvider; Đồng hồ 44sp + vạch mint; Widget Cover thêm dòng bài đang phát + nhãn YÊU THÍCH; Lưới ứng dụng thống nhất card mint, icon 44dp, nhãn 11sp có "…", nút làm mới tròn mint; preview trong đảo Widget khớp ô thật (icon + vạch nhãn). Chi tiết ở `design-system/cflip/pages/widgets.md`.
> **vc258 (15/11/2026)** — sửa 2 lỗi build của vc255+vc257 (lần build gộp đầu tiên mới lộ ra): `setViewLayoutWidth/Height` không có dạng 2 tham số `(viewId, px)` như vc255 đã viết — API thật là 3 tham số `(viewId, Float size, unit)`, nên 4 chỗ gọi trong widget giờ truyền px đã tính kèm `COMPLEX_UNIT_PX`; `TrashIcon` đọc `CFlipColors.Danger` trong lambda vẽ của Canvas (không phải ngữ cảnh @Composable kể từ khi vc257 đổi CFlipColors thành accessor) nên màu giờ đọc ngoài Canvas rồi mới dùng trong đó.
> **vc257 (15/11/2026)** — sửa nút trên thẻ thông báo trong Quick Panel → Thông báo (action mở UI giờ launch-first như tap — trước đây gửi trần từ service nên rơi sang màn hình chính; actionIntent null không còn báo thành công giả; Xóa chỉ ẩn thẻ khi dismiss thật sự chạy); tab Quick Panel chuyển có trượt + mờ theo hướng (trước đây chỉ mờ 150ms); nền trong app chỉ còn 1 MÀU PHẲNG theo theme (gỡ toàn bộ gradient/TopGlow/blob) + thẻ liquid glass (trong suốt + viền sáng + highlight + bóng mềm, không blur trên nền phẳng); thêm cài đặt Giao diện Tối/Sáng/Theo hệ thống trong đảo Hình nền & Kiểu (chỉ trong app, cover luôn tối; "theo ứng dụng" hiểu là theo hệ thống); thẻ CẢNH BÁO giữ nền đặc, không blur.
> **vc256 (15/11/2026)** — sửa màn Sửa nhóm: ô app trong lưới nhóm cao 96dp → 112dp để nhãn có chỗ 2 dòng (trước đây 11sp 1 dòng chỉ đọc được ~11 ký tự, app chung tiền tố như "Samsung …" không phân biệt nổi); nhãn 12sp, tối đa 2 dòng, căn giữa — giờ đọc được ~20 ký tự; dragRowHPx đổi theo, giữ nguyên 4 cột + toàn bộ logic kéo-thả.
> **vc255 (15/11/2026)** — sửa đảo Hình nền & Kiểu theo phản hồi vc254: lưới Quick Panel → Ứng dụng tính ô theo chiều rộng panel (ô cố định 76dp làm cột 5/6 tràn và khuất app); cỡ icon giờ theo slider ở cả 3 mặt (cover kẹp theo chiều rộng ô, panel kẹp theo ô tối đa 64dp, widget kẹp 24–64dp qua setViewLayoutWidth/Height); sắp xếp dùng chung AppSort cho cover/panel/widget (widget trước đây bỏ qua sortMode); panel áp hình nền (crop giữa màn hình ngoài + phủ tối) và màu nền (trước đây không vẽ gì dù rebuild key có ghi); chọn lại ảnh nền lưu tên file mới (tên cũ cố định làm CoverApp không vẽ lại); hàng Usage Access chuyển từ tab Cá nhân hóa sang đảo Quyền trên màn hình chính (không tính vào bộ 3 bắt buộc).
> **vc254 (15/11/2026)** — màn hình chính trong app thiết kế lại theo REA 9Flip + Cover Power (decompile, chỉ học ý tưởng): gỡ 2 đảo Ứng dụng/Thông báo (kèm file AppDrawerScreen.kt + NotificationDrawerScreen.kt, SortModePicker chuyển sang file riêng vì SettingsScreen còn dùng); thêm 3 đảo mới — Nhóm ứng dụng (mở GroupEditorScreen có sẵn), Hình nền & Kiểu (mở SettingsScreen có sẵn), Mở gần đây (màn list mới RecentAppsScreen từ RecentAppsRepository); bố cục thành 2 nhóm có nhãn (TRẠNG THÁI: Trạng thái rồi Quyền; MÀN HÌNH NGOÀI: Widget, Nhóm, Hình nền & Kiểu, Quick Panel) + đảo Mở gần đây ở giữa; nền trong app thành 3 lớp (gradient nền + TopGlow + 2 blob mờ); bỏ tiền tố "CFlip — " ở 4 nhãn widget + tiêu đề widget + tiêu đề thông báo chụp màn hình; widget Lưới ứng dụng: nút header ↻/← có pressed-state (tròn mint + chữ đậm, vì RemoteViews không animate scale/transition — cell đã có selector từ vc153).
> **vc253 (15/11/2026)** — sửa lỗi build của vc252: `Notification` không có `isGroupSummary()` (kiểm chứng trên android-35 `android.jar` — chỉ có hằng `FLAG_GROUP_SUMMARY`); kiểm tra summary của nhóm đổi thành `(notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0`. Không đổi gì khác.
> **vc252 (15/11/2026)** — thẻ Thông báo trong Quick Panel màn hình ngoài chỉ hiện thông báo đang có, chia 2 nhóm: Now Bar trước (mirror LiveBridge đã unwrap về app nguồn + thông báo ongoing như nhạc/cuộc gọi/điều hướng), rồi các thông báo còn lại mới nhất trước. Loại thông báo của chính CFlip; mỗi nhóm chỉ 1 hàng đại diện (summary có nội dung, không thì con mới nhất); mirror không hiện riêng — chỉ hiện 1 lần dưới dạng app nguồn, icon + tên đều của app nguồn (launcher icon, không bao giờ icon LiveBridge); icon load ở refresh() trên binder thread, cache theo package trong từng lần refresh. Không lịch sử, không màn chi tiết, chip Mở/Xoá/Trả lời/actions giữ nguyên.
> **vc251 (15/11/2026)** — 2 thanh độ sáng + âm lượng ở Quick Panel màn hình ngoài thành hàng dọc (2 thanh đứng cạnh nhau) mỗi khi có media (đang phát HOẶC tạm dừng), thay vì chỉ khi đang phát: điều kiện `slidersVertical` đổi từ `mediaInfo?.isPlaying == true` thành `mediaInfo != null` trong CoverControlCenter.kt. Chế độ dọc của NineFlipSlider giữ nguyên (đổ đầy từ dưới lên, icon ở dưới, kéo lên = tăng). Không có media thì vẫn ngang như trước.
> **vc250 (14/11/2026)** — sửa lỗi build của vc248: `ColorDot(argb: Int)` nhưng `BackgroundStyles.colors` là `List<Long>` (`0xFF...L`) nên `compileReleaseKotlin` lỗi `Argument type mismatch` ở SettingsScreen.kt:154; đổi tham số thành `argb: Long` (thân hàm dùng `Color(argb)` vốn nhận Long). Không đổi gì khác.
> **vc249 (14/11/2026)** — sửa 4 widget trắng/lỗi tải (Đồng hồ, Nhạc, Nhạc nhỏ, Lưới ứng dụng): logcat trên Flip5 cho thấy host widget màn hình ngoài của Samsung từ chối inflate `android.view.View` thuần trong RemoteViews (`InflateException: Class not allowed to be inflated android.view.View` ở widget_clock line #23); 4 vạch trang trí `<View>` trong widget_clock.xml, widget_music.xml, widget_music_small.xml và widget_appgrid.xml đổi thành `<ImageView>` giữ nguyên kích thước/margin/màu + `contentDescription="@null"`. Giữ nguyên toàn bộ thiết kế depth của vc247 (gradient card/băng, selector tile, chip) và bản sửa exported/requestUpdate. Chi tiết ở `design-system/cflip/pages/widgets.md`.
> **vc248 (14/11/2026)** — làm kỹ các màn hình con + hiệu ứng theo skill UI/UX Pro Max: SettingsScreen (section vào stagger, SortModePicker dùng chung, SwitchRow bấm cả hàng, chấm màu 48dp, nút 48dp), GroupEditorScreen (header stagger, empty state dạng card, OrderArrow 48dp cao, ✕ gỡ 32dp, hàng picker 48dp, mọi TextButton 48dp), MediaScreen (tiêu đề crossfade khi đổi bài, đĩa vào fade), NotificationsScreen (chip press scale, header stagger), QuickControlsScreen (section stagger, nút cấp quyền 48dp), MirrorControls (vào GlassCard, pill xoay 48dp có haptic, hàng Hai màn hình bấm cả hàng), MainScreen (HubSwitch bấm cả hàng, 2 hàng dropdown press scale, giá trị trạng thái crossfade), dialog giữ app thống nhất thành MenuDialog/MenuActionRow dùng chung (Design.kt) + EnterItem dùng chung (Motion.kt). Không động widget, không đổi chuỗi/logic. Chi tiết ở `design-system/cflip/pages/main-app.md`.
> **vc247 (14/11/2026)** — sửa 3 widget chết (Đồng hồ, Nhạc, Nhạc nhỏ): receiver đổi exported=false → true cho đúng 3 widget, thêm MusicWidgetProvider.requestUpdate() làm mới cả 2 biến thể nhạc, nối Clock/Music requestUpdate vào MyNotificationListenerService (mỗi khi có thông báo), CoverControlCenter (mỗi khi panel làm mới media) và BootReceiver (sau reboot/cập nhật app); đồng thời làm sâu thiết kế widget theo skill UI/UX Pro Max: card gradient chéo, cửa sổ băng gradient, trạng thái nhạc thành chip mint chữ đen, pin/thông báo của Đồng hồ thành 2 chip, dòng bài đang phát của Cover thành hàng nổi có icon nốt nhạc, ô fav + ô lưới app thành tile nổi (nhấn sáng mint), thêm nhãn "CFLIP · SIDE A" cho băng. Chi tiết ở `design-system/cflip/pages/widgets.md`.

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
