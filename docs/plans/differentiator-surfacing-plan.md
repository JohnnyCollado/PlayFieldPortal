# B6 - Surface the differentiators

Source: `docs/feedback/frontend-user-research.md`, deal-breaker #5 (trust and privacy) plus the
"What this means for PFP specifically" section: PFP's portability, local-first privacy, and deep
theming are genuine differentiators that "users clearly value but rarely find".

## Problem

Three things PFP already does are effectively invisible:

1. **Artwork and library portability.** ES-DE-compatible artwork, a user-owned portable artwork
   folder (`feature-artwork/portable/`), and an ES-DE importer (`importer/`) mean a user does not
   re-scrape when they switch frontends. The research calls "not re-scraping when you switch" a
   genuine adoption gate (ES-DE #2097, the RetroHandhelds thread).
2. **Local-first, no telemetry.** Android Authority flagged trust concerns for an app that replaces
   the home screen. PFP encrypts keys with a Keystore AES-GCM helper and keeps data local, but
   nothing in the app says so.
3. **Deep theming.** The `.pfptheme` format and the Theme Studio desktop companion are unusual in
   this category.

This is the cheapest plan in the batch: no new capability, only making existing capability legible.

## Goal

A user evaluating PFP encounters each differentiator in the app, at the moment it is relevant,
without reading the README.

## Approach

### 1. A privacy and data page in Settings

Plain language, no marketing: what leaves the device (scraper API calls, optional Discord and
RetroAchievements), what never does, where data is stored, how keys are protected, and how to
export or delete everything. One screen, linked from first run and from Settings root.

### 2. Portability where it is relevant

- In the scraping screen: state that artwork is written to the user's own portable folder in an
  ES-DE-compatible layout, and link to that folder.
- In setup: offer "import artwork from an existing ES-DE library" as a first-class option beside
  "scrape from the internet". A user with an existing library should never be asked to re-scrape
  as the default path.
- Add an explicit "export my library and artwork" action so portability is demonstrable, not
  claimed.

### 3. Theming discoverability

A single entry point that shows installed themes, links to the Theme Studio, and explains what a
`.pfptheme` bundle is. Do not build new theming; just make the existing surface findable.

### 4. Do not overclaim

Every statement on the privacy page must be verifiable against the code. If an optional integration
does send data, say so on the same page. An inaccurate privacy claim is worse than none, given the
deal-breaker being addressed.

## Files touched

- New: privacy and data screen in `feature-settings`
- `InitialSetupScreen.kt` (ES-DE import as a first-class setup branch)
- Scraping and artwork settings screens (portable folder visibility)
- Themes settings screen (Studio link and format explanation)

## Tests

- Mostly manual and copy review. Add one test asserting the export action produces a readable
  bundle at the advertised location.
- Add a checklist to the review: every claim on the privacy page maps to a specific file or module.

## Risks

- Scope creep into marketing copy. Keep it factual and short.
- Claims drift as features change. Note in the page's source comment that it must be re-verified
  whenever a network integration is added.

## Done when

The privacy page exists and is accurate, ES-DE import is offered at setup time rather than buried,
and a new user can find the theming story without help.
