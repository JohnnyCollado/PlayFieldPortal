# Android Emulator Intent Launch Research (July 2026)

Verified reference for extending `KnownEmulatorCatalog` and automating per-user emulator
intent setup. Nothing in this document is guessed: every intent recipe is copied verbatim
from a maintained frontend launch database and translated into PFP catalog fields.

## Sources

1. **ES-DE Frontend, official Android config** (master branch, fetched 2026-07-23):
   - `resources/systems/android/es_systems.xml` — launch commands
   - `resources/systems/android/es_find_rules.xml` — package/activity registry
   - https://gitlab.com/es-de/emulationstation-de
2. **GlazedBelmont es-de-android-custom-systems** (community pack covering Switch forks
   and other systems ES-DE does not ship): https://github.com/GlazedBelmont/es-de-android-custom-systems
3. Spot-check searches for 2026 status (Eden, RPCSX, aPS3e — links at bottom).

ES-DE placeholder semantics, mapped to PFP concepts:

| ES-DE placeholder | Meaning | PFP equivalent |
|---|---|---|
| `%ROMSAF%` | SAF `content://` document URI | `game.romUri` / `{rom_uri}` |
| `%ROMPROVIDER%` | `content://` URI served by the frontend's own FileProvider | PFP FileProvider URI (current fallback for raw paths) |
| `%ROM%` | raw file path string | `{rom_path}` |
| `%ACTION%=x` | intent action | `intentAction` |
| `%CATEGORY%=x` | intent category | `intentCategory` |
| `%DATA%=x` | intent data URI | ACTION_VIEW data |
| `%EXTRA_k%=v` | string extra | `intentExtras` |
| `%EXTRABOOL_k%=v` | boolean extra | `intentBoolExtras` |
| `%EXTRAINTEGER_k%=v` | int extra | **not supported by PFP model yet** |
| `%EXTRAARRAY_k%=a,b` | string-array extra | **not supported by PFP model yet** |
| `%ACTIVITY_CLEAR_TASK/TOP%` | intent flags | `intentFlags` |
| `%INJECT%=%BASENAME%.ext` | value read from a sidecar file next to the ROM | **not supported by PFP model yet** |

---

## A. Corrections / risk flags for existing catalog entries

1. **DuckStation, NetherSX2, Dolphin pass `{rom_path}` where ES-DE passes a SAF URI.**
   ES-DE sends `bootPath=%ROMSAF%` (DuckStation, NetherSX2) and `AutoStartFile=%ROMSAF%`
   (Dolphin). Under scoped storage a raw path the emulator cannot read will fail; a granted
   `content://` URI is the modern contract. Recommendation: switch these extras to
   `{rom_uri}` (the resolver already grants read permission when `{rom_uri}` is used).
   Keep `{rom_path}` only as legacy fallback for world-readable paths.
2. **Switch entry uses ACTION_VIEW; the verified yuzu-family recipe is different.**
   Every yuzu-lineage emulator (Yuzu, Eden, Sudachi, Citron, Sumi, Uzuy, Ziunx) is launched
   by frontends with action `android.nfc.action.TECH_DISCOVERED`, component pinned to
   `<pkg>/org.<lineage>.
   <lineage>_emu.activities.EmulationActivity`, and the ROM as a plain `content://` data URI
   (ES-DE uses its FileProvider variant, `%ROMPROVIDER%`). That odd NFC action is the only
   action their `EmulationActivity` intent filter accepts. The current catalog entry
   (ACTION_VIEW + octet-stream, no activity) resolves to the file-browser activity at best.
3. **EKA2L1**: ES-DE pins activity `com.github.eka2l1/.emu.EmulatorActivity` with
   ACTION_VIEW + CLEAR_TASK/CLEAR_TOP + FileProvider URI. Catalog entry has no activity.
4. **melonDS**: confirmed correct (`me.magnum.melonds.LAUNCH_ROM`, extra `uri`). Nightly
   package `me.magnum.melonds.nightly` uses action `me.magnum.melonds.nightly.LAUNCH_ROM` —
   the action string is package-prefixed, so it cannot be shared across variants.
5. **PPSSPP, DraStic, M64Plus FZ, Mupen64Plus-AE, My Boy!, My OldBoy!, Flycast, Redream,
   X360 Mobile, ARMSX2, Azahar, Lime3DS, all Robert Broglia `.emu` apps**: confirmed as-is.
   (Flycast also has a legacy activity alias `com.reicast.emulator.MainActivity`.)

---

