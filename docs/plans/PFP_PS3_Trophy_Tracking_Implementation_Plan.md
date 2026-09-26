# Play Field Portal — PS3 Trophy Tracking (ARMSX3) Implementation Plan

**Status:** Master plan — proposed, awaiting approval (2026-09-25).
**Repository baseline reviewed:** `achievement-ambience` at `2dfad125` (2026-09-25).
**Primary modules:** `feature/feature-achievements`, `feature/feature-settings`, `core/core-data`, `core/core-domain`.
**Emulator:** ARMSX3 (Android). Its data root is `<PS3>/config/dev_hdd0/`, confirmed on device.
**Format reference:** §3 of this document — reverse-engineered from a real capture and validated; see §2 for what was and was not observed.
**Related plans:** `PFP_Local_Achievements_Selective_Sync_Implementation_Plan.md`, `PFP_Local_Windows_Achievements_Folder_Matching_Implementation_Plan.md`.

## 1. Goal and product contract

Add `PS3_TROPHY` as a fourth achievement provider: a PS3 game's trophies read entirely from ARMSX3's local files, joined to the library game through the trophy set id the game's own disc image declares.

Fully offline. Like `VITA_TROPHY` and unlike `STEAM`/`LOCAL_STEAM`, there is no web schema, no API key and no account — a fetch can never report `MissingCredentials`, and rarity has no source. Read-only in the strongest sense: PFP never writes anything into the emulator's data folder or into a game image.

Product rules:

- The user grants **one folder** — the ARMSX3 PS3 data folder — exactly as they grant Vita3K's `ux0` today. No path is ever hardcoded.
- A game links to its trophy set by **the NPWR id the game itself declares**, not by title. Title matching is the degraded fallback, used only when the id cannot be read.
- A trophy set with no `TROPUSR.DAT` yet tracks at 0%. An unreadable one is **unknown**, never "nothing earned" — the same discipline the Vita and Local Steam providers already hold.
- Platinum is a real PS3 trophy and maps to `ShibaTier.PLATINUM` (the crown), as on Vita — never minted locally the way Steam's is.
- A game with DLC trophy subsets shows **one merged coin list** and one completion percentage, not several entries (§5).

### Why this is mostly a clone

`ps3` already exists as a seeded platform with extensions `iso,pkg,ps3dir`, so PS3 games **already scan into the library as ordinary ROMs**. Vita needed `VitaGameScanner` because Vita3K runs only installed titles from `ux0`; PS3 needs no scanner at all. That removes the single largest piece of the Vita provider from this work.

## 2. Evidence base and its limits

Two independent sources: a device capture, and the shipping APK (`ARMSX3-1.0.1-a15-armv8.2-sdk35`) read for its string tables. The APK confirms ARMSX3 is an RPCS3 derivative — `rsx::overlays`, `sceNpTrophy*`, `TROPUSRLoader`, `TRPLoader` all appear in `libarmsx3-core.so` — so the file formats are RPCS3's, not bespoke.

### 2.1 Confirmed from the binary

| Evidence | Meaning |
| --- | --- |
| `/dev_hdd0/home/%s/trophy/%s/` | The exact path template: user id, then NPCOMMID |
| `/dev_hdd0/home/%08u/`, `/dev_hdd0/home/%08d/` | The user id is an 8-digit zero-padded number — enumerate `home/*`, never hardcode `00000001` |
| `/TROPCONF.SFM`, `/TROPUSR.DAT`, `/TROP%03d.PNG` | Exactly the filenames documented in §3 |
| `/dev_bdvd/PS3_GAME/TROPDIR/` | `TROPDIR` is read from the **mounted disc**, confirming the ISO join of §4 |
| `TRPLoader::Install`, `Invalid checksum of TROPHY.TRP file` | The emulator extracts `TROPHY.TRP` into the trophy folder at registration |
| **No `TROPTRNS` or `TROPSYS` string anywhere** | Those files are not absent by accident — this build never writes them |
| `getExternalStorageDirectory` + `PS3/config/dev_hdd0/` | The data root is a **raw path in shared storage**, always SAF-grantable |

That last row is a real simplification over Vita: `Vita3KLibrary`'s KDoc notes that Vita3K's `ux0` is often app-private and unreadable by SAF, which is why the user must point PFP at a shared-storage copy. ARMSX3's root is in shared storage by construction, so a single grant always works.

