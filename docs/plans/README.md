# Plan Index

Plans generated from `docs/feedback/` plus one directly requested feature fix. Each plan is
independent and reviewable on its own. Status reflects the state of the `polishing-UI` branch as of
2026-08-26 (see the git log for the implementing commits).

Status key: ✅ implemented · 🟡 partially implemented · ❌ not implemented

## Open issue: Settings controller navigation

The Settings overlay still has a persistent controller-navigation synchronization bug. Section headers must be reachable by Up/Down but must not be selectable. The expected sequence is `header → first row → second row`; currently, navigating from a header can skip the first row, and repeated Up presses can leave the header visually hidden. See [settings-controller-navigation-issue.md](settings-controller-navigation-issue.md) for the observed behavior, likely causes, instrumentation requirements, and regression scenarios.

## Source: architecture-review-20260820-013228.html

| # | Plan | Status | Review verdict | Effort |
|---|---|---|---|---|
| A1 | [Deep library scan module](library-scan-module-plan.md) | ✅ `0aee279` extracts `LibraryScanner` (`scanPlatform` / `scanAllEnabled`); both callers delegate; ADR 0001; `LibraryScannerTest` + `LibraryRescanCoordinatorTest` cover the guards | Strong (top recommendation) | M |
| A2 | [Split PfpThemeStore into bundle + applied-look](theme-module-depth-plan.md) | ❌ `PfpThemeStore` untouched; the 2026-08-14/20 theme commits (wave-only bundles, hardening) are the prior work this plan builds on | Worth exploring | L |
| A3 | [One rescan-trigger module behind thin Android adapters](rescan-trigger-adapter-plan.md) | 🟡 The coordinator now owns throttle/debounce/single-flight over the shared `LibraryScanner` (the plan's "may be absorbed entirely" option), and resume/mount/unplug all route through it. No `RescanTriggerBus`, no clock-driven trigger tests; the three entry points still repeat their Hilt/scope boilerplate | Worth exploring | S |
| A4 | [Library projection out of XMBViewModel](library-projection-module-plan.md) | ❌ `XMBViewModel` untouched; the multi-disc projection landed at the DAO-query level instead (the alternative C1 explicitly allowed) | Speculative (deferred) | L |

Dependency order: A1 -> A3 -> A4. A2 is independent.

## Source: frontend-user-research.md

| # | Plan | Status | Research theme | Effort |
|---|---|---|---|---|
| B1 | [Launch reliability and recovery](launch-reliability-plan.md) | 🟡 Missing-ROM half is done: detection/tracking (`4248f4a`), Missing bucket (`6dbf8d5`), rescan on resume/mount/unplug (`978eebb`, `7d13ea2`, `c41650f`), re-scan removes missing (`76d0ed7`), and a missing-game launch refusal on Game Detail. Post-launch verification, the recovery sheet, and `launch_outcomes` history are not | Complaint #1, deal-breaker #1 | L |
| B2 | [Scraper throughput and honest failure reporting](scraper-reliability-plan.md) | ❌ | Complaint #2, deal-breaker #2 | M |
| B3 | [Setup and onboarding friction](onboarding-friction-plan.md) | 🟡 First-run wizard shipped (`151c8cc`) and hardened (re-runnable, flows match Settings, media roots picked in the wizard now scan). No derived `SetupState`, no end-on-a-real-launch, no empty states that name the missing step | Complaint #3, deal-breaker #4 | M |
| B4 | [Emulator and core assignment clarity](emulator-assignment-clarity-plan.md) | 🟡 Game Detail now shows the resolved emulator name and its source level (per-game override / memory card / platform default / first valid) via a private `ResolvedLaunchProfile`. No per-platform assignment screen, no core visibility, no bulk override clearing | Complaint #5, must-have | M |
| B5 | [OS and emulator update resilience](os-update-resilience-plan.md) | ❌ | Complaint #4, deal-breaker #3 | M |
| B6 | [Surface the differentiators](differentiator-surfacing-plan.md) | ❌ | Deal-breaker #5, adoption gate | S |
| B7 | [Niche request triage](niche-backlog-triage-plan.md) | ❌ | Section 3 (low priority) | S |

## Source: direct request

| # | Plan | Status | Effort |
|---|---|---|---|
| C1 | [Multi-disc games as one library entry](multi-disc-games-plan.md) | ✅ Fully implemented: set identity (`812ef36`), reconciliation (`6e637d6`), one-primary-per-set invariant + single-row projection (`2c00f66`), plus every follow-up from [multi-disc-next-session.md](multi-disc-next-session.md) — disc picker on game detail, set-level Missing semantics, legacy multi-folder scan reconciliation, schema v39. Remaining items are the optional backlog (disc-count badge, set-level artwork decision, on-device E2E run) | M |

Effort key: S = under a day, M = a few days, L = a week or more.