## B. Verified new entries to add

Recipes below are the exact ES-DE / community commands translated to catalog fields.
`SAF` = pass ROM as granted content URI in `data`; `FP` = FileProvider content URI.

### PlayStation family

| Emulator | Package(s) | Activity | Recipe |
|---|---|---|---|
| ePSXe (PS1) | `com.epsxe.ePSXe` | `.ePSXe` | ACTION_MAIN, string extra `com.epsxe.ePSXe.isoName` = raw path |
| FPse (PS1) | `com.emulator.fpse` | `.Main` | ACTION_VIEW, data = FP URI |
| FPseNG (PS1) | `com.emulator.fpse64` | `.Main` | ACTION_VIEW, data = FP URI |
| Play! (PS2) | `com.virtualapplications.play` | `.MainActivity` | ACTION_VIEW, data = SAF |
| NetherSX2-Turnip (PS2) | `xyz.aethersx2.tturnip` | `xyz.aethersx2.android.EmulationActivity` | same recipe as NetherSX2 (MAIN, `bootPath`, CLEAR_TASK/TOP) |
| NetherSX2-Turnip Classic (PS2) | `xyz.aethersx2.cturnip` | `xyz.aethersx2.android.EmulationActivity` | same as above |
| EmuCoreX (PS2) | `com.sbro.emucorex` | `.MainActivity` | ACTION_VIEW + CLEAR_TASK, data = SAF |
| ARMSX2 new builds (PS2) | `come.nanodata.armsx2`, `come.nanodata.armsx2.debug` | `kr.co.iefriends.pcsx2.MainActivity` | ACTION_VIEW, data = SAF |
| aPS3e (PS3) | `aenu.aps3e.premium`, `aenu.aps3e` | `aenu.aps3e.EmulatorActivity` | action `aenu.intent.action.APS3E`, extra `iso_uri` = SAF (ISO mode) or `game_dir` = raw dir path |
| Vita3K (Vita) | `org.vita3k.emulator`, `org.vita3k.emulator.ikhoeyZX` | `.Emulator` | string-ARRAY extra `AppStartParameters` = `["-r", "<TITLE_ID>"]` — launches by installed Title ID, not ROM file |
| EmuCoreV (Vita) | `com.sbro.emucorev` | `.core.vita.Emulator` | same array-extra pattern as Vita3K |

### Nintendo