ARMSX3's own in-app help states the registration behavior plainly: *"The game installs its trophy set the first time it runs, and this reads the same data the console would."* A game that has never been booted has **no trophy folder at all** — the app's own empty state says the game "has not opened it yet; many games only do that once you reach a menu or start playing."

### 2.2 The device capture

One capture, pulled from the user's device on 2026-09-25:

- `dev_hdd0/home/00000001/trophy/NPWR05915_00/` — `TROPCONF.SFM` (9,112 B), `TROPUSR.DAT` (10,096 B), `ICON0.PNG`, `TROP000.PNG`…`TROP047.PNG`.
- The matching disc image, `Witch and the Hundred Knight, The (USA).iso` (3.7 GB), read structurally.

What was **verified**:

- All 48 type-4 blocks cross-checked against `TROPCONF.SFM`: grade and parent id match on every trophy, zero mismatches.
- Block walk consumes the file exactly — `0x2770` walked equals the `0x2770` file size.
- Four unlocked trophies decode to 2026-09-24 05:50–06:09, consistent with a real play session.
- The ISO's `PS3_GAME/TROPDIR/` contains exactly `NPWR05915_00`, matching the on-disk folder.
- `PS3_GAME/PARAM.SFO` parses with the repo's existing `ParamSfo`: `TITLE_ID=BLUS30964`, `TITLE=The Witch and the Hundred Knight`, `CATEGORY=DG`.

What was **not** observed, and must therefore not be assumed:

- **Only block types 4 and 6 appear in this file.** A DLC trophy set, a fully completed set, or a different game may carry others. The parser must walk blocks and skip unknown types rather than assume a fixed set — the TOC design makes that natural and it is the difference between surviving an unseen set and corrupting one.
- **No platinum was unlocked.** Grade code `1` = Platinum is confirmed from the definitions block and `TROPCONF.SFM`'s `ttype="P"`, not from an unlocked row.
- **Only one user profile** (`home/00000001`) and **one trophy set** existed.
- **The ISO is decrypted.** An encrypted dump will not parse as ISO9660 at all.
- **Only a `CATEGORY=DG` disc game** was seen. `pkg`/`ps3dir` installs were not.

## 3. File format reference

### 3.1 Layout on disk

```
<granted PS3 folder>/
└── config/dev_hdd0/home/<USERID>/trophy/<NPWR#####_00>/
    ├── TROPCONF.SFM      definitions (XML)
    ├── TROPUSR.DAT       unlock state (binary, big-endian)
    ├── ICON0.PNG         set icon
    └── TROP000.PNG …     per-trophy icons, zero-padded to the trophy id
```

`<USERID>` is a profile directory (`00000001`); enumerate `home/*` rather than hardcoding it.

### 3.2 `TROPCONF.SFM`

PS3's equivalent of Vita's `TROP.SFM`, and **structurally identical for our purposes**:

```xml
<trophyconf version="1.1" policy="large">
 <npcommid>NPWR05915_00</npcommid>
 <title-name>The Witch and the Hundred Knight</title-name>
 <trophy id="000" hidden="no" ttype="P" pid="-1">
  <name>Grand Finale</name>
  <detail>All trophies have been acquired. Excellent work!</detail>
 </trophy>
```

The existing `TropSfm` reader parses this **unchanged**: same `<trophy id/hidden/ttype>` attributes, same `<name>`/`<detail>` children, same `<npcommid>` and `<title-name>` tags. Ids are zero-padded (`"000"`), which `toIntOrNull()` handles. The additional `pid` attribute is ignorable.

**The hidden flag lives only here.** Unlike Vita, `TROPUSR.DAT` carries no hidden mask.

### 3.3 `TROPUSR.DAT`

Big-endian throughout. Unlike the Vita file's fixed offsets, this is a table of contents plus walkable typed blocks.

