# Windows Game Export

> Direct request, 2026-09-11. Indexed as `C18` in [the plan index](README.md).
> Not started. Work the Execution Task Index in order, one bounded task per helper.
> Every file and symbol below was read on `artwork-revisions`; nothing is assumed.

## Context

Windows games reach PlayFieldPortal as GameNative and Winlator export files (Library ▸ Import PC
Games ▸ Scan Import Folder), as pinned shortcuts, or as captured `INSTALL_SHORTCUT`s. On a fresh
install, or after the library is rebuilt, the games come back but their artwork often does not
reconnect to the right game, and every Change Match the user confirmed is gone.

The user wants an **export file** that restores Windows game entities, in the spirit of the
GameNative/Winlator import files. Decisions taken with the user (2026-09-11):

1. **Format:** modelled on the GameNative and Winlator import files, and read by the same import
   flow.
2. **Artwork is not in the file.** The user reconnects it by relinking; the file only has to make
   that relink land one-to-one.
3. **Entries for games that are not installed are skipped**, not created. The user also flagged
   that the *current* import has this problem: it creates entries for games that aren't installed.

## Current Behavior

- **Import files.** `RomScanner.scanPcFolder` (`RomScanner.kt:556`) walks a picked folder, or
  `<ROM root>/windows/import`, for `.steam .epic .gog .amazon .pcgame` (GameNative: the file body
  is the store app id) and `.desktop` (Winlator: launched by path). The title is the file name
  without its extension (`:576`).
- **Import.** `PcGameScanner.scan` (`PcGameScanner.kt:59`) builds a launch intent for each file
  and upserts a `windows` game when neither `getByIntentUri` nor the title dedupe
  (`findWindowsGame`, `:208`) finds one. Its only gate is that a launcher *app* is installed
  (`buildPcLaunch`, `:168`). Nothing checks whether the *game* is installed, so a stale export
  file becomes a library entry.
- **Missing games.** `is_missing` is only ever set from `rom_path` (`GameDao.markMissing`,
  `GameDao.kt:265`). A Windows game has no `rom_path`, so it can never be marked missing.
- **Identity kept today.** `storefront` + `storefront_game_id` (C16 task 0.5, backfilled by
  42→43), the provider ids `ss_id` / `tgdb_id` / `igdb_id` / `steam_grid_db_id`, `scraped_title`
  and `user_title_override`.
- **How artwork is named.** A new portable name is
  `PortableNameResolver.fromTitle(userTitleOverride ?: scrapedTitle ?: title)`, with " (2)" on a
  collision (`RoutingArtworkStore.kt:424-430`), plus `_NN` for extra screenshots and videos.
- **How relink reconnects.** `ArtworkImportManager.relinkLibrary` (`:293`) tries **records
  first**: a file whose stem equals an existing `artwork_records.portable_name` goes to that
  record's game (`:329-333`, `:382`). Otherwise the fuzzy `ArtworkImportMatcher` matches the file
  against `userTitleOverride ?: scrapedTitle ?: title` (`:312`); Windows games have no ROM stem.

## Root Cause

After a reinstall the records are gone, so relink has only the fuzzy title match, and a
re-imported Windows game's title is its export *file name*. Artwork was saved under the name the
game had *then*: the scraped title, a user override, or a " (2)" collision name. When those
differ, the file is an orphan or reaches the wrong game. The one piece of data that would make
relink exact, the portable name each file was saved under, is exactly what is lost.

## Goals

1. Export every Windows game's identity and artwork names to one file, with no artwork bytes.
2. Import restores that identity onto Windows games **already in the library**, and skips the rest.
3. After an import, relink reconnects each exported artwork file to its game by exact name.
4. The GameNative/Winlator scan stops creating entries for games that are not installed, as far
   as the device can tell (task W.6, gated on an investigation).

## Non-Goals

