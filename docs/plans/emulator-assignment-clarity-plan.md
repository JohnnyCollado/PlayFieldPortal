# B4 - Emulator and core assignment clarity

Source: `docs/feedback/frontend-user-research.md`, complaint cluster #5 ("emulator / core
assignment is fiddly") and must-have #4 ("per-system emulator assignment plus per-game override").
Precedent: Daijisho #726 and #194 (custom player unclear), PFP #10.

## Problem

PFP already has the machinery: `EmulatorProfile` with a `coreMap`, `EmulatorProfileRepository`,
`EmulatorLaunchPreference`, `GameLaunchPreferences`, `RetroArchCoreScanner`, and a
`KnownEmulatorCatalog` of 789 lines. The resolution ladder exists. What the corpus complains about
is that the ladder is invisible: a user looking at a game cannot see which emulator and core will
actually be used, where that decision came from (per-game override, per-platform default, catalog
default), or how to change just that one level.

This is the difference between a feature that exists and a feature users can operate. It also
feeds complaint #1, since "launched into the wrong emulator" is usually "the ladder picked
something I did not know about".

## Goal

The effective emulator and core for any game is visible at a glance, attributed to the level that
decided it, and changeable at that level without side effects on the others.

## Approach

### 1. Make the resolution explainable

Add a pure function that returns not just the winning profile but the reason:
`ResolvedLaunch(profile, core, source)` where `source` is `PerGameOverride`, `PlatformDefault`,
`CatalogDefault`, or `OnlyInstalled`. Everything below already knows this; it just is not returned.

### 2. Show it on game detail

A single line on the game detail screen: emulator name, core name, and the level it came from, with
a tap target to change it. Changing from here writes a per-game override and nothing else.

### 3. A per-platform assignment screen

One row per platform showing the default emulator and core, the number of games it covers, and how
many of those games have a per-game override. Overrides must be visible and clearable in bulk from
here, because an invisible override is the classic "why does only this one game launch wrong".

### 4. Core assignment that reflects reality

`RetroArchCoreScanner` finds cores; `validateBeforeLaunch` already refuses to launch when a
platform has no mapped core. Surface that state before launch: a platform whose default is
RetroArch with no core mapped should be visibly incomplete in the assignment screen, not discovered
at launch time.

### 5. Multi-emulator platforms

Where several installed emulators can run a platform (very common for PSX, PSP, DS), the
assignment screen lists all of them with the catalog's recommendation marked, rather than silently
picking one.

## Files touched

- `EmulatorLaunchPreference.kt`, `EmulatorProfileRepository.kt`, `GameLaunchPreferences.kt`
- `EmulatorsSettingsScreen.kt`, `EmulatorsSettingsViewModel.kt`, `EmulatorProfileEditorScreen.kt`
- `GameDetailScreen.kt`, `GameDetailViewModel.kt`
- New: `ResolvedLaunch.kt` plus its resolver

## Tests

- Resolution source is reported correctly for each ladder level.
- A per-game override wins over a platform default and reports `PerGameOverride`.
- Clearing an override falls back to the platform default, not to the catalog default.
- A platform with a RetroArch default and no mapped core reports as incomplete.
- Bulk-clearing overrides for a platform leaves the platform default untouched.

## Risks

- The resolution ladder is load-bearing for every launch. Extract the explanation without changing
  precedence, and pin the current precedence with tests before touching anything.
- Bulk-clear is destructive to user configuration. Confirm it, and scope it to one platform.

## Done when

Any game shows which emulator and core will run it and why, and a user can change exactly one level
of the ladder without guessing.
