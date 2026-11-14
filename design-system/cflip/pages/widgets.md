# Widget + Panel page rules (CFlip) — overrides MASTER.md for widget/panel screens

Covers: the Widget drawer + Notification drawer in the main app, the
Quick Panel tile screen (QuickControlsScreen.kt, cover Quick tab), and
the 4 Samsung cover widgets (RemoteViews XML layouts).

- Identity: same as cover/main-app (MASTER.md "CFlip adaptation") —
  near-black `#08090A`, mint `#7FE3C0` / `#2FBF8F`, text `#F5F7F6` /
  `#9AA8A1` / `#66766E`, glass cards, 20dp island corners. The generated
  web palette (purple/pink, light background, Russo One) is REJECTED
  here too. RemoteViews layouts use the same tokens as raw hex
  (`#F5F7F6` primary, `#9AA8A1` secondary, `#08090A` card bg) because
  RemoteViews cannot read Compose tokens.
- Widget drawer ("Widget màn hình ngoài"): top bar (back + status
  "Đã bật 1 widget" / "Chưa bật widget nào") + 20sp title, then
  sections "TRONG PANEL MÀN HÌNH NGOÀI" (one toggle row: Lưới ứng dụng
  with a static mini-grid preview + mint Switch, 64dp min height) and
  "WIDGET SAMSUNG" (4 info rows, no toggles — only Samsung's picker
  can add them — + the two Samsung actions). Empty panel state is an
  inline card, never a blank screen. Terminology: the app can only
  toggle "appgrid" into the cover panel; "clock" is added only from
  the cover panel itself, and MainScreen still strips a saved "clock"
  key (vc135) — never offer a clock toggle in-app.
- Notification drawer ("Thông báo"): top bar (back + count) + 20sp
  title, compact GlassCards (10dp padding), app icon 40dp + app label
  11sp + title 14sp + text 2 lines + time, action chips (Mở / Trả lời
  / Xoá + per-action chips) at 48dp min height. Unread = mint dot +
  bold title; read = plain. Read state is session-local (remembered
  keys, reset when the drawer closes) — CoverNotification has no read
  field and the listener service is untouched.
- Panel tiles (QuickControlsScreen): 3-column rows, tiles 64dp min
  height, icon 24dp + label 12sp Medium, press scale 0.95 + haptic
  tick, sections "ÂM LƯỢNG · ĐỘ SÁNG" / "MỞ PANEL" / "HỆ THỐNG · CẦN
  ACCESSIBILITY" / "MỞ RỘNG". Tiles that need Accessibility dim to
  50% when it is off (toast on tap stays). Flashlight tile answers
  with a haptic tick. No tile is invented: the 5 "MỞ PANEL" tiles and
  the 4 system tiles are exactly the ones that were there.
- RemoteViews widgets: static look only — RemoteViews cannot run
  Compose animation, so no animation is attempted there. Card bg
  `#16181C` + 1dp mint hairline (`#297FE3C0`); pressed grid cells
  brighten mint (`#337FE3C0`); all text uses the token hexes above.
  Never rename/remove an `@+id` a provider references, never change
  a widget's declared size, never rename a provider.
- Motion: shared tokens only (`ui/Motion.kt`). Drawer rows stagger
  30ms (cap 12); toggle/tiles press 0.95 spring + haptic; reduced
  motion snaps to end state. No AnimatedVisibility inside a Box
  (vc243 lesson), no FittedBox, no Shizuku, no new dependencies.
- Do NOT touch: equalizer bars, rotation icons (awaiting owner's
  files), mirror / notification / reply / accessibility logic.
