# Cue design tokens

`tokens.json` is the single source of truth for Cue's visual language across
Android, iOS and macOS. Each platform renders the tokens with its native toolkit;
nothing is shared at the UI code level.

Principles, borrowed from the best of Paste:

- **Cards carry content.** Every entry is a card on a quiet surface. Cards are
  flat (no shadow) on Android and use system materials on Apple platforms.
- **Search comes first.** The list is for scanning, search is for finding.
- **One accent, used sparingly.** The indigo seed appears on primary actions,
  codes and the countdown ring. Large areas stay neutral.
- **The ring is the brand.** The countdown ring is the app icon, the per-entry
  timer and the keyboard's progress indicator.
- **Codes are numbers, not text.** Bold, tabular digits, grouped the way the
  user chose, big enough to read from across a desk.

## Android mapping

| Token | Where |
| --- | --- |
| `color.light.*`, `color.dark.*` | `app/src/main/res/values/colors.xml` (`md_theme_*`), generated from the seed with Material's Fidelity scheme |
| `color.semantic.code` | `?attr/colorCode` in `themes.xml` |
| `radius.*` | `cue_radius_*` dimens; `ShapeAppearance.Cue.*` wired into the Material shape attributes |
| `type.code.*` | `TextAppearance.Cue.Code.Display / Compact / Inline` |
| `space.*` | `cue_space_*` dimens |
| `brand.gradient`, `brand.mark` | launcher icon layers, `ic_cue_logo`, `ic_cue_mark` |
| `size.countdownRing`, `motion.countdown` | `drawable/progress_ring.xml` on `TotpProgressBar` (list), `CountdownRingView` (keyboard) |

Regenerate the color roles after changing the seed:

```
python -m venv .venv && .venv/bin/pip install materialyoucolor
# then run the snippet in design/regen_colors.py
```
