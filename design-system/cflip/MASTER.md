# Design System Master File

> **LOGIC:** When building a specific page, first check `design-system/pages/[page-name].md`.
> If that file exists, its rules **override** this Master file.
> If not, strictly follow the rules below.

---

**Project:** CFlip
**Generated:** 2026-11-14 10:16:15
**Category:** Wallpaper & Theme App

---

## Global Rules

### Color Palette

| Role | Hex | CSS Variable |
|------|-----|--------------|
| Primary | `#7C3AED` | `--color-primary` |
| On Primary | `#FFFFFF` | `--color-on-primary` |
| Secondary | `#EC4899` | `--color-secondary` |
| On Secondary | `#000000` | `--color-on-secondary` |
| Accent/CTA | `#2563EB` | `--color-accent` |
| On Accent/CTA | `#FFFFFF` | `--color-on-accent` |
| Background | `#FAF5FF` | `--color-background` |
| Foreground | `#0F172A` | `--color-foreground` |
| Card | `#FFFFFF` | `--color-card` |
| Card Foreground | `#0F172A` | `--color-card-foreground` |
| Muted | `#F7F3FD` | `--color-muted` |
| Muted Foreground | `#475569` | `--color-muted-foreground` |
| Border | `#EFE7FC` | `--color-border` |
| Destructive | `#DC2626` | `--color-destructive` |
| On Destructive | `#FFFFFF` | `--color-on-destructive` |
| Ring | `#7C3AED` | `--color-ring` |

**Color Notes:** Aesthetic purple + trending pink

### Typography

- **Heading Font:** Russo One
- **Body Font:** Chakra Petch
- **Mood:** gaming, bold, action, esports, competitive, energetic
- **Google Fonts:** [Russo One + Chakra Petch](https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@300;400;500;600;700&family=Russo+One&display=swap)

**CSS Import:**
```css
@import url('https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@300;400;500;600;700&family=Russo+One&display=swap');
```

### Spacing Variables

| Token | Value | Usage |
|-------|-------|-------|
| `--space-xs` | `4px` / `0.25rem` | Tight gaps |
| `--space-sm` | `8px` / `0.5rem` | Icon gaps, inline spacing |
| `--space-md` | `16px` / `1rem` | Standard padding |
| `--space-lg` | `24px` / `1.5rem` | Section padding |
| `--space-xl` | `32px` / `2rem` | Large gaps |
| `--space-2xl` | `48px` / `3rem` | Section margins |
| `--space-3xl` | `64px` / `4rem` | Hero padding |

### Shadow Depths

| Level | Value | Usage |
|-------|-------|-------|
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,0.05)` | Subtle lift |
| `--shadow-md` | `0 4px 6px rgba(0,0,0,0.1)` | Cards, buttons |
| `--shadow-lg` | `0 10px 15px rgba(0,0,0,0.1)` | Modals, dropdowns |
| `--shadow-xl` | `0 20px 25px rgba(0,0,0,0.15)` | Hero images, featured cards |

---

## Component Specs

### Buttons

```css
/* Primary Button */
.btn-primary {
  background: #2563EB;
  color: #FFFFFF;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  transition: all 200ms ease;
  cursor: pointer;
}

.btn-primary:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

/* Secondary Button */
.btn-secondary {
  background: transparent;
  color: #7C3AED;
  border: 2px solid #7C3AED;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  transition: all 200ms ease;
  cursor: pointer;
}
```

### Cards

```css
.card {
  background: #FAF5FF;
  border-radius: 12px;
  padding: 24px;
  box-shadow: var(--shadow-md);
  transition: all 200ms ease;
  cursor: pointer;
}

.card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}
```

### Inputs

```css
.input {
  padding: 12px 16px;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  font-size: 16px;
  transition: border-color 200ms ease;
}

.input:focus {
  border-color: #7C3AED;
  outline: none;
  box-shadow: 0 0 0 3px #7C3AED20;
}
```

### Modals

```css
.modal-overlay {
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
}