| Emulator | Package(s) | Activity | Recipe |
|---|---|---|---|
| Eden (Switch) | `dev.eden.eden_emulator` (+ `.nightly`, `dev.legacy.eden_emulator`, spoof build `com.miHoYo.Yuanshen`) | `org.yuzu.yuzu_emu.activities.EmulationActivity` | action `android.nfc.action.TECH_DISCOVERED`, data = FP URI |
| Yuzu / Yuzu EA | `org.yuzu.yuzu_emu`, `org.yuzu.yuzu_emu.ea` | `org.yuzu.yuzu_emu.activities.EmulationActivity` | TECH_DISCOVERED, data = FP URI |
| Sudachi | `org.sudachi.sudachi_emu`, `.ea` | `org.sudachi.sudachi_emu.activities.EmulationActivity` | TECH_DISCOVERED, data = FP URI |
| Citron | `org.citron.citron_emu`, `.ea` (+ spoofs `com.antutu.ABenchMark`, `com.miHoYo.Yuanshen`) | `org.citron.citron_emu.activities.EmulationActivity` | TECH_DISCOVERED, data = FP URI |
| Sumi | `com.sumi.SumiEmulator` | `org.sumi.sumi_emu.activities.EmulationActivity` | TECH_DISCOVERED, data = FP URI |
| Uzuy | `org.uzuy.uzuy_emu`, `.ea`, `.mmjr` | `org.uzuy.uzuy_emu.activities.EmulationActivity` | TECH_DISCOVERED, data = FP URI |
| Suyu | `org.suyu.suyu_emu`, `dev.suyu.suyu_emu` | `<pkg>.activities.EmulationActivity` | ACTION_VIEW, data = FP URI |
| Kenji-NX (Ryujinx lineage) | `org.kenjinx.android` | `.MainActivity` | action `org.kenjinx.android.LAUNCH_GAME`, string extra `bootPath` = SAF |
| Benji-SC | `org.benjisc.android` | `org.kenjinx.android.MainActivity` | same LAUNCH_GAME recipe as Kenji-NX |
| Skyline (legacy) | `skyline.emu` | `emu.skyline.EmulationActivity` | ACTION_VIEW, data = FP URI |
| Dolphin MMJR (GC/Wii) | `org.mm.jr` | `org.dolphinemu.dolphinemu.ui.main.MainActivity` | ACTION_VIEW, extra `AutoStartFile` = SAF |
| Dolphin MMJR2 | `org.dolphinemu.mmjr` | same MainActivity | same |
| Dolphin PrimeHack | `org.dolphinemu.primehack`, `org.shiiion.primehack` | same MainActivity | same |
| Cemu (Wii U) | `info.cemu.cemu` (older `info.cemu.Cemu`) | `<pkg>.emulation.EmulationActivity` | data = SAF (no action override) |
| AzaharPlus (3DS) | `io.github.azaharplus.android` | `org.citra.citra_emu.activities.EmulationActivity` | CLEAR_TASK/TOP, data = SAF |
| Mandarine (3DS) | `io.github.mandarine3ds.mandarine` | `.activities.EmulationActivity` | CLEAR_TASK/TOP, data = SAF |
| Borked3DS (3DS) | `io.github.borked3ds.android` | `.activities.EmulationActivity` | CLEAR_TASK/TOP, data = SAF |
| Panda3DS | `com.panda3ds.pandroid` | `.app.MainActivity` | data = FP URI |
| Citra MMJ | `org.citra.emu` | `.ui.EmulationActivity` | string extra `GamePath` = raw path |
| NooDS (DS/GBA) | `com.hydra.noods` | `.FileBrowser` | CLEAR_TASK/TOP, string extra `LaunchPath` = raw path |
| melonDS Nightly | `me.magnum.melonds.nightly` | `me.magnum.melonds.ui.emulator.EmulatorActivity` | action `me.magnum.melonds.nightly.LAUNCH_ROM`, extra `uri` = SAF |
| SkyEmu (GB/GBC/GBA/DS) | `com.sky.SkyEmu` | `.EnhancedNativeActivity` | ACTION_VIEW + CLEAR_TASK/TOP, data = FP URI |
| Pizza Boy GBA | `it.dbtecno.pizzaboygbapro`, `it.dbtecno.pizzaboygba` | `<pkg>.MainActivity` | CLEAR_TASK/TOP, string extra `rom_uri` = SAF |
| Pizza Boy GBC | `it.dbtecno.pizzaboypro`, `it.dbtecno.pizzaboy` | `<pkg>.MainActivity` | same |
| Linkboy (GB/GBC/GBA) | `com.pixelrespawn.linkboy` | `.EmulatorActivity` | ACTION_VIEW, data = SAF |
| Nesoid (NES) | `com.androidemu.nes` | `.EmulatorActivity` | ACTION_VIEW, data = raw path URI |
| iNES (NES) | `com.fms.ines.free` | `com.fms.emulib.TVActivity` | ACTION_VIEW + CLEAR_TASK/TOP, data = SAF |
| Virtual Virtual Boy | `com.simongellis.vvb` | `.MainActivity` | ACTION_VIEW + CLEAR_TASK/TOP, data = FP URI |

### Sega

| Emulator | Package(s) | Activity | Recipe |
|---|---|---|---|
| Yaba Sanshiro 2 (Saturn) | `org.devmiyax.yabasanshioro2.pro`, `org.devmiyax.yabasanshioro2` | `org.uoyabause.android.Yabause` | ACTION_VIEW + CLEAR_TASK/TOP, string extra `org.uoyabause.android.FileNameUri` = SAF |
| Saturn.emu | `com.explusalpha.SaturnEmu` | `com.imagine.BaseActivity` | data = SAF (same `.emu` pattern already in catalog) |
| Pizza Boy SC (multi-Sega) | `it.dbtecno.pizzaboyscpro`, `it.dbtecno.pizzaboyscbasic` | `.MainActivity` | CLEAR_TASK/TOP, string extra `rom_uri` = SAF |
| MasterGear (SMS/GG/SG-1000) | `com.fms.mg` | `com.fms.emulib.TVActivity` | ACTION_VIEW + CLEAR_TASK/TOP, data = SAF |
| SUPER3 (Model 3) | `com.izzy2lost.super3` | `.MainActivity` | ACTION_VIEW, data = SAF |

### Microsoft

