---
name: design-review
description: Review Compose UI for modern design quality — Material 3 correctness, semantic design tokens, accessibility (48dp targets, content descriptions, 200% font scale), light/dark and dynamic color, and Wear OS design constraints. Use whenever the user asks about design, styling, theming, colors, accessibility, or "make it look modern" — and proactively after building or reworking any Compose screen in apps/android or apps/wear.
---

# Design review — Compose UI

Design in this repo means: Material 3 done properly, tokens over raw colors, and
accessibility treated as part of the design. Review changed composables unless
asked otherwise.

## Checklist

1. **Material 3 only.** Phone screens use `androidx.compose.material3`; watch
   screens use `androidx.wear.compose:compose-material3` (the Wear component set —
   phone components on Wear are a finding). No Material 2 remnants.
2. **Semantic tokens, not raw colors.** Color semantics in this app are safety
   signals: recording, paused, warning, error, success. Reference design tokens /
   `MaterialTheme.colorScheme` roles; hardcoded `Color(0x...)` needs a semantic
   reason (flag it otherwise).
3. **Theme support.** Light and dark both look right; dynamic color where
   appropriate; edge-to-edge with insets handled.
4. **Accessibility = part of the design:**
   - ≥48dp touch targets (use `minimumInteractiveComponentSize` / padding)
   - content descriptions on non-text controls — the recording status indicator
     and any icon-only button MUST describe state ("Recording", "Paused")
   - font scale to 200% without clipping or fixed-height containers
   - TalkBack order makes sense on the main flows (status → controls)
   - text uses `sp`, not `dp`; spacing uses tokens, not magic numbers
5. **State truth on Wear.** Watch UI renders only *acknowledged* phone state — a
   "Recording" indication that assumes a command succeeded is a design bug.
6. **Compose discipline.** Stable/immutable parameters where recomposition matters,
   `Modifier` parameters on reusable composables, previews for layouts
   (`@Preview` light + dark).

## Report format

```
Design review: <files>
PASS/finding per checklist item, with file:line
Top 3 fixes, in order of user impact
```

Reference for the design roadmap: "Android design system" section of TODO.md.