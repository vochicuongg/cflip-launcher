# Main-app page rules (CFlip) — overrides MASTER.md for the in-app screens

Main app = CFlip opened on the big phone screen (Flip5 unfolded,
~360–400dp wide). This is the hub the user configures CFlip from — NOT
the cover. Cover rules live in pages/cover.md and still apply to
CoverApp / CoverLauncherScreen / MediaScreen / NotificationsScreen.

- Canvas: phone portrait, `statusBarsPadding()` on the root Column,
  20dp horizontal gutters, 24dp between sections, 4/8dp rhythm.
  Long text never runs edge-to-edge; hint text max ~2 lines.
- Identity: same as cover (MASTER.md "CFlip adaptation") — near-black
  `#08090A`, mint `#7FE3C0` / `#2FBF8F`, text `#F5F7F6` / `#9AA8A1` /
  `#66766E`, glass cards, 28dp island corners (vc257, was 20dp). The generated web palette
  (purple/pink, light background, Russo One) is REJECTED here too.
- Hero: wordmark "CFlip" (19sp bold) + version pill; no tagline
  paragraph, no decorative hero image.
- Hierarchy: one idea per island — CẢNH BÁO (danger tint) first,
  then Quyền, Trạng thái, Ứng dụng, Widget, Quick Panel. A label sits
  8dp above its card, never detached.
- App drawer ("Tất cả ứng dụng"): search field (rounded 20dp, leading
  search icon, placeholder "Tìm ứng dụng…"), sort segmented control
  (A–Z / Mới mở / Mới cài — the same `settings.sortMode` the cover
  reads, so cover and drawer always agree), 4-column adaptive grid
  (min 80dp cells, 56dp icons, 12sp labels, 1 line + ellipsis),
  favourite star top-end, unread badge top-start (same pop pattern as
  the cover badge, vc243). Empty search shows EmptyState with a
  suggestion, never a blank screen.
- Motion: shared tokens only (`ui/Motion.kt` — press 0.95 spring,
  Fast 150 / Medium 250 / Slow 300ms, stagger 30ms cap 12). Hub
  sections enter staggered 40ms apart (cap 6); drawer cells reuse
  the cover stagger. Animate only alpha, offset, scale, colour —
  never width/height. Entrance runs ONCE per app launch (remembered
  flag, survives returning from cover/drawer/editor); reduced motion
  (`rememberReducedMotion()`) snaps everything to its end state.
- Press feedback: every tappable row/card answers within 150ms
  (ripple + press scale 0.95 where a shared interaction source
  exists). Touch targets >= 48dp.
- Do NOT touch: equalizer bars, rotation icons (awaiting owner's
  files), mirror / notification / reply / accessibility logic.
  No Shizuku, ever. No FittedBox around existing layouts.
  No new dependencies; only Compose APIs already in BOM 2024.09.

