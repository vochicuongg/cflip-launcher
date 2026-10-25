# CFlip 2.1.1 — Rà lỗi rồi mới build (25/10/2026)

Không thêm tính năng mới, không đếm lượt dùng, không quota/paywall.
Chỉ sửa các lỗi chắc chắn tìm được bằng đọc code tĩnh (chưa build trên VM này,
chưa test trên Flip5 — xem cuối file).

## Đã sửa (12 lỗi)

1. **Mở app (AppLauncher)** — fallback khi mở lên display phụ thất bại
   không còn crash nếu lần mở thứ hai cũng lỗi (vd. SecurityException).
2. **Đèn flash (QuickControls)** — `getCameraCharacteristics()` có thể ném
   `CameraAccessException` khi camera đang bận; giờ trả về "không có đèn"
   thay vì crash.
3. **Âm lượng (QuickControls)** — `AudioManager` null hoặc lỗi không còn
   gây NPE; `volume()` trả về 0/1 an toàn.
4. **Độ sáng (QuickControlsScreen)** — trước đây mỗi bước kéo slider khi
   chưa cấp quyền lại hiện 1 toast, spam màn hình. Giờ chỉ toast 1 lần
   khi thả tay ra.
5. **Seek nhạc (MediaScreen)** — slider trước đây lấy thẳng vị trí từ
   poll mỗi giây nên đang kéo thì bị giật về vị trí thật. Giờ đang kéo
   thì hiện vị trí kéo, thả ra mới seek.
6. **Số cột lưới (SettingsScreen)** — slider cũ cho chọn 1, 2 cột nhưng
   code lưới chỉ nhận 0 (tự động) và 3–6, nên chọn 1/2 thì hiển thị sai
   mà thực tế vẫn tự động. Slider giờ chỉ còn 0, 3, 4, 5, 6.
7. **Hình nền cover (SettingsScreen + CoverApp)** — `GetContent` chỉ cấp
   quyền tạm nên ảnh mất sau khởi động lại. Giờ copy ảnh vào bộ nhớ
   riêng của app (`cover_wallpaper.jpg`), xóa ảnh cũng xóa file.
   CoverApp giải mã ảnh thu nhỏ tối đa 1024px thay vì ảnh gốc
   (tránh OOM/đơ với ảnh 50MP).
8. **Đồng hồ cover (CoverPresentation)** — trước đây đồng hồ chỉ set 1 lần
   lúc mở, đứng yên cho tới khi tạo lại Presentation. Giờ tick mỗi giây,
   dừng khi Presentation đóng.
9. **Xoay khi phản chiếu (ScreenMirrorService + MirrorPresentation)** —
   trước đây đổi xoay chỉ xoay TextureView, virtual display vẫn giữ kích
   thước cũ nên hình bị bóp. Giờ tạo lại virtual display đúng kích thước
   mới trên cùng surface/projection.
10. **Widget (CoverWidgetProvider)** — `toBitmap()` trước đây sửa bounds
    trực tiếp trên drawable dùng chung của PackageManager. Giờ copy +
    mutate trước khi vẽ, như bong bóng đã làm.
11. **Bong bóng (BubbleOverlayService)** — panel 252dp trước đây chạy ra
    ngoài màn hình khi bong bóng ở sát mép phải (giờ clamp theo chiều
    rộng màn hình); nút "Khoá máy" khi chưa bật Accessibility trước đây
    im lặng không làm gì, giờ báo toast.
12. **Màn hình chính (MainScreen)** — nút Back hệ thống trước đây thoát
    luôn app khi đang ở màn "thử cover"; giờ Back quay về màn setup.
    Presentation cũng được dismiss khi màn hình này bị hủy.

## Cũng sửa nhỏ

- **Gần đây (CoverApp)** — danh sách Recents trước đây chỉ tính lại khi
  favorites đổi, nên ẩn/hiện app hoặc vừa mở app xong cũng không cập
  nhật. Giờ tính lại khi mở tab Apps, khi ẩn/hiện app, khi favorites đổi.

## Chưa sửa / chỉ kiểm được trên Flip5 thật

- Presentation có hiện lên Flex Window trên One UI 9 hay không (giới hạn
  nền tảng, Samsung có thể chặn app bên thứ ba).
- Mirror gập kín bị đen là hành vi nền tảng (màn hình chính tắt), không
  phải lỗi.
- Tốc độ load ngăn kéo app (query + load icon trên main thread) — chấp
  nhận được với ~200 app, chưa tối ưu sang background.
- Build chưa chạy trên VM này: Gradle local trên VM này hỏng do Java
  loopback (xem AGENTS.md). Build bằng GitHub Actions (workflow
  "Build Debug APK", bấm Run workflow) hoặc `gradlew.bat assembleDebug`
  trên máy Windows của Cường, rồi cài và chạy checklist TESTING.md
  theo 1 đợt gập duy nhất.