```
0x00  u32   magic = 0x818F54AD
0x04  u32   version = 0x00010000
0x08  u32   TOC entry count (2 observed)
0x0C..0x2F  reserved, zero
0x30  TOC entries, 32 bytes each:
        u32 type
        u32 payloadSize
        u32 (1)
        u32 headerEnd (0x30)
        u32 0
        u32 sectionOffset
        u32 0, u32 0

Block header (16 bytes), repeated from each sectionOffset:
        u32 type
        u32 payloadSize
        u32 index
        u32 pad
      followed by payloadSize bytes of payload.
      Total block stride = 16 + payloadSize.

type 4  payload 0x50, one per trophy — definitions mirror
  +0x00  i32  trophy id   (equals the block index)
  +0x04  i32  grade       1=Platinum 2=Gold 3=Silver 4=Bronze
  +0x08  i32  pid         parent group id, -1 for the platinum
  remainder zero

type 6  payload 0x60, one per trophy — unlock state
  +0x00  u32  trophy id
  +0x04  u32  unlocked    1 = earned, 0 = locked
  +0x10  u64  unlockTime
  +0x18  u64  unlockTime  (duplicate; identical in every unlocked row observed)
  remainder zero
```

**Timestamps are microseconds since 0001-01-01T00:00:00Z** — the CELL RTC / PSN epoch, not Unix. Convert with `unixMillis = (us - 62_135_596_800_000_000) / 1_000`. A value of 0 means never unlocked.

Grade codes are identical to Vita's, so `VitaTrophySource`'s tier mapping transfers with no change.

### 3.4 Differences from the Vita provider

| | Vita | PS3 |
| --- | --- | --- |
| Endianness | little | **big** |
| Layout | fixed offsets | **TOC + walkable typed blocks** |
| Hidden flag | bitmask in `TROPUSR.DAT` | **`TROPCONF.SFM` only** |
| Timestamp | seconds since 1970 | **µs since year 1** |
| Files | split `conf/` + `data/` | one folder per set |
| Definitions file | `TROP.SFM` | `TROPCONF.SFM` |
| Grade codes | 1/2/3/4 | identical |
| Platinum | real trophy | real trophy |
| Game discovery | needs a scanner | **none — `ps3` is already a ROM platform** |

## 4. The game → trophy set join

The NPWR id is declared by the game, at `PS3_GAME/TROPDIR/<NPWR#####_00>/`. Where that lives depends on the game's shape:

| Shape | `TROPDIR` location | Read path |
| --- | --- | --- |
| `.iso` (`CATEGORY=DG`) | **inside the image** | `DiscImage` — ISO9660 |
| `ps3dir` (folder dump) | on disk under the game folder | SAF directory walk |
| `.pkg` installed (`CATEGORY=HG`) | `dev_hdd0/game/<TITLE_ID>/TROPDIR/` | SAF directory walk |

For the ISO case the repo already has almost everything: `DiscImageOpener` opens a game's image from a raw path or SAF URI without reading the whole multi-GB file, and `DiscImage.findFile()` resolves ISO9660 paths to `(lba, size)`.

**The one gap:** `findFile` locates a *named* entry but cannot enumerate a directory, and the NPWR folder name is unknown up front. This needs a `listDir(lba, size)` helper — roughly 25 lines reusing the directory-record walk already inside `findFile`. Its `dirSectors = 1` limitation is acceptable: a `TROPDIR` holds one or a few entries, far inside one 2,048-byte sector.

`PS3_GAME/PARAM.SFO` additionally yields `TITLE_ID` and `TITLE` through the existing `ParamSfo` parser, which is worth reading in the same pass — it gives the serial for display and a better title for the fallback.

This is the same source the emulator itself reads: ARMSX3 resolves `/dev_bdvd/PS3_GAME/TROPDIR/` off the mounted disc and extracts that set's `TROPHY.TRP` into the trophy folder on first registration (§2.1). PFP reading `TROPDIR` from the image is therefore reading exactly what the emulator will act on — including **before** the game has ever been booted, when no trophy folder exists yet. That is a genuine capability: PFP can tell the user a game has 48 trophies waiting before they have played it, which the emulator's own trophy screen cannot.

**Fallback.** When the id cannot be read (encrypted ISO, unreadable image), fall back to matching `TROPCONF.SFM`'s `<title-name>` against the library title through the 5-rule normalizer, which handles the article swap in this very sample (`Witch and the Hundred Knight, The` → `The Witch and the Hundred Knight`). A fallback match is a *suggestion*: it links only at EXACT/HIGH confidence, and anything less asks.

## 5. Trophy subsets — merged into one coin list

**Decision (2026-09-25):** a game with DLC trophy subsets shows **one merged coin list**, not several entries. One game, one Shiba page, one completion percentage.

