# ADR-0002: Rescan triggers are event-driven, never a filesystem watcher

- **Status:** Implemented
- **Date:** 2026-09-08
- **Scope:** `feature:feature-library` trigger path (`RescanTriggerBus`, `LibraryRescanCoordinator`)
  and its Android adapters in `app`

## Context

PFP's original design principle read: *"ROM scanning is always user-initiated. No background
polling, no FileObserver, no surprises."* The shipped code has not matched that sentence since
the triggered-rescan work: `LibraryRescanCoordinator` submits rescans on **app resume**,
**media mount**, and **USB disconnect**, and ADR-0001 already treats "triggered rescans" as a
first-class caller of `LibraryScanner`.

Read literally, the old principle says the code is broken. It is not. The principle was written
against a different threat — a daemon that walks the user's storage continuously — and it
over-generalized into "no automatic scanning at all". Meanwhile the real reason the triggers
exist is a usability one: a user who copies ROMs onto an SD card, plugs it in, and returns to
PFP expects the games to be there. Making them find a menu item to discover their own files was
the single most common source of "my games are missing" confusion.

Leaving the contradiction unrecorded is what makes it dangerous. A future architecture review
reading the principle would correctly flag `RescanTriggerBus` as a violation and propose
deleting it, re-introducing the usability problem it was built to solve.

## Decision

Scanning is **event-driven, not watched**. Three triggers may start a rescan without the user
asking:

| Trigger | Source | Guard |
|---|---|---|
| `AppResumed` | `MainActivity` lifecycle | Throttled — at most one resume-driven scan per 5 minutes (`RESUME_THROTTLE_MS`) |
| `MediaMounted` | `MediaMountReceiver` | Debounced 2 s (`MOUNT_DEBOUNCE_MS`); a later mount cancels the pending job |
| `UsbDisconnected` | `UsbDisconnectReceiver` | Same debounce path as mount — a removed drive is as strong a signal as an inserted one |

Every trigger converges on one guarded entry point in `RescanTriggerBus`:

- **Single-flight.** `scanMutex.tryLock()` — a trigger arriving while a scan runs is dropped, not
  queued. Stale queued work would scan a tree the finishing pass already covered.
- **Discovery before incremental.** `RomRootDiscoveryScanner.discover()` runs first so a ROM
  dropped into a folder for a console with no Memory Card yet is picked up in the same pass. A
  discovery failure is logged and non-fatal; the incremental scan still runs.
- **Injected scope and clock.** `@RescanApplicationScope` supplies the scope; `RescanClock` is a
  wall-clock seam so the throttle boundary is drivable from tests rather than being a sleep.

What remains refused, and is the actual content of the original principle:

- **No `FileObserver`, no `ContentObserver` on media, no polling loop.** Nothing in PFP watches the
  filesystem for changes. Every scan traces back to a discrete lifecycle or broadcast event.
- **No background service or `WorkManager` job** whose purpose is to scan. Scans run in the
  application scope while the app is alive; a backgrounded PFP does not scan on a timer.
- **No scan the user cannot see.** Scans surface through `BackgroundTaskNotifier`.

The design principle is therefore restated as **"No background polling"** rather than
"scanning is always user-initiated", and `ARCHITECTURE.md` documents the triggers explicitly.

## Consequences

### Positive

- The documented principle and the code agree, so neither is evidence against the other.
- The guards are named and located in one module, so "is this trigger safe?" is answerable by
  reading `RescanTriggerBus` alone.
- `RescanClock` keeps the throttle boundary a unit test rather than a flaky timing test.

### Negative

- Three entry points mean three chances to miss a guard; the bus is the only thing preventing
  that, so trigger adapters must stay thin and never scan directly.
- A 5-minute resume throttle is a guess, not a measurement. A user who adds ROMs and returns
  within the window still sees a stale library until a manual scan or a mount event.
- `RescanTrigger.UsbDisconnected` exists in the enum but no adapter submits it —
  `UsbDisconnectReceiver` routes through `onMediaMounted()` because the debounce path is
  identical. The enum case documents intent that the wiring does not yet distinguish.

## Alternatives rejected

- **Restore strict manual-only scanning.** Deletes the triggers and returns the "my games are
  missing" confusion that motivated them. This ADR exists specifically to stop this being
  re-proposed on the strength of the old principle's wording.
- **`FileObserver` on ROM roots.** Watches cost battery, do not survive SAF trees, and fire per
  file during a bulk copy — the debounce problem gets worse, not better.
- **Periodic `WorkManager` scan.** Scanning when the user is not present buys nothing: the
  library is only observed when PFP is on screen, which is exactly when `AppResumed` fires.
- **Queue triggers instead of dropping them.** Rejected for the same reason as ADR-0001's
  single-flight decision — a queued scan runs against a tree the previous pass already surveyed.
- **Scan on every resume with no throttle.** A user bouncing between PFP and an emulator would
  re-walk every ROM source on each return.
