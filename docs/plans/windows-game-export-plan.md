# Manual PC Game Export

> Direct request, 2026-09-11. Indexed as `C18` in [the plan index](README.md).
> Not started. Work the Execution Task Index in order, one bounded task per helper.
> Every file and symbol below was read on `artwork-revisions`; nothing is assumed.
>
> **Rewritten 2026-09-11.** The first draft of C18 restored identity onto games already in the
> library and never created a game. That misread the request, which is below.

## The request

GameNative and Winlator can export a game to a file, and PFP's Scan Import Folder reads those files
to create the game. Games added **manually** have no such file. The user wants PFP to mirror that
export process for them, because **after a fresh install, artwork relink does not work for manually
added games**.

Decisions taken with the user (2026-09-11):

1. **An Export button**, pressed by the user, not an automatic write.
2. **Only games that cannot come back on their own** are exported: Add by ID games and legacy
   `INSTALL_SHORTCUT` captures. Games from GameNative/Winlator export files, and pinned shortcuts,
   are skipped.
3. **Exact artwork names.** The file carries the name every artwork file was saved under, and the
   import reconnects by those names before any fuzzy matching.
4. **Mirror GameNative:** one small file per game, in the folder Scan Import Folder already reads.
   No artwork bytes in the file.
5. **Pins are exported for artwork claims only** (D1). A pin comes back after a reinstall through pin
   reconcile, but under its shortcut label, so its artwork needs the exact names too. A pin entry never
   creates a game.
6. **Any single Windows game can be exported on its own** (user request, 2026-09-11): an **Export Game**
   entry in both Game Detail ▸ Options and the XMB tile's Triangle menu, offered for every Windows game
   with a launch intent or a pin, including games from GameNative/Winlator export files. Importing one of
   those matches the existing game instead of duplicating it, and still restores its artwork by exact
   name, which fuzzy relink cannot do for a game titled after its export file.

## Current Behavior

- **Add by ID** (`LibraryManagerViewModel.addPcGameById`, `:751-790`) builds a launch intent through
  `PcLauncherAdapters` and upserts one `windows` row (`:770-778`): the typed `title`, `packageName`,
  `isManualEntry = true`, `launchIntentUri`. Nothing is written to storage, and no storefront pair is
  recorded. GameHub-family ids come in two namespaces, `local_<uuid>` for local EXEs and numeric
  (`PcLauncherAdapter.kt:34-41`).
- **Legacy captures** (`PcShortcutImporter.importLegacyShortcut`, `:183-215`) store the captured
  `launchIntentUri`, plus a storefront pair when the intent names one.
- **Pins** (`PcShortcutImporter.importPinnedShortcut`, `:149-180`) store `packageName` + `shortcutId`,
  and no intent. After a PFP reinstall they come back through `reconcilePinnedShortcuts`, which also
  reads pins recorded by a previous install (`:76-80`).
- **Launcher export files** are read by `RomScanner.scanPcFolder` (`:556`) for
  `PC_EXPORT_EXTENSIONS` (`:67`: `.steam .epic .gog .amazon .pcgame .desktop`). The title is the file
  name and the body is the app id. `PcGameScanner.scan` (`:59`) turns each into a row
  (`:99-113`): **also `isManualEntry = true` with a `launchIntentUri`**, plus the storefront pair.
- **The import folder** is `<ROM root>/windows/import`, from `WindowsLibrarySetup.importFolders()`
  (`:142`). `SafFolderOps` can only create directories today (`:173-184`).
- **Artwork names.** A new slot's portable name is the ROM file name, else
  `PortableNameResolver.fromTitle(userTitleOverride ?: scrapedTitle ?: title)`, with `" (2)"` on a
  collision (`RoutingArtworkStore.kt:424-430`), plus `_NN` for extra screenshots and videos.
  `ArtworkRecordDao.getForGame` (`:21-22`) lists a game's records.
