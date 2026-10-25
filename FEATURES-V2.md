# CFlip — Bản đồ tính năng v2 (từ 22/10/2026)

CFlip là bản **tự viết (clean-room)**: đối chiếu *danh mục tính năng* của một
cover launcher mẫu trên thị trường (đã phân tích tĩnh bằng REA/JADX ngày
22/10/2026, báo cáo ở ngoài repo) rồi tự hiện thực bằng code và thiết kế
riêng của CFlip. Không sao chép code, hình ảnh, icon, câu chữ hay giao diện
của app mẫu; mọi tính năng làm được đều mở sẵn, không paywall.

## Đã có (v1.0–v1.2)
- Ngăn kéo app đầy đủ + tìm kiếm, ghim yêu thích (tab Home + Apps)
- Recents (UsageStats + lịch sử mở trong app)
- Thông báo: mở, xoá, bấm action, trả lời trực tiếp (RemoteInput)
- Media controls (play/pause/next/prev, seek, tên bài/nghệ sĩ)
- Quick controls + system actions qua Accessibility (khoá máy, Recents, khay thông báo, Quick Settings)
- Cá nhân hoá cơ bản: cột lưới, cỡ icon, nhãn, màu nền, hình nền cover, đồng hồ 24h
- CoverPresentation + foreground service; widget cho Good Lock/MultiStar
- Bong bóng nổi (bubble menu) — đường không cần Good Lock
- Mirror thử nghiệm (ScreenMirrorService + MirrorPresentation)

## Lộ trình v2 — các tính năng còn thiếu so với bản đồ mẫu
| # | Tính năng | Phiên bản dự kiến | Ghi chú hiện thực riêng của CFlip |
|---|---|---|---|
| 1 | **Hidden Apps** (ẩn app khỏi ngăn kéo) | **v1.3 ✅** | Lọc ngay trong AppRepository nên mọi bề mặt đều tôn trọng; giữ app → menu → Ẩn; quản lý ở tab Style |
| 2 | Chỉ báo pin trên cover (Home + widget) | **2.0 ✅** (Home + thanh trạng thái) | BatteryManager sticky intent, cập nhật 30 giây; widget để sau |
| 1b | **Thiết kế lại toàn bộ giao diện (CFlip 2.0)** | **2.0 ✅** | Design system riêng: thẻ kính (glass card), accent mint, thanh điều hướng dạng pill nổi có huy hiệu thông báo, Home hero (đồng hồ lớn + pin + đang phát + yêu thích), lưới app có sao ghim, Media có đĩa tròn + nút phát lớn, Quick dạng ô gạch — code giao diện tự viết 100% |
| 3 | Custom Groups (nhóm app tuỳ ý, không giới hạn) | v1.4 | Nhóm lưu DataStore-style SharedPreferences; chip chọn nhóm ở tab Apps |
| 4 | Huy hiệu số thông báo trên icon app (badges) | v1.4 | Đếm từ NotificationState theo package |
| 5 | Banner thông báo mới trên cover | v1.4 | Banner tự ẩn 4 giây khi có thông báo mới lúc cover đang hiện |
| 6 | Widget media thế hệ 2 + ảnh bìa album | v1.4 | Thiết kế riêng của CFlip (đĩa tròn xoay theo trạng thái phát) |
| 7 | Window Mode (mở app dạng cửa sổ trên cover) | v1.5 (thử nghiệm) | ActivityOptions.setWindowingMode(FREEFORM) + launch display id; phụ thuộc One UI cho phép hay không |
| 8 | Rotation Helper (ép xoay app trên cover) | v1.5 (thử nghiệm) | Overlay host riêng, học theo *cơ chế* đã quan sát, code tự viết |
| 9 | Hình nền cover động (live wallpaper) | v1.6 | WallpaperService của CFlip |
| 10 | Lắc để mở launcher | v1.6 | Cảm biến gia tốc + ngưỡng chống kích hoạt nhầm |
| 11 | Host activity trampoline cho phiên cover | v1.6 | Activity 1×1 trong suốt + mã phiên chống yêu cầu cũ (stale-request guard) |

## Nguyên tắc giao diện
Đủ các *màn chức năng* tương đương (Home/Apps/Notif/Media/Quick/Style + Groups),
nhưng ngôn ngữ thị giác là của CFlip: accent xanh mint `#7FE3C0`, nền tối,
icon và câu chữ tự viết. Không dùng tài nguyên của app mẫu.
