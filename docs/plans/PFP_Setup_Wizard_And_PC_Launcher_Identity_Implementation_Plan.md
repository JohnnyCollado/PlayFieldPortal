# Play Field Portal — Setup Wizard Additions & PC Launcher Identity Implementation Plan

**Status:** Implemented and device-verified on the Odin3 2026-10-01 (T1–T11). §6's artboard was signed off the same day with a *Show Hidden Apps* row added. See §9 for where the build departs from this plan.
**Repository baseline reviewed:** `final-polish` at `5960678c` (2026-10-01).
**Primary modules:** `feature/feature-settings` (wizard, Library Manager), `feature/feature-launcher` (`PcLauncherCatalog`, `KnownEmulatorCatalog`), `core/core-domain` (`KnownEmulatorPackages`), `core/core-data` (`Ps3DataLibrary`, `Vita3KLibrary`), `feature/feature-xmb` (Windows setup prompt).
**Design reference:** Design canvas *Setup Wizard Additions* — https://claude.ai/artifact/8WdLDgMeykWyQitqKyLp69 (Odin3, 1920×1080).
**Related plans:** `PFP_PS3_Trophy_Tracking_Implementation_Plan.md`, `PFP_Local_Windows_Achievements_Folder_Matching_Implementation_Plan.md`.

## 1. Goal

First-run setup should cover everything PFP needs to be fully useful, and PC launchers should be identified by what the installed app really is — not by a package id that several forks share.

Three outcomes:

1. **Initial Setup gains six pages** (Controller, Trophies, Emulators, Windows Games, Hints & Touch, Home App) and a corrected footer.
2. **PC launchers are identified by signer, label and version**, with the app's real Android label shown everywhere.
3. **When identity is still ambiguous, the user decides** in a new *Unknown Windows Emulators* section of Import PC Games.

### Out of scope (deliberately deferred)

- **Restore from backup** on the Welcome page. The user wants a proper backup-and-restore redesign first.
- **Importable emulator JSON packs.** Planned separately (§8). This plan only avoids work that would fight it.

## 2. Evidence base

Gathered on the Odin3 (`47dc8656`) on 2026-10-01.

### 2.1 App labels are the reliable display name

Labels read from the installed APKs (`aapt2 dump badging` on the manifest + `resources.arsc`):

| Package | Android label | What the code / Obtainium pack assumed |
| --- | --- | --- |
| `com.winlator.vanilla` | **Winlator Ludashi** | not recognised anywhere |
| `com.winlator.cmod` | Winlator Cmod | pack says "winlator" |
| `org.dolphinemu.dolphinemu` | Dolphin Emulator | — |
| `com.xiaoji.egggame` | GameHub (v6.3.1) | treated as "GameHub Lite" by `brandMatches` |

Every display surface must use `PackageManager.getApplicationLabel()`, never a hardcoded name.

### 2.2 Package ids are shared across forks

From the BannerHub README (The412Banner/BannerHub) and the GameHub Lite README (Producdevity/gamehub-lite):

| Package id | Built by |
| --- | --- |
| `com.xiaoji.egggame` | official GameHub **and** BannerHub *Original* |
| `gamehub.lite` | GameHub Lite **and** BannerHub *Normal.GHL* |
| `com.antutu.ABenchMark`, `com.antutu.benchmark.full`, `com.ludashi.aibench`, `com.tencent.ig` | GameHub Lite **and** BannerHub variants |
| `banner.hub`, `com.miHoYo.GenshinImpact`, `com.tencent.tmgp.cf` | BannerHub only |

BannerHub labels every variant "BannerHub …" (e.g. *BannerHub Original*, *BannerHub Ludashi*), signs all of them with the **AOSP testkey**, and from v3.4.0 reports its **own** version (3.x). GameHub Lite re-signs with its builder's key and reports the GameHub base version (5.1.0).

### 2.3 The Odin3's `com.xiaoji.egggame`

Label *GameHub*, version 6.3.1, signer `CN=gamesir` (SHA-256 `f6dc89251d2edf60c5721524d59f2dc6373825a73b1b81d031cafeeff31c9775`). A repack cannot carry GameSir's signature, so this is the official app — or "BannerHub v6", a separate project the BannerHub README only references, if that one does not repackage. Either way the launch contract is V6. This is exactly the case §6 exists for.