- Artwork files in the export (user decision).
- Creating a game from an export entry. Games only come from launcher exports, pins or captures.
- Favorites, notes, collections, play time and settings. The full backup (`BackupManager`)
  already carries them, and they do not affect artwork mapping.
- Text metadata. A restored `ss_id` lets Update Metadata fetch it again by id.
- Consoles and ROM games. Their artwork already reconnects by ROM stem.

## Design

**File.** One JSON document, `PlayFieldPortal-windows-<yyyyMMdd>.pfpwin`, with
`"format": "pfp-windows-games"`, `"version": 1` and a `games` array. Each entry:

| Field | Source | Used for |
|---|---|---|
| `title`, `scrapedTitle`, `userTitleOverride` | `games` | matching; restoring the names artwork was saved under |
| `storefront`, `storefrontGameId` | `games` | matching (the pair, never the id alone) |
| `launcher`, `winlatorShortcutPath` | the launch intent (`shortcut_path` extra) | matching a Winlator game, which has no store id |
| `ssId`, `tgdbId`, `igdbId`, `steamGridDbId` | `games` | restoring confirmed matches |
| `artwork[]`: `kind`, `sortOrder`, `portableName` | `artwork_records` | the exact names relink needs |

Decoding is lenient (`ignoreUnknownKeys`): an unknown `version` above 1 is refused with a
message, never half-read.

**Import path.** `scanPcFolder` also recognises `.pfpwin`. `PcGameScanner.scan` handles
`.pfpwin` files **after** the launcher exports in the same pass, so a game imported by that scan
can already be matched.

**Matching an entry to a game**, strongest first, and only among `windows` games:

1. **Storefront pair.** `(storefront, storefrontGameId)` equal. A Steam 620 is never a GOG 620.
2. **Winlator shortcut path**, equal to the game's `shortcut_path`.
3. **Normalized title**, `PcGameScanner`'s letters-and-digits rule applied to any of the entry's
   three titles and the game's. Only a **unique** hit counts; two candidates is a skip.

No hit means the game isn't in the library (not reinstalled), so the entry is **skipped** and
counted.

**Applying an entry.** Fill-only, and nothing the user set on the new install is overwritten:

- A provider id is written only when the game's column is null.
- `scraped_title` is written only when null.
- `user_title_override` is written only when the game has none.
- The storefront pair goes through the existing `updateStorefrontIdentity` (already fill-only).
- The `artwork[]` names are handed to relink (see D1).

**Report.** "Restored N Windows games · skipped M not in the library · K ambiguous". It shows in
the Library Manager message row the scan already uses.

## Decisions Needed

**D1. How the artwork names reach relink.** Relink reconnects by exact name only through
`artwork_records`, and those rows need a real document URI, so the names can't simply be
inserted as records.

