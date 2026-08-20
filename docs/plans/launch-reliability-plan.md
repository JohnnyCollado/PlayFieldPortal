# B1 - Launch reliability and recovery

Source: `docs/feedback/frontend-user-research.md`, complaint cluster #1 and deal-breaker #1.
This is the highest-leverage item in the whole feedback set: it is simultaneously the most common
complaint, a universal must-have, and the top reason users abandon a launcher.

## Problem

Across Daijisho (#703, #889, #579, #562), ES-DE (#2068) and PFP (#16 plus pasted Reddit reports),
the same three failures recur:

1. The game launches into the wrong emulator or the wrong core.
2. RetroArch opens to a black screen the user cannot exit or force-quit, and PFP has no idea
   anything went wrong.
3. After an Android or emulator update the intent stops resolving (`ActivityNotFound`).

PFP already does the right thing up front:
[`EmulatorIntentResolver.validateBeforeLaunch`](../../feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/EmulatorIntentResolver.kt)
checks the emulator is installed, the ROM or launch token exists, and a RetroArch core is mapped,
and returns a readable message instead of throwing. What is missing is everything *after*
`startActivity`: PFP hands off and never looks back. There is no record of whether the launch
actually produced a running game, so a black screen is indistinguishable from a good session.

## Goal

Every launch either works, or fails with a specific message and a repair action the user can take
without leaving PFP. A launch that goes wrong is detected, not silently accepted.

## Approach

### 1. Post-launch verification

After `startActivity`, watch for the emulator actually coming to the foreground within a short
timeout, using the resumed-activity signal PFP already has as a HOME launcher. If it never does, or
if PFP is resumed again almost immediately, record a failed launch and show a recovery sheet
instead of nothing.

### 2. Recovery sheet

One screen, reachable from a failed launch and from game detail, offering:

- Force-stop the emulator via an `ACTION_APPLICATION_DETAILS_SETTINGS` deep link. PFP cannot kill
  another process itself, so send the user one tap away instead of leaving them stuck.
- Pick a different emulator for this game.
- Pick a different core for this platform.
- Copy the launch diagnostic. The resolver already logs the full intent URI, platform, core, and
  ROM path at `Timber.d`.

### 3. Launch outcome history

Persist per-game launch outcomes (`succeeded`, `never_foregrounded`, `intent_failed`) with the
resolved emulator and core. Two payoffs: the recovery sheet can say "this failed the last 3 times
with core X", and a repeat failure can proactively offer the alternate emulator.

### 4. Preflight for the known-bad cases

Extend `validateBeforeLaunch` with the failures the corpus names:

- RetroArch mapped for a platform whose core is not installed. Already messaged; verify the text
  survives on a device where the emulator's private storage is unreadable.
- A SAF `romUri` whose grant has been revoked. Today only parseability is checked, so a revoked
  grant reaches the emulator as an unreadable URI and looks exactly like a black screen.
- An emulator installed but with no exported activity matching the profile's intent type.

### 5. Catch every ActivityNotFoundException

`GameDetailScreen.kt:170` catches it. The launch call sites in `XMBViewModel.kt` (`:2357`, `:6372`,
`:6616`) do not obviously. Audit every `startActivity` on a game path and route all of them through
one `LaunchDispatcher` that catches, records the outcome, and opens the recovery sheet.

## Files touched

- `EmulatorIntentResolver.kt` (preflight additions)
- New: `feature/feature-launcher/.../LaunchDispatcher.kt`, `LaunchOutcomeRecorder.kt`
- New Room table `launch_outcomes` plus a migration
- `GameDetailViewModel.kt`, `GameDetailScreen.kt`, `XMBViewModel.kt` launch call sites
- New: recovery sheet UI in `feature-xmb`

## Tests

- Resolver returns a specific failure for: emulator missing, core unmapped, revoked SAF grant,
  missing ROM file, missing launch token.
- Dispatcher records `intent_failed` on `ActivityNotFoundException` and does not crash.
- Dispatcher records `never_foregrounded` when the emulator never comes to front inside the window.
- A successful launch records `succeeded` and does not open the recovery sheet.
- Outcome history survives a process restart.

## Risks

- Foreground detection is heuristic. Tune the window generously and treat ambiguity as success. A
  false "your launch failed" popup after a good session is worse than missing a real failure.
- Do not add a usage-stats or foreground-service permission for this. The research names trust and
  privacy as deal-breaker #5; asking for usage access to detect black screens trades one
  deal-breaker for another. Use only signals a HOME launcher already has.

## Done when

Every game launch path funnels through one dispatcher, failures produce a named reason plus a
repair action, and the RetroArch no-core and revoked-grant cases are reproducible in tests.