- Widget drawer ("Widget màn hình ngoài", vc245): same top bar +
  title pattern as the app drawer; section "TRONG PANEL MÀN HÌNH
  NGOÀI" holds the single in-app toggle (Lưới ứng dụng, static mini
  preview + mint Switch), section "WIDGET SAMSUNG" lists the 4
  providers as info rows (Samsung's picker adds them, never the app).
  Empty panel state is an inline card, never a blank screen.
- Notification drawer ("Thông báo", vc245): compact cards (10dp
  padding), app icon + title + 2-line text + time, action chips at
  48dp, unread = mint dot + bold title (session-local read marks —
  the service has no read field, so marks reset when the drawer
  closes). Hub entry card shows count + latest preview.
- Hub card styles are unified (vc245, glass in vc257): every island uses Island()
  (GlassCard — translucent fill + bright rim + specular + soft shadow) — Permissions no longer has its own flat Surface
  box. Island order: Hero, CẢNH BÁO, Quyền, Trạng thái, Ứng dụng,
  Thông báo, Widget, Quick Panel.
- Touch targets (vc245, pro-rules): every tappable row is >= 48dp —
  PermissionRow (heightIn 48dp), HubSwitch rows (8dp vertical
  padding), size segments + BubbleActionRow + OpenModeRow (12dp
  padding + heightIn 48dp), notification/action chips (heightIn
  48dp), panel tiles (64dp min).

- Sub-screens + micro-interactions (vc248): the big islands were done
  in vc244/vc245; this round syncs the smaller screens to the same
  system — spacing 4/8dp, Island/GlassCard surfaces, title/subtitle
  tiers, 48dp targets, press scale 0.95 + haptic tick on tappable
  controls, shared `EnterItem` entrance (fade + 24px rise, 30ms
  stagger cap 12, reduced-motion snaps to end state) for screen
  sections. Per screen:
  - SettingsScreen (cover STYLE tab): 6 sections enter staggered;
    sort uses the shared SortModePicker (the old inline copy had
    its own 33dp pills); SwitchRow toggles on the whole row, 48dp;
    wallpaper dots keep a 38dp visual on a 48dp hit area; all
    buttons 48dp.
  - GroupEditorScreen: headers enter once per screen open; list
    rows are NOT staggered (LazyColumn recycling would replay the
    entrance mid-scroll) — rows get 48dp buttons + 48dp-tall
    OrderArrows (32dp wide on purpose: 2 arrows + up to 3 buttons
    per row would squeeze the name to nothing at 48dp square);
    empty list / empty trash are centred GlassCards, not bare text;
    member-tile ✕ hit area 32dp (glyph stays 12sp); picker rows
    48dp; footer Hủy/Xong 48dp.
  - MediaScreen: disc enters once per tab visit; title/artist
    crossfade (150ms) on track change. Disc/equalizer visuals
    untouched.
  - NotificationsScreen (cover tab): header enters once; ActionChip
    gains press scale (still 48dp). Rows not staggered (same
    LazyColumn recycling reason).
  - QuickControlsScreen: 5 sections enter staggered; permission
    button 48dp. ControlTile already had press scale + haptic.
  - MirrorControls: now in a GlassCard (was bare text + M3
    buttons); rotation is a segmented pill row (48dp, press scale +
    haptic, mint when selected); mirror buttons 48dp; dual-display
    row toggles on the whole row, 48dp. Strings unchanged
    (including the unaccented ones — pre-existing).
  - MainScreen rows: HubSwitch toggles on the whole row, 48dp;
    BubbleActionRow/OpenModeRow gain press scale; StatColumn value
    crossfades on change.
  - Dialogs: both long-press menus (cover + drawer) now use the
    shared MenuDialog/MenuActionRow (Design.kt) — dark Surface,
    24dp corners, styled title, 48dp action rows with press scale.
    BubbleControls is @Deprecated and unused — kept as-is.
    CoverPreviews is preview-only — kept as-is.

- vc257 FLAT BACKGROUND + THEME + LIQUID GLASS (user, 15/11):
  - Background: ONE FLAT COLOUR per theme, nothing else — no
    gradient, no TopGlow, no blobs (the 3-layer background and its
    morph/drift are deleted; the earlier "blobs always round" idea
    is void). Dark = `#08090A`, Light = `#F1F4EF`. Every in-app
    Compose screen uses the same flat `CFlipColors.Bg`
    (MainScreen, SettingsScreen, GroupEditorScreen,
    RecentAppsScreen, WidgetDrawerScreen).
  - Theme: `settings.themeMode` (Dark / Light / Follow system) in
    the Style island ("GIAO DIỆN", `ThemeModePicker` — same pills
    as SortModePicker). MainScreen provides the scheme via
    `LocalCFlipScheme`; `CFlipColors` reads it through @Composable
    getters, so call sites are unchanged. The cover (showCover
    branch) re-provides Dark — it always stays dark (user
    wallpaper / style colour); the View panel is untouched.
  - Light palette (measured, all text pairs >= 4.5:1): bg
    `#F1F4EF`, text `#132019` / `#54655C` / `#5A6C61`, accent
    `#0E7A5C` (darkened mint — light `#7FE3C0` is only 1.6:1 as
    text on light), on-accent white, danger `#C93A26`.
  - Liquid glass (`GlassCard`, 28dp corners): translucent fill
    (Dark: warm white 9%; Light: white 60%) + gradient hairline
    border + specular wash over the top edge + soft shadow (black
    on dark, soft grey on light). On the flat background there is
    nothing behind the card to blur, so the rim + highlight +
    shadow carry the glass read — no blur is faked. Skill search
    for glassmorphism in the jetpack-compose stack returned 0
    matches, so the tokens are CFlip's own (repo GlassCard +
    contrast math), not the skill's.
  - CẢNH BÁO warning card: NEVER blurred / glass — solid danger
    tint + danger border, exactly as before (user constraint).
