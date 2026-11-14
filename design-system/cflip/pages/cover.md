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

## Density (vc245) — content must fit the 720x748 cover without crowding

Cover space is scarce, so padding/spacing was tightened everywhere
content was loose — but never below a 48dp touch target, and never by
scaling content down with FittedBox (banned since Round 138/139):

| Place | Before | After |
|---|---|---|
| Root column horizontal padding | 12dp | 10dp |
| Status row top padding | 10dp | 8dp |
| Home clock | 56sp | 52sp |
| Home spacers (date / media / hint) | 10 / 10 / 14dp | 8 / 8 / 10dp |
| Search field vertical padding | 8dp | 6dp |
| App grid vertical spacing / cell padding / label gap | 10 / 4 / 3dp | 8 / 2 / 2dp |
| Pill nav outer / inner / tab padding | 10 / 6 / 8dp | 8 / 5 / 7dp |
| Notification title / list spacing / card padding | 20sp / 10dp / 14dp | 18sp / 8dp / 10dp (compact GlassCard) |
| Notification text lines / action row gap | 3 / 8dp | 2 / 6dp |
| Media title / spacers (disc, title, controls) | 20sp / 18 / 16 / 14dp | 18sp / 12 / 12 / 10dp |
| Quick tab root spacing | 10dp | 8dp |

Unchanged on purpose: app icon size (settings-driven, default 56dp),
disc 148dp, RoundControl 72/52dp, tile icon 24dp — these are the
things the finger actually lands on, so they stay big.