| Emulator | Package(s) | Activity | Recipe |
|---|---|---|---|
| aX360e (X360) | `aenu.ax360e`, `aenu.ax360e.free` | `aenu.ax360e.EmulatorActivity` | action `aenu.intent.action.AX360E`, string extra `game_uri` = SAF |
| hakuX (OG Xbox) | `com.rfandango.haku_x` | `.LauncherActivity` | ACTION_VIEW, data = SAF |
| X1 BOX (OG Xbox) | `com.izzy2lost.x1box` | `.LauncherActivity` | ACTION_VIEW, data = SAF |

### PC / Windows on Android

All Winlator variants share: activity `XServerDisplayActivity`, CLEAR_TASK/TOP,
string extra `shortcut_path` = raw path to a `.desktop` shortcut file (not a game binary).

| Variant | Package | Activity |
|---|---|---|
| Winlator (glibc mainline) | `com.winlator` | `.XServerDisplayActivity` |
| Winlator Cmod | `com.winlator.cmod` | `.XServerDisplayActivity` |
| Winlator Cmod PRoot | `com.cmodded.winlator` | `com.winlator.XServerDisplayActivity` |
| Winlator vanilla/ludashi/spoof builds | `com.winlator.vanilla`, `com.ludashi.benchmark`, `com.miHoYo.GenshinImpact` | `com.winlator.cmod.XServerDisplayActivity` |

Related PC clients (need int-extra support):

- **GameNative** `app.gamenative/.MainActivity`, action `app.gamenative.LAUNCH_GAME`,
  string extra `game_source` (`EPIC`/`GOG`/`CUSTOM_GAME`, omit for Steam), **int** extra
  `app_id`.
- **GameHub Lite** `emuready.gamehub.lite` or `gamehub.lite` (plus many spoof packages),
  activity `com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity`, action
  `gamehub.lite.LAUNCH_GAME`, bool `autoStartGame=true`, string `steamAppId` or
  `localGameId`.

### Other systems

| Emulator | Package(s) | Activity | Recipe |
|---|---|---|---|
| MAME4droid 2024/Current (arcade) | `com.seleuco.mame4d2024` | `com.seleuco.mame4droid.MAME4droid` | ACTION_VIEW, data = FP URI; optional string extra `cli_params` for MESS-style machine targets |
| MAME4droid 0.139 | `com.seleuco.mame4droid` | `.MAME4droid` | ACTION_VIEW, data = FP URI |
| ScummVM | `org.scummvm.scummvm` (+ `.debug`) | `org.scummvm.scummvm.SplashActivity` | ACTION_MAIN, data = game target id read from a `.scummvm` sidecar file (ES-DE `%INJECT%`) |
| J2ME Loader | `ru.playsoftware.j2meloader` | `.MainActivity` | CLEAR_TASK/TOP, data = FP URI of the `.jar` |
| JL-Mod | `ru.woesss.j2meloader` | `ru.playsoftware.j2meloader.MainActivity` | same |
| Real3DOPlayer (3DO) | `ru.vastness.altmer.real3doplayer` | `.EmulatorActivity` | data = SAF |
| IrataJaguar (Jaguar) | `ru.vastness.altmer.iratajaguar` | `.EmulatorActivity` | data = SAF |
| ColEm (ColecoVision) | `com.fms.colem.deluxe`, `com.fms.colem` | `com.fms.emulib.TVActivity` | ACTION_VIEW + CLEAR_TASK/TOP, data = SAF |
| fMSX (MSX) | `com.fms.fmsx.deluxe`, `com.fms.fmsx` | `com.fms.emulib.TVActivity` | same |
| Speccy (ZX Spectrum) | `com.fms.speccy.deluxe`, `com.fms.speccy` | `com.fms.emulib.TVActivity` | same |
| MSX.emu / C64.emu / 2600.emu / NGP.emu | `com.explusalpha.MsxEmu` / `C64Emu` / `A2600Emu` / `NgpEmu` | `com.imagine.BaseActivity` | data = SAF/FP (existing `.emu` pattern) |
| OpenBOR | `org.openbor.engine` | `.GameActivity` | ACTION_MAIN only (no per-game arg) |
| Ruffle (Flash) | `rs.ruffle` | `.PlayerActivity` | ACTION_VIEW, data = FP URI |
| SWF Player (Flash) | `com.issess.flashplayer` (+ pro) | `.player.FlashPlayerActivity` | ACTION_VIEW + CLEAR_TASK/TOP, data = FP URI |
| Visual Pinball | `org.vpinball.vpinball_bgfx`, `org.vpinball.app`, `.gl` | `org.vpinball.app.VPinballActivity` / `VpxLauncherActivity` | CLEAR_TASK/TOP, data = SAF |
| DroidArcadia (Arcadia 2001) | `com.amigan.droidarcadia` | `.MainActivity` | ACTION_VIEW, MIME `application/zip`, data = FP URI |
| D.Smile (V.Smile) | `com.dsmile.emulator` | `.ui.EmuActivity` | ACTION_VIEW, data = SAF |
| EKA2L1 (Symbian/N-Gage) | `com.github.eka2l1` | `.emu.EmulatorActivity` | ACTION_VIEW + CLEAR_TASK/TOP, data = FP URI |
| Starboard (PortMaster) | `org.force9.starboard` | `.ui.game.GameActivity` | string extra `port_file` = raw path |
| Infinity (PICO-8) | `me.dt2dev.infinity` | `.SchemeActivity` | ACTION_VIEW, data = FP URI |