- **(a) Relink runs inside the import (recommended).** When an artwork folder is linked, the
  import calls `relinkLibrary(claims)`, where `claims` is the entries' `(platform, kind, name) →
  gameId` map, consulted where `ownersByName` is today. No schema change. If no folder is linked,
  the import restores identity only and says a relink after linking will fall back to title
  matching.
- **(b) Persist the claims** in a new `artwork_name_claims` table (migration 43→44) that every
  later relink consults until a claimed file is found. This fits "reconnect when relinking" as a
  separate step, but costs a migration and a table nothing else uses.

**D2. Where Export lives.** Recommended: an **Export Windows Games** row under Library ▸ Import
PC Games ▸ Exported Games, beside Scan Import Folder, saving through `CreateDocument`. This is the
app's first `CreateDocument` use; `BackupManager` writes into a chosen folder instead.

**D3. What "not installed" can mean (W.6).** It must be established before it is coded:

- **GameNative:** does it delete its export file when a game is uninstalled? If it does, a stale
  file means nothing. If not, PFP has no signal at all: no GameNative API is used in the tree.
- **Winlator:** a `.desktop` is itself the shortcut file inside the container, so its presence may
  be enough, or the prefix path it points at may need checking.

## Execution Task Index

| ID | Task | Depends On | Status |
|---|---|---|---|
| W.1 | Pure `.pfpwin` model and codec: encode, lenient decode, version refusal | None | READY |
| W.2 | Pure entry→game matcher: storefront pair → Winlator path → unique normalized title, else skip | W.1 | READY |
| W.3 | Exporter plus the Library Manager row (D2) | W.1, D2 | BLOCKED on D2 |
| W.4 | Importer: `.pfpwin` in the folder scan, after launcher exports; fill-only apply; report | W.2 | READY |
| W.5 | Artwork reconnect by exported names (D1) | W.4, D1 | BLOCKED on D1 |
| W.6 | Current scan skips games that are not installed | D3 | BLOCKED on D3 |

### W.1: `.pfpwin` model and codec
- **Scope:** `@Serializable` models plus `WindowsGameExportCodec.encode/decode`, pure Kotlin in
  `feature-settings` `pc/` (beside `PcGameScanner`).
- **Tests first:** `WindowsGameExportCodecTest`:
  - a round trip;
  - an unknown field is ignored;
  - `version: 2` is refused with a message;
  - a missing `games` array decodes as empty;
  - an entry with no title and no store pair is dropped.
- **Stop:** codec green. No I/O, no UI.

### W.2: Matcher
- **Scope:** `WindowsExportMatcher.match(entry, windowsGames): Match | Skip(reason)`, pure.
- **Tests first:**
  - the pair beats the title;
  - a Steam 620 never matches a GOG 620;
  - the Winlator path matches with no store id;
  - a title matches on any of the three entry titles;
  - two title hits are skipped as ambiguous;
  - no hit is skipped as not in the library;
  - a non-`windows` game is never a candidate.

### W.3: Exporter
- **Scope:**
  - read `windows` games plus their `artwork_records` (kind, sort order, portable name);
  - read `shortcut_path` out of `launch_intent_uri` (pure, beside `StorefrontIdentity`'s parser);
  - write through `CreateDocument`;
  - add the row (D2).
- **Tests first:**
  - the exporter maps a game with two screenshots to two `artwork` items;
  - a game with no artwork exports with an empty list;
  - a non-Windows game is not exported.

### W.4: Importer
- **Scope:**
  - `PC_EXPORT_EXTENSIONS` gains `pfpwin`;
  - `PcGameScanner.scan` defers `.pfpwin` files until the launcher exports are done, then decodes,
    matches (W.2) and applies fill-only;
  - the report text.
- **Tests first:**
  - a populated column is never overwritten;
  - a null provider id is filled;
  - an entry with no game is counted as skipped and creates no row;
  - a game created earlier in the same scan is matched.
- **Do not change:** how `.steam`/`.desktop` files themselves import (that is W.6).

### W.5: Artwork reconnect (after D1)
- **(a):** `relinkLibrary` gains an optional claims map consulted before the fuzzy matcher, and
  the import calls it. Tests: a claimed name reconnects to its game even when the game's title
  differs; a " (2)" name reconnects; an unclaimed file still goes through the fuzzy matcher.
- **(b):** the migration, its `Migration43To44Test`, the DAO, and relink consulting it.

### W.6: Skip uninstalled games in the current scan (after D3)
- **First:** check on device what GameNative and Winlator leave behind after an uninstall, and
  record the result here.
- **Then:** skip (never delete) an export file with no installed game. An existing row is left
  alone, since removing a game is a user action. Stop and report if there is no reliable signal.

## Verification

- Unit: the tests listed per task (feature-settings, feature-artwork for W.5).
- Device, once:
  1. Export on the Thor.
  2. Clear app data, and re-import the launcher exports.
  3. Import the `.pfpwin`, then relink.
  4. Every Windows game has its artwork back, including a game whose artwork was saved under a
     scraped or overridden title, and its confirmed ScreenScraper match reads "Confirmed".