`TROPDIR` is the grouping authority. It lists every NPWR id the game declares, so the disc itself tells us which sets belong together — no guessing from id shape, and no assumption about how subsets are numbered. `Ps3TropDirReader` therefore returns an **ordered list** of NPCOMMIDs rather than one.

### 5.1 The rules

- **Provider game id** is the **first** set `TROPDIR` lists — the base set. That is the link's stable identity; adding DLC later must never change it.
- **Coin ids must be namespaced.** Each subset numbers its trophies from 0, so merging on the raw trophy id collides: base trophy 3 and subset trophy 3 would overwrite each other. `providerAchievementId` is therefore `"<npCommId>:<trophyId>"` — uniformly, base set included, so there is never a mixed key convention to reason about later.
- **Order** is `TROPDIR` order, then trophy id within each set. The base set's platinum stays first.
- **Exactly one platinum.** Only the base set carries one; subsets do not. Never synthesize a per-subset platinum, and never let a subset's grade table introduce a second `ShibaTier.PLATINUM`.
- **A subset absent from disk is simply absent.** Its `TROPCONF.SFM` only exists once that DLC's trophy set is installed and registered. A declared-but-missing subset contributes nothing and is not an error — the same rule as a never-booted base set (§4).
- **Growth is normal.** Installing DLC makes the list longer on the next sync. `AchievementSetWriter` replaces a set and its coins transactionally, so a grown list is written atomically; discovery must re-enumerate subsets on every read rather than caching the first result.

### 5.2 Consequence for the fallback path

The title fallback has no `TROPDIR`, so it has no grouping information. It links only the single set it matched, and a later successful disc read is what completes the game. Do not infer grouping from the NPWR stem: shared numbering is an observation about ids, not a guarantee, and a wrong merge silently mixes two games' trophies.

## 6. No database migration

`provider_game_links.provider` stores the `AchievementProvider` enum **name as a string**, so adding `PS3_TROPHY` needs no schema change and no migration. This plan touches no database version.

Adding the enum constant will break every exhaustive `when` over `AchievementProvider` — `RemoteAchievementSources.forProvider`, `ShibaCoinsViewModel.onLinkPanelSelect`, and the Shiba/Player Status view models among them. That is the desired behavior: the compiler enumerates the sites that need a decision. Do not add an `else` branch to silence it.

## 7. Execution Task Index

Each task is one reviewable change with its own tests. A task that finds the baseline has moved stops and reports rather than inventing scope.

### Task 1 — Baseline confirmation note

**Files:** read-only across `AchievementProvider.kt`, `RemoteAchievementSources.kt`, `LocalProviderCheckStrategies.kt`, `VitaTrophyDiscovery.kt`, `Vita3KLibrary.kt`, `DiscImage.kt`, `DiscImageOpener.kt`, `ParamSfo.kt`, `PlatformSeeder.kt`, `LibraryManagerScreen.kt`.

- Confirm `ps3` is still seeded with `iso,pkg,ps3dir` and that PS3 ROMs scan into the library today.
- Enumerate every exhaustive `when` over `AchievementProvider` that a new constant will break.
- Confirm `DiscImage.findFile`'s record-walk shape, so `listDir` reuses it rather than duplicating it.

**Done when:** a short note records the real call sites and any drift from this plan. No behavior changes.

### Task 2 — `TropUsrPs3Parser`

**Files:** new `provider/ps3/TropUsrPs3Parser.kt`, new test.

- Implement §3.3: validate the magic, read the TOC, walk blocks by `16 + payloadSize`, and build `{id, grade, pid}` from type 4 and `{id, unlocked, unlockedAtEpochMillis}` from type 6.
- **Skip unknown block types** rather than failing or assuming only 4 and 6 exist (§2).
- Return null — not an empty set — for a file that is not a recognizable `TROPUSR.DAT`, so the caller falls back to definitions-only rather than reporting zero unlocks.
- Bound the walk: refuse a block whose stride would run past the buffer, and cap the block count.

**Done when:** tests cover a synthesized fixture built from the documented layout (grades, a platinum with `pid = -1`, locked and unlocked rows, an unknown block type between known ones), a truncated file, a bad magic, and the epoch conversion. Cross-check once by hand against the real capture at `D:\NEXTJJEN\ps3-trophy-sample`.

*Fixture note:* build the test fixture in code from §3.3 rather than committing the captured `TROPUSR.DAT`. The real file carries an `Sce-Np-Trophy-Signature` and game metadata, and a synthesized fixture documents the format in the test itself. Keep the capture outside the repo for manual verification.

