# C1 - Multi-disc games as one library entry

Source: direct request. Reported symptom: a multi-disc game shows as one game under Dreamcast, PSX,
and other disc-based platforms.

## What the code actually does today

There is no concept of a disc set anywhere in the library. Three separate mechanisms combine to
produce the confusing result:

**1. The disc tag is stripped from the title.**
[`cleanRomTitle`](../../feature/feature-library/src/main/kotlin/com/playfieldportal/feature/library/scanner/RomScanner.kt)
removes every parenthesised group: `"Final Fantasy VII (Disc 1) (USA)"` becomes
`"Final Fantasy VII"`. Every disc of a set therefore gets an identical display title. Rows are
still distinct in the database - `rom_path` is uniquely indexed
([`GameEntity.kt:18`](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/entity/GameEntity.kt))
- but on screen they are indistinguishable, and once artwork matches by simplified title they are
visually one game repeated N times. Anything that keys off title collapses them for real:
`ArtworkImportMatcher` already has to special-case multi-disc ambiguity.

**2. The disc resolver never runs on the main scan path.**
[`DiscImageResolver`](../../feature/feature-library/src/main/kotlin/com/playfieldportal/feature/library/scanner/DiscImageResolver.kt)
suppresses `.bin` companions of a `.cue` and is called from `RomScanner.scan` (the ROM-root walk).
It is **not** called from `scanDirectory` or `scanTree`, which are the Memory Card scan paths that
`LibraryManagerViewModel.scanConsole` actually uses. Those paths filter purely by the platform's
`romExtensions`, and `psx` is seeded as `"cue,bin,iso,pbp,chd,ecm,mds,m3u"`
([`PlatformSeeder.kt:31`](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/seeder/PlatformSeeder.kt)),
so on a PSX Memory Card every `.bin` becomes its own game row alongside its `.cue`.

**3. `.m3u` is treated as just another ROM.** It is in `romExtensions` for psx, saturn, dreamcast,
segacd, and pcengine, and in `contextDependentExtensions` but in neither `definitiveExtensions` nor
`folderSensitiveExtensions`. So the Memory Card paths add the playlist as a game row on top of the
per-disc rows, while the ROM-root walk skips it entirely. An `.m3u` is the one file that genuinely
represents the whole set, and it is the one PFP handles least deliberately.

Net effect for a typical PSX folder containing three `.cue`, three `.bin`, and one `.m3u`: seven
rows, six of them titled "Final Fantasy VII", none of them marked as belonging together.

## Confirm the symptom before building (do this first)

The described symptom - "only showing as one game" - is consistent with the title collapse in (1),
but the mechanisms above can also produce the opposite (too many rows). Before writing code,
reproduce on the reporting device and record:

- the exact folder layout and file extensions for one affected PSX title and one Dreamcast title
- whether the library came from a Memory Card scan or a ROM-root scan
- the row count in `games` for that title (`SELECT title, rom_path FROM games WHERE title LIKE ...`)

Whichever direction it turns out to be, the target design below fixes both, but the repro decides
which regression test is the primary one.

## Target behaviour

- One library entry per multi-disc game, showing the game's real title.
- Launching it launches the right disc: the `.m3u` when one exists (the emulator then handles disc
  swapping itself), otherwise disc 1, with a disc picker for choosing another.
- Each disc keeps its own row in the database - paths, play sessions, and achievements stay
  per-disc - but only the set's primary entry is projected into the library.
- Artwork, favorites, and collections attach to the set, not to a disc.

## Approach

### 1. Parse the disc tag instead of deleting it

Add a pure `DiscTag` parser covering the tag forms real dumps use, before `cleanRomTitle` strips
them:

- `(Disc 1)`, `(Disc 1 of 3)`, `(Disk 1)`, `(CD1)`, `(CD 1)`, `[Disc 1]`
- localised and No-Intro / Redump variants worth supporting: `(Disc 1) (Rev 1)` ordering, and a
  trailing `- Disc 1` form
- returns `discNumber`, `discTotal` (nullable), and the title with only the disc tag removed

`cleanRomTitle` keeps its current behaviour for everything else; the disc tag becomes structured
data rather than discarded text.

### 2. Give games a set identity

Two new nullable columns on `games` plus a migration:

- `disc_set_key TEXT` - the normalized set identity: platform id plus the disc-stripped, region-
  and revision-stripped title, plus the containing folder. Folder is part of the key so two
  different dumps of the same game in different folders do not merge.
- `disc_number INTEGER`
- `is_disc_primary INTEGER` - exactly one row per set

The uniqueness of `rom_path` is unchanged. This is additive; existing rows get NULL and behave
exactly as today until a rescan populates them.

### 3. Make `.m3u` the set primary when present

Where an `.m3u` sits beside the discs it lists:

- parse its entries, resolve them against scanned paths
- the `.m3u` row becomes `is_disc_primary = 1` with `disc_number = NULL`
- the listed disc rows join the same set and are not primary

This is the correct behaviour for RetroArch and most standalone emulators: launching the playlist
gives working in-emulator disc swapping, which launching a bare `.cue` does not.

### 4. Run disc resolution on the Memory Card paths

`scanDirectory` and `scanTree` must apply the same companion-suppression that `scan` gets from
`DiscImageResolver`, so a `.bin` listed in a sibling `.cue` never becomes a game row. For
`scanTree` this means reading the `.cue` through SAF; `DiscImageOpener` in `feature-achievements`
already follows a `.cue` to its `.bin` over SAF and is the model to follow - consider lifting that
logic into a shared helper rather than writing it twice.

Dreamcast `.gdi` sets need the same treatment: a `.gdi` references `trackNN.bin` / `.raw` files
that must never appear as games. Dreamcast's seeded `romExtensions` happens to exclude `bin`, which
hides the problem today but does not fix it for a user who edits the extension list.

### 5. Project the set as one row

The library projection returns the primary row for each set and carries `discCount`. Everything
downstream - platform counts, All Games, Favorites, Missing - counts the set once. If
[A4](library-projection-module-plan.md) has landed, this belongs in `LibraryProjection`; if not,
add it at the DAO query level (`observeByPlatform`, `observeGamesOnly`, `countGamesByPlatform`)
with a `WHERE disc_set_key IS NULL OR is_disc_primary = 1` clause.

### 6. Disc picker on game detail

When `discCount > 1`, game detail shows a disc selector. Selecting a disc launches that disc's
path through the normal resolver. Default action stays "launch the primary".

### 7. Missing-bucket semantics

A set is missing only when every disc is missing. A set with some discs present is present, and
surfaces a per-disc warning on detail. Feed this through `LibraryReconciler` so the Missing bucket
added in `6dbf8d5` does not start flickering whole games in and out because one disc moved.

## Files touched

- New: `feature/feature-library/.../scanner/DiscTag.kt` (pure parser), `DiscSetBuilder.kt`
- `RomScanner.kt` (`cleanRomTitle`, `scan`, `scanDirectory`, `scanTree`)
- `DiscImageResolver.kt` (`.gdi` handling, shared SAF cue reading)
- `GameEntity.kt` plus a Room migration; `GameDao.kt` library queries
- `LibraryReconciler.kt` (set-level missing)
- `GameDetailScreen.kt` / `GameDetailViewModel.kt` (disc picker)
- `ArtworkImportMatcher.kt` / `ArtworkNaming.kt` - `slug` already emits `-discN`; decide whether
  set-level artwork uses the set key instead

## Tests

Pure parser (`DiscTagTest`):

- every tag form above parses to the right number and stripped title
- `"Final Fantasy VII (Disc 1) (USA)"` and `"Final Fantasy VII (USA) (Disc 1)"` produce the same
  set key
- a title containing the word "disc" that is not a tag (`"Disc Jam"`) is not misparsed
- a game with no disc tag gets a NULL set key and is unaffected

Set building (`DiscSetBuilderTest`):

- three `.cue` files in one folder form one set with three discs and disc 1 primary
- an `.m3u` beside them takes over as primary and the `.cue` rows stop being primary
- an `.m3u` listing files that were not scanned does not create a set
- two same-named games in different folders do not merge
- Dreamcast: `Game (Disc 1).gdi` plus its `trackNN.bin` files produce one disc, no track rows
- PSX Memory Card scan: `.cue` plus `.bin` pairs produce one row per disc, no `.bin` rows

Projection and reconcile:

- platform count counts a three-disc game once
- a set with two of three discs present is not marked missing
- a set with zero discs present is marked missing

Migration:

- existing rows migrate with NULL set keys and render exactly as before
- a rescan after migration populates set keys without duplicating any row

## Risks

- **Over-merging is worse than under-merging.** A key that is too loose merges genuinely different
  games (a demo and a full release, two regions). Include the folder in the key, keep region and
  revision out of the *title* but let the folder disambiguate, and require the same platform.
- **`rom_path` uniqueness must not change.** Every downstream dedupe, tombstone, present-path
  union, and reconcile step depends on it. This plan only adds columns.
- **Achievements matching is per-disc by design.** RetroAchievements hashes a specific disc image.
  Do not collapse the achievement identity along with the library row.
- **The migration must be reversible in effect.** With NULL set keys everything behaves as today,
  so a bad rollout can be recovered by clearing the two columns.

## Done when

A three-disc PSX game and a two-disc Dreamcast game each appear once in their platform row with the
correct title, launch into a working disc, expose a disc picker, count as one game, and go missing
only when all their discs do - on both the ROM-root and Memory Card scan paths.