.modal {
  background: white;
  border-radius: 16px;
  padding: 32px;
  box-shadow: var(--shadow-xl);
  max-width: 500px;
  width: 90%;
}
```

---

## Style Guidelines

**Style:** Vibrant & Block-based

**Keywords:** Bold, energetic, playful, block layout, geometric shapes, high color contrast, duotone, modern, energetic

**Best For:** Startups, creative agencies, gaming, social media, youth-focused, entertainment, consumer

**Key Effects:** Large sections (48px+ gaps), animated patterns, bold hover (color shift), scroll-snap, large type (32px+), 200-300ms

### Page Pattern

**Pattern Name:** Hero + Testimonials + CTA

- **Conversion Strategy:** Social proof before CTA. Use a concise set of verified testimonials with photo, name, and role. CTA after social proof. Provide previous/next and pause controls; stop rotation on focus, hover, and reduced motion; announce slide position. Previous/next buttons and keyboard controls must expose every slide without dragging.
- **CTA Placement:** Hero (sticky) + Post-testimonials
- **Section Order:** Hero > Problem statement > Solution overview > Testimonials carousel > CTA

---

## Anti-Patterns (Do NOT Use)

- ❌ Muted colors
- ❌ Low energy

### Additional Forbidden Patterns

- ❌ **Emojis as icons** — Use SVG icons (Heroicons, Lucide, Simple Icons)
- ❌ **Missing cursor:pointer** — All clickable elements must have cursor:pointer
- ❌ **Layout-shifting hovers** — Avoid scale transforms that shift layout
- ❌ **Low contrast text** — Maintain 4.5:1 minimum contrast ratio
- ❌ **Instant state changes** — Always use transitions (150-300ms)
- ❌ **Invisible focus states** — Focus states must be visible for a11y

---

## Pre-Delivery Checklist

Before delivering any UI code, verify:

- [ ] No emojis used as icons (use SVG instead)
- [ ] All icons from consistent icon set (Heroicons/Lucide)
- [ ] `cursor-pointer` on all clickable elements
- [ ] Hover states with smooth transitions (150-300ms)
- [ ] Light mode: text contrast 4.5:1 minimum
- [ ] Focus states visible for keyboard navigation
- [ ] `prefers-reduced-motion` respected
- [ ] Responsive: 375px, 768px, 1024px, 1440px
- [ ] No content hidden behind fixed navbars
- [ ] No horizontal scroll on mobile

---

## CFlip adaptation (vc242, 2026-11-14) — OVERRIDES the generated palette above

The search tool returned a web landing-page system (Vibrant & Block-based,
purple #7C3AED + pink #EC4899, light background, Russo One / Chakra Petch).
That does NOT fit a dark AMOLED cover launcher, so it is adapted, not applied:

- **Identity kept:** CFlip's established dark theme wins — near-black bg
  `#08090A`, mint accent `#7FE3C0` / deep `#2FBF8F` / dark `#07271C`,
  text `#F5F7F6` / `#9AA8A1` / `#66766E`, glass cards (white 7% + hairline).
  The generated purple/pink is REJECTED (would clash with the mint brand
  and fail contrast on the dark cover). See `ui/Design.kt` (CFlipColors).
- **Typography kept:** system sans (no custom font on the cover — the
  subset SF font lives in LiveBridge, not here); sizes from `ui/theme/Theme.kt`.
- **Motion (new in vc242, `ui/Motion.kt`):** shared tokens — press spring
  0.95 / Fast 150ms / Medium 250ms / Slow 300ms / stagger 30ms (cap 12).
  Animate only alpha, offset, scale, colour. Never width/height. Respect
  reduced motion via animator-duration-scale == 0.
- **Logo (vc242):** flip-phone mark — two rounded panels + hinge ticks,
  mint strokes on `#111111` (`res/drawable/ic_launcher_foreground.xml`,
  traced from `icon-master.png` (kept at ~/workspace/cflip-vc242-logo/, outside the repo because the snapshot push rejects binaries). Stroke-only, no fill.
- **Cover constraints (see pages/cover.md):** 720x748, 128dp swipe bar,
  one-hand use, touch targets >= 48dp, 4/8dp spacing rhythm.

- **vc257 FLAT BACKGROUND + THEME + LIQUID GLASS (user, 15/11) —
  overrides the layered background above:**
  - In-app background is ONE FLAT COLOUR per theme, nothing else:
    Dark `#08090A`, Light `#F1F4EF`. The 3-layer background (L0
    gradient + L1 TopGlow + L2 blobs with morph/drift) is deleted
    from MainScreen; the earlier "blobs always round" idea is void
    (blobs are gone). Other in-app screens use the same flat
    `CFlipColors.Bg`.
  - Theme: `settings.themeMode` — Dark (default, today's look) /
    Light / Follow system ("theo ứng dụng" read as Follow system;
    confirm with the user) — picked in the Style island
    ("GIAO DIỆN"). `CFlipScheme` (Dark/Light) in `ui/Design.kt`,
    provided by MainScreen via `LocalCFlipScheme`; `CFlipColors`
    reads it through @Composable getters. The cover always stays
    dark (showCover re-provides Dark); the View panel is untouched.
  - Light palette (measured, text pairs >= 4.5:1): bg `#F1F4EF`,
    text `#132019` / `#54655C` / `#5A6C61`, accent `#0E7A5C`
    (darkened mint — light `#7FE3C0` is 1.6:1 as text on light),
    on-accent white, danger `#C93A26`, glass fill white 60%.
  - Liquid glass (`GlassCard`, 28dp corners): translucent fill +
    gradient hairline + specular wash over the top edge + soft
    shadow. On the flat background there is nothing to blur, so
    the rim + highlight + shadow carry the glass read — no blur
    is faked. Skill search for glassmorphism in the
    jetpack-compose stack returned 0 matches; tokens are CFlip's
    own (repo GlassCard + contrast math).
  - CẢNH BÁO warning card: NEVER blurred / glass — solid danger
    tint + danger border, exactly as before (user constraint,
    15/11).
  - Quick Panel tabs (View, not Compose): slide + fade by
    direction (28dp, 190ms, DecelerateInterpolator; was alpha-only
    150ms), reduced motion shows the tab at once.