### Task 3 — PS3 data folder grant

**Files:** new `core-data/repository/Ps3DataLibrary.kt`, `LibraryManagerScreen.kt`, `LibraryManagerViewModel.kt`.

- Clone `Vita3KLibrary`: one DataStore key, `takePersistableUriPermission` on set, a flow, a snapshot read, and `clear()`.
- Settings row on the PS3 card detail, mirroring **Vita3K Data Folder**: "PS3 Data Folder — pick your ARMSX3 PS3 folder so PFP can read trophies".
- Resolution must be **tolerant of what the user picks**: accept a grant at the ARMSX3 root (contains `config/`), at `config/`, at `dev_hdd0/`, or at the `trophy/` folder itself, then resolve down to `dev_hdd0/home/<user>/trophy/`. `VitaTrophyDiscovery.grantRoot()` already does the two-level version of this; extend the idea.

**Done when:** each accepted grant depth resolves to the same trophy directory, and an unset or revoked grant reports "not configured" rather than "no trophies".

### Task 4 — `Ps3TrophyDiscovery`

**Files:** new `provider/ps3/Ps3TrophyDiscovery.kt`, new test.

- Enumerate `home/*` profiles and their `trophy/<NPWR>` sets; return the set ids available.
- `loadOneSet(npCommId)` joins `TROPCONF.SFM` (via the existing `TropSfm`) with `TropUsrPs3Parser` output and the `TROP%03d.PNG` icons, exactly as `VitaTrophyDiscovery.loadSet` does.
- `loadMerged(npCommIds: List<String>)` is what callers use: loads each set **in the given order**, namespaces every coin id as `"<npCommId>:<trophyId>"`, and concatenates (§5.1). Sets absent from disk are skipped silently, not failed.
- Re-enumerate on every call — never cache the subset list, or newly installed DLC stays invisible until restart.
- A set with no `TROPUSR.DAT` loads definitions-only at 0%; an unreadable one returns null.
- Read-only and grant-scoped: every URI comes from tree-scoped child queries.

**Done when:** tests cover multiple profiles, a set missing `TROPUSR.DAT`, a missing icon, an unset grant, and — for the merge — that two sets each numbering trophies from 0 produce distinct coin ids, that order follows the input list, that exactly one PLATINUM survives, and that a declared-but-absent subset is skipped rather than failing the load.

### Task 5 — `DiscImage.listDir` and the TROPDIR reader

**Files:** `DiscImage.kt`, new `provider/ps3/Ps3TropDirReader.kt`, tests.

- `listDir(lba, size): List<Entry>` returning names plus `(lba, size)` and a directory flag, reusing `findFile`'s record walk. Skip the `\x00` and `\x01` self/parent records and strip the `;1` version suffix.
- `Ps3TropDirReader.npCommIdsFor(game): List<String>` — **every** `NPWR…` entry under `TROPDIR`, in directory order, since that list is the subset grouping authority (§5). For an `.iso`, open through `DiscImageOpener`, `findFile("PS3_GAME\\TROPDIR")`, list it; also read `PS3_GAME\\PARAM.SFO` for `TITLE_ID`/`TITLE`. For `ps3dir` and `pkg`-installed games, walk the equivalent on-disk paths (§4).
- The **first** entry is the base set and becomes the provider game id. Preserve directory order; do not sort, and do not reorder by id.
- Every failure is a *reason*, not a silent null: encrypted/unreadable image, no `PS3_GAME`, no `TROPDIR`, no `NPWR` entry.

**Done when:** `listDir` is covered by a unit test, the reader returns `["NPWR05915_00"]` plus `BLUS30964` for the captured sample's structure, and a synthesized multi-entry `TROPDIR` returns all of them in order. Never reads more than a few sectors of a multi-GB image.

### Task 6 — `Ps3TrophySource` and provider wiring

**Files:** `AchievementProvider.kt`, new `provider/ps3/Ps3TrophySource.kt`, `RemoteAchievementSources.kt`, `LocalProviderCheckStrategies.kt`, plus every `when` Task 1 enumerated.

