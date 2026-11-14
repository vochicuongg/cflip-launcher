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
  `#66766E`, glass cards, 20dp island corners. The generated web palette
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
  (`rememberReducedMotion()`) snaps everything to its end state,
  including the ambient background blobs.
- Press feedback: every tappable row/card answers within 150ms
  (ripple + press scale 0.95 where a shared interaction source
  exists). Touch targets >= 48dp.
- Do NOT touch: equalizer bars, rotation icons (awaiting owner's
  files), mirror / notification / reply / accessibility logic.
  No Shizuku, ever. No FittedBox around existing layouts.
  No new dependencies; only Compose APIs already in BOM 2024.09.
