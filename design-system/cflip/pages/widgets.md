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
- vc246 CASSETTE (the reference app lesson, from REA static evidence only — no
  copied assets/code): the reference app's cover music is a Cassette family —
  full + small + small-tape cover variants (+ 3 home variants), each
  a separate provider sharing the Samsung `sub_screen` meta-data,
  plus an AppGrid that is a full launcher surface via
  RemoteViewsService + GridView with folder drill-down (CFlip already
  had the folders). What CFlip learned: (1) music as a physical
  object — the Music widget is now a tape: state eyebrow in words
  (ĐANG PHÁT/TẠM DỪNG, never colour alone), title 16sp/artist 12sp,
  a tape window (two mint reels + tape line, static), a progress bar
  (MediaInfo position/duration, hidden when duration is 0) with
  elapsed/total, and a big 56dp mint play-pause (black icon) flanked
  by 48dp prev/next/refresh; (2) size choice — a compact Cassette
  Small widget (352x160dp, `MusicSmallWidgetProvider`, a subclass
  reusing MusicWidgetProvider's whole code path, only the layout
  differs; broadcasts target `javaClass` so taps land on the right
  subclass; ids absent from the small layout are RemoteViews no-ops);
  (3) glanceability — Clock time 38→44sp + mint accent bar; Cover
  widget gains a now-playing line (GONE when nothing plays) + a
  "YÊU THÍCH" label; AppGrid shares the mint card bg, 44dp icons,
  11sp labels with ellipsis, 10dp grid spacing, round mint refresh.
  What CFlip did NOT learn: the reference app has no dedicated clock widget to
  copy; the reference app's PRO banner / paywall and the reference app's premium quotas
  are not adopted (no paywall by principle); no animated/live
  wallpaper, no floating-menu activity. The skill's 4th web palette
  (purple/pink, Righteous/Poppins) was REJECTED again; the ux search
  for "widget glanceable information density" returned off-topic
  rows, only "don't convey info by colour alone" applied (already
  the rule for unread + now the music state eyebrow).
- Widget drawer preview (WidgetDrawerScreen): mini-grid mock matches
  the real cells — 12dp icon square + 2dp label bar per cell, 2x4,
  mint/grey pattern; Samsung info list now has 5 rows (the small
  cassette added) with matching descriptions.
- vc247 DEAD-WIDGET FIX + DEPTH (verified against the real code, not
  assumed): on vc246 only Cover + AppGrid worked. The split follows
  the refresh path exactly: (1) manifest exported — Cover false,
  AppGrid true, Music/MusicSmall/Clock false; (2) Cover survives
  exported=false because MyNotificationListenerService.kt:66 calls
  CoverWidgetProvider.requestUpdate() on every notification tick;
  (3) ClockWidgetProvider.requestUpdate() existed but had ZERO
  call-sites anywhere in the repo; (4) MusicWidgetProvider had NO
  requestUpdate at all; (5) AppGridWidgetProvider.refreshAll() is
  called from GroupEditorScreen + its own actions. the reference app's evidence
  CUTS AGAINST exported as the sole cause: all 6 of its cassette
  providers are exported=false and they work — the reference app refreshes its
  widgets from inside the app (CoverFloatingMenuReceiver
  SHOW/HIDE/REFRESH). CFlip's Samsung meta-data has NO deviation:
  all 5 samsung_*_info.xml share display="sub_screen", 352dp sizes,
  keyguard category (the reference app shares one samsung info XML across its
  cassettes; CFlip keeps one per widget — equivalent). Fix, both
  directions covered: (a) exported=false → true for exactly Music /
  MusicSmall / Clock (AppGrid/Cover untouched); (b) new
  MusicWidgetProvider.requestUpdate() refreshing BOTH music classes
  with an instance of each class (layoutRes + javaClass broadcasts
  follow the instance); (c) triggers — notification-listener tick
  (Clock + Music, next to Cover's line), CoverControlCenter
  buildPanel after the Tab-1 media refresh (Music), BootReceiver
  after boot/app-update (Clock + Music, runs even when the Quick
  Panel switch is off). All loop-free: requestUpdate only writes
  RemoteViews, never posts a notification or rebuilds the panel.
  Skill searches for this round: "android widget visual hierarchy
  glanceable card depth" --domain ux returned only a VisionOS depth
  layering row (glass/z-offset — applied as layered surfaces) plus
  off-topic hover/breadcrumbs rows; "music widget cassette retro
  design" --domain product returned arcade pixel-art (rejected —
  would break the mint brand) and a wallpaper/glassmorphism row
  (layered surfaces only). No direct cassette match was found, so
  the redesign follows the skill's pro-rules (depth layering, 4/8dp
  rhythm, state never by colour alone, vector-only icons) rather
  than a database hit. Depth, all RemoteViews-legal (gradients,
  layered shapes, chips — no animation): card bg is a 135° gradient
  (#1E2126 → #101214) instead of flat #16181C; tape window a 90°
  gradient (#14181C → #0A0C0D); music state is a solid mint chip
  with black text; clock battery/notif are two dark chips (new
  ic_w_battery vector; bell tinted mint); Cover now-playing is a
  lifted strip with a note icon (new ic_w_note vector) and the
  provider now hides the whole row (id widget_media_row); fav
  slots + app-grid cells are lifted tiles (widget_cell_selector
  default is bg_widget_cell now, pressed still brightens mint);
  header/grid divider hairline in the app grid; cassette gains a
  static "CFLIP · SIDE A" label. The small cassette is NOT given
  the state chip or progress row: at 160dp the layout is already
  full (title + artist + tape + 48/56dp controls) and adding rows
  would clip on the cover — it gains depth from the gradients
  only. The web palette was NOT re-run (rejected 4 times already);
  mint on near-black is kept.
- Motion: shared tokens only (`ui/Motion.kt`). Drawer rows stagger
  30ms (cap 12); toggle/tiles press 0.95 spring + haptic; reduced
  motion snaps to end state. No AnimatedVisibility inside a Box
  (vc243 lesson), no FittedBox, no Shizuku, no new dependencies.
- Do NOT touch: equalizer bars, rotation icons (awaiting owner's
  files), mirror / notification / reply / accessibility logic.
- vc249 PLAIN-VIEW BAN (logcat-proven on Flip5, 14/11/2026): Samsung's
  cover AppWidgetHost refuses to inflate a plain `android.view.View`
  in RemoteViews (`InflateException: Class not allowed to be inflated
  android.view.View`, seen on widget_clock line #23) — one `<View>`
  kills its whole widget. The 4 decorative bars (clock mint rule,
  cassette tape line x2, app-grid header divider) are `<ImageView>`
  now, same size/margins/colours + `contentDescription="@null"`.
  RULE: in `res/layout/widget_*.xml` use ONLY LinearLayout /
  RelativeLayout / FrameLayout / GridLayout / TextView / TextClock /
  ImageView / ImageButton / Button / ProgressBar / GridView /
  ListView. NEVER `<View>`, `<Space>`, `<ViewStub>` or a custom view.
  Grep before every widget push.
