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

Known gap: the home screen widget is inflated by the launcher, which ignores
`fontFamily` in RemoteViews on current Pixel launchers, so widget text falls back
to the system font (bold). Everything else renders in Cue Display.


| Token | Where |
| --- | --- |
| `color.light.*`, `color.dark.*` | `app/src/main/res/values/colors.xml` (`md_theme_*`), generated from the seed with Material's Fidelity scheme |
| `color.semantic.code` | `?attr/colorCode` in `themes.xml` |
| `radius.*` | `cue_radius_*` dimens; `ShapeAppearance.Cue.*` wired into the Material shape attributes |
| `type.family.display` | `res/font/cue_display.xml` (Manrope statics renamed "Cue Display"; license in `fonts/`) |
| `type.code.*` | `TextAppearance.Cue.Code.Display / Compact / Inline` |
| `type.wordmark` | `TextAppearance.Cue.Wordmark`, `ic_cue_logo_small` / `ic_cue_logo_toolbar` |
| `brand.rule` | `drawable/cue_brand_rule.xml` |
| `shape.chip` | `Widget.Cue.Chip` (set as the theme's `chipStyle`) |
| keyboard sheet, card rows, hero | `ime_panel_background`, `cue_card_background`, `cue_card_background_hero` |
| `space.*` | `cue_space_*` dimens |
| `brand.gradient`, `brand.mark` | launcher icon layers, `ic_cue_logo`, `ic_cue_mark` |
| `size.countdownRing`, `motion.countdown` | `drawable/progress_ring.xml` on `TotpProgressBar` (list), `CountdownRingView` (keyboard) |

Regenerate the color roles after changing the seed:

```
python -m venv .venv && .venv/bin/pip install materialyoucolor
# then run the snippet in design/regen_colors.py
```