- **Relink** (`ArtworkImportManager.relinkLibrary`, `:293`) reconnects a file **exactly** when its stem
  equals a prior `artwork_records.portable_name` (`ownersByName`, `:329-333`, used at `:382-388`), and
  otherwise through the fuzzy `ArtworkImportMatcher` against
  `userTitleOverride ?: scrapedTitle ?: title` (`:312`). A Windows game has no ROM stem.
- **Launch safety.** `ShortcutIntentSanitizer.sanitize(raw, pm)` (core-common, `:34`) strips
  URI-permission grants and ClipData and pins the intent to an installed component.

## Root Cause

A fresh install deletes the database, so relink has no records and only the fuzzy title match. A
GameNative or Winlator game at least comes back, from its export file. An Add by ID game or a
capture comes back only if the user re-adds it, under whatever name they type. Its artwork was saved
under the name the game had *then* (scraped title, override, or a `" (2)"` collision name), so relink
either leaves the files orphaned or hands them to the wrong game. Nothing on storage records the game
or the names its files were saved under.

## Goals

1. **Export Manual Games** writes one file per game that cannot come back on its own, and one per
   pinned shortcut that has artwork, into the import folder, with no artwork bytes.
2. **Scan Import Folder** reads those files and recreates the games with the same launch intent,
   titles, storefront pair and confirmed provider matches.
3. The same scan reconnects each exported artwork file to its game **by exact name**.
4. Any one Windows game can be exported from its own menus, without exporting the rest.

## Non-Goals

- Automatic export on add, rename or match (user decision: a button).
- Exporting games from launcher export files (they come back on their own), or recreating pins (pin
  reconcile does that; pins are exported for artwork claims only).
- Artwork bytes, favorites, notes, collections, play time and settings. The full backup
  (`BackupManager`) carries those.
- Consoles and ROM games: their artwork reconnects by ROM stem.
- Deleting export files for games removed since. Re-exporting overwrites same-named files only.
- The current scan creating entries for PC games that are not installed. Reported by the user during
  the first draft, it is unrelated to this request and stays open as task `X.6`.

## Design

**File.** `<name>.pfpgame`, one JSON document per game, in the first folder returned by
`importFolders()`. `<name>` is `PortableNameResolver.fromTitle(displayTitle)`, with `" (2)"` when two
exported games share a name. A re-export overwrites a same-named file rather than letting SAF append
`(1)`. `.pfpgame` does not collide with GameNative's `.pcgame`.

```json
{
  "format": "pfp-pc-game", "version": 1,
  "title": "…", "scrapedTitle": "…", "userTitleOverride": null,
  "launcherPackage": "banner.hub",
  "launchIntentUri": "intent:#Intent;…;end",
  "shortcutId": null,
  "storefront": "STEAM", "storefrontGameId": "620",
  "ssId": 425726, "tgdbId": null, "igdbId": null, "steamGridDbId": 5483322,
  "artwork": [ { "kind": "ICON", "sortOrder": 0, "portableName": "Portal 2" } ]
}
```

Decoding is lenient (`ignoreUnknownKeys`). A non-JSON body or another `format` is rejected, and a
`version` above 1 is refused with a message, never half read. Every entry needs a title and a
`launcherPackage`. An entry with a `shortcutId` is a **pin entry**: any `launchIntentUri` in it is
dropped, so a pin entry can never create or launch anything. Any other entry needs a
`launchIntentUri`. Artwork items with no kind or name are dropped. Because the file is on shared
storage, a body over 256 KB or with more than 200 artwork items is rejected.

**The launch intent is kept verbatim**, not rebuilt from a launcher id, because a legacy capture has
no id to rebuild from. The file lives on shared storage, so the import **never trusts it**: the
intent must parse, `launcherPackage` must be a verified PC launcher
(`PcLauncherCatalog.isVerifiedPcLauncher`) and the intent's own package, and
`ShortcutIntentSanitizer.sanitize` must accept it. The sanitized intent's URI is what gets stored.
Anything else is skipped and counted.

**Which games are exported.** From the `windows` games:

1. A pin (`shortcutId != null`) is exported as a pin entry when it has artwork records, and skipped
   when it has none. Rules 2 and 3 do not apply to it.
2. Skip a row with no `launchIntentUri`.
3. Skip a row that a launcher export file in the import folders would recreate: its intent URI is
   one the scan would build from a file, or its storefront pair equals a file's
   (extension → store, body → id), or its Winlator `shortcut_path` extra equals a `.desktop` file's
   raw path.
4. Export the rest. That is Add by ID games and captures, including a GameNative game whose export
   file the user has since deleted.

Rule 3 is needed because a scanned row and an Add by ID row look the same in the database
(`isManualEntry = true` plus a launch intent, `PcGameScanner.kt:99-113` vs
`LibraryManagerViewModel.kt:770-778`). No column records how a game arrived, and adding one would
cost a migration and still say nothing about rows that already exist.

**Import.** `.pfpgame` joins the folder scan and is handled **after** the launcher export files in
the same pass, so a game those files just recreated is found instead of duplicated. Each entry, after
the safety check, goes to the first match of:

1. the sanitized intent URI (`getByIntentUri`);
2. the storefront pair, among `windows` games;
3. the launcher (the whole GameHub family counts as one launcher, since its variants ship under
   several package names) together with the normalized title of any of the entry's three titles.

On a match the row is **filled, never overwritten**: a null provider id, `scraped_title`,
`user_title_override` or storefront pair takes the file's value. With no match the game is
**created** from the file (title, titles, package, sanitized intent, storefront pair, provider ids,
`isManualEntry = true`), like a launcher export. Either way the entry's `artwork[]` becomes
**claims**: `("windows", kind, portableName.lowercase()) → gameId`.

