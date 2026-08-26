# Session Handoff — 2026-08-26

Working notes for picking this branch up on another machine. Everything below is
**uncommitted** in the working tree on branch `polishing-UI` (no commits were made in this
session — do not assume the git log reflects any of it). Note: `multi-disc-next-session.md`
claims "No uncommitted work remains"; that predates this session and is now inaccurate.

## What shipped this session (all uncommitted)

### 1. Content-based disc region detection (schema v40) — the big one

Detects a disc's TV format/region from the **image content, never the filename**, and uses it to
refine multi-disc set membership. Platforms: psx, ps2, psp, gc, wii, saturn, dreamcast, segacd,
x360, ps3. PC Engine deliberately excluded (no reliable in-image marker).

- `core/core-domain/.../model/GameRegion.kt` (new) — enum `NTSC_U`/`PAL`/`NTSC_J` + `fromName`.
- `core/core-domain/.../model/Game.kt` — new `region: GameRegion?` field (default null).
- `core/core-data/.../entity/GameEntity.kt` — `region` TEXT column + both mappings; schema v40.
- `core/core-data/.../PFPDatabase.kt` — `version = 40`, `MIGRATION_39_40`.
- `core/core-data/.../di/DatabaseModule.kt` — registers `MIGRATION_39_40`.
- `feature/feature-library/.../scanner/DiscRegionDetectors.kt` (new) — pure object, unit-testable:
  `detectPsx` (license string "Licensed by Sony…America/Europe/Inc." + SLUS/SLES/SLPS serial
  fallback), `detectPs2` (`REGION=` in SYSTEM.CNF + serial), `detectPsp` (UMD_DATA.BIN product
  code ULUS/ULES/ULJM), `detectBootBin` (GC/Wii region byte at 0x58 of boot.bin, game-id char
  fallback, magic guard), `detectIpBin` (Saturn/DC/SegaCD region char), `detectX360` (XEX2
  Execution Info game-region bitfield), `detectPs3Sfo` (TITLE_ID BLUS/BLES/BLJM in PARAM.SFO).
  All conservative: ambiguity → null (Unknown), which never mis-splits.
- `feature/feature-library/.../scanner/DiscRegionReader.kt` (new) — `@Singleton` Hilt, raw-path
  + SAF document-URI head reads; resolves `.cue`/`.gdi` sheets to the first data-track `.bin`;
  capped reads (256 KB psx/ps2, etc.); soft failure → null.
- `DiscSetBuilder.kt` — new `RegionReader` fun-interface; region read once per path; **sibling
  disc folders whose detected regions genuinely disagree split into separate sets** (only when
  EVERY member has a known region); unknown falls back to merging; detected region persisted on
  rows; `reconcile` diffs `region` so rescans heal stored rows.
  ⚠️ Signature note: `assign`/`reconcile` are now
  `(games, regionReader = default, m3uReader)` — **m3uReader is the trailing lambda**.
  Production callers pass `discRegionReader::read` positionally then `m3uPlaylistReader::read`.
- Wiring: `RomScanner` (3 call sites) and `DiscSetReconciler` inject `DiscRegionReader` and pass
  it through; `LibraryScannerTest` constructs the reconciler with the new arg.
- Tests: `DiscRegionDetectorsTest` (new, 17 cases), `DiscSetBuilderTest` region cases,
  `Migration39To40Test` (new).

Caveats: `.chd`/`.pbp`/`.cso`/`.wbfs`/`.rvz` containers report Unknown (no parser yet) → fall
back to merging — safe. PS3 ISOs (SFO lives inside the UDF fs, not the head) usually report
Unknown → safe merge. Region is stored but not surfaced in the UI yet.

### 2. XMB context-menu disc picker (direct-launch path)

"Choose Disc" in the game's Y/△ context menu so direct-launch users can boot a specific disc
without opening Game Detail.

- `XMBViewModel.kt` — `activeGameDiscId` in state (cleared on close); `openGameContextMenu`
  does an async disc-set lookup and adds **Choose Disc** only for multi-disc sets;
  `openDiscPickerMenu` second-level submenu (primary marked with a checkmark);
  `disc_pick_`/`choose_disc` dispatch; picking a disc plays the launch sfx and opens Game Detail
  with auto-launch (same path a direct-launch confirm uses).
