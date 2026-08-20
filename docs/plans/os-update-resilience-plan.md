# B5 - OS and emulator update resilience

Source: `docs/feedback/frontend-user-research.md`, complaint cluster #4 ("breaks after an Android
or emulator update") and deal-breaker #3 (abandonment - "an unmaintained launcher that breaks on
the next Android version is dead"). Precedent: the Daijisho Android 13 cluster (#562, #579, #889)
and its RetroAchievements API breakages (#457, #590, #746).

## Problem

Two classes of breakage, both of which PFP is exposed to.

**Platform.** PFP targets SDK 35 with a min of 29 and is a HOME launcher, so it sits on top of
the most-restricted Android surfaces: package visibility, implicit intents, `FileProvider` grants,
SAF persistence, broadcast receivers, and background work. The Daijisho Android 13 cluster is
exactly what happens when a launcher's intent assumptions age out: launches start throwing
`ActivityNotFound` for reasons the app never checks.

**Third-party.** Emulator packages rename activities, change extras, or drop intent filters
between versions. `KnownEmulatorCatalog` hardcodes those assumptions across 789 lines and has no
mechanism to notice when one stops being true. Similarly the RetroAchievements and ScreenScraper
APIs change under the app.

## Goal

When the ground moves, PFP detects it, tells the user something true, and keeps working in a
degraded mode rather than failing opaquely.

## Approach

### 1. Self-check the catalog against the device

On app start (throttled) and after any package-changed broadcast, verify each configured emulator
profile still resolves: the package exists, and the declared component or intent filter still
resolves via `PackageManager`. When a profile stops resolving, mark it broken, surface it once, and
offer the assignment screen from [B4](emulator-assignment-clarity-plan.md).

`EmulatorDetector.kt:111` already comments on the `ActivityNotFoundException` cause. Turn that
knowledge into a periodic check rather than a launch-time surprise.

### 2. Listen for package changes

Register for package add/replace/remove for the emulator packages PFP knows about. An emulator
update is the single most common trigger for a broken profile, and it is a signal PFP can get for
free as a launcher.

### 3. Pin the platform assumptions in tests

Write an explicit test module listing every Android behaviour PFP depends on that is version-gated:
package visibility queries in the manifest, `FLAG_GRANT_READ_URI_PERMISSION` on ROM handoff,
persisted SAF grants, exact-broadcast registration, foreground restrictions. One test per
assumption, named after it, so a target-SDK bump produces a readable failure list.

### 4. Degrade, do not die, on API changes

For RetroAchievements and the scrapers: a schema change should disable that provider with a named
reason and leave the rest of the app functional. Tie into the `ScrapeFailure` reasons from
[B2](scraper-reliability-plan.md) rather than inventing a parallel mechanism.

### 5. Make diagnostics exportable

PFP has file logging (`PfpFileLoggingTree`) and a logs screen. Make one-tap export of a redacted
diagnostic bundle (device, Android version, target SDK, emulator packages and versions, last N
launch outcomes) prominent. This is what turns "it broke after the update" into a fixable report,
and it directly counters the abandonment fear in deal-breaker #3.

## Files touched

- `EmulatorDetector.kt`, `KnownEmulatorCatalog.kt`, `EmulatorProfileRepository.kt`
- New: a package-change receiver and a profile health checker
- `LogsSettingsScreen.kt` (diagnostic bundle export)
- New: `app/src/test/.../PlatformAssumptionsTest.kt`

## Tests

- A profile whose package is uninstalled is marked broken, not silently used.
- A profile whose declared component no longer resolves is marked broken.
- A package-replaced broadcast for a known emulator triggers a re-check.
- The health check is throttled and does not run on every resume.
- The diagnostic bundle contains no ROM paths, account identifiers, or API keys.

## Risks

- Package visibility on API 30+ means `PackageManager` queries silently return nothing without the
  right manifest `queries` entries. A health check that cannot see a package must report "unknown",
  never "broken", or every user gets false alarms.
- Redaction in the diagnostic bundle is a privacy requirement, not a nicety. Deal-breaker #5 is
  trust; a diagnostic export that leaks account data would be self-inflicted.

## Done when

An emulator update or an uninstall is noticed and reported before the user hits a failed launch,
and every version-gated platform assumption has a named test.