---

## C. Model/resolver work implied by the data

1. `{rom_uri}` support in COMPONENT extras already exists — migrate DuckStation, NetherSX2,
   Dolphin templates to it (Section A.1).
2. Add **int extras** and **string-array extras** to `EmulatorProfile`/`KnownEmulator`
   (needed by GameNative, Vita3K, EmuCoreV).
3. Add a **data-URI-with-custom-action** launch shape: several recipes are
   "component + custom action + content data URI" (yuzu family TECH_DISCOVERED,
   Suyu/Skyline VIEW + FileProvider). The current ACTION_VIEW builder always uses
   ACTION_VIEW; the COMPONENT builder never sets `data`. One of the two needs a small
   extension.
4. **ID-based launches** (Vita3K title IDs, aPS3e game serials, GameNative/GameHub app
   ids, ScummVM target ids): these are not ROM-file launches. Automation needs a per-game
   "launch token" field or sidecar-file convention (ES-DE uses `%INJECT%` sidecar files).
5. Spoofed package names (`com.miHoYo.Yuanshen`, `com.antutu.ABenchMark`, etc.) are used
   by GPU-driver-unlock builds of Citron/Sumi/Uzuy/Winlator. Detection by package name
   alone will mislabel them; if we add them, label as "(driver-spoof build)" and detect by
   the activity class resolving, not just the package existing.

## D. Known unknowns (not in any verified database — do not ship without a manifest dump)

- **RPCSX** — RESOLVED 2026-07-23, see Section E: package `net.rpcsx`, but its emulation
  activity is `exported="false"`, so no frontend launch is possible today.
- **mGBA standalone intent**: our catalog carries it (ACTION_VIEW + SAF), but ES-DE has no
  standalone mGBA entry to cross-check against. Keep, but verify on-device.
- **X360 Mobile / ARMSX2 fast-moving forks**: package names churn between releases;
  re-verify at release time.

---

## E. Obtainium Emulation Pack analysis (user-provided, `emu-pack-links/`, pack v7.11.0)

Two Obtainium export JSONs (standard + dual-screen variant, 54/58 apps, heavily
overlapping). Value: canonical source repos per package, an installed-base priority
signal, and several identity confirmations. Classification below separates launch
targets from tools.

### E.1 Emulators (launch targets) — cross-check against Sections A/B

