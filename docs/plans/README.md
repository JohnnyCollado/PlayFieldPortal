# Plan Index

Plans generated from `docs/feedback/` plus one directly requested feature fix. Each plan is
independent and reviewable on its own. Nothing here has been implemented.

## Source: architecture-review-20260820-013228.html

| # | Plan | Review verdict | Effort |
|---|---|---|---|
| A1 | [Deep library scan module](library-scan-module-plan.md) | Strong (top recommendation) | M |
| A2 | [Split PfpThemeStore into bundle + applied-look](theme-module-depth-plan.md) | Worth exploring | L |
| A3 | [One rescan-trigger module behind thin Android adapters](rescan-trigger-adapter-plan.md) | Worth exploring | S |
| A4 | [Library projection out of XMBViewModel](library-projection-module-plan.md) | Speculative (deferred) | L |

Dependency order: A1 -> A3 -> A4. A2 is independent.

## Source: frontend-user-research.md

| # | Plan | Research theme | Effort |
|---|---|---|---|
| B1 | [Launch reliability and recovery](launch-reliability-plan.md) | Complaint #1, deal-breaker #1 | L |
| B2 | [Scraper throughput and honest failure reporting](scraper-reliability-plan.md) | Complaint #2, deal-breaker #2 | M |
| B3 | [Setup and onboarding friction](onboarding-friction-plan.md) | Complaint #3, deal-breaker #4 | M |
| B4 | [Emulator and core assignment clarity](emulator-assignment-clarity-plan.md) | Complaint #5, must-have | M |
| B5 | [OS and emulator update resilience](os-update-resilience-plan.md) | Complaint #4, deal-breaker #3 | M |
| B6 | [Surface the differentiators](differentiator-surfacing-plan.md) | Deal-breaker #5, adoption gate | S |
| B7 | [Niche request triage](niche-backlog-triage-plan.md) | Section 3 (low priority) | S |

## Source: direct request

| # | Plan | Effort |
|---|---|---|
| C1 | [Multi-disc games as one library entry](multi-disc-games-plan.md) | M |

Effort key: S = under a day, M = a few days, L = a week or more.