### 2.4 Obtainium Emulation Pack v7.18.0 gaps

Of the pack's 35 emulators, 30 are already known. Gaps:

| Package id | App | Action |
| --- | --- | --- |
| `com.winlator.ludashi` | Winlator-Ludashi | add to the Winlator launcher (with `com.winlator.vanilla`) |
| `io.github.gopher64.gopher64` | Gopher64 (N64) | add to `KnownEmulatorPackages` |
| `emu.x360mobile.com` | X360 Mobile | code has `emu.x360.mobile` — correct to the published id |
| `io.navivani.swiff`, `xendroid.compose` | Swiff, Xendroid | **hold** until the target system is confirmed |

## 3. Initial Setup — new page order

Pages are shown only when they apply. Step numbers are the full sequence.

| # | Page | Shown when | New? |
| --- | --- | --- | --- |
| 1 | Welcome | always | — |
| 2 | **Controller** | always | new |
| 3–9 | ROM, Music, Video, Photo, Artwork, Services, Achievements | always | — |
| 10 | **Trophies** | Vita3K **or** ARMSX3 installed | replaces `VITA` |
| 11 | RetroArch | RetroArch installed | — |
| 12 | **Emulators** | any known emulator installed | new |
| 13 | **Windows Games** | any verified PC launcher installed | new |
| 14 | **Hints & Touch** | always | new |
| 15 | **Home App** | PFP is not already the Home app | new |
| 16 | Finish | always | summary extended |

`SetupStep` gains `CONTROLLER, TROPHIES, EMULATORS, WINDOWS, HINTS, HOME_APP`; `VITA` is removed (folded into `TROPHIES`).

### 3.1 Controller (step 2)

Rows: **Controller Type** (Xbox / PlayStation / Nintendo, cycles on confirm), **A / B Swap**, **X / Y Swap**, Continue. Reuses `ControllerSettingsViewModel`'s prefs (`layoutPrefs.displayType`, `confirmBackLayout`, `xyLayout`) — same writes Settings ▸ Controller makes. Placed second so every later page shows the right glyphs.

### 3.2 Trophies (step 10)

One page, two sections, each present only when its emulator is installed:

- **PS VITA · VITA3K** — today's Vita page content (`Vita3KLibrary`).
- **PS3 · ARMSX3** — *Set ARMSX3 Data Folder*, sublabel "Grant PS3/config/dev_hdd0 — or any folder above or below it". Linked state shows a `WizardRootRow` (✎ / 🗑) like Vita. Backed by the existing `Ps3DataLibrary.setDataFolder` / `clear`, the same grant Library Manager makes today.

Heading: "Link your trophy folders." Finish summary gains *PS3 Data Folder*.

### 3.3 Emulators (step 12)

One short value row per console with a known emulator installed: console name → the auto-picked emulator's **Android label**; confirm cycles through the installed candidates for that console. Reads/writes the same assignment `EmulatorAutoConfigService` and Settings ▸ Emulators use. Footer Ⓐ reads *Change*.

### 3.4 Windows Games (step 13)

Shown only when a verified PC launcher is installed (§5). Rows: *Detected* (labels of every verified launcher, joined with " · "), *Set Windows Games Folder* (the Library Manager Windows-root grant), Continue. Setting the folder here clears the need for the XMB's one-time *Finish your Windows Library* prompt; the prompt stays as the fallback for users who skip.

### 3.5 Hints & Touch (step 14)

*Button Hints* (checkbox — `contextMenuHintEnabled`), *Hint Delay* (cycles 1–5 s — `contextMenuHintDelaySeconds`), *Touch Button* (Auto / Always Show / Always Hide — `touchNavButtonMode`). Same prefs as Display settings.

### 3.6 Home App (step 15)

Skipped when `isHomeLauncher` is already true. *Current Home App* (value row), *Set as Home App* (launches `LauncherShortcutRepository.homeRoleRequestIntent()` — already used by Import PC Games), Continue. Re-checks the role on resume.

### 3.7 Footer (all pages)

