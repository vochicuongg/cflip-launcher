# TESTING — CFlip Launcher

## Quyết định hiện tại (17/10/2026): TEST TRÊN MÁY THẬT TRƯỚC

Cường quyết định test trước bằng **Z Flip5 thật của mình**, thay cho chiến lược
virtual-first đã bàn trước đó. Để bảo vệ bản lề/màn hình, vẫn áp dụng nguyên tắc
**test theo đợt**: gom checklist, chạy trọn 1 lần, ghi kết quả — không gập theo
từng lần sửa code nhỏ. Sửa UI thì soi bằng CoverPreviews/emulator trước, chỉ
đưa lên máy thật khi đã gom đủ thay đổi.

## 1) Checklist trên Flip5 thật (chạy theo thứ tự)

### Cài đặt + quyền
- [ ] Run từ Android Studio (hoặc cài APK từ GitHub Actions) — app mở không crash
- [ ] Cấp Notification Access → quay lại app, tab Notif hết báo chưa cấp quyền
- [ ] Cấp Accessibility → tab Quick: Lock / Recents / Notifications / Quick Settings bấm được
- [ ] Cấp Overlay, Usage Access, Modify system settings

### 6 tab (dùng nút "Mở CFlip ngay trên màn hình chính" — chưa cần gập)
- [ ] Home: đồng hồ chạy đúng giây, favorites hiện, giữ 1 app ở tab Apps để ghim → Home có app đó
- [ ] Apps: tìm kiếm lọc đúng, Recents có app vừa mở (sau khi cấp Usage Access)
- [ ] Notif: nhắn thử 1 tin Zalo/Messenger → hiện trong tab, Open/Dismiss/Action/Reply chạy
- [ ] Media: phát nhạc YouTube Music → tên bài hiện, Play/Pause/Next/Prev/seek chạy
- [ ] Quick: flashlight bật/tắt, volume + brightness kéo có tác dụng, các panel mở đúng
- [ ] Style: đổi cột 4, icon 72dp, tắt nhãn, đổi màu nền, chọn hình nền → quay lại Home thấy đổi ngay; tắt/mở lại app vẫn giữ

### Cover thật (gập máy — 1 đợt duy nhất)
- [ ] Start foreground service → gập máy → Logcat `CFlipFG`: có log display added không?
- [ ] Presentation có hiện trên Flex Window không? Nếu bị chặn, ghi nguyên văn lỗi trong log
- [ ] Mở 1 app từ Presentation — app mở ở cover hay màn hình chính? Ghi lại theo bản One UI hiện tại
- [ ] Đường MultiStar: Good Lock → MultiStar → Launcher Widget → thêm widget CFlip → vuốt cover tới widget, bấm mở được không?
- [ ] Để gập 10 phút → mở ra, service còn sống không? (`adb shell dumpsys activity services com.cuong.cflip`)

Ghi kết quả từng dòng vào cuối file này kèm ngày + số bản One UI — đó là dữ liệu
quyết định Phase tiếp theo (đi sâu Presentation hay chuyển hẳn sang widget).

## 2) Máy ảo (bổ trợ, khi không muốn đụng máy thật)

- Compose Preview: mở `ui/CoverPreviews.kt` — 720×748 (Flip5/6) và 948×1048 (Flip7).
- Emulator: Device Manager tạo AVD theo các profile cũ (Flip5/6 cover 720×748,
  Flip7 cover 948×1048); khi AVD chạy: `Extended Controls > Displays >
  Add secondary display` → Custom 720×748 / 306 dpi để test Presentation +
  `setLaunchDisplayId()` trên display phụ mô phỏng.
- Samsung Remote Test Lab vẫn là phương án test gập nặng trên máy Samsung của hãng.

## 3) Unit test

```bat
gradlew.bat testDebugUnitTest
```
Có test cho `DisplayPicker` và `RecentSorter` (gộp recents từ UsageStats + lịch sử).

## v1.1 - Mirror & Dual Display checks (real Flip5)
1. Open CFlip > Quick tab > scroll to "Mirror & Dual Display".
2. Dual display: turn the switch ON with the phone OPEN - check the cover
   screen lights up with the cover UI while you keep using the main screen.
   (One UI may still refuse; watch Logcat tag CFlipFG.)
3. Rotation: tap 90 - cover content should rotate (watch it in flex mode).
4. Mirror: tap "Bat phan chieu len cover", approve the system capture dialog,
   put the phone in flex/tent mode - the cover should show a live copy of the
   main screen. Rotate via the 0/90/180/270 buttons while mirroring.
5. Fold fully closed while mirroring: mirror is expected to go BLACK (main
   display is off) - that is platform behaviour, not a bug. The normal cover
   Presentation remains the folded-mode experience.
6. Stop mirroring from the same section; the normal cover UI should return.

## v1.2 - No-GoodLock fold experiment (ONE fold)
1. Install the build, open CFlip, start the foreground service.
2. Quick tab > "Bong bong menu": grant overlay permission if asked, switch the
   bubble ON - a blue "C" bubble appears; drag it, tap it, the menu opens.
3. Fold ONCE and look at the cover: (a) bubble visible? (b) cover UI visible?
4. Unfold and run: adb logcat -d -s CFlipFG CFlipBubble
   The decisive lines are "Hinge bucket=0 ... displays: [...]" (the display
   list at the exact fold moment) and any "Bubble"/"Presentation shown" lines.