- Add `PS3_TROPHY` to the enum with a comment matching the existing style.
- `Ps3TrophySource` mirrors `VitaTrophySource`: provider game id is the **base** NPCOMMID, tiers map from the shared grade codes, `RARITY_UNAVAILABLE` throughout, `earnedHardcore` mirrors the unlock (PS3 has no hardcore split), `earnedAt` in millis.
- `fetch(baseNpCommId)` resolves the game's subset list through `Ps3TropDirReader`, then calls `loadMerged` — one fetch, one merged coin list (§5).
- **The subset list is not persisted.** Nothing stores it: the link holds only the base NPCOMMID, and this plan adds no table (§6). Re-reading `TROPDIR` costs a file descriptor and a handful of 2,048-byte sector reads — cheap even on a 3.7 GB image, and far cheaper than the recursive SAF walks the Local Steam provider used to pay. Re-reading is also what makes newly installed DLC appear on the next sync without any invalidation logic. Cache it in memory for the duration of a sync pass if a pass touches one game twice; never across passes.
- Check strategy: PS3 has no cheap change signal, so clone `VitaTrophyCheckStrategy`'s conservative interval rather than inventing a new cadence.
- Resolve each broken `when` deliberately; add no `else` branches.

**Done when:** a PS3 game syncs end to end from a granted folder, and every previously exhaustive `when` handles the new constant explicitly.

### Task 7 — Matching

**Files:** `AchievementAutoMatcher.kt`, tests.

- `matchOne` routes `platformId == "ps3"` to a PS3 branch: read the NPWR ids through `Ps3TropDirReader`, link the **first** as the provider game id (§5.1), and let the source merge the rest at fetch time.
- Per-game Auto-Match on the Shiba page gets the same branch, with plain-language reasons: grant not set, image unreadable, no `TROPDIR`, set not present under the grant.
- **A game whose `TROPDIR` names a set that is not yet on disk is linked anyway**, at 0%, with a "play it once to start tracking" note — the emulator creates the folder on first registration (§2.1), and the link is already deterministic from the disc. Do not treat this as a failure.
- Title fallback only when the id cannot be read, and only at EXACT/HIGH confidence (§4).

**Done when:** each failure reason surfaces distinctly, a never-booted game says so rather than reporting zero trophies, and no fallback link is ever made below HIGH confidence.

### Task 8 — Surfacing

**Files:** `ShibaCoinsScreen.kt`, `AchievementsSettingsScreen.kt` if a status row is wanted, `NotificationIcons.kt` if the provider needs a glyph.

- The Shiba page's unlinked panel gains PS3 wording, in the shape the Vita branch already uses ("PS3 trophies link from the game's own disc — set your PS3 Data Folder and scan").
- Verify tier glyphs render for a set containing a Platinum.

**Done when:** a PS3 game's Shiba page reads correctly in all three states — unlinked, linked at 0%, linked with unlocks.

### Task 9 — Documentation and device verification

**Files:** `README.md` (new section beside 4.20), `CHANGELOG.md`.

- Document the folder grant, what PFP reads, and that nothing is ever written.
- Device pass: grant at each accepted depth; a game with unlocks; a game never booted; an encrypted ISO; a `ps3dir` game if available; a revoked grant; an offline start.
- **A DLC-trophy game if one can be found** (§8.2): confirm the merged list shows both sets, one platinum, base-set trophies first, and that installing the DLC grows the list on the next sync without duplicating anything.

**Done when:** the README covers the provider and the device checklist is recorded with outcomes.

## 8. Open questions

1. **`pkg` / `ps3dir` coverage.** Only a `CATEGORY=DG` ISO was observed. Task 5's on-disk branches are written from the documented PS3 layout, not from a capture — worth a sample before trusting them.
2. ~~**Trophy subsets.**~~ **Decided 2026-09-25: merge into one coin list.** See §5 for the rules. ARMSX3's own trophy model carries a `subsetId` field, so subsets are a case the emulator explicitly handles and the merge must be correct rather than incidental. Still unverified against a real multi-subset game — no such title was in the capture, so §5's ordering and platinum rules are written from the format, not observed. Worth confirming with one DLC-trophy game before shipping.
3. **Trophy set versions.** `<trophyset-version>` exists in the SFM; a patched game can ship a larger set than the one already on disk. Behavior on mismatch is undefined here.

## 9. Verification

```bash
./gradlew :feature:feature-achievements:testDebugUnitTest
```

```bash
./gradlew :core:core-data:testDebugUnitTest :feature:feature-settings:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```
