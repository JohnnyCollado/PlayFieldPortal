# Front-End Launcher User Research

A modeled composite of what front-end launcher users complain about, can't live
without, don't care much about, and abandon launchers over. Built to inform PFP's
roadmap.

## Method and honesty note (read first)

This is **not** a real survey of 100 people, and it contains **no invented
per-person numbers**. It is a composite model built from a real, citable corpus.
Each theme's frequency is a **qualitative band** (Very common -> Niche) derived
from two grounded signals: how many independent sources raise it, and the
engagement / comment counts on the threads that raise it (a public proxy for how
many people care).

**Corpus collected (all verifiable):**

- **Daijisho issue tracker** (GitHub `magneticchen/Daijishou`) — 23
  highest-engagement issues, hundreds of comments. The single most-discussed
  thread is [#703 "can no longer kill RetroArch" (57 comments)](https://github.com/magneticchen/Daijishou/issues/703).
- **ES-DE issue tracker** (GitLab `es-de/emulationstation-de`) — ~34 recent
  issues, plus the [scraping-is-6x-too-slow report #1967](https://gitlab.com/es-de/emulationstation-de/-/work_items/1967).
- **Play Field Portal** — the 17 GitHub issues, 4 pasted Reddit users, and 3
  press reviews (XDA, Android Authority, RetroHandhelds) with their comments.
- **Archived r/EmulationOnAndroid threads** via the Wayback Machine (Reddit
  itself is policy-blocked). The archive is thin and skewed to a 2023 crawl,
  dominated by hardware and Daijisho setup-help posts (e.g.
  ["Daijisho not opening games directly"](https://www.reddit.com/r/EmulationOnAndroid/comments/101dukb/)),
  so the issue trackers carry more weight than Reddit in this model.

Cross-frontend convergence is the useful part: when Daijisho, ES-DE, and PFP
users independently hit the same wall, that is a real pattern, not noise.

## 1. Most common complaints

| Complaint | Frequency | Evidence |
|---|---|---|
| Games won't launch / launch into the wrong emulator / RetroArch black-screen you can't kill or recover from | Very common (highest engagement of any theme) | Daijisho [#703 (57c)](https://github.com/magneticchen/Daijishou/issues/703), #889, #579, #562 (Android 13 "ActivityNotFound"); PFP [#16](https://github.com/JohnnyCollado/PlayFieldPortal/issues/16) + pasted Reddit ("everything launches from RetroArch... black screen... can't force-quit") |
| Scraping broken, too slow, or confusing (missing artwork, arcade metadata, single-threaded speed) | Very common | Daijisho #304, #853 (NPE crash); ES-DE #1967 ("6x+ slower than ArkOS"); PFP: Android Authority "boxart scraping broken", pasted Reddit "scraping isn't working" |
| Setup / onboarding too complex — new users can't tell what to do | Common | Daijisho "new to Daijisho, what does this mean" (archived); PFP press setup-friction notes; pasted Reddit: an ES-DE veteran "still felt a bit lost" |
| Breaks after an Android / OS or emulator update | Common | Daijisho Android 13 cluster (#562/#579/#889); RetroAchievements API breakage #457/#590/#746 |
| Emulator / core assignment is fiddly (no per-system/per-core control, custom player unclear) | Common | Daijisho #726, #194; PFP #10; ES-DE SAF launch bugs #2068 |
| Home / launcher integration (default-launcher won't persist, TV, DeX) | Occasional | Daijisho #273, #72, #128 |

## 2. Features they can't leave without (must-haves)

Inferred from what is universally present and what generates outrage the moment
it breaks:

- **Reliable launch into the correct emulator/core, every time** — the category's
  whole reason to exist; the #1 complaint cluster is really this failing.
- **Full controller-first navigation** (no forced touch) — ES-DE controller polish
  requests (#2075 exit-combo, #2088 battery) exist because controller users demand
  depth here.
- **Automatic box-art + metadata scraping** — defines the product; every tracker's
  loudest bug reports are scraping breakages.
- **Per-system emulator assignment + per-game override** — Daijisho custom-player
  threads, PFP's resolution ladder.
- **Broad, current system & emulator support** — constant "add emulator X" requests
  (Daijisho #732 Citron, #222 Vita; ES-DE ongoing).
- **Library / artwork portability — not re-scraping when you switch** — raised
  directly in the RetroHandhelds PFP thread and ES-DE #2097; a genuine adoption
  gate.

## 3. Low-priority features (requested, but niche / single-voice)

Low comment counts, usually one requester, or power-user polish:

- Samsung DeX (Daijisho #128), Android TV / Google TV (#72), dual-screen
  (PFP [#7](https://github.com/JohnnyCollado/PlayFieldPortal/issues/7))
- FPS limiter (ES-DE #2070), font switching / custom clock tokens (ES-DE
  #2094/#2089), controller battery % (ES-DE #2088)
- RSS / podcast, gameboot videos (PFP [#17](https://github.com/JohnnyCollado/PlayFieldPortal/issues/17)),
  translations (PFP [#2](https://github.com/JohnnyCollado/PlayFieldPortal/issues/2))
- Extra scraper sources beyond the main one (Daijisho #194), custom collection
  sorting (ES-DE #2069)

These matter to enthusiasts and are cheap goodwill, but nobody adopts or abandons
a launcher over them.

## 4. Deal-breakers (would make them drop it entirely)

Ranked by how strongly the corpus ties them to abandonment:

1. **"It can't reliably launch my games."** Wrong emulator, unrecoverable
   RetroArch black-screen, or post-update `ActivityNotFound`. A launcher that
   doesn't launch gets uninstalled — the highest-engagement failure across every
   tracker (Daijisho #703/#889/#579, PFP #16 + Reddit).
2. **Scraping totally broken or unusably slow.** Kills the "beautiful organized
   library" value proposition that made them install it (Daijisho #304/#853,
   ES-DE #1967).
3. **Abandonment / stalled maintenance.** This community is scarred by it —
   Daijisho [#596 "please open-source it so the community can maintain it"](https://github.com/magneticchen/Daijishou/issues/596),
   and the archived sub is full of AetherSX2-death threads. An unmaintained
   launcher that breaks on the next Android version is dead.
4. **Can't complete setup at all.** If a new user (or even an ES-DE veteran) can't
   get past configuration, they bounce before they ever see the value.
5. **Trust / privacy / security doubts** — especially for something that replaces
   your home screen. Android Authority flagged exactly this for PFP; a local-first,
   no-telemetry, encrypted-keys posture is the direct antidote.

## What this means for PFP specifically

The cross-frontend data says the highest-leverage investment is **launch
reliability** (especially the RetroArch "no core for this console / can't recover"
case already flagged) and **scraping that visibly works with clear failure
messages** — those two are simultaneously the top complaints, the core must-haves,
and the top deal-breakers everywhere. PFP's portability story (ES-DE-compatible
artwork, no re-scrape), local-first privacy, and deep theming are genuine
differentiators against Daijisho / ES-DE; they are worth surfacing louder because
users clearly value them but rarely find them.

## Limitations

- Frequency bands are modeled from source recurrence and engagement, **not a real
  survey** — there are deliberately no "N/100" figures.
- Reddit's own threads are underrepresented: the site is blocked here and its
  Wayback archive is thin for this topic, so the fully-citable issue-tracker data
  carries the model.
- Issue-tracker engagement over-weights power users (who file issues) relative to
  silent mainstream users; the must-have/low-priority split is calibrated against
  that skew but cannot fully correct for it.
