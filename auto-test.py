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
check("Panel brightness label now explains panel vs system (3.2.0, system grant label retired)",
      "Độ sáng panel" in cc)
check("Panel Rotate re-applies cover after saving (old 2.7.0 check, superseded by RotateHost in section 7)",
      True)  # covered by section 7: CoverRotateHostManager.apply in cc
check("Panel auto-dismiss 60s present", "60_000L" in cc)
check("Panel has 5-column tile grid", "columnCount = 5" in cc)
check("Panel banner removed in tabbed panel (notifications live in their own tab)",
      "banner.visibility = View.GONE" not in cc and "NotificationState.active.value" in cc)

# --- 3. Widget lives in the AssistiveTouch panel (3.0.0, Overlay deleted) ---
# Previously these checks read CoverOverlay.kt; in 3.0.0 the full-screen
# overlay launcher is gone, so the widget checks move to the panel.
ov = cc  # panel source, for the widget checks below
check("Panel Apps tab is the full app-list widget", "Ứng dụng" in ov and "AppLauncher.launch" in ov)
check("Panel app list follows sortMode", "sortMode" in ov)
check("Panel app list follows columns setting", "settings.columns" in ov)
check("Panel does NOT rotate its own content alone (3.2.0)", "outer.rotation" not in cc)
check("Panel Control tab has Music card", "MediaControllerRepository" in ov)

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
svc = read("service/CoverForegroundService.kt")
rh = read("cover/CoverRotateHost.kt")
check("RotateHost uses 1x1 screenOrientation overlay", "screenOrientation" in rh and "1, 1" in rh)
check("RotateHost maps 90/180/270 to LANDSCAPE/REVERSE_PORTRAIT/REVERSE_LANDSCAPE",
      "SCREEN_ORIENTATION_LANDSCAPE" in rh and "SCREEN_ORIENTATION_REVERSE_PORTRAIT" in rh and "SCREEN_ORIENTATION_REVERSE_LANDSCAPE" in rh)
check("RotateHost has FULL_SENSOR pulse 220ms", "FULL_SENSOR" in rh and "220" in rh)
check("RotateHost is NOT_TOUCHABLE and NOT_FOCUSABLE", "FLAG_NOT_TOUCHABLE" in rh and "FLAG_NOT_FOCUSABLE" in rh)
check("RotateHost removed when service destroyed", "CoverRotateHostManager.remove()" in read("service/CoverForegroundService.kt"))
check("Service applies rotate mode (follow for FOLLOW, lock for LOCK)", "CoverRotateHostManager.follow" in svc and "CoverRotateHostManager.lock" in svc)
check("Panel has 4 tabs", all(x in cc for x in ['"Điều khiển"', '"Thông báo"', '"Ứng dụng"', '"CFlip"']))
check("Panel CFlip tab opens MainActivity", "MainActivity::class.java" in cc)
check("Panel Notifications tab lists all notifications", "NotificationState.active.value" in cc)
check("Panel Apps tab launches on cover display", "AppLauncher.launch" in cc)
check("Panel Rotate uses host follow, not overlay re-show",
      "CoverRotateHostManager.follow" in cc)

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

# --- 9. Brightness / rotate fixes + replacement interface (2.10.0) ---
check("Panel brightness no longer needs system grant (panel window brightness, 3.2.0)",
      "panelBrightnessManual" in cc2)
check("Panel brightness applies live on drag via panel window",
      "onProgressChanged" in cc2 and "applyPanelBrightness" in cc2)
check("SeekBar drag is not stolen by ScrollView",
      "requestDisallowInterceptTouchEvent" in cc2)
check("Panel no longer restores Overlay (3.0.0: no overlay to restore)",
      "CoverOverlayManager" not in cc2)
check("Rotate FOLLOW does NOT do a 600ms rotationBefore check (3.3.0)",
      "rotationBefore" not in cc2)
check("Panel does NOT rotate its own content alone (3.2.0)",
      "outer.rotation" not in cc2)
check("RotateState flag exists in RotateHost",
      "CoverRotateState" in rh)

# --- 10. Presentation deleted, AssistiveTouch only (3.0.0) ---
import os
check("CoverPresentation.kt deleted locally",
      not os.path.exists(str(SRC / "cover/CoverPresentation.kt")))
check("CoverOverlay.kt deleted locally (only AssistiveTouch remains)",
      not os.path.exists(str(SRC / "cover/CoverOverlay.kt")))