| Pack entry | Package | Source repo | Status vs. our data |
|---|---|---|---|
| aPS3e | `aenu.aps3e` | aenu1/aps3e | matches Section B recipe |
| Azahar | `org.azahar_emu.azahar` | azahar-emu/azahar | matches catalog |
| Cemu | `info.cemu.cemu` | SSimco/Cemu (dual-screen pack: sapphirerhodonite/cemu, same package) | matches Section B |
| Citra MMJ | `org.citra.emu` | weihuoya/citra | matches Section B (`GamePath` extra) |
| Dolphin | `org.dolphinemu.dolphinemu` | dolphin-emu.org | matches catalog; apply A.1 fix |
| DuckStation | `com.github.stenzek.duckstation` | mirror (rmacias workers.dev) | matches catalog; apply A.1 fix |
| Eden | `dev.eden.eden_emulator` | git.eden-emu.dev/eden-emu/eden | matches Section B (TECH_DISCOVERED) |
| Flycast | `com.flycast.emulator` | flyinghead/flycast | matches catalog |
| melonDS (+Nightly) | `me.magnum.melonds`, `.nightly` | rafaelvcaetano/melonDS-android | matches; nightly action is package-prefixed |
| MelonDualDS | `me.magnum.melondualds` | SapphireRhodonite/melonDS-android | confirms community find-rule |
| Play! | `com.virtualapplications.play` | purei.org | matches Section B |
| PPSSPP | `org.ppsspp.ppsspp` | ppsspp.org | matches catalog |
| RetroArch AArch64 | `com.retroarch.aarch64` | buildbot.libretro.com/stable | matches resolver's core-path handling |
| RPCSX | `net.rpcsx` | RPCSX/rpcsx-ui-android | see E.3 — NOT launchable |
| ScummVM | `org.scummvm.scummvm` | scummvm.org | matches Section B |
| Vita3K | `org.vita3k.emulator` | Vita3K/Vita3K-builds | matches Section B (title-ID array extra) |
| NetherSX2 | `xyz.aethersx2.android` | Trixarian/NetherSX2-patch | matches catalog; apply A.1 fix |
| NetherSX2-Turnip | `xyz.aethersx2.tturnip` | nckstwrt/NetherSX2-Turnip | matches Section B |
| ARMSX2 | `com.armsx2` | ARMSX2/ARMSX2 | matches catalog |
| Citron Neo | `org.citron.citron_emu` | citron-neo/emulator | Citron continues as "Citron Neo", same package — Section B recipe stands |
| Xemu Android | `com.izzy2lost.x1box` | izzy2lost/xemu | confirms "X1 BOX" = the xemu OG Xbox port |
| hakuX | `com.rfandango.haku_x` | rfandango/hakuX | matches Section B |
| Pico8 Android | `io.wip.pico8` | Macs75/pico8-android | matches find-rule (PICO-8 via Godot launcher) |
| GameNative | `app.gamenative` | utkarshdalal/GameNative | matches Section B (needs int extra) |
| GameHub Lite | `gamehub.lite` | Producdevity/gamehub-lite | matches Section B |
| Winlator | `com.winlator` | brunodev85/winlator | matches Section B |
| Winlator Cmod | `com.winlator.cmod` | coffincolors/winlator | matches Section B |
| Winlator-Ludashi | `com.winlator.ludashi` | StevenMXZ/Winlator-Ludashi | NEW package variant not in ES-DE/community rules; assume Winlator recipe but verify activity class before shipping |
| Starboard | `org.force9.starboard` | get-starboard/starboard | matches Section B (`port_file` extra) |

Pack scope note: no standalone GB/GBC/GBA, SNES, N64, Saturn, or MAME apps — the pack
relies on RetroArch AArch64 for 8/16-bit and arcade. RetroArch core-map support remains
the backbone for those platforms.

### E.2 Not emulators (do not add to the launch catalog)

- **Frontends** (competitors/peers, not launch targets): Daijishou, Pegasus, Cocoon FE,
  Argosy (RomM client), iiSU, Cannoli, Console Launcher, NeoStation, RetroHrai,
  ES-DE Companion.
- **Streaming clients**: Moonlight (`com.limelight`), Artemis (`com.limelight.noir`) —
  could be future launch targets for a "PC streaming" feature, but they are not emulators;
  Artemis/Moonlight do support `moonlight://` style deep links (not researched here).
- **Utilities**: Syncthing-Fork, ES-DE Android Apps, OdinTools, Bifrost, Pixel Guide,
  EmuReady Lite (compatibility database), CHDroid (CHD converter), Mjolnir + Jarngreipr
  (LG V60 dual-screen tools), Cluster Tune, Emulnk, RAOfflineProxy.
- **Driver packages (Track Only)**: Adreno-Tools-Drivers, Mr. Purple Turnip drivers —
  GPU drivers, no launchable component.
- **RAOfflineProxy** (`com.raofflineproxy`, misantronic/RAOfflineProxy): RetroAchievements
  offline proxy — irrelevant to launching, possibly relevant to the achievements feature.

### E.3 BIOS setup: what ES-DE actually does (verified 2026-07-23)

Checked ES-DE's `ANDROID.md` and `USERGUIDE.md` (master). **ES-DE has no BIOS setup or
verification feature.** What it ships is documentation only:

- A "Needs BIOS" yes/no column in its supported-systems table.
- Per-emulator prose instructions ("place panafz10.bin with MD5 51f2f43a... in ROMs/3do",
  "RetroArch will just black-screen on launch if the BIOS is missing", etc.).
- One passive behavior: BIOS/device zips listed in the regular MAME driver file are
  filtered out of gamelists so they don't appear as games.
- It never checks, copies, downloads, or validates a BIOS file. Setup failures surface as
  emulator-side errors or hangs at launch.

