# A4 - Library projection out of XMBViewModel

Source: `docs/feedback/architecture-review-20260820-013228.html`, Candidate 04 (Speculative).
Depends on: [A1](library-scan-module-plan.md) landing first. The review says explicitly to defer
this until the scan module shape is settled.

## Problem

`XMBViewModel` is 7,282 lines and is the integration point for nearly every concern: music, video,
photos, Discord, achievements, themes, layout editing, input, navigation - and now the library
projection. The Missing-ROM work (`6dbf8d5`) crossed persistence, scan policy, detail-screen launch
guards, pseudo-platform navigation, and rendering, and all of it landed in the same state holder.

Pure helpers are testable, but call-site locality is poor: to understand how the Missing row is
built you read a 7k-line file.

## Goal

A `LibraryProjection` module that observes Room and emits the row model for every library bucket -
All Games, Favorites, Missing, collections, Memory Card rows - including counts, visibility rules,
and pseudo-platform construction. The XMB shell keeps navigation, focus, and rendering.

## Approach

1. Inventory first. List every library-related field, flow, and function currently in
   `XMBViewModel` with line numbers. Do not start moving code before this list exists - it is the
   scope contract and the review checklist.
2. Define the output type: `LibraryRows(platforms, allGames, favorites, missing, collections)`
   where each entry is a plain row model with id, title, artwork handle, count, and a
   `pseudoPlatform` marker. No Compose types, no `XMBState`.
3. Build `LibraryProjection` as a class exposing `Flow<LibraryRows>`, composed from the existing
   Room flows. Move visibility rules (Missing shown only when non-empty, Missing games excluded
   from platform counts) into it.
4. Swap `XMBViewModel` to collect that single flow. Delete the fields the inventory marked as
   moved, one bucket at a time, keeping the app compiling between steps.
5. Leave the launch guards in the detail screen. They are a launch concern, not a projection
   concern - they belong with [B1](launch-reliability-plan.md).

## Files touched

- New: `feature/feature-xmb/.../library/LibraryProjection.kt`, `LibraryRows.kt`
- Edit: `XMBViewModel.kt` (should shrink by 400+ lines), `XMBItemList.kt`
- New: `feature/feature-xmb/src/test/.../library/LibraryProjectionTest.kt`

## Tests

- Missing row is absent when nothing is missing and present when something is
- platform counts exclude missing games (matches `countGamesByPlatform`)
- a game present in both Favorites and a collection appears in both without duplication
- pseudo-platform rows sort where the current UI puts them
- an empty library emits an empty row set, not a crash

## Risks

- Highest chance of visual regression of any plan here, because the XMB shell is the product.
  Capture before/after screenshots of every category row.
- Scope creep is the real danger: it is tempting to also move music/video/photo rows. Do not.
  This plan is library buckets only.

## Done when

Library row construction is discoverable in one file with its own tests, `XMBViewModel` no longer
observes Room directly for library buckets, and the XMB looks pixel-identical.