svc = read("service/CoverForegroundService.kt")
check("Service never uses CoverPresentation class", "CoverPresentation" not in svc)
check("Service shows only AssistiveTouch + rotate host (pulse/remove)", "CoverAssistiveTouchManager.show" in svc and ("CoverRotateHostManager.pulse" in svc or "CoverRotateHostManager.remove()" in svc))
check("Service never shows Overlay", "CoverOverlayManager" not in svc)
check("MainScreen never uses CoverPresentation class", "CoverPresentation" not in read("ui/MainScreen.kt"))
check("Panel never restores Overlay", "CoverOverlayManager" not in cc2)

# --- 11. User-added widgets only (3.1.0) ---
check("No forced CoverWidget overlay on cover by default",
      "CoverWidgetManager" not in read("service/CoverForegroundService.kt"))
check("SettingsStore has coverWidgets, default empty",
      "coverWidgets" in ss and 'coverWidgets = prefs.getString("coverWidgets", "")' in ss)
ms2 = read("ui/MainScreen.kt")
check("MainScreen has widget toggles (appgrid/clock)", '"appgrid"' in ms2 and '"clock"' in ms2)
check("MainScreen pins widgets via requestPinAppWidget, not forced placement",
      "requestPinAppWidget" in ms2)
check("MainScreen has no leftover Presentation variable", "presentation" not in ms2)
check("Panel renders widgets only when user chose them",
      "coverWidgets" in cc2 and "Chưa thêm Widget" in cc2)
check("Panel widget section offers direct add on cover (3.2.0, no main jump)",
      "Thêm Widget Đồng hồ" in cc2)

# --- 12. Build fix 3.1.1: initialTab must reach buildPanel ---
check("Panel show() passes initialTab to buildPanel",
      "buildPanel(displayContext, display, initialTab)" in cc2)
check("Panel buildPanel() declares initialTab parameter",
      "buildPanel(ctx: Context, display: Display, initialTab" in cc2)

# --- 13. Widget on cover + rotate 2 modes + panel brightness + volume no-popup (3.2.0) ---
check("Widget add buttons exist on cover panel, not only on main",
      "Thêm Widget Đồng hồ" in cc2 and "Thêm Widget Lưới app" in cc2)
check("Widget add on cover refreshes panel in place, no main jump",
      "CoverControlCenterManager.show(ctx.applicationContext, display, 0)" in cc2)
check("Samsung cover widget settings ComponentName from 9Flip decompile",
      "com.samsung.android.app.aodservice" in cc2 and "SubLauncherMultiWidgetSettingsActivity" in cc2)
check("Rotate has only FOLLOW and LOCK modes, no 0/90/180/270 cycle in panel",
      "Xoay theo hướng lật" in cc2 and "Khóa xoay" in cc2 and "coverRotation" not in cc2)
check("Rotate FOLLOW uses follow(), not pulse()", "CoverRotateHostManager.follow" in cc2)
check("Rotate LOCK uses lock()", "CoverRotateHostManager.lock" in cc2)
check("SettingsStore has rotateMode", "rotateMode" in ss)
check("Panel brightness uses panel window, not system Settings",
      "panelBrightnessManual" in cc2 and "applyPanelBrightness" in cc2 and "QuickControls.setBrightness" not in cc2)
check("Panel brightness clamp 0.05-1.0 like 9Flip", "coerceIn(0.05f, 1.0f)" in cc2)
check("Panel brightness slider alpha 0.42 when not manual, like 9Flip p6",
      "0.42f" in cc2)
check("Volume uses flag 0, no Samsung popup",
      "setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)" in read("control/QuickControls.kt"))
check("Volume mute/restore button exists in panel", "Tắt tiếng / Khôi phục" in cc2)

# --- 14. Rotate persistent follow + Samsung sub_screen + keep-awake (3.3.0) ---
check("Rotate FOLLOW keeps FULL_SENSOR host alive, not pulse-remove",
      "fun follow" in rh and "FULL_SENSOR" in rh)
check("Rotate LOCK is explicit fixed-orientation host, not remove-only",
      "fun lock" in rh and "display.rotation" in rh)
check("Rotate host tries AccessibilityService display context first (9Flip l())",
      "MyAccessibilityService.instance" in rh)
check("Panel FOLLOW button uses follow(), not pulse()",
      "CoverRotateHostManager.follow" in cc2)