Implication for PFP: matching ES-DE costs nothing (a "Needs BIOS" flag per platform plus
help text). Exceeding ES-DE is where real user value is, and the models to copy are
Batocera/EmuDeck-style **BIOS checkers**: a curated table of required/optional BIOS files
per platform+emulator with MD5 checksums, a scan of a user-designated BIOS folder (SAF),
and a missing/invalid report. Checksum data is publicly documented per libretro core on
docs.libretro.com and in the Batocera bios lists.

Constraints discovered for a PFP implementation:

1. PFP must never download or bundle BIOS files (copyrighted) — verify and place
   user-supplied files only.
2. Placement is only possible where PFP can write: RetroArch's public system directory
   (user grants the folder via SAF) is placeable; standalone emulators using app-private
   storage or their own SAF-picked data dirs (DuckStation, Azahar, Vita3K, ...) are
   verify-and-instruct only — Android blocks writing to another app's `Android/data`.
3. RetroArch black-screens rather than erroring on missing BIOS, which PFP cannot detect
   post-launch — pre-launch verification is exactly the gap worth filling.

### E.4 RPCSX verification (primary evidence, 2026-07-23)

Fetched `app/src/main/AndroidManifest.xml` from RPCSX/rpcsx-ui-android master:

- Package `net.rpcsx`. Emulation activity `.RPCSXActivity` declares intent filter action
  `rpcsx.intent.action.Emulator` but is **`android:exported="false"`** — third-party
  frontends cannot start it. Only `.MainActivity` (plain MAIN/LAUNCHER) is exported.