**A pin entry is matched, never created.** It matches by `launcherPackage` + `shortcutId`
(`GameRepository.getLauncherShortcut`, after the scan's own pin reconcile has run), then by launcher and
normalized title. A match is filled like any other and gives its claims. An unmatched pin entry is
skipped and counted.

**Relink.** When the pass collected claims, it calls `relinkLibrary(claims)`. Claims join
`ownersByName` before any file is matched, so a claimed name reconnects exactly, `" (2)"` and
overridden titles included, and an unclaimed file still goes through the fuzzy matcher. Relink
writes each reconnected record with `portable_name = fileStem` (`ArtworkImportManager.kt:458`), so
later relinks stay exact with no claims at all. With no artwork folder linked, `relinkLibrary`
returns null and the report says to relink after linking.

**Reports.**
- Export: "Exported N games to windows/import · M come back on their own".
- Scan: the existing "Imported N PC game(s)" counts `.pfpgame` games, plus "reconnected K artwork
  files" and "skipped S export files that could not be trusted".

## Decisions

**D1. Pinned shortcuts' artwork. Decided 2026-09-11: pins are exported for claims only.** A pin comes
back after a reinstall, but its title is the shortcut label, not the name its artwork was saved under,
so its relink had the same fuzzy-only problem. The import matches a pin entry and never creates a row,
since `reconcilePinnedShortcuts` does that.

## Rejected Alternatives

- **Rebuilding the intent from a launcher id** instead of storing it. It would survive a switch between
  GameHub variants, but a legacy capture has no id, and two paths would have to be secured instead
  of one.
- **A provenance column** recording how each game arrived. A migration, and blind for every existing
  row, which is exactly the library the user wants to export.
- **One file for all games.** Unlike the launchers' model, and one corrupt write would lose every
  game's entry.
- **Automatic export** (user decision).

## Execution Task Index

| ID | Task | Depends On | Status |
|---|---|---|---|
| X.1 | Pure `.pfpgame` model and codec: encode, lenient decode, version refusal, pin entries, invalid entries rejected | None | DONE (uncommitted; unit tests green) |
| X.2 | Pure export selection: pins with artwork as pin entries; skip intent-less rows and rows a launcher export file recreates | None | DONE (uncommitted; unit tests green) |
| X.3 | Exporter and the Export Manual Games row: read games and artwork records, write the files | X.1, X.2 | DONE (uncommitted; unit tests green, device check not reported) |
| X.4 | Importer: `.pfpgame` in the folder scan after launcher files; safety check; match, fill-only or create; collect claims | X.1 | DONE (uncommitted; unit tests green) |
| X.5 | Relink with claims, called by the scan when claims were collected | X.4 | DONE (uncommitted; unit tests green, fresh-install round trip pending) |
| X.6 | Current scan skips PC games that are not installed | Investigation | BLOCKED on what GameNative and Winlator leave behind after an uninstall |
| X.7 | Export Game for one Windows game, from Game Detail ▸ Options and the XMB Triangle menu | X.3 | IN PROGRESS |

Every task: **if blocked**, stop and report what was attempted, what blocked it, which file caused it
and what decision is needed (`PLANNING_WORKFLOW.md` §4). The user runs every Gradle command.

### X.1: `.pfpgame` model and codec
- **Scope:** `@Serializable` models plus `PcGameExportCodec.encode/decode`, pure Kotlin in
  feature-settings `pc/` beside `PcGameScanner`.
- **Tests first** (`PcGameExportCodecTest`):
  - a round trip with two artwork items;
  - an unknown field is ignored;
  - `version: 2` is refused with a message;
  - an entry with no title, no `launcherPackage`, or neither a `launchIntentUri` nor a `shortcutId` is
    rejected;
  - a pin entry keeps its `shortcutId` and loses any `launchIntentUri`;
  - another `format`, an oversized body and a non-JSON body are rejected, not a crash;
  - an artwork item with no name is dropped.
- **Stop:** codec green. No I/O, no UI.

**X.1 implemented (2026-09-11, uncommitted, not yet built).** `PcGameExport.kt` (model, `PcGameExportArtwork`,
`PcGameExportDecode`, `PcGameExportCodec`) and `PcGameExportCodecTest`, both in feature-settings `pc/`.
Decisions taken while landing it:
- **`decode` never throws.** It returns `Valid(export)` or `Rejected(reason)`, where `reason` completes
  "This export file …", so X.4 can count and log a rejected file without a try/catch.
- **The format is checked on the raw JSON tree before the model is decoded**, so a newer `version` is
  refused before any field is interpreted, and another JSON file is never read as a game.
- **A valid entry comes back normalized.** Titles and ids are trimmed, blank optional values become
  null, and non-positive provider ids become null, so X.4's fill-only apply can never write an empty
  value over a real one. A missing `version` reads as 1.
- **`encode` always stamps this version's `format` and `version`**, whatever the model carries, and
  writes pretty-printed JSON, since users open these files as they do the launchers' exports.
- Beyond the listed tests: the written file's format and version, a missing `format`, a JSON array or
  bare number as the body, too many artwork items, and blank or non-positive optional values.

### X.2: Export selection
- **Scope:** `ManualGameExportSelector.select(windowsGames, gamesWithArtwork, launcherFiles,
  reproducedIntentUris)`, pure. `reproducedIntentUris` is computed by the caller, which has
  `PackageManager`.
- **Tests first:**
  - a pin with artwork records is exported as a pin entry, and a pin with none is not;
  - a row with no intent is never exported;
  - a row whose intent URI a launcher file reproduces is skipped;
  - a row whose storefront pair matches a `.steam` file is skipped, and a Steam 620 file does not skip
    a GOG 620 row;
  - a Winlator row whose `shortcut_path` matches a `.desktop` file is skipped;
  - an Add by ID GameHub `local_<uuid>` row is exported;
  - a GameNative Add by ID row with no matching file is exported.

**X.2 implemented (2026-09-11, uncommitted, not yet built).** `ManualGameExportSelector` and its test in
feature-settings `pc/`, plus a core-data change. Decisions taken while landing it:
- **The intent-URI extras parser moved into core-data's `IntentUriExtras`.** It was private inside
  `StorefrontIdentity`, and the selector needs `S.shortcut_path` from the same stored string.
  `StorefrontIdentity.fromLaunchIntentUri` now delegates to it, so there is one parser.
- **That parser decoded `%XX` escapes one character at a time**, so a Winlator path with a non-ASCII
  name (`é` is `%C3%A9`) came out garbled and could never equal the scanned `.desktop` file's path.
  It now decodes UTF-8. Store ids and store names are ASCII, so `StorefrontIdentity`'s results,
  and the 42→43 backfill that uses it, are unchanged.
- **A row with no storefront columns is compared by the pair its intent names.** Add by ID never
  writes the columns, so without this a GameNative game added by id would be exported even when a
  `.steam` file for it exists.
- **`skipped` counts every game passed in that was not exported**, for the Export report.
- **Budget:** one test file over (`IntentUriExtrasTest` in core-data), since the parser change belongs to
  core-data and is tested there, not only through the selector.

### X.3: Exporter and button
- **Scope:**
  - `PcGameExporter` (feature-settings `pc/`) reads `windows` games and `ArtworkRecordDao.getForGame`,
    selects (X.2), encodes (X.1) and writes each file into `importFolders().first()` through
    `DocumentsContract.createDocument`, overwriting a same-named file;
  - `PcGameScanner` exposes the intent URIs its launcher files would build (from `buildPcLaunch`,
    unchanged);
  - a `LibraryManagerViewModel` function, and an **Export Manual Games** row under Exported Games
    (`LibraryManagerScreen.kt:699-705`), beside Scan Import Folder.
- **Tests first:** the exporter's pure half: a game with two screenshots exports two artwork items, a
  game with no artwork exports an empty list, and two games with one name get `" (2)"`.
- **Stop:** with no ROM root, the row says to add one and writes nothing.

**X.3 implemented (2026-09-11, uncommitted, not yet built).** New `PcGameExporter.kt`
(`PcGameExportBuilder`, `PcGameExporter`, `PcGameExportFile`, `PcGameExportReport`) and
`PcGameExportBuilderTest`. Modified: `PcGameScanner`, `LibraryManagerViewModel`, `LibraryManagerScreen`, and
`LibraryManagerViewModelTest` (constructor only). Decisions taken while landing it:
- **`PcGameScanner.launcherExports()`** returns the launcher export files in the import folders and the
  intent URIs `buildPcLaunch` builds from them. The three installed-launcher lookups moved into one
  private `installedLaunchers(pm)`, which the scan and the export share, so they cannot disagree about
  which launcher a file resolves to. The scan's behaviour is unchanged.
- **Files are written with `application/octet-stream`**, the mime `LocalSteamSchemaWriter` already uses
  for the same reason: a typed mime makes the storage provider append its extension
  (`Portal 2.pfpgame.json`).
- **A re-export opens a same-named file with mode `"wt"`** instead of creating a new one, so the folder
  never collects `Portal 2 (1).pfpgame`. Same-named means ignoring case, as FAT compares names.
- **File names come from `PortableNameResolver.fromTitle(displayTitle)`**, the sanitizer artwork file
  names use, with `" (2)"`, `" (3)"`, … among the exported games.
- **The builder skips a game the import would reject** (no launcher package, or neither an intent nor a
  shortcut), so the export never writes a file the scan then counts as untrusted.
- **The ViewModel took a new constructor argument** (`PcGameExporter`), the one test file change beyond the
  task's own; the existing test passes a relaxed mock.
- **The report** reads "Exported N PC game(s) to windows/import · M need no file · K couldn't be written",
  or says no game needs a file.

### X.4: Importer
- **Scope:**
  - `RomScanner.PC_EXPORT_EXTENSIONS` gains `pfpgame` (its body is already read as `idContent`);
  - `PcGameScanner.scan` defers `.pfpgame` files until the launcher files are done, then decodes,
    runs the safety check, matches, fills or creates, and collects claims;
  - the matching and apply decisions live in a pure `PcGameImportPlanner`, so they are testable
    without Android.
- **Tests first** (planner):
  - an intent for a package that is not a verified PC launcher is skipped;
  - an entry whose `launcherPackage` differs from the intent's package is skipped;
  - a populated column is never overwritten, and a null provider id is filled;
  - a game created from a launcher file earlier in the same pass is matched, not duplicated;
  - a BannerHub entry matches a game now launched through GameHub Lite, by title;
  - no match creates exactly one row;
  - a pin entry matches by launcher package and shortcut id, is filled but never created, and an
    unmatched pin entry is skipped;
  - every entry's artwork becomes claims keyed by lowercased name.
- **Do not change:** how launcher export files themselves import (that is X.6).

**X.4 implemented (2026-09-11, uncommitted, not yet built).** New `PcGameImportPlanner.kt`
(`WindowsGameKeys`, `LaunchCheck`, `PcGameImportDecision`, `PcGameImportSkip`, `PcGameImportPlanner`,
`PcGameArtworkClaims`) and `PcGameImportPlannerTest`. Modified: `RomScanner`, `PcGameScanner`,
`ManualGameExportSelector`, and the Scan Import Folder sublabel in `LibraryManagerScreen`. Decisions taken
while landing it:
- **`.pfpgame` files never reach `buildPcLaunch`.** The scan's launcher loop hands every export file to
  it, which returns null for an unknown extension, so the files would have been counted as "no matching
  launcher installed". They are held back and applied after the loop. X.3's `launcherExports()` filters
  them out too.
- **The trust check is split in two.** `PcGameScanner.checkLaunch` does the Android part: the launcher is
  installed and `isVerifiedPcLauncher` (which does not check installation for non-GameHub packages), the
  intent parses, and `ShortcutIntentSanitizer.sanitize` accepts it. The planner does the rest, purely:
  the intent's component or package must equal the entry's `launcherPackage`. A launcher that is not
  available is a *skip*; an intent that fails a check is *untrusted*; both are counted.
- **Only the sanitized intent is stored.** Matching by intent accepts either the file's text or the
  sanitized form, so a game re-added by hand is still found.
- **Match order:** same intent → same storefront pair (first fit) → same launcher and title (unique fit).
  Pins match by `packageName` + `shortcutId`, then launcher and title, and are never created. The
  launcher key treats the whole GameHub family as one launcher.
- **Fill-only apply** is `game.copy(…)` + `upsert`, the pattern `PcShortcutImporter` already uses on
  matched rows. A match with nothing missing writes nothing. The storefront pair is filled only as a
  pair, and only when the game has neither half.
- **Games created or filled in the pass are added to the in-memory list** the planner matches against,
  so two entries for one game converge.
- **Claims:** a name claimed by two different games is claimed by neither, so X.5's relink falls back to
  its own matching instead of guessing. They travel in `PcScanReport.artworkClaims`, which X.5 consumes;
  nothing reads them yet.
- **Shared keys moved into `WindowsGameKeys`:** the storefront-pair reading from X.2's selector and the
  scan's title normalization, so the export, the import and the dedupe use one definition each.
- **Size guard in `RomScanner`:** a `.pfpgame` over 256 KB is not read at all (`idContent = null`), so a
  hostile file on shared storage cannot be loaded into memory; the codec then rejects it.
- **`PcScanReport.newGames`** now counts games created from `.pfpgame` files, so the Windows card is
  ensured after a restore. The message reports restored, matched, skipped and untrusted counts.

### X.5: Relink with claims
- **Scope:** `relinkLibrary(claims: Map<Triple<String, String, String>, Long> = emptyMap())`. Claims
  are merged into `ownersByName` before matching, and `PcGameScanner` calls it when claims were
  collected, adding the result to the report.
- **Tests first:** extract the owner lookup (claims, then prior records, then the fuzzy matcher, then
  the ordinal-stripped base) into a pure function, and test: a claimed name reconnects when the game's
  title differs; a `" (2)"` name reconnects; `name_01` reconnects to its claimed base; an unclaimed
  file still reaches the fuzzy matcher. If the lookup cannot be extracted without restructuring
  `relinkLibrary`, stop and report.

**X.5 implemented (2026-09-11, uncommitted, not yet built).** New `RelinkOwnerLookup` (feature-artwork
`importer/`) and `RelinkOwnerLookupTest`. Modified: `ArtworkImportManager.relinkLibrary`, `PcGameScanner`,
`PcGameImportPlanner.kt` (`PcGameArtworkClaims.unresolved`), and two cases added to `PcGameImportPlannerTest`.
Decisions taken while landing it:
- **The lookup extracted cleanly**, as one call replacing the inline chain; the rest of `relinkLibrary` is
  untouched. `claims` is a defaulted parameter, so the two existing callers (Artwork settings and initial
  setup) are unchanged.
- **Order:** claim on the full stem → record on the full stem → claim on the ordinal-stripped base →
  fuzzy on the full name → record on the base → fuzzy on the base. With no claims that is exactly the
  old chain, pinned by a test. A claim on the base outranks fuzzy on the full name because a claim is
  exact evidence and fuzzy is a guess. It stays behind a record on the full stem, so C16 0.4's `_07`
  rule still holds for anything PFP wrote itself.
- **The scan relinks only when a claim is unfulfilled**: some claiming game has no record of that kind
  under that name. The `.pfpgame` files stay in the import folder and every scan reads them, the XMB's
  Scan This Console included, so without the check each scan would walk the whole artwork library.
  Relink writes each reconnected record under its file stem, so one successful relink makes later scans
  skip it.
- **Relink is the full Scan & Relink**, with its missing-record sweep, the same as pressing Relink in
  Artwork settings. It deletes records only, never files, and only with a live grant.
- **Outcomes are reported**: reconnected (with the games updated), artwork folder not linked (scan
  again after linking; claims are not persisted, the files are the persistence), or failed.
  `PcScanReport.artworkRelinkedGames` carries the count.
- **Budget:** one test file over (`PcGameImportPlannerTest` gained the `unresolved` cases), since the check
  lives beside the claims it reads.

### X.6: Skip uninstalled PC games in the current scan
- **First:** check on device what GameNative and Winlator leave behind after an uninstall, and record
  it here.
- **Then:** skip (never delete) an export file with no installed game. Stop and report if there is no
  reliable signal.

### X.7: Export Game, one game at a time
- **Objective:** export one Windows game to `windows/import` from either of its menus (decision 6).
- **Existing code** (line numbers as of 2026-09-11; re-verify before editing):
  - Game Detail: `DetailAction` (`GameDetailViewModel.kt:197-210`), `visibleActions` (`:156-158`, already
    filters per game), `activateAction` (`:541-561`), `showActionMessage` (`:563`).
  - XMB: `openGameContextMenuCore` (`XMBViewModel.kt:5057-5150`), whose Windows-only gate for Install
    Goldberg Achievements is at `:5077-5079`; item dispatch at `:5506`; `installGoldbergForGame`
    (`:5822-5852`) shows its outcome through `InfoDialogState`.
  - X.3's `PcGameExportBuilder` and `PcGameExporter.write`.
- **Requirements:**
  - `PcGameExporter.exportGame(gameId): PcGameExportReport`. It reads the game and its artwork records,
    builds a single file with `PcGameExportBuilder`, and writes it with X.3's writer. It does **not**
    go through `ManualGameExportSelector`: the user asked for this game.
  - It refuses with a message, writing nothing, for a non-Windows game, a game with no launcher
    package, a game with neither a launch intent nor a shortcut, and when there is no ROM root or
    import folder.
  - **Name collisions:** the bulk export numbers duplicate names across its run, which one game cannot
    see. When `<name>.pfpgame` exists, read and decode it, and overwrite it only when it is **this**
    game's file (same launch intent, or same launcher package and shortcut id). Otherwise try
    `" (2)"`, `" (3)"`, … until a name is free or already this game's. An existing file that does not
    decode is never overwritten.
  - Game Detail: `DetailAction.EXPORT("Export Game")` after `METADATA`. `visibleActions` shows it only
    for a Windows game. `activateAction` runs the export and shows the report through
    `showActionMessage`.
  - XMB: `XMBContextMenuItem("export_game", "Export Game")` inside the existing Windows gate, and an
    `"export_game"` dispatch whose outcome shows as an `InfoDialogState` titled with the game.
  - Message: "Exported <title> to windows/import as <file name>." or the refusal's reason.
- **Do not change:** the bulk export's selection, or the import.
- **Expected files:** `PcGameExporter.kt`, `GameDetailViewModel.kt`, `XMBViewModel.kt`; tests in
  `PcGameExportBuilderTest` and `GameDetailViewModelTest` (constructor, plus the visibility case). One
  test file over the §4 budget, because the Game Detail visibility rule is tested where the ViewModel is.
- **Tests first:**
  - the owner check: the same intent is the same game; the same package and shortcut id is the same
    pin; another game with the same title is not;
  - the name: a free name is used as is; a name holding this game's file is reused; a name holding
    another game's file, or an unreadable one, moves to `" (2)"`, then `" (3)"`;
  - Game Detail lists Export Game for a Windows game, and not for a ROM game or an Android game.
- **Stop:** if a screen has an exhaustive `when` over `DetailAction` (an icon or label map), add the
  entry there, and report it if it needs more than one line.
- **Verification (device, `:app:installFullDebug`):** Export Game from Game Detail on an Add by ID game,
  and from the XMB Triangle menu on a game from a GameNative export file. Both files appear in
  `windows/import`, a second export of the same game overwrites its own file, and Scan Import Folder
  reports them as matched with no duplicate.
- **Dependencies:** X.3.

**X.7 implemented (2026-09-11, uncommitted, not yet built).** Modified: `PcGameExporter.kt`,
`GameDetailViewModel.kt`, `XMBViewModel.kt`, `PcGameExportBuilderTest`, `GameDetailViewModelTest`. Decisions
taken while landing it:
- **`PcGameExportBuilder.exportFor(game, artwork)`** is the one place an entry is built; the bulk `build`
  now calls it too, so the two exports cannot write different content for one game.
- **Ownership compares typed extras, not only the URI text.** After an import a game stores its
  *sanitized* intent, whose text can differ from the intent in its own file (launch flags stripped), so
  a plain string comparison would have treated a re-export as another game's file and written a
  `" (2)"` copy. Two intents are the same when their text is equal, or when their typed extras
  (`localGameId`, `app_id`, `shortcut_path`, …) are equal and non-empty, under the same launcher package.
  An intent with no extras only matches on exact text. A pin matches on package and shortcut id.
- **Reading existing files:** only `.pfpgame` children are read, anything over 256 KB or not decoding
  counts as unreadable, and an unreadable file is never overwritten.
- **One import-folder lookup** (`importFolder()`, with the scan's setup self-heal) now serves both
  exports.
- **`DetailAction.EXPORT` sits after Update Metadata**, and `visibleActions` became a single filter
  (Emulator hidden for package-backed games, Export shown for Windows games). The screen's
  `dynamicLabel` already had an `else`, so no screen change was needed.
- **The XMB entry sits inside the existing Windows gate**, beside Install Goldberg Achievements, and shows
  its outcome the same way, as an `InfoDialogState` titled with the game.
- **Budget:** one test file over, as planned.

## Verification

- Unit: the tests listed per task (feature-settings, feature-library for the extension, feature-artwork
  for X.5).
- Device, once, with `./gradlew :app:installFullDebug` (Apply Changes does not deliver new classes):
  1. Add a GameHub-family game by `local_<uuid>` id, and a GameNative game by id with no export file.
     Give each artwork, one under a title override and one that collides into `" (2)"`.
  2. Export Manual Games, and check the `.pfpgame` files in `windows/import`.
  3. Clear app data, re-link the ROM root and the artwork folder, and run Scan Import Folder.
  4. Both games are back with their titles, launch works, every artwork file is reconnected, and a
     confirmed ScreenScraper match reads "Confirmed".
