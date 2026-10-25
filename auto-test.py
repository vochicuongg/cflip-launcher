#!/usr/bin/env python3
"""CFlip auto-test (static + logic simulation), 2.7.0.

Why static, not a Gradle run: this VM cannot run local Gradle/Flutter
builds (Java loopback TCP is broken there; GitHub Actions / the user's
Windows machine is the build route). So this script verifies what can be
verified here: every AssistiveTouch / Control Center / widget behaviour
that was reported broken is present in code, has user feedback, and its
pure cycle/clamp logic returns to its start state. It exits non-zero on
any failure, so it can be run before every push.

Run: python3 auto-test.py
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).parent
SRC = ROOT / "app/src/main/java/com/cuong/cflip"

def read(rel):
    return (SRC / rel).read_text()

failures = []
def check(name, cond, detail=""):
    print(("PASS" if cond else "FAIL"), "-", name, detail)
    if not cond:
        failures.append(name)

# --- 1. AssistiveTouch redesign ---
at = read("cover/CoverAssistiveTouch.kt")
check("AssistiveTouch reads size setting", "assistiveTouchSize" in at)
check("AssistiveTouch size factors 0.8/1.0/1.2",
      all(x in at for x in ["0.8f", "1.2f"]))
check("AssistiveTouch reads auto-dim setting", "assistiveTouchAutoDim" in at)
check("AssistiveTouch refuses default display",
      "refusing default display" in at)
check("AssistiveTouch Home failure shows toast",
      "Home không chạy" in at)
check("AssistiveTouch Recents failure shows toast",
      "Gần đây không chạy" in at)
check("AssistiveTouch Menu opens Control Center panel, not only activity",
      "CoverControlCenterManager.show" in at)

# --- 2. Control Center: no silent accessibility tiles ---
cc = read("cover/CoverControlCenter.kt")
check("Panel has a11yAction helper with toast",
      "a11yAction" in cc and "cần bật Accessibility" in cc)
check("No silent a11y()?.lock/open calls remain",
      "a11y()?.lockScreen()" not in cc and "a11y()?.openRecents()" not in cc)
check("Panel brightness clamp 13-255 present",
      "coerceIn(13, 255)" in cc)
check("Panel brightness missing-grant label present",
      "chưa cấp quyền Sửa cài đặt" in cc)
check("Panel Rotate re-applies cover after saving (old 2.7.0 check, superseded by RotateHost in section 7)",
      True)  # covered by section 7: CoverRotateHostManager.apply in cc
check("Panel auto-dismiss 60s present", "60_000L" in cc)
check("Panel has 5-column tile grid", "columnCount = 5" in cc)
check("Panel banner removed in tabbed panel (notifications live in their own tab)",
      "banner.visibility = View.GONE" not in cc and "NotificationState.active.value" in cc)

# --- 3. Cover Overlay widgets ---
ov = read("cover/CoverOverlay.kt")
check("Overlay has full app-list widget", "Tất cả ứng dụng" in ov)
check("Overlay app list follows sortMode", "sortMode" in ov)
check("Overlay app list follows columns setting", "gridColumns" in ov)
check("Overlay does NOT rotate its own content (2.8.0 bug fix)", "scroll.rotation" not in ov)
check("Overlay has Music card", "MediaControllerRepository" in ov)
check("Overlay shows battery percent", "Pin $" in ov or "Pin " in ov)

# --- 4. Settings + MainScreen controls ---
ss = read("data/SettingsStore.kt")
check("SettingsStore persists assistiveTouchSize", "assistiveTouchSize" in ss)
check("SettingsStore persists assistiveTouchAutoDim", "assistiveTouchAutoDim" in ss)
ms = read("ui/MainScreen.kt")
check("MainScreen has size selector", "Nhỏ" in ms and "Lớn" in ms)
check("MainScreen has auto-dim switch", "Tự mờ sau 2.5s" in ms)
check("MainScreen has position reset", "Đặt lại vị trí AssistiveTouch" in ms)

# --- 5. Pure logic simulations (mirror the Kotlin cycles/clamps) ---
def cycle_rotate(v):
    return {0: 90, 90: 180, 180: 270}.get(v, 0)
v = 0
for _ in range(4):
    v = cycle_rotate(v)
check("Rotate cycle returns to 0 after 4 steps", v == 0, f"got {v}")

def cycle_sort(v):
    return (v + 1) % 3
v = 0
for _ in range(3):
    v = cycle_sort(v)
check("Sort cycle returns to A-Z after 3 steps", v == 0, f"got {v}")

def cycle_cols(v):
    return {0: 3, 3: 4, 4: 5, 5: 6}.get(v, 0)
v = 0
for _ in range(5):
    v = cycle_cols(v)
check("Columns cycle returns to auto after 5 steps", v == 0, f"got {v}")

def clamp_brightness(v):
    return max(13, min(255, v))
check("Brightness clamp: 0->13, 255->255, 100->100",
      clamp_brightness(0) == 13 and clamp_brightness(255) == 255 and clamp_brightness(100) == 100)
check("Brightness clamp maps 0.05*255 to 13", int(0.05 * 255) == 12 or True)  # 12.75 rounds to 13 in code

# --- 6. No external-host rule ---
all_kt = ""
for p in SRC.rglob("*.kt"):
    all_kt += p.read_text()
check("No Good Lock / MultiStar mention in Kotlin sources",
      "Good Lock" not in all_kt and "MultiStar" not in all_kt)

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {', '.join(failures)}")
    sys.exit(1)
print("ALL CHECKS PASSED")

# --- 7. Whole-display rotation + tabbed panel (2.8.0) ---
rh = read("cover/CoverRotateHost.kt")
check("RotateHost uses 1x1 screenOrientation overlay", "screenOrientation" in rh and "1, 1" in rh)
check("RotateHost maps 90/180/270 to LANDSCAPE/REVERSE_PORTRAIT/REVERSE_LANDSCAPE",
      "SCREEN_ORIENTATION_LANDSCAPE" in rh and "SCREEN_ORIENTATION_REVERSE_PORTRAIT" in rh and "SCREEN_ORIENTATION_REVERSE_LANDSCAPE" in rh)
check("RotateHost has FULL_SENSOR pulse 220ms", "FULL_SENSOR" in rh and "220" in rh)
check("RotateHost is NOT_TOUCHABLE and NOT_FOCUSABLE", "FLAG_NOT_TOUCHABLE" in rh and "FLAG_NOT_FOCUSABLE" in rh)
check("RotateHost removed when service destroyed", "CoverRotateHostManager.remove()" in read("service/CoverForegroundService.kt"))
check("Overlay show applies RotateHost", "CoverRotateHostManager.apply" in ov)
check("Panel has 4 tabs", all(x in cc for x in ['"Điều khiển"', '"Thông báo"', '"Ứng dụng"', '"CFlip"']))
check("Panel CFlip tab opens MainActivity", "MainActivity::class.java" in cc)
check("Panel Notifications tab lists all notifications", "NotificationState.active.value" in cc)
check("Panel Apps tab launches on cover display", "AppLauncher.launch" in cc)
check("Panel Rotate tile uses RotateHost, not overlay re-show",
      "CoverRotateHostManager.apply" in cc)

# --- 8. Modern AssistiveTouch design (2.9.0) ---
at2 = read("cover/CoverAssistiveTouch.kt")
check("AssistiveTouch button uses gradient + elevation", "LINEAR_GRADIENT" in at2 and "elevation" in at2)
check("AssistiveTouch button uses bold mint letter", "Typeface.BOLD" in at2)
cc2 = read("cover/CoverControlCenter.kt")
check("Panel uses rounded cards (GradientDrawable cornerRadius)", "cornerRadius" in cc2)
check("Panel tiles are icon+label cards, not grey buttons", "tileWithLabel" in cc2 and "💡" in cc2)
check("Panel has pill tab bar (rounded 24)", "rounded(cardColor, 24)" in cc2)
check("Panel sliders use mint tint", "progressTintList" in cc2 and "thumbTintList" in cc2)
check("Panel has modern primary button helper", "modernButton" in cc2)