Remove "Press the ◄► buttons to go back, or the ► button to continue." ◀ ▶ are owned by row inline actions (`WizardRootRow` ✎/🗑) and today do nothing on Welcome. New footer: **Ⓐ Enter · Ⓑ Back · RB Skip** (RB = advance without changing anything). Finish shows Ⓐ Enter · Ⓑ Back only. Back on Welcome stays dimmed.

## 4. Catalog corrections

- `PcLauncherCatalog` Winlator entry: add `com.winlator.ludashi`, `com.winlator.vanilla`.
- `KnownEmulatorPackages`: add `io.github.gopher64.gopher64`; replace `emu.x360.mobile` with `emu.x360mobile.com` (keep the old id only if a build using it is confirmed).
- No entries for Swiff / Xendroid yet.

## 5. PC launcher identity

`PcLauncherCatalog` keeps its component fingerprint as the **gate** (it is what stops the genuine AnTuTu / PUBG / Genshin / CrossFire from registering, and it picks the V5/V6 launch contract). Branding moves from `brandMatches` (label contains "banner" → BannerHub, everything else → GameHub Lite) to an ordered resolution:

1. Fingerprint fails → not a launcher (unless the user says otherwise in §6).
2. Signer SHA-256 = GameSir (`f6dc8925…9775`) → **GameHub**.
3. Label contains "bannerhub" → **BannerHub** (label suffix kept: *Original*, *Ludashi*, …).
4. Label contains "lite", or package is `gamehub.lite` with a non-BannerHub label → **GameHub Lite**.
5. Otherwise → GameHub-family launcher, **ambiguous** (listed in §6), shown under its own label.

New `PcLauncherType.GAMEHUB` alongside `GAMEHUB_LITE` and `BANNERHUB_V6`. All three share the GameHub-family launch adapter, so launching is unaffected; only identity and display change. Signer read via `PackageManager.getPackageInfo(pkg, GET_SIGNING_CERTIFICATES)`, cached with the existing `lastUpdateTime` fingerprint cache.

The display name everywhere (PC Launchers rows, wizard *Detected* row, imported game subtitles) becomes the app's Android label.

## 6. Unknown Windows Emulators (Import PC Games)

**Artboard signed off 2026-10-01.** A new group directly under *PC Launchers* on Settings ▸ Library Manager ▸ Import PC Games:

```
UNKNOWN WINDOWS EMULATORS
[icon]  GameHub                      ◀ Not a launcher ▶
        com.xiaoji.egggame · could be GameHub or BannerHub
[icon]  Winlator Ludashi             ◀ Winlator ▶
        com.winlator.vanilla · not in PFP's list
```

Each row: the app's real icon and label, package id + reason as the sublabel, and a value that cycles on confirm through *Not a launcher · GameHub · GameHub Lite · BannerHub · Winlator · GameNative*.

**Who is listed** — only uncertain installs:

- GameHub-family pool packages whose fingerprint fails but whose label names a family launcher (a variant that relocated its classes).
- Fingerprint-verified installs that resolve to step 5 of §5 (ambiguous).
- Packages outside every catalog whose label or package id contains `winlator`, `wine`, `gamehub`, `bannerhub`, `box64` or `mobox`.

Confidently identified launchers never appear here; neither do the genuine spoof-name apps.

**What the choice does**

