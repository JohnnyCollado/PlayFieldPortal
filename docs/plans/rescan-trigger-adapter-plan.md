# A3 - One rescan-trigger module behind thin Android adapters

Source: `docs/feedback/architecture-review-20260820-013228.html`, Candidate 03 (Worth exploring).
Depends on: [A1](library-scan-module-plan.md).

## Problem

Three Android entry points each repeat the same boilerplate before reaching the coordinator:

- [MainActivity.kt](../../app/src/main/kotlin/com/playfieldportal/launcher/MainActivity.kt) - resume
- [MediaMountReceiver.kt](../../app/src/main/kotlin/com/playfieldportal/launcher/receiver/MediaMountReceiver.kt) - mount
- [UsbDisconnectReceiver.kt](../../app/src/main/kotlin/com/playfieldportal/launcher/receiver/UsbDisconnectReceiver.kt) - unplug

Each does its own Hilt `EntryPoint` lookup, creates an IO coroutine scope, logs, and swallows
exceptions. `LibraryRescanCoordinator` owns throttle, debounce, and single-flight, but the mutable
trigger state is spread across those entry points and the coordinator's own token/time fields.
Receiver lifetime and cancellation cannot be tested through the current interface.

## Goal

Android lifecycle quirks live in adapters. Trigger semantics - normalization, throttling,
debouncing, single-flight, cancellation - live in one testable module that hands one normalized
request to the scan module from A1.

## Approach

1. Define `RescanTrigger` (enum or sealed: `AppResumed`, `MediaMounted`, `UsbDisconnected`) and a
   `RescanTriggerBus` with `fun submit(trigger: RescanTrigger)` and an internal
   `CoroutineScope` supplied by DI, not created per entry point.
2. Move the Hilt `EntryPoint` lookup into one shared helper the three adapters call, so each
   receiver's `onReceive` is a two-liner: resolve the bus, submit the trigger.
3. Move throttle/debounce/single-flight state out of the coordinator's fields and into the bus,
   keyed by a virtual clock so tests can advance time instead of sleeping.
4. The bus calls `LibraryScanner.scanAllEnabled` (A1). Without A1 it would call the coordinator's
   loop, so land A1 first.
5. Preserve every existing Android signal and the current user-visible timing exactly. This is a
   seam change, not a behaviour change.

## Files touched

- New: `feature/feature-library/.../scanner/RescanTriggerBus.kt`, `RescanTrigger.kt`
- Edit: `MainActivity.kt`, `MediaMountReceiver.kt`, `UsbDisconnectReceiver.kt`,
  `LibraryRescanCoordinator.kt` (may be absorbed entirely - decide during implementation)

## Tests

`RescanTriggerBusTest` with an injected test clock and a fake scanner:

- two mounts inside the debounce window produce one scan
- resume during an in-flight scan does not start a second (single-flight)
- unplug edge is not swallowed by a throttle that a resume just armed
- cancelling the scope stops the in-flight scan and leaves no pending job
- an exception from the scanner is logged and does not kill the bus

## Risks

- USB unplug is edge-sensitive; the fix in `c41650f` must stay green. Add its case to the bus test
  before moving any code.
- Receivers are process-lifecycle sensitive. A DI-provided scope must outlive `onReceive` or scans
  get cancelled mid-flight; use `goAsync()` or an application-scoped `CoroutineScope`.

## Done when

The three entry points contain no scan or scheduling logic, trigger timing has clock-driven tests,
and mount/unplug/resume still behave identically on a real device.
