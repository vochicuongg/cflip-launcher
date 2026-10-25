# ROADMAP — CFlip Launcher

## Phase 0 — Scaffold v0.1 (XONG 17/10/2026, tên cũ MyFlip Launcher)
- [x] Project Kotlin + Compose, khung các subsystem, unit test DisplayPicker, tài liệu test máy ảo

## Đổi tên + v1.0 (17/10/2026)
- [x] Đổi tên thành **CFlip Launcher** (C = Cường), package `com.cuong.cflip`, version 1.0.0
- [x] Quyết định test: máy thật Flip5 trước (theo đợt, gom checklist)

## Phase 1 — Cover UI hoàn chỉnh
- [x] CoverApp 6 tab: Home / Apps / Notif / Media / Quick / Style, vuốt ngang đổi tab
- [x] Icon app thật, tìm kiếm app, favorites (long-press ghim/bỏ), favorites hiện trước (tùy chọn)
- [x] Đồng hồ cover cập nhật theo giây, định dạng 24h/12h tùy chọn

## Phase 2 — Recents + hiển thị màn hình phụ
- [x] Recents: UsageStats + fallback lịch sử mở app trong CFlip (`RecentSorter` có unit test)
- [x] CoverPresentation đầy đủ: đồng hồ, favorites grid, recents, số thông báo, media controls
- [x] Foreground service: DisplayListener tự show Presentation khi có display phụ, tự ẩn khi mất

## Phase 3 — Thông báo + Media hoàn chỉnh
- [x] Danh sách thông báo, Open / Dismiss / action buttons / **inline reply qua RemoteInput**
- [x] Media: metadata, Play/Pause, Next/Prev, seek bar, tự refresh mỗi giây

## Phase 4 — Quick controls + cá nhân hóa
- [x] Flashlight, volume, brightness (qua quyền Modify system settings), panel Wi-Fi/Internet/NFC, Bluetooth, Settings
- [x] System actions qua Accessibility: Lock, Recents, Notifications shade, Quick Settings, Home, Back
- [x] Cá nhân hóa: cột lưới Auto/3–6, cỡ icon 40–84dp, ẩn/hiện nhãn, 5 màu nền, hình nền tự chọn (lưu URI bền vững), favorites first

## Việc còn lại (phụ thuộc kết quả test máy thật)
- [ ] Chạy checklist TESTING.md mục 1 trên Flip5, ghi kết quả theo bản One UI
- [ ] Nếu Presentation bị One UI chặn: chuyển trải nghiệm cover chính sang widget MultiStar nâng cao (widget nhiều shortcut thay vì 1 nút mở app)
- [ ] Tinh chỉnh sau test: tốc độ mở app, mức ngốn pin của foreground service, cỡ chữ trên cover thật

## Ngoài phạm vi (cố ý không làm)
- Không decompile/vá APK của app khác, không bypass billing của bất kỳ app nào
- Không đưa lên Google Play (đang dùng QUERY_ALL_PACKAGES + Accessibility theo kiểu cá nhân)