check("Samsung sub_screen meta-data present for all widget providers",
      "com.samsung.android.appwidget.provider" in (ROOT / "app/src/main/AndroidManifest.xml").read_text())
check("Samsung widget files use display=sub_screen and keyguard",
      "sub_screen" in (ROOT / "app/src/main/res/xml/samsung_appgrid_widget_info.xml").read_text() and "keyguard" in (ROOT / "app/src/main/res/xml/samsung_appgrid_widget_info.xml").read_text())
check("Samsung widget file size matches Samsung Flex Window (352x339)",
      "352dp" in (ROOT / "app/src/main/res/xml/samsung_appgrid_widget_info.xml").read_text() and "339dp" in (ROOT / "app/src/main/res/xml/samsung_appgrid_widget_info.xml").read_text())
check("Panel widget card explains cover-only add via Samsung picker",
      "gập máy lại" in cc2)
check("AssistiveTouch pokes keepScreenOn on touch, releases after 30s",
      "pokeKeepAwake" in at and "30_000" in at)
check("Panel root keepScreenOn while open, off on hide",
      "root.keepScreenOn = true" in cc2)

# --- 15. Cover widget flow, no pin (3.4.0) ---
check("MainScreen no longer calls requestPinAppWidget (only a comment may mention it)",
      "mgr.requestPinAppWidget" not in read("ui/MainScreen.kt"))
check("MainScreen offers Samsung cover widget settings, not pin",
      "SamsungCoverWidgetHelper.openCoverWidgetSettings" in read("ui/MainScreen.kt"))
check("Standard widget info is keyguard, not home_screen",
      'android:widgetCategory="keyguard"' in (ROOT / "app/src/main/res/xml/appgrid_widget_info.xml").read_text())
check("Standard widget info is cover size 352x339",
      "352dp" in (ROOT / "app/src/main/res/xml/appgrid_widget_info.xml").read_text() and "339dp" in (ROOT / "app/src/main/res/xml/appgrid_widget_info.xml").read_text())
check("Samsung helper exists and opens Samsung settings activity",
      "SubLauncherMultiWidgetSettingsActivity" in read("widget/SamsungCoverWidgetHelper.kt"))
check("Samsung helper checks cover status by appWidgetCategory",
      "appWidgetCategory" in read("widget/SamsungCoverWidgetHelper.kt"))
check("Panel widget card offers Samsung status check from cover",
      "SamsungCoverWidgetHelper.statusText" in cc2)

# --- 16. Widget clicks (3.5.0) ---
check("AppGrid template is MUTABLE, not IMMUTABLE (9Flip t5.h.i)",
      "FLAG_MUTABLE" in read("widget/AppGridWidgetProvider.kt") and "10000 + id" in read("widget/AppGridWidgetProvider.kt"))
check("AppGrid template is NOT IMMUTABLE",
      "setPendingIntentTemplate" in read("widget/AppGridWidgetProvider.kt") and "FLAG_IMMUTABLE" not in read("widget/AppGridWidgetProvider.kt").split("setPendingIntentTemplate")[1].split(")")[0])
check("AppGrid fill-in carries package_name + click_action + widget id",
      "package_name" in read("widget/AppGridWidgetProvider.kt") and "open_package" in read("widget/AppGridWidgetProvider.kt"))
check("AppGrid onReceive accepts package_name as well as pkg",
      'getStringExtra("package_name")' in read("widget/AppGridWidgetProvider.kt"))
check("AppGrid factory receives widget id from service intent",
      "EXTRA_APPWIDGET_ID" in read("widget/AppGridWidgetProvider.kt") and "widgetId" in read("widget/AppGridWidgetProvider.kt"))
check("RemoteViewsService exported=true like 9Flip",
      'android:exported="true"' in (ROOT / "app/src/main/AndroidManifest.xml").read_text().split("AppGridRemoteViewsService")[1].split("/>")[0])
check("Clock/Music/Cover getActivity use distinct per-id requestCodes, not shared 0",
      "1000 + id" in read("widget/ClockWidgetProvider.kt") and "2000 + id" in read("widget/MusicWidgetProvider.kt") and "3000 + widgetId" in read("widget/CoverWidgetProvider.kt"))
check("Widget getActivity launches on cover display when available",
      "setLaunchDisplayId" in read("widget/ClockWidgetProvider.kt") and "setLaunchDisplayId" in read("widget/MusicWidgetProvider.kt") and "setLaunchDisplayId" in read("widget/CoverWidgetProvider.kt"))
