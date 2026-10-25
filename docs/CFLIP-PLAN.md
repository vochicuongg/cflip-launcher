# CFlip — kế hoạch hoàn thiện như 1 module độc lập, không dựa Good Lock

CFlip là module cover độc lập dùng API Android chuẩn. Good Lock MultiStar chỉ là đường
dự phòng cuối cùng khi mọi đường chuẩn đều bị Samsung chặn, không tính năng cốt lõi nào
bắt buộc phải có nó.

## Thứ tự đường vào cover (thử theo thứ tự, đường sau chỉ khi đường trước bị từ chối)

1. Overlay qua WindowManager của màn phụ: `createDisplayContext(display)` → WindowManager
   của display đó → addView TYPE_APPLICATION_OVERLAY toàn màn hình. Không cần activity token.
2. Presentation: đường cũ, giữ làm fallback.
3. Activity trực tiếp trên display đích: `setLaunchDisplayId` + watchdog 900ms + retry 5 lần.
4. Widget chuẩn Android: AppWidgetProvider + RemoteViewsService, host nào cũng được.
5. MediaProjection mirror: chiếu màn hình chính sang cover.
6. Good Lock: chỉ khi 1–5 đều bị chặn.

## Đã có trong CFlip (tới 2.3.2)

Màn hình chính mới (2.3.0), lưới app + favorites + sort A-Z/Mới mở/Mới cài + tìm kiếm +
ẩn app, tab Thông báo / Nhạc / Điều khiển nhanh / Cá nhân hóa, đèn / âm lượng / độ sáng,
nền tĩnh, xoay cover 0/90/180/270, mirror MediaProjection, bubble kéo được, widget chuẩn,
dịch vụ foreground giữ cover sống khi gập máy.

## Còn thiếu, theo thứ tự ưu tiên

### P0 — làm ngay sau khi test overlay 2.3.2 trên Flip5

1. BootReceiver (BOOT_COMPLETED + MY_PACKAGE_REPLACED): chỉ warm catalog + icon, không tự
   bật service.
2. PackageChangeReceiver (PACKAGE_ADDED/CHANGED/REMOVED/REPLACED/FULLY_REMOVED, debounce
   350ms): sửa lỗi app mới cài không hiện trong lưới cover.
3. Xác nhận overlay sống/chết trên Flip5. Nếu cả overlay lẫn Presentation đều chết thì
   chuyển trọng tâm sang widget chuẩn + mirror và ghi rõ giới hạn Samsung trong README.

### P1 — tính năng cover cốt lõi

4. AssistiveTouch thật trên màn phụ — ĐÃ LÀM ở 2.4.0: chạm = Home, giữ = Recents, đúp
   = Menu (mở tab Điều khiển nhanh trên màn phụ), kéo-thả + lưu vị trí + clamp, tự mờ
   sau 2500ms, chỉ hiện trên màn phụ (từ chối display mặc định). Màn hình chính không
   bao giờ hiện bong bóng nữa; công tắc nằm ở Cài đặt cover. Còn thiếu so với bản đầy đủ:
   icon tùy chỉnh và setting open_mode chọn giữa swipe_indicator và assistive_touch.
5. Swipe indicator ở cạnh màn phụ, dùng cùng đường overlay.
6. Control Center thành panel riêng: tự đóng sau 60s, đóng bằng vuốt ngang >112px / chạm
   vùng trống / Back; thêm tile Wi-Fi, Bluetooth, Không làm phiền, Xoay, Mirror.
7. Nhóm tùy chỉnh (tối đa 4 nhóm) + nút đổi sắp xếp ngay trên tab Apps.
8. Notification: debounce 250ms cho snapshot, nhánh ưu tiên cho cuộc gọi / full-screen
   intent, banner nổi trên cover.
9. AppLauncher: watchdog 900ms + retry tối đa 5 lần + scale clamp 0.5–1.0.
10. Mirror: tách activity xin quyền MediaProjection riêng, thêm generation cho phiên mirror.

### P2 — hoàn thiện

11. Wizard cài đặt 3 bước cho người mới (quyền → dịch vụ → thử gập máy).
12. Toggle hiện pin trên cover; album-art làm nền tạm khi đang phát nhạc.
13. Widget nhạc nhỏ; nút xoay tay + chế độ AUTO/MANUAL/OFF; live wallpaper (cuối cùng).

## Nguyên tắc

- Không paywall, không đếm lượt dùng, không giới hạn tính năng theo gói.
- Không force-stop app khác, không phụ thuộc package Good Lock.
- Mọi giới hạn nền tảng (Samsung chặn cửa sổ bên thứ ba trên Flex Window) phải được ghi rõ
  là giới hạn nền tảng, không đổ cho lỗi code khi chưa có bằng chứng trên máy thật.