- `XMBShell.kt` / `GameDetailScreen.kt` — new `initialDiscId` param threaded through.
- `GameDetailViewModel.kt` — `loadGame(id, requestedDiscId)` selects the requested disc when it
  is a set member, else falls back to primary.
- Tests: 3 new `GameDetailViewModelTest` cases.

Behavior note: picking a disc always boots it immediately (direct-launch-consistent), regardless
of the Launch Games Directly setting.

### 3. Per-disc subfolder unification (folder normalization)

`DiscSetBuilder.discNormalizedFolder()` / `cleanedFolderSegment()` — the set key's folder
component is cleaned like a title: trailing disc tag stripped, then `cleanRomTitle` removes
region/revision tags. So `Parasite Eve II (USA) (Disc 1)/` + `Parasite Eve II (Disc 2)/`
unify into one set (Disc 1 primary), while structurally different folders (NA/ vs EU/) stay
apart. `DiscSetReconciler` re-derives keys over existing rows every scan, so already-scanned
rows heal on the next rescan.

### 4. Tombstone retirement (removed games re-add on any scan)

Per user decision: removed games always come back on the next scan.

- `XMBViewModel.removeGameFromLibrary` — plain row delete, no tombstone write; dropped
  `ScanTombstoneDao` injection.
- `ExistingRomPathResolver` — no longer folds tombstones into the existing-path set.
- `MemoryCardRepository.remove` — dropped the tombstone clear.
- `ScanTombstoneEntity` / `ScanTombstoneDao` — marked retired but kept (Room schema stability).
- `docs/adr/0001-...` — policy updated.
- Existing tombstone rows become inert immediately (nothing reads them) — no migration needed.

### 5. Migration crash fix (partial index) — bug found in this session

**Symptom (reproduced live on the AYN Thor)**: app crashed on every launch with
`IllegalStateException: Migration didn't properly handle: games`; DB stuck at schema v39.

**Root cause**: `MIGRATION_38_39` (and the `DatabaseModule` `onCreate` callback) created a
**partial** unique index `index_games_one_disc_primary` via raw SQL. Room cannot express partial
indexes in its schema export, so post-migration validation saw an unexpected index and refused
to open the DB. Any DB that took the 38→39 path crashed forever (validation runs before commit →
version stays 39 → retry every launch).

**Fix**:
- `MIGRATION_38_39` — no longer creates the index (added defensive
  `DROP INDEX IF EXISTS index_games_one_disc_primary`).
- `MIGRATION_39_40` — now `DROP INDEX IF EXISTS index_games_one_disc_primary` **before**
  `ALTER TABLE games ADD COLUMN region TEXT` (heals broken v39 DBs).
- `DatabaseModule` — removed the `onCreate` callback that created the index on fresh installs.
- One-primary-per-set invariant is enforced app-side by `DiscSetBuilder`/`DiscSetReconciler`
  every scan (the DB-level guard was belt-and-suspenders).
- Tests: `Migration39To40Test` gained a regression test that reproduces the broken state
  (v39 + the partial index) and asserts the migration drops it and validates cleanly;
  `Migration38To39Test` comments/drop updated.

**Verified on device**: `./gradlew :app:installFullDebug` (full flavor; lite adds `.lite`
suffix), launched over existing data → no crash, DB migrated to v40, index gone, `region`
column present, app stable. Keep partial-index raw SQL out of migrations permanently — anything
a migration/`onCreate` creates that the schema export can't represent trips validation on the
next schema bump.

## Verification

```bash
./gradlew :feature:feature-library:testDebugUnitTest :core:core-data:testDebugUnitTest \
  :feature:feature-xmb:testDebugUnitTest :feature:feature-settings:testDebugUnitTest
./gradlew compileDebugKotlin
./gradlew :app:installFullDebug   # deploy to device (preserves app data)
```

feature-library lint is clean; core-data lint has 2 pre-existing errors in
`NetworkMonitor.kt` (untouched by this work).

## Open items / next steps

- [ ] Parse compressed containers (`.chd` first — PS1/PS2/Saturn/DC/GC/Wii) to detect region
      from the inner boot data instead of reporting Unknown.
- [ ] Surface detected region in the UI (Game Detail badge / library filter).
- [ ] Backlog from `multi-disc-next-session.md` still open: disc-count badge on set rows,
      set-level artwork decision, on-device E2E with a real sibling `.m3u`.
- [ ] Future migration (v41+) should defensively `DROP INDEX IF EXISTS
      index_games_one_disc_primary` for any install that predates this fix.
