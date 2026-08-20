# A2 - Split PfpThemeStore into bundle ownership and applied-look ownership

Source: `docs/feedback/architecture-review-20260820-013228.html`, Candidate 02 (Worth exploring).

## Problem

`PfpThemeStore` (348 lines) is a deep implementation behind a very broad interface. It owns theme
library indexing, SAF reads, bounded bitmap decoding, bitmap lifecycle, sidecar generation, bundle
persistence, export naming, icon extraction, layout sanitization, wave-style mapping, and the
DataStore cascade writes. Meanwhile `ThemesSettingsViewModel` and the XMB preference readers both
carry knowledge of the same DataStore preference keys, so a new theme field means edits in storage,
settings, and XMB.

Its test (`PfpThemeStoreTest`) has to stand up Robolectric, real files, and DataStore to reach any
behaviour, which makes pure decisions (which look wins the cascade) expensive to test.

## Goal

Two cohesive concepts instead of one grab bag:

- `ThemeLibrary` - owns `.pfptheme` bundles: import, validate, list, export, delete, sidecars,
  extracted icons. Talks to storage only through adapters.
- `AppliedLook` - owns what the running app renders: the resolved cascade (colors, wave style,
  layout, icon slots) as a single observable value. Settings and XMB both read this one value and
  neither knows a preference key.

## Approach

1. Introduce `AppliedLook` as a data class plus a pure function `resolveAppliedLook(themeRecord,
   userOverrides): AppliedLook`. Test it with plain JVM tests - no Robolectric.
2. Put storage behind two narrow adapters: `ThemeFileSource` (SAF and file reads, bitmap decode)
   and `AppliedLookStore` (the DataStore cascade read/write). Both are interfaces with a real
   implementation and a fake for tests.
3. Split `PfpThemeStore` in place: move bundle operations to `ThemeLibrary`, cascade operations to
   an `AppliedLookRepository` that exposes `Flow<AppliedLook>` plus `apply(themeId)` and
   `override(field, value)`.
4. Replace every direct preference-key read in `ThemesSettingsViewModel` and the XMB readers with
   a collect on `Flow<AppliedLook>`. Grep for the key names to prove none remain outside the store.
5. Do not design the final interface before step 1 lands - the review explicitly warns against
   picking the shape up front.

## Files touched

- `PfpThemeStore.kt`, `PfpThemeCodec.kt`, `SafeMedia.kt`
- `ThemesSettingsViewModel.kt`, `ThemesSettingsScreen.kt`
- XMB preference readers (find via a grep for the theme DataStore keys)
- `PfpThemeStoreTest.kt` splits into `AppliedLookTest` (pure) plus a slim adapter test

## Tests

- Pure: cascade precedence, wave-style mapping, layout sanitization, icon-slot fallback, a theme
  missing every optional field, a wave-only bundle (already covered by
  `1e7d9f2 test(themes): Cover wave-only .pfptheme import` - keep that case green)
- Adapter: one Robolectric test each for SAF read and DataStore write
- Regression: import, apply, export, delete round trip

## Risks

- Highest-risk plan in this batch. Theme application was just hardened in `39438c2`; a refactor can
  silently undo that. Land it behind a checklist of manual theme applications on a real device.
- The Theme Studio (`studio`) module shares `core:theme-kit`. Keep every change on the Android side
  of the seam or the desktop build breaks.

## Done when

No file outside the theme module references a theme DataStore key, the applied-look decisions have
JVM-only tests, and importing plus applying a real `.pfptheme` still works end to end.