- Stored per **package id + signer SHA-256** (new table or DataStore map). A reinstall with a different signer asks again.
- The app then appears under *PC Launchers* with its label; the GameHub-family V5/V6 contract still comes from the fingerprint, Winlator/GameNative choices use their adapters.
- *Not a launcher* hides the row permanently (resettable from the row's Options).
- A user choice outranks any automatic rule, including future JSON packs (§8).

## 7. Tasks

Per house practice, each task writes its tests first.

| # | Task | Tests first |
| --- | --- | --- |
| T1 | Catalog corrections (§4) | `KnownEmulatorPackagesTest`, `PcLauncherCatalog` package-claim tests for the new ids |
| T2 | Identity resolution (§5): signer read + cache, ordered rules, `PcLauncherType.GAMEHUB`, label as display name | pure `resolveIdentity(signerSha, label, packageName, versionMajor, generation)` table tests covering every row of §2.2 and the Odin3 case |
| T3 | Wizard footer (§3.7) + RB Skip | wizard nav test: ◀ ▶ never change step; RB advances; Back dimmed on Welcome |
| T4 | `SetupStep` reorder + visibility rules (§3) | step-sequence tests per installed-app combination (none / Vita only / ARMSX3 only / both / PC launcher / already Home) |
| T5 | Controller page (§3.1) | VM test: page writes the same prefs as Settings ▸ Controller |
| T6 | Trophies page (§3.2) | VM test: PS3 grant/release via `Ps3DataLibrary`; section visibility |
| T7 | Emulators page (§3.3) | VM test: per-console candidates from installed packages, labels not ids |
| T8 | Windows Games page (§3.4) | VM test: *Detected* lists verified launchers by label; folder grant clears the XMB prompt condition |
| T9 | Hints & Touch, Home App (§3.5–3.6) | VM tests: prefs round-trip; Home page skipped when already Home |
| T10 | Finish summary rows | summary content test |
| T11 | Unknown Windows Emulators (§6) — after sign-off | candidate-selection tests (each listing rule + exclusions); choice persistence keyed by package+signer; choice outranks rules |

Build and test commands are run by the user (`:feature:feature-settings:testDebugUnitTest`, `:feature:feature-launcher:testDebugUnitTest`, `:core:core-domain:testDebugUnitTest`, then `:app:installFullDebug`).

## 8. Future: importable emulator packs (not in this plan)

Direction recorded so this plan does not work against it: move `KnownEmulatorCatalog` (already pure data) to a bundled `emulators.json` read by the same parser imports will use; schema-versioned packs carrying `emulators`, `pcLaunchers` and `appIdentities` (signer SHA-256, label patterns, version ranges — i.e. §5's rules as data); precedence *user choice > imported pack > built-in*; intents restricted to the entry's own packages, flag allowlist, preview + one-step rollback; SAF import first, URL subscription later. §5 should therefore keep its rules in one table-shaped structure that can later be loaded instead of compiled.

## 9. As built (2026-10-01)

Where the implementation departs from, or settles, the plan above:

- **X360 Mobile.** No build using `emu.x360.mobile` was confirmed, so it is replaced outright. Migration 54→55 rewrites the seeded Xbox 360 platform default, because an uninstalled platform default fails a launch instead of falling back. A default the user changed is kept. The recipe keeps its `emu.x360.mobile.X360MobileGameLaunchActivity` class name (an application id does not rename classes); this is unverified on a device.
- **Identity (§5).** `resolveIdentity` takes `(signer, label, package, generation)`. No rule needed the version, so it was left out. The rules are one ordered table in `PcLauncherCatalog`, ready for §8 packs.
- **§6 listing rule "fingerprint fails but label names a family launcher".** It can't occur: `resolveGeneration`'s label fallback already verifies such an install, so it lands in rule 5 (ambiguous) or a named brand.
- **§6 values.** An undecided row reads *Choose…*; the first confirm sets *Not a launcher*. Each confirm is stored straight away (per package + signer). A decided row stays in the group for the rest of the visit, then moves to PC Launchers or behind *Show Hidden Apps*. *Show Hidden Apps* replaces the per-row Options reset.
- **§6 scope.** A user choice shapes Import PC Games' PC Launchers list. The Windows export scan, shortcut routing (`isVerifiedPcLauncher`) and Initial Setup's *Detected* row still use the automatic rules only.
- **Windows Games page (§3.4).** *Set Windows Games Folder* points the Windows Memory Card at the picked tree (`WindowsLibrarySetup.usePickedFolder`). It also drops any pending *Finish your Windows Library* prompt. No Library Manager row did this before. The info line no longer promises achievement matching, which this pick does not start.
- **Emulators page (§3.3).** Rows come from Emulator Assignment's own row builder (`PlatformAssignRowsBuilder`), so they never disagree with Settings ▸ Emulators. A console needs games and a standalone emulator to get a row. Before the ROM scan has found anything, the page explains that instead.
- **Controller page (§3.1).** It drives `ControllerSettingsViewModel` itself rather than copying its writes.
- **Hints & Touch (§3.5).** The prefs moved behind `InterfaceHintPrefs`, which Display settings now shares.
