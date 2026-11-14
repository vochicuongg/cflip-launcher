# Cover page rules (CFlip) — overrides MASTER.md for cover screens

- Canvas: Galaxy Z Flip5 cover 720x748 (Flip 7: 948x1048). One-hand, thumb reach.
- Swipe bar stays 128dp. No content under the pill nav or the status row.
- Touch targets >= 48dp; gaps between tappable items >= 8dp.
- Text: primary >= 4.5:1 on the dark scrim; never grey-on-grey.
- Motion: tab slide 300ms + fade 200ms (existing, vc130); grid stagger 30ms
  per cell capped at 12; badge 150ms; press spring 0.95. Reduced motion
  collapses everything to end state (see ui/Motion.kt).
- Do NOT touch: equalizer bars, rotation icons (awaiting owner's files),
  mirror / notification / reply / accessibility logic. No Shizuku, ever.
- No new dependencies; only Compose APIs already in BOM 2024.09.
