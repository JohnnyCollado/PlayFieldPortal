# B3 - Setup and onboarding friction

Source: `docs/feedback/frontend-user-research.md`, complaint cluster #3 and deal-breaker #4
("Can't complete setup at all"). The corpus notes that even an ES-DE veteran trying PFP "still felt
a bit lost", and Daijisho's archived threads are dominated by setup-help posts.

## Problem

PFP has an `InitialSetupScreen` and `InitialSetupViewModel`, plus a `WizardMediaScanRunner`, so the
skeleton exists. The failure mode the corpus describes is not a missing wizard, it is a wizard that
ends before the user has a working library: folders granted but nothing scanned, ROMs scanned but
no emulator mapped, or an emulator mapped but no core, and no signal telling the user which of
those is the reason their library is empty.

Compounding it: PFP replaces the home screen. A user who is half-configured is stuck looking at a
launcher that does not launch anything, which is also deal-breaker #1.

## Goal

A new user reaches "I picked a game and it ran" without leaving the wizard, and at every moment the
app can state what is still missing and what to tap.

## Approach

### 1. Define setup completeness as a value, not a screen

Add a `SetupState` computed from real data, not from a "wizard finished" flag:

- ROM root granted (yes/no)
- at least one platform with at least one scanned game
- at least one emulator detected and mapped for that platform
- for RetroArch mappings, a core assigned
- artwork scraped for at least one game (optional, non-blocking)

### 2. End the wizard on a real launch

The last wizard step should be "here is a game we found, try launching it". A wizard that ends on
"setup complete" without a launch is exactly what produces the "I still felt lost" report. Reuse
the B1 dispatcher so a failed first launch lands in the recovery sheet rather than a black screen.

### 3. Empty states that name the missing step

Every empty surface - a platform row with no games, an XMB with no platforms - shows the first
unmet condition from `SetupState` and a button that goes straight to the screen that fixes it. No
generic "no games found".

### 4. Lean on what already works

`EmulatorDetector` and `EmulatorAutoConfigService` already exist, and `RomScanner.createSubfolders`
can build the whole ES-DE folder structure under a picked root. Make the wizard offer that
structure-creation path prominently for users with no library yet, since "where do I put my ROMs"
is the single most common setup-help question in the archived Daijisho threads.

### 5. Make setup resumable and re-enterable

A user who bails halfway must be able to return to exactly the unmet step from Settings, and the
wizard must never re-ask for a grant that is already held.

## Files touched

- `InitialSetupScreen.kt`, `InitialSetupViewModel.kt`
- New: `SetupState.kt` plus a `SetupStateProvider` reading grants, games, emulator profiles
- `WizardMediaScanRunner.kt`
- XMB empty-state composables in `feature-xmb`

## Tests

- `SetupState` reports the correct first unmet condition for each partial configuration
- a granted-but-unscanned root reports "scan", not "grant"
- a scanned platform with no emulator reports "emulator", not "scan"
- setup state is derived fresh, so revoking a grant outside the app re-opens the right step
- the wizard never re-requests a grant it already holds

## Risks

- Do not turn this into a blocking gate. A user who wants to skip straight to the XMB must be able
  to; the empty states carry the guidance instead.
- Setup completeness is derived, so it must be cheap. Compute it from counts, not by re-walking
  folders.

## Done when

A fresh install can go from launch to a running game without opening Settings, and every empty
screen names the specific next step.