- Conclusion: RPCSX can only be launched app-level (like PFP's `resolveNativeApp` path),
  not per-game. Re-check the manifest on new releases; the intent action already exists,
  so per-game launch may just be an `exported` flip away.
- Platform: conflicting sources. The core repo README says "An experimental PlayStation 4
  and PlayStation 5 emulator for Linux"; press coverage and the pack ecosystem treat the
  Android UI as the PS3 (RPCS3-successor) app; the pack's own description says PS4. Do not
  assign `platformIds` until verified on-device.

## F. On-device recipe validation (Stage 2, 2026-07-24)

Device: Galaxy Tab S9 Wi-Fi (SM-X710), Adreno 740, Android 16 / API 36, arm64-v8a.
Method: each catalog recipe checked against the **actually-installed** APK manifest
(`aapt2 dump xmltree` on the on-device base.apk, plus `dumpsys package`). This validates
recipe *resolution* — target activity exists, is exported, handles the expected
action/VIEW — which is the #1 launch failure mode. It does **not** validate full boot
(BIOS/keys/cores) or the SAF read path (that runs through PFP, not adb).

### F.1 Result — every installed emulator's recipe resolves (0 activity-class bugs)

| Console(s) | Emulator (pkg) | Activity | Verified |
|---|---|---|---|
| NDS | melonDS (`me.magnum.melonds`) | `…ui.emulator.EmulatorActivity` | exported=true, action `me.magnum.melonds.LAUNCH_ROM` |
| 3DS | Azahar (`org.azahar_emu.azahar`) | `org.citra.citra_emu.activities.EmulationActivity` | exported, VIEW |
| Switch | Citron (`org.citron.citron_emu`) | `…activities.EmulationActivity` | exported, VIEW + `TECH_DISCOVERED` |
| Switch | Eden (`dev.eden.eden_emulator`) | `org.yuzu.yuzu_emu.activities.EmulationActivity` | exported, VIEW + `TECH_DISCOVERED` |
| Dreamcast | Flycast (`com.flycast.emulator`) | `com.flycast.emulator.MainActivity` | exported, VIEW |
| PS2 | NetherSX2 (`xyz.aethersx2.android`) | `xyz.aethersx2.android.EmulationActivity` | exported=true |
| PS2 | ARMSX2 (`com.armsx2`) | `com.armsx2.MainActivity` | exported, VIEW |
| Wii U | Cemu (`info.cemu.cemu`) | `info.cemu.cemu.emulation.EmulationActivity` | exported, VIEW |
| Xbox | X1 BOX / xemu (`com.izzy2lost.x1box`) | `com.izzy2lost.x1box.LauncherActivity` | exported, VIEW |
| Xbox | hakuX (`com.rfandango.haku_x`) | `com.rfandango.haku_x.LauncherActivity` | exported, VIEW |
| All 2D systems | RetroArch (`com.retroarch`) | `com.retroarch.browser.retroactivity.RetroActivityFuture` | exported=true |

- **ARMSX2 "Refresh" build (2.6.5)** ships *both* `com.armsx2.Main` and
  `com.armsx2.MainActivity`, both exported VIEW handlers; the catalog's `.MainActivity`
  resolves. Drawer launcher is a third activity, `.BootSplashActivity`. Which of `.Main`
  vs `.MainActivity` boots straight to the game vs. the menu is a runtime nuance for
  on-device testing, not a resolution failure.
- Eden/Citron confirm Section A.2 / C.3: their `EmulationActivity` really does accept
  `android.nfc.action.TECH_DISCOVERED` (the shipped `attachRomData` recipe is correct).

### F.2 Raw-path vs SAF — the load-bearing distinction for testing

adb dry-runs (`am start … --es <extra> <raw path>`) hit a shared-storage permission wall
that **does not apply to PFP's own launches**:

- adb passes a **raw filesystem path**. A sideloaded emulator reading it needs
  `MANAGE_EXTERNAL_STORAGE` (all-files access), which is off by default and — on Android
  13+ for sideloaded apps — is gated behind *restricted settings* (greyed toggle). Failure
  is silent: `E MediaProvider: Permission to access file … denied` (DocumentFile path) or a
  bare native `EACCES` with no log at all (native `open()` path, e.g. an ISO).
- PFP passes a **SAF/FileProvider `content://` URI with `FLAG_GRANT_READ_URI_PERMISSION`**
  (already implemented in `EmulatorIntentResolver`). This grants the emulator per-URI read
  access to that one ROM — **no app-level storage permission required**. So the recipes
  validated in F.1 should launch through PFP even where raw-path adb probes could not read
  the file.
- **Exception — folder/`game_dir` launches** (PS3 JB disc folders): these pass a raw
  directory path, not a content URI, so they *do* require the target emulator to hold
  all-files access. PFP cannot grant it. See F.3.

### F.3 PS3 folder launch (aPS3e) — verified recipe + a real permission trap

Tested with a decrypted JB disc folder (`PS3_DISC.SFB` + `PS3_GAME/USRDIR/EBOOT.BIN`) and
a decrypted `.dec.iso`, on aPS3e 2.40 (`aenu.aps3e`).

- Launch activity confirmed on-device: `aenu.aps3e/.EmulatorActivity`, handling both
  `android.intent.action.VIEW` and `aenu.intent.action.APS3E`. The folder recipe
  (`--es game_dir <raw dir>`) and ISO recipe (`--es iso_uri <…>`) both resolve and start
  the emulation activity — recipe is correct.
- Boot blocked by storage access, not the recipe: aPS3e reads the game by raw path, so it
  needs all-files access. On this device the toggle was **greyed out** because the app was
  sideloaded → Android *restricted settings*. Fix chain a user must walk:
  1. App info → ⋮ menu → **Allow restricted settings** (un-greys the toggle).
  2. Grant **All files access** (Settings → Apps → Special access → All files access).
  3. Then the `game_dir`/`iso_uri` raw-path launch reads the game.
- `appops set aenu.aps3e MANAGE_EXTERNAL_STORAGE allow` (with and without `--uid`) is
  **silently blocked by Samsung/Knox** — the grant does not persist from the shell. Only
  the UI toggle works. `am start -a android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
  deep-links to the toggle screen.
- Firmware is a separate hard requirement (aPS3e `dev_flash` must be present) — a boot with
  access but no firmware still fails.

**Catalog/PFP implication:** a folder-launch (PS3 JB) recipe must (a) pass the raw dir in
`game_dir` — distinct from the ISO `iso_uri` recipe already noted in Sections A/B — and
(b) pre-check `MANAGE_EXTERNAL_STORAGE` on the target emulator, warn when missing, and
deep-link the user through the restricted-settings unlock + all-files toggle. Without that,
the only symptom is a silent denial / black screen. This is the strongest evidence yet that
folder games need first-class handling separate from file/URI games.

### F.4 Prerequisites that gate boot but not resolution

- **RetroArch cores** live only in internal storage (`/data/user/0/com.retroarch/cores/`,
  == `/data/data/com.retroarch/cores/` — matches the resolver's normalized path). The build
  is not debuggable, so adb cannot place cores; they must be installed via the in-app Core
  Downloader (or Install/Restore from the staged `Roms/downloads` zips). Until then, the
  2D-console recipes resolve but will not boot.
- **BIOS/keys/firmware** per console (Switch keys, Dreamcast/Sega CD/Neo Geo CD BIOS, PS3
  firmware) — the BIOS-checker feature (Section E.3) is the intended mitigation.
