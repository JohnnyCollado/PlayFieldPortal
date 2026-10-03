# Play Field Portal — Playlist File Import: Implementation Plan

Import `.m3u`, `.m3u8`, `.pls` and `.xspf` playlist **files**, picked with Android's document
picker, into Music and Video playlists. Each entry is matched to an item already in the library,
and the result is a normal PFP playlist.

**Status:** Not started. The approach is approved. §12 lists what still needs the user's yes:
the result sheet's look (Q1) has to be signed off before T4.2 is built.
**Repository baseline reviewed:** `final-polish` at `ab536499` (2026-10-01). The tree was clean.
**Primary modules:** `core/core-domain` (parser, matcher, naming; pure Kotlin), `core/core-data`
(DAO transaction, repositories), `feature/feature-xmb` (runner, rows, picker, result sheet).
**Design reference:** Overnight mockups, section `id="p4"` ("Import playlists from files, for
Music and Video"): the row, the system picker, the result sheet and the matching table. §3 repeats
every rule taken from it, so you do not need to open the mockup to build this.

> **Working rules for the implementing session**
> - Write the tests first. Create each task's test file against the API before that API exists,
>   watch the tests fail to compile or fail, then write the production code.
> - Do not run Gradle unless the user asks. Give them the task's command from §10 in a bash block.
> - A task is done only when its tests pass and the build has **no new Kotlin warnings**.
> - Match the comment style around your code. Use `// ── Section ──` rulers, write KDoc that
>   explains *why*, and do not add comments that restate the code.
> - Do not commit.

---

## 1. Context

- A PFP playlist is an ordered list of ids. `Playlist`
  (`core/core-domain/.../model/Playlist.kt`) and `VideoPlaylist` (`.../model/VideoPlaylist.kt`)
  carry only `id`, `name` and a count. Membership lives in `playlist_tracks` /
  `video_playlist_items`, and those rows reference `MusicTrack.id` / `Video.id` as TEXT, with no
  foreign key to the media tables (`PlaylistEntity.kt`, `VideoPlaylistEntity.kt`).
- **Importing a file therefore means resolving its entries to library ids.** PFP never stores a
  file path in a playlist.
- `MusicTrack` and `Video` both carry `uri` (a SAF document URI, never a path), `displayName` and
  `relativePath`. `MusicTrack` also has `title`, `artist` and `durationMs`.
- **`relativePath` holds the item's directory relative to the scanned root, and does not include
  the root folder's own name.** It is null when the file sits directly under the root. See
  `SafFile.relativePath` in `core-data/.../saf/SafTreeWalk.kt` ("" → stored as null by
  `MusicScanner.toTrackOrNull` / `VideoScanner.toVideoOrNull`). The matcher's path key is
  therefore `relativePath + "/" + displayName`, and it is relative to some root PFP does not record
  as a path.

## 2. Current behaviour (what exists, verified)

| Area | Where | Today |
| --- | --- | --- |
| Repository writes | `MusicRepository` / `VideoRepository` (core-domain), `MusicRepositoryImpl` / `VideoRepositoryImpl` (core-data) | `createPlaylist(name)` inserts with `sortOrder = maxSortOrder() + 1`. `addTrackToPlaylist` / `addVideoToPlaylist` add **one** row per call, at `maxPosition + 1`, then `touch`. Nothing creates and fills a playlist in one call, so a failure partway through leaves half a playlist behind. |
| DAO | `PlaylistDao`, `VideoPlaylistDao` | `insert`, `addTrack` / `addVideo` with `OnConflictStrategy.IGNORE`. The composite PK `(playlist_id, track_id)` means **an item can be in a playlist only once**. There is no `@Transaction` method. The precedent for one is `MusicTrackDao.replaceForFolder`, a `@Transaction` default interface method. |
| Name uniqueness | `onConfirmPlaylistName` in `XMBViewModel` | Not enforced. Two playlists may share a name. |
| Music ▸ Playlists | `handleMusicSelection` → `PLAYLISTS_ITEM_ID` → `openMusicBrowser(MusicBrowserView.Playlists)` | Playlists opens the **full-screen Music Browser**. `rebuildBrowserPlaylistRows()` builds its rows with `playlistRootItems(filtered)`, which is playlist rows plus "Create Playlist". The inline `MusicNav.Playlists` branch (~L2727) also calls `playlistRootItems`, but **nothing navigates to `MusicNav.Playlists` anymore** (see R1). |
| Video ▸ Playlists | `handleVideoSelection` → `openVideoView(VideoNav.Playlists)` → `videoPlaylistItems(playlists)` (~L3386) | This is an inline XMB list: playlist rows plus "Create Playlist" (`CREATE_VIDEO_PLAYLIST_ITEM_ID`, subtitle "Start a new video playlist"). |
| Browser list menu | `browserListMenuItems()` (~L4302), handled at ~L7491 under `menu.browserList && itemId.startsWith("music_browser_")` | Shows Resume and Sort. `cycleSort()` already does nothing in the Playlists view. |
| ADD_ACTION look | `XMBItemList.kt` `itemSlotKeyFor` → `"item_add"`, `Icons.Filled.Add` | Every ADD_ACTION row draws the themed "+" glyph. |
| Document picker | `XMBShell.kt` ~L359, the `batchMatchPicker` / `requestLocalSteamFolderPick` pair | **This is the pattern to follow.** The ViewModel raises a request flag. The shell holds the `rememberLauncherForActivityResult`, launches it, clears the flag, and hands the result back. `ArtworkStudioScreen` already uses `OpenMultipleDocuments()`. |
| Shell modals | `shellModalSpec(...)` in `XMBShell.kt`, `forwardToShellModal` in `XMBViewModel`, `pendingShellModalAction` | Shared modals are driven through `PfpModalHost`. `PfpModalSpec.Results` (`core-ui/.../PfpModalHost.kt`) is the notification **Results sheet**: summary chips, L1/R1 filter tabs, a list, a detail pane, ✕ = `actionLabel`, △ = "Copy List" / "Copied", ○ = "Close". Its input is `NotificationDetail.Results` (core-domain), built with `NotificationDetail.results(items, summary, labels)`, which sorts failures first and caps at 500. `XMBViewModel.copyNotificationText` writes the clipboard. |
| Playlist file types | `AudioFileFilter.PLAYLIST_EXTENSIONS` (core-data) | Scans already skip playlist files, so they never show up as tracks. `AUDIO_EXTENSIONS` and `VideoFileFilter.VIDEO_EXTENSIONS` (core-data) are the media-kind lists. |
| Existing m3u code | `M3uPlaylistReader` (feature-library) | Reads raw `.m3u` lines for ROM disc sets through a `Context`. It has no path handling, no EXTINF and no other formats. **It is not reused and is not touched** (see Rejected Alternatives). |
| XML parsing | `EsDeGamelistParser` (feature-artwork) | Uses `android.util.Xml`, and its own test notes that the XML walk "needs an Android pull parser (validated on device)". **It cannot be unit-tested on the JVM.** |

## 3. Behaviour (approved)

### 3.1 Entry points

- **Music ▸ Playlists** and **Video ▸ Playlists** each get an `ADD_ACTION` row **directly below
  Create Playlist**. Title `Import Playlist`, subtitle `From an .m3u, .m3u8, .pls or .xspf file`.
  It uses the same row type and the same "+" glyph as Create Playlist.
- The **Music Browser's list menu** gets `Import Playlist`, but only while the browser shows the
  Playlists list.
- Selecting either one opens the system picker (`OpenMultipleDocuments`), limited to playlist
  types. The user can pick several files. Cancelling the picker does nothing.

### 3.2 Parsing

| Format | Entries | Name | Per-entry hints |
| --- | --- | --- | --- |
| `.m3u` / `.m3u8` | each non-blank line not starting with `#` | `#PLAYLIST:` | `#EXTINF:<secs>[ attrs],<Artist - Title>` applies to the next entry line |
| `.pls` | `FileN=` ordered by N (keys are case-insensitive) | none | `TitleN=` (Artist - Title), `LengthN=` (seconds, `-1` = unknown) |
| `.xspf` | `<track><location>` in document order | the playlist-level `<title>` | `<creator>`, the track's `<title>`, `<duration>` (ms) |

- **Text decoding.** Strip a BOM (UTF-8, or UTF-16 LE/BE, which also selects the charset). Lines
  may end in CRLF, LF or a lone CR. `.m3u8`, `.pls` and `.xspf` are read as UTF-8. A `.m3u` is read
  as UTF-8 when the bytes are valid UTF-8, and as Windows-1252 otherwise (Winamp-era exports).
- **The format comes from the extension.** When the extension is not one of the four, the parser
  sniffs the content: `[playlist]` → PLS, `<?xml` or `<playlist` → XSPF, anything else → M3U.
- **Limits.** Files over **4 MiB** are rejected with a reason. Entries past **10 000** are dropped
  and counted.
- **Malformed input never throws.** The parser returns whatever it read before the problem.

### 3.3 Matching (per entry, in order)

| Step | Matches when |
| --- | --- |
| Skip: web link | The location has a URI scheme other than `file` (`http`, `https`, `rtsp`, `mms`, `rtmp`, `ftp`, `udp`, …). A one-letter "scheme" is a Windows drive, not a scheme. |
| Skip: wrong type | The extension is in the **other** kind's list. For example `clip.mp4` in a music import, or `song.mp3` in a video import. Nested playlist files are also skipped. |
| 1 · Path | The item's path key, `relativePath/displayName` (**at least two segments**), equals the **trailing segments** of the entry's path. If the entry is the shorter of the two, the entry must equal the item key's trailing segments instead. When several items match, the one with the **longest** shared suffix wins. An exact tie falls through to the next step. |
| 2 · File name | Exactly one library item of this kind has the entry's file name. |
| 3 · Title (music only) | The entry has **both** an artist and a title (from EXTINF, PLS `TitleN` split on ` - ` or ` – `, or XSPF `<creator>` + `<title>`), and exactly one track's `artist` and `title` tags equal them. If several tracks match and the entry has a duration, the one tagged within ±2 s wins. |
| Skip: duplicate | The entry resolves to an item an earlier entry already matched. |
| Not found | Nothing matched. The reason is "not found", or "more than one match" when a tie survived every step. |

**Path normalisation** applies before step 1 or step 2 compares anything:

- Strip `file:`, `file://` and `file:///`, along with `localhost`.
- Percent-decode `file:` URIs, using **UTF-8 byte decoding that leaves `+` alone**. Do not use
  `URLDecoder`, which turns `+` into a space. A plain path is compared both raw and decoded.
  Malformed escapes such as `100%.mp3` are kept as written.
- Treat `\` as `/`. Drop a drive (`C:`) and a UNC prefix (`\\host\share`).
- Resolve `.` and `..`. Any `..` left at the start is dropped.
- Compare case-insensitively (`Locale.ROOT`) after Unicode **NFC** normalisation, so names exported
  from macOS in NFD still match.

### 3.4 Naming

- Take the name from the file (`#PLAYLIST:` or the XSPF `<title>`), trimmed. If that is blank, use
  the picked file's display name without its last extension (`My.Mix.m3u` → `My.Mix`). If that is
  blank too, use `Imported Playlist`.
- **When the name clashes** (case-insensitive, against existing playlists of the same kind and
  playlists created earlier in the same batch), import as `Name (2)`, then `Name (3)`, and so on.
  A name that already ends in ` (n)` continues counting from n. **Never merge or overwrite.**

### 3.5 Writing

- `importPlaylist(name, ids)` on each repository creates the playlist and its rows **in one Room
  transaction**. Positions run `0..n-1` in file order, and the playlist is appended to the end of
  the sort order.
- **If nothing matched, nothing is created.** The sheet then explains why.

### 3.6 Result

- The result appears in the existing **Results sheet** (`PfpModalSpec.Results`), raised from the
  shell's modal host.
  - Title: `Imported "<name>"`, or `Nothing imported from "<file name>"` when nothing matched.
  - Labels: `ResultsLabels(done = "Found", failed = "Not in your library", skipped = "Skipped")`.
  - Rows: every unmatched entry (FAILED, reason `not found` / `more than one match`), every
    skipped one (SKIPPED, reason `web link` / `wrong type` / `already in this playlist`), and every
    found one (DONE, primary = the item's display title). The sheet opens on the failures tab
    whenever anything failed, which is the mockup's list.
  - An unmatched row's `primary` is the entry as written: `Artist – Title` when the file gave one,
    otherwise the raw location.
  - Buttons: ✕ `Open Playlist` (absent when nothing was created), △ `Copy List` (the existing
    `DetailSheetText.results` text), ○ Close.
- **Several files** produce one sheet per file, shown one after another. `meta` reads `1 of 3`.
  Closing a sheet shows the next one. `Open Playlist` opens that playlist and discards the rest of
  the queue (Q2).
- A file that cannot be read (permission, I/O, over the size limit, not a playlist) gets a sheet
  too. Its title is `Couldn't import "<file name>"` and a one-line summary gives the reason; nothing
  is created.

## 4. Goals

1. Import works for all four formats, from both sections, with multi-select.
2. Parser, path normalisation, matcher and naming are **pure Kotlin in core-domain** and are
   covered by JVM tests.
3. An import is atomic: either the whole playlist exists or none of it does.
4. Every entry that did not make it in is listed with a reason, and the list can be copied.

## 5. Non-goals (do not touch)

- **Scanning library folders for playlist files**, and offering them for import. This is a
  follow-up. `AudioFileFilter` stays as it is.
- **Export.** Writing `.m3u8` from a playlist is a later small addition, and needs no hooks now.
- **`.wpl`, `.asx`, `.zpl`, `.pla`, `.plist`.** These are not offered by the picker and not parsed.
- **`M3uPlaylistReader`, `DiscSetBuilder`, `RomScanner`, `LibraryScanner`.** ROM disc sets are a
  separate system.
- **Merging into an existing playlist**, or any "Add from file" inside a playlist.
- **Room schema or migrations.** No entity changes are needed. **Backup/restore** (`BackupDao`).
- **Name uniqueness for Create / Rename.** Clash handling applies to import only.
- **A notification-panel row or background task** for imports. The sheet is the whole report.
- **`PfpDetailSheets.kt` / `PfpModalHost.kt` look or behaviour.** They are reused unchanged.
- **New theme slots or glyphs.** Import uses ADD_ACTION's existing `"item_add"` glyph.
- **The inline `MusicNav.Playlists` dead path** (R1). It is not removed here.

## 6. Architectural decisions

| # | Decision | Why | Rules out |
| --- | --- | --- | --- |
| D1 | The parser, location normalisation, matcher and naming live in a new package, `com.playfieldportal.core.domain.playlist`, in **core-domain**. They are pure Kotlin, with only `java.*` / `kotlin.*` imports. | They can then be tested on the JVM with no Robolectric. core-domain is already home to pure rules (`ListArrangement`, `UmdSlotResolver`), and both feature modules can reach it. | Putting them in feature-xmb or feature-library. Any `android.*` import in the package. |
| D2 | **XSPF is read by a small hand-written tag scanner** that handles `<title>`, `<trackList>`, `<track>`, `<location>`, `<creator>`, `<duration>`, CDATA, comments, the five named entities and numeric character references. | `android.util.Xml` cannot run in JVM tests (the `EsDeGamelistParser` precedent). The JDK's DOM would behave differently on the JVM and on the device, and Android's `DocumentBuilderFactory` rejects the hardening flags. A scanner never resolves entities or DTDs, so it has no XXE surface. | `android.util.Xml`, `DocumentBuilderFactory`, and any XML library dependency. |
| D3 | The matcher works on a neutral **`PlaylistCandidate`** (id, relativePath, displayName, title?, artist?, durationMs?) and is given the media **kind** plus the **own and other extension sets** as parameters. | One matcher serves Music and Video, and core-domain does not depend on core-data's `AudioFileFilter` / `VideoFileFilter`. The caller passes those sets in. | Two matchers. Copying the extension lists into core-domain. |
| D4 | The matcher returns **one outcome per entry, in file order**, plus the ordered, de-duplicated matched ids. | The result sheet and the repository write both come from the same report, so the counts cannot disagree with what was saved. | The UI recomputing reasons. |
| D5 | The transaction is a **`@Transaction` default method on `PlaylistDao` / `VideoPlaylistDao`**, which inserts the playlist and then batch-inserts its rows. `importPlaylist(name, ids)` on each repository calls it once. | This follows the `MusicTrackDao.replaceForFolder` precedent, and the repositories do not hold the database. | `db.withTransaction` in the repository. Looping `addTrackToPlaylist`. |
| D6 | Calling `importPlaylist` with an **empty** id list is an `IllegalArgumentException`. The runner never calls it with zero matches. | "Nothing matched → create nothing" is decided once, in the runner, and the repository cannot silently create an empty playlist. | An empty imported playlist. |
| D7 | Orchestration happens in a **`PlaylistImportRunner`** (feature-xmb, `@Inject`, same style as `GameArtworkFetchRunner`). It is given decoded files (`name` + `bytes`), and does parse → library snapshot (`observeAllTracks().first()` / `observeAllVideos().first()`) → match → name → write → report. Reading a `Uri` sits in a thin `PlaylistDocumentReader` (ContentResolver + `OpenableColumns.DISPLAY_NAME`). | This keeps the runner testable with mockk repositories and no `Context`, and keeps `XMBViewModel` (11.6k lines) to wiring only. | Import logic inside `XMBViewModel`. |
| D8 | The picker is launched from **`XMBShell`** on a `requestPlaylistImportPick: PlaylistImportKind?` flag, the same pattern as `requestLocalSteamFolderPick`. | Only a composable can hold an activity-result launcher. | Launching from the ViewModel. |
| D9 | The result is shown with **`PfpModalSpec.Results` through `shellModalSpec`**, with presses forwarded via `forwardToShellModal`, and the state is added to `hasBlockingOverlay`. | This reuses the Results sheet, Copy List and the modal host, as approved. | A new sheet composable, or a new button row (Q1). |
| D10 | The new `Import Playlist` rows sit in the **shared row builders**. `playlistRootItems` is used by both the Music Browser and the dead inline path, and `videoPlaylistItems` builds the video list. The builders move to a small internal file in the style of `GameContextMenuItems.kt`, so they can be tested. | The browser and the XMB cannot drift apart, and the order "Create, then Import" is pinned by a test. | A browser-only row. |

## 7. Rejected alternatives

- **Extending `M3uPlaylistReader`.** It lives in feature-library, takes a `Game`, needs a
  `Context`, and serves disc sets, whose rules are different (no EXTINF, paths relative to the ROM).
  Coupling the two would let a playlist change break disc-set detection. Moving it onto the new
  parser later is allowed, but is not part of this plan.
- **Matching on `MusicTrack.uri`.** It is a SAF document URI, and playlist files from a PC or
  another app never contain those.
- **Resolving the root folder's real path from its tree URI**, so that items get a full
  `primary:Music/...` key. That would break ties between roots, but it means decoding provider-
  specific document ids. Ties are reported as "more than one match" instead (R6).
- **Title-only matching.** Too many false positives ("Intro", "Untitled"). Step 3 needs both
  artist and title.
- **Fuzzy or partial-path matching.** A wrong match is worse than a listed miss, because the user
  can see a miss and fix it.
- **Merging into a playlist with the same name.** The user ruled it out (§3.4).
- **One combined sheet for a multi-file import.** It cannot offer a single Open Playlist, and its
  rows would need a "which file" column. Per-file sheets reuse the sheet unchanged (Q2).
- **Posting the result as a notification row.** That would persist history nobody asked for, and
  the notification payload cap would cut large reports.

## 8. Data / persistence and compatibility

- **No schema change, and no migration.** The new DAO methods are `@Insert(onConflict = IGNORE)
  addTracks(List<PlaylistTrackEntity>)` (and `addVideos`), plus the `@Transaction` default
  `insertWithTracks` / `insertWithVideos`.
- Adding abstract DAO methods breaks **`FakePlaylistDao` in `MusicRepositoryImplTest.kt`**, which
  must implement `addTracks`. There is no fake `VideoPlaylistDao` and no hand-written fake
  `MusicRepository` / `VideoRepository`. `VideoDetailViewModelTest` mocks `VideoRepository` with
  mockk, and that keeps compiling.
- Imported playlists are ordinary rows, so backup, rename, delete and Add Tracks all work on them
  unchanged.

## 9. Test cases (write before the code they cover)

P0 must exist before the task is done. P1 should. P2 is optional.

### 9.1 `PlaylistFileParserTest`: core-domain, pure JVM (T1.1, T1.2)

**P0**
1. M3U: plain path lines become entries in file order. Blank lines and `#` comments are ignored.
2. Extended M3U: `#EXTINF:241,Fleetwood Mac - Dreams` gives the next entry duration 241 000 ms,
   artist `Fleetwood Mac` and title `Dreams`.
3. `#PLAYLIST:Road Trip` sets the name. Without it the name is null.
4. A UTF-8 BOM is stripped. The first entry does not start with `\uFEFF`.
5. CRLF, LF and lone-CR files all give the same entries.
6. A `.m3u` containing byte `0xE9` (invalid as UTF-8) decodes as Windows-1252 `é`. The same bytes
   in a `.m3u8` are not decoded as Windows-1252.
7. Windows (`D:\Music\a.flac`), Android-absolute (`/storage/emulated/0/Music/a.flac`), relative
   (`../misc/intro.mp3`) and `file:///` locations are passed through **as written** (normalising is
   §9.2's job).
8. PLS: `File2`/`File1`/`Title1`/`Length1` written out of order still come back ordered by N, with
   their titles and lengths. `Length1=-1` means no duration. Keys are case-insensitive.
9. XSPF: the playlist `<title>` is the name, and a track's `<title>` is **not** taken as the
   playlist name. `<location>`, `<creator>`, `<title>` and `<duration>` (ms) map per track.
10. XSPF: `&amp; &lt; &gt; &quot; &apos; &#233; &#xE9;` are decoded, and CDATA content is read.
11. Malformed input (truncated XSPF, a PLS with no `[playlist]` header, binary junk) returns
    whatever it read and never throws.

**P1**

12. A UTF-16 LE file with a BOM decodes.
13. `#EXTINF:-1 tvg-id="x" group-title="a,b",Artist - Title`: duration null, and the title is taken
    after the comma that ends the attributes, not the one inside quotes.
14. An `#EXTINF` with no path line after it (followed by EOF or another `#EXTINF`) is dropped.
15. An EXTINF title with no ` - ` gives a title and no artist. An en dash ` – ` also splits.
16. The format is sniffed for an unknown extension (`[playlist]` → PLS, `<?xml` → XSPF, otherwise
    M3U).
17. Over 4 MiB is rejected with a reason. Past 10 000 entries, the rest are dropped and counted.
18. XSPF comments, namespaces and attributes on `<playlist>` are tolerated, and the first of
    several `<location>`s is used.

**P2**

19. Unknown directives (`#EXTALB`, `#EXTGRP`, `#EXT-X-…`) are ignored.
20. Leading and trailing whitespace on path lines is trimmed. Inner spaces are kept.

### 9.2 `PlaylistLocationTest`: core-domain, pure JVM (T1.3)

**P0**
1. `D:\Music\Fleet Foxes\Helplessness Blues\03 Montezuma.flac` → segments
   `[music, fleet foxes, helplessness blues, 03 montezuma.flac]` (drive dropped, lowercased).
2. `/storage/emulated/0/Music/A/b.mp3` → segments ending `[a, b.mp3]`.
3. `file:///storage/emulated/0/Music/A%20B/c.flac` → `a b` decoded. `file:///C:/Music/x.mp3`
   drops the drive.
4. `+` survives in both raw and `file://` forms (`AC+DC.mp3`).
5. Malformed escapes (`100%.mp3`, `%zz`) are kept literally, with no exception.
6. `../misc/intro.mp3`, `./a/b.mp3` and `a\..\b\c.mp3` resolve to `[misc, intro.mp3]`,
   `[a, b.mp3]` and `[b, c.mp3]`.
7. `http://`, `https://`, `rtsp://`, `mms://` → web link. `C:\x.mp3` and `c:/x.mp3` → **not** web.
8. Blank or whitespace-only → empty.

**P1**

9. `file://localhost/...` and `file:/C:/...` are handled.
10. UNC `\\server\share\Music\a.mp3` drops host and share.
11. NFD `Beyonce\u0301` equals NFC `Beyoncé`.
12. A raw path containing a literal `%20` produces both a raw and a decoded variant.

### 9.3 `PlaylistMatcherTest`: core-domain, pure JVM (T1.4)

**P0**
1. Step 1: a Windows absolute entry matches the candidate `Fleet Foxes/Helplessness Blues` +
   `03 Montezuma.flac`.
2. Step 1: a relative entry `Helplessness Blues/03 Montezuma.flac` matches the longer candidate
   key.
3. Step 1: two candidates share the file name (`Album A/01.flac`, `Album B/01.flac`), and the
   entry's folder picks the right one.
4. Step 1: when two candidates both match, the one sharing more trailing segments with the
   entry wins (entry `…/Artist/Album/01.flac`: `Artist/Album/01.flac` beats a root-relative `Album/01.flac` from another folder).
5. Step 1 tie (the same `Album/01.flac` under two roots), with no tags: not found, reason
   "more than one match".
6. Step 2: `../misc/intro.mp3` matches the only `intro.mp3`, wherever it is.
7. Step 2: two `intro.mp3` in the library, with no other clue: not found ("more than one match").
8. Step 3: `Fleetwood Mac - Dreams` matches the track tagged artist `Fleetwood Mac`, title
   `Dreams`. Case and extra whitespace are ignored.
9. Step 3 is never used for `VIDEO`.
10. Step 3 needs both artist and title. A title-only entry is not found.
11. Web link → skipped (WEB_LINK), and the matcher does not look it up.
12. `clip.mp4` in MUSIC and `song.mp3` in VIDEO → skipped (WRONG_TYPE).
13. The second entry resolving to an already-matched item → skipped (DUPLICATE). The matched ids
    contain it once.
14. The matched ids keep file order. The outcome list has one outcome per entry, in order.
15. Matching is case-insensitive across the whole path.

**P1**

16. A candidate with a null `relativePath` (root level) is never matched by step 1, only by
    steps 2 and 3.
17. Step 3 tie broken by EXTINF duration within ±2 s. Outside the window it stays a tie.
18. A nested `.m3u` entry → skipped (WRONG_TYPE).
19. An empty library: everything not found, except web links and wrong-type entries, which are
    still skipped.
20. 5 000 entries against 20 000 candidates finishes within a test timeout of 2 s (index by file
    name, no N×M scan).

**P2**

21. An entry with an extension in neither list (`.cue`) is still tried.

### 9.4 `PlaylistImportNamingTest`: core-domain, pure JVM (T1.5)

**P0**
1. `#PLAYLIST:` / XSPF title wins over the file name.
2. No parsed name: `Road Trip.m3u8` → `Road Trip`, and `My.Mix.m3u` → `My.Mix`.
3. A blank or whitespace parsed name falls back to the file name. The result is trimmed.
4. A clash with `Road Trip` gives `Road Trip (2)`. If `(2)` is also taken, it gives
   `Road Trip (3)`.
5. The clash check is case-insensitive (`road trip` blocks `Road Trip`).

**P1**

6. Importing `Road Trip (2)` when it exists gives `Road Trip (3)`, not `Road Trip (2) (2)`.
7. A blank name and a blank file name give `Imported Playlist`.

### 9.5 `PlaylistDaoImportTest` and `VideoPlaylistDaoImportTest`: core-data, Robolectric in-memory Room, in the style of `VideoDaoReplaceTest` (T2.1, T2.2)

**P0**
1. `insertWithTracks` creates one playlist whose rows hold the given ids at positions `0..n-1`,
   and `observeTracks` returns them in that order.
2. The new playlist's `sort_order` comes after the existing playlists'.
3. It returns the new playlist's id, and the playlist's count equals n.

**P1**

4. Ids that are repeated in the input are stored once (PK + IGNORE), and the order of first
   appearance is kept.

### 9.6 `MusicRepositoryImplTest` (extend) and `VideoRepositoryImportTest` (T2.1, T2.2)

**P0**
1. `importPlaylist("Road Trip", [t2, t1])` creates exactly one playlist named `Road Trip` whose
   tracks are `[t2, t1]`.
2. `importPlaylist(name, emptyList())` throws `IllegalArgumentException`, and no playlist exists
   afterwards.

### 9.7 `PlaylistImportRunnerTest`: feature-xmb, JVM + mockk (T3.1)

**P0**
1. A file whose entries all miss: `importPlaylist` is **never** called, and the report says nothing
   was created and lists each entry's reason.
2. A file with matches: `importPlaylist` is called once, with the derived clash-free name and the
   matched ids in file order.
3. Music uses `AUDIO_EXTENSIONS` as its own list and `VIDEO_EXTENSIONS` as the other. Video uses
   them the other way round.
4. Two picked files that both resolve to `Road Trip` produce `Road Trip` and `Road Trip (2)`.

**P1**

5. A file that cannot be read (the reader returns a failure) gets an error report, and the next
   file in the batch is still imported.
6. A picked file with a non-playlist extension → error report "not a playlist file", and nothing
   is written.
7. Report → `NotificationDetail.Results` mapping: the labels are Found / Not in your library /
   Skipped, the reasons read as in §3.6, and an unmatched row with artist and title shows
   `Artist – Title`.

### 9.8 `PlaylistImportRowsTest` (T4.1) and `ShellModalSpecTest` / `PlaylistImportOverlayTest` (T4.2): feature-xmb

**P1** (UI wiring; behaviour pinned through the pure helpers)
1. Music playlist rows end with Create Playlist and then Import Playlist, with the exact titles,
   subtitles and `ADD_ACTION` type.
2. Video playlist rows: the same order, and the Import row has its own id, distinct from the music
   one.
3. The browser list menu offers `music_browser_import` in the Playlists view only.
4. `shellModalSpec` maps an import sheet state to `PfpModalSpec.Results` with title
   `Imported "Road Trip"`, actionLabel `Open Playlist`, and actionLabel null when nothing was
   created.
5. `hasBlockingOverlay` is true while an import sheet is up.
6. A notification-panel layer still wins over the import sheet (the order in `shellModalSpec`).

## 10. Commands for the user

Run them one block at a time.

```bash
./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistFileParserTest*"
```

```bash
./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistLocationTest*"
```

```bash
./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistMatcherTest*"
```

```bash
./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistImportNamingTest*"
```

```bash
./gradlew :core:core-data:testDebugUnitTest --tests "*PlaylistDaoImportTest*" --tests "*MusicRepositoryImplTest*"
```

```bash
./gradlew :core:core-data:testDebugUnitTest --tests "*VideoPlaylistDaoImportTest*" --tests "*VideoRepositoryImportTest*"
```

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*PlaylistImportRunnerTest*"
```

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*PlaylistImportRowsTest*" --tests "*ShellModalSpecTest*" --tests "*PlaylistImportOverlayTest*"
```

```bash
./gradlew :app:installFullDebug
```

## 11. Implementation phases and tasks

The rules below apply to **every** task. Each task also has its own fields.

- **Stop condition.** Stop when the task's acceptance criteria hold. Do not start the next task,
  and do not "tidy" code nearby.
- **If blocked**, stop and report: (1) what you tried, (2) what blocked you, (3) which file or
  system caused it, (4) what decision you need. Do not invent architecture to keep moving.
- **Done** means the task's P0 (and P1, where listed) tests are green, there are no new Kotlin
  warnings, and nothing outside the task's files changed.

### Phase 1: Pure core (core-domain, package `core.domain.playlist`)

**T1.1 — M3U/M3U8 parser and text decoding**
- *Objective:* turn the bytes of an `.m3u` / `.m3u8` into `ParsedPlaylist(format, name, entries,
  dropped)`. Each `PlaylistEntry` has the location as written, plus an optional title, artist and
  durationMs.
- *Scope:* the entry and result types, BOM / charset / line-ending handling, format detection by
  extension plus sniffing, the M3U and EXTINF rules, the size and entry limits. Tests 9.1 #1–7,
  11–17 (the M3U parts), 19–20.
- *Do not change:* `M3uPlaylistReader`, `AudioFileFilter`, or anything outside the new package.
- *Acceptance:* the tests above are green. The package has no `android.*` import.
- *Budget:* 1–2 new main files, and 1 new test file.
- *Test:* `./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistFileParserTest*"`

**T1.2 — PLS and XSPF parsers** (depends on T1.1)
- *Objective:* the same `ParsedPlaylist` from `.pls` and `.xspf`.
- *Scope:* PLS index pairing; the D2 tag scanner for XSPF (entities, CDATA, comments). Tests 9.1
  #8–11, 16, 18.
- *Do not change:* the T1.1 types, except to add fields that the XSPF/PLS mapping needs. No XML
  libraries, and no `android.util.Xml`.
- *Acceptance:* all of 9.1 P0 and P1 is green.
- *Budget:* 1 new main file (or the T1.1 file extended), and the T1.1 test file extended.
- *Test:* `./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistFileParserTest*"`

**T1.3 — Location normalisation** (no dependency)
- *Objective:* classify a raw location as web link, empty, or a path given as normalised segments
  (with a decoded variant), following §3.3.
- *Scope:* tests 9.2.
- *Do not change:* the parser. It keeps locations as written.
- *Acceptance:* 9.2 P0 and P1 are green. No `URLDecoder`.
- *Budget:* 1 new main file and 1 new test file.
- *Test:* `./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistLocationTest*"`

**T1.4 — Matcher** (depends on T1.1, T1.3)
- *Objective:* map entries and `PlaylistCandidate`s to one outcome per entry plus the ordered,
  de-duplicated matched ids (D3, D4).
- *Scope:* skip rules, steps 1–3, ties, the duration tiebreak, the file-name index. Tests 9.3.
- *Do not change:* `MusicTrack` and `Video`. Map them to candidates at the call site (T3.1), not
  in the models.
- *Acceptance:* 9.3 P0 and P1 are green.
- *Budget:* 1 new main file and 1 new test file.
- *Test:* `./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistMatcherTest*"`

**T1.5 — Name derivation and clash** (depends on T1.1)
- *Objective:* the base name rule and `uniqueName(base, takenNames)`, following §3.4.
- *Scope:* tests 9.4.
- *Do not change:* the Create / Rename flows.
- *Acceptance:* 9.4 P0 and P1 are green.
- *Budget:* 1 new main file and 1 new test file.
- *Test:* `./gradlew :core:core-domain:testDebugUnitTest --tests "*PlaylistImportNamingTest*"`

### Phase 2: Atomic write (core-data)

**T2.1 — Music `importPlaylist`** (no dependency on Phase 1)
- *Objective:* `MusicRepository.importPlaylist(name, trackIds): Long`, which creates and fills
  the playlist in one transaction (D5, D6).
- *Scope:* `PlaylistDao.addTracks` and the `@Transaction insertWithTracks` default method; the
  interface method and the implementation; `FakePlaylistDao` updated; tests 9.5 (music) and
  9.6 #1–2.
- *Do not change:* `createPlaylist`, `addTrackToPlaylist`, `toggleTrackInPlaylist`, the entities,
  `PFPDatabase` and its version.
- *Acceptance:* the tests are green. The existing `MusicRepositoryImplTest` cases still pass.
- *Budget:* modify `PlaylistDao.kt`, `MusicRepository.kt`, `MusicRepositoryImpl.kt` and
  `MusicRepositoryImplTest.kt`; add `PlaylistDaoImportTest.kt`.
- *Test:* `./gradlew :core:core-data:testDebugUnitTest --tests "*PlaylistDaoImportTest*" --tests "*MusicRepositoryImplTest*"`

**T2.2 — Video `importPlaylist`** (mirrors T2.1)
- *Objective:* the same for `VideoRepository` / `VideoPlaylistDao`.
- *Scope:* `addVideos`, `insertWithVideos`, the interface and the implementation; tests 9.5
  (video) and 9.6 as `VideoRepositoryImportTest` (Robolectric Room, or a fake DAO, whichever the
  implementer prefers).
- *Do not change:* the existing video playlist calls, and `VideoDetailViewModel`.
- *Acceptance:* the tests are green. `:feature:feature-xmb` still compiles (mockk use of
  `VideoRepository`).
- *Budget:* modify `VideoPlaylistDao.kt`, `VideoRepository.kt` and `VideoRepositoryImpl.kt`; add
  1–2 test files.
- *Test:* `./gradlew :core:core-data:testDebugUnitTest --tests "*VideoPlaylistDaoImportTest*" --tests "*VideoRepositoryImportTest*"`

### Phase 3: Orchestration (feature-xmb)

**T3.1 — `PlaylistImportRunner` and the report mapping** (depends on T1.2, T1.4, T1.5, T2.1, T2.2)
- *Objective:* given a kind and a list of picked files (name + bytes, or a read failure), produce
  one report per file, writing a playlist only when something matched (D7). Also map a report to
  `NotificationDetail.Results` with the §3.6 labels and reasons.
- *Scope:* the runner; `PlaylistDocumentReader` (a `ContentResolver` read with the 4 MiB limit,
  plus `OpenableColumns.DISPLAY_NAME`; not unit-tested, it is checked on the device); tests 9.7.
- *Do not change:* `XMBViewModel` (wiring is T4), `GameArtworkFetchRunner`, notification code.
- *Acceptance:* 9.7 P0 and P1 are green.
- *Budget:* add `PlaylistImportRunner.kt`, `PlaylistDocumentReader.kt` and
  `PlaylistImportRunnerTest.kt`.
- *Test:* `./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*PlaylistImportRunnerTest*"`

### Phase 4: XMB wiring (feature-xmb)

**T4.1 — Rows, menu entry and the picker** (depends on T3.1)
- *Objective:* the three entry points of §3.1 open the system picker, and the picked URIs reach the
  runner.
- *Scope:* move `playlistRootItems` / `videoPlaylistItems` into an internal file and append the
  Import row (D10); new id constants; handle the row in `handleMusicSelection`,
  `handleVideoSelection` and `handleMusicBrowserRow`; `music_browser_import` in
  `browserListMenuItems()` (Playlists view only) and in the `music_browser_` handler;
  `requestPlaylistImportPick` and the `OpenMultipleDocuments` launcher in `XMBShell` (MIME types:
  `audio/x-mpegurl`, `audio/mpegurl`, `application/vnd.apple.mpegurl`, `application/x-mpegurl`,
  `audio/x-scpls`, `application/xspf+xml`; every pick is validated by extension afterwards); and
  `onPlaylistFilesPicked(kind, uris)`, which runs the runner off the main thread. Until T4.2 the
  reports are only logged. Tests 9.8 #1–3.
- *Do not change:* Create Playlist behaviour, Sort / Resume, `XMBItemList` rendering, theme slots.
- *Acceptance:* the tests are green. Selecting either row (or the menu entry) opens the picker on
  the device.
- *Budget:* modify `XMBViewModel.kt` and `XMBShell.kt`; add `PlaylistListItems.kt` and
  `PlaylistImportRowsTest.kt`.
- *Test:* `./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*PlaylistImportRowsTest*"`

**T4.2 — Result sheet** (depends on T4.1, **and Q1 signed off**)
- *Objective:* show each report in the shared Results sheet (D9), with Open Playlist, Copy List
  and Close, queued one per file.
- *Scope:* an import-sheet queue in `XMBUiState`; `hasBlockingOverlay`; a dispatch branch that
  calls `forwardToShellModal`, next to the `infoDialog` branch; a `PfpModalSpec.Results` branch in
  `shellModalSpec` after the notification layers; Open Playlist →
  `openMusicBrowser(MusicBrowserView.Playlist(id, name))` /
  `openVideoView(VideoNav.Playlist(id, name))`; Copy → `copyNotificationText`. Tests 9.8 #4–6.
- *Do not change:* `PfpDetailSheets.kt`, `PfpModalHost.kt`, the notification panel's sheets.
- *Acceptance:* the tests are green. Every existing `ShellModalSpecTest` case still passes.
- *Budget:* modify `XMBViewModel.kt`, `XMBShell.kt` and `ShellModalSpecTest.kt`; add
  `PlaylistImportOverlayTest.kt`.
- *Test:* `./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShellModalSpecTest*" --tests "*PlaylistImportOverlayTest*"`

**T4.3 — Device pass** (depends on T4.2). The user drives the device and Claude does not; see §13.
Build with `./gradlew :app:installFullDebug`.

## 12. Open questions for the user

1. **The result sheet's look (it blocks T4.2).** The approved mockup shows a compact sheet with
   three counts and an Open Playlist / Copy List / Done **button row**. Reusing the existing
   Results sheet, as decided, gives the 720 dp sheet instead: count chips, L1/R1 tabs (All /
   Not in your library / Skipped / Found), a detail pane, and **hint-bar actions** (✕ Open
   Playlist, △ Copy List, ○ **Close**, not "Done"). Recommendation: accept the existing sheet as it
   is, and show a screenshot after T4.2. The alternative is new UI, which needs a new mockup.
2. **A multi-file import shows one sheet per file** (`1 of 3`), and Open Playlist drops the rest
   of the queue. Recommendation: yes. The alternative is one combined sheet without Open Playlist.
3. **A new reason, "more than one match".** It is not in the mockup, but without it a tie reads as
   "not found", which is misleading. Recommendation: add it.
4. **`content://` entries** (playlists exported by another Android app) could be matched exactly
   against `uri`. Recommendation: not now. They count as not found.
5. **The Import row's glyph.** The mockup shows `⇩`. The plan keeps ADD_ACTION's themed "+" (the
   mockup caption says "same row type and look"). Confirm.
6. **The Music Browser shows the Import row and the menu entry together.** The browser's
   Playlists list is the only Music ▸ Playlists list (R1), so both appear on the same screen.
   Recommendation: keep both, as approved.

## 13. Device checklist (Odin3 or Thor, debug build)

1. Music ▸ Playlists: Import Playlist sits below Create Playlist. A opens the picker, which shows
   `.m3u`, `.m3u8`, `.pls` and `.xspf` files (R4).
2. Pick a PC-exported `.m3u8` with `D:\…` paths: the playlist appears in file order, and the sheet
   counts match.
3. Pick a playlist containing a web stream and a missing file: both are listed with reasons, and
   Copy List puts them on the clipboard.
4. Pick a file where nothing matches: no playlist is created, and the sheet says why.
5. Import the same file twice: `Road Trip (2)`.
6. Pick three files: three sheets, `1 of 3`. Open Playlist on the first opens it.
7. Video ▸ Playlists: the same as 1–2 with a video `.m3u`. An `.mp3` entry is skipped as the
   wrong type.
8. Music Browser ▸ Playlists ▸ △ menu ▸ Import Playlist opens the picker. The same menu on a track
   view does not offer it.
9. With the sheet up, the D-pad never moves the XMB or the browser behind it.

## 14. Risks found while grounding

- **R1. Inline Music ▸ Playlists is dead.** `PLAYLISTS_ITEM_ID` opens the Music Browser, and no
  code navigates to `MusicNav.Playlists`, although its branch (~L2727) and `handleMusicSelection`'s
  playlist handling remain. The Import row reaches users through the **browser**, because
  `playlistRootItems` is shared. That code is left alone (Non-goals).
- **R2. `relativePath` excludes the root folder's name**, and is null for root-level files.
  Matching is suffix-based, and items at the root can only match by file name or tags.
- **R3. Percent-decoding.** `java.net.URLDecoder` decodes `+` as a space and throws on `%zz`. A
  hand-written UTF-8 percent decoder is needed (9.2 #4–5).
- **R4. Picker MIME filtering is unreliable.** Many providers report playlists as
  `application/octet-stream`, so they show greyed out. If device check 1 fails, the fallback is
  `*/*` plus the extension check. That would change the approved "limited to playlist types"
  behaviour, so it goes to the user.
- **R5. Encoding.** Old `.m3u` files are often Windows-1252. The UTF-8-validity fallback covers
  them, but not other code pages (Shift-JIS, for example).
- **R6. Ties across roots.** The same `Album/01.flac` under two music folders cannot be told apart
  without tags. It is reported, not guessed.
- **R7. The transaction rollback cannot be forced in a test.** Inserts with IGNORE do not fail
  partway. Atomicity rests on Room's `@Transaction`, as `replaceForFolder` does. The tests pin the
  single-call contract and the ordering.
- **R8. The composite PK hides duplicates.** The DB silently ignores them, so the matcher must
  de-duplicate itself in order to report them (9.3 #13).
- **R9. Test-compile break.** `FakePlaylistDao` must implement the new abstract DAO method in the
  same task (T2.1).
- **R10. The library snapshot can be stale.** A scan running during an import can remove an item
  after it was matched. The playlist join then drops that row, exactly as it does for any playlist
  today.
- **R11. The sheet deviates from the approved mockup** (Q1). The memory rule "mockup before UI
  changes" applies, so get the yes before T4.2.

## 15. Execution task index

| ID | Task | Depends on | Effort | Status |
| --- | --- | --- | --- | --- |
| T1.1 | M3U/M3U8 parser and text decoding | None | S | DONE |
| T1.2 | PLS and XSPF parsers | T1.1 | S | DONE |
| T1.3 | Location normalisation | None | S | DONE |
| T1.4 | Matcher | T1.1, T1.3 | S | DONE |
| T1.5 | Name derivation and clash | T1.1 | S | DONE |
| T2.1 | Music `importPlaylist` (DAO transaction and repository) | None | S | DONE |
| T2.2 | Video `importPlaylist` | T2.1 (pattern) | S | DONE |
| T3.1 | `PlaylistImportRunner`, document reader, report → Results mapping | T1.2, T1.4, T1.5, T2.1, T2.2 | S | DONE |
| T4.1 | Import rows, browser menu entry, picker launch | T3.1 | S | DONE |
| T4.2 | Result sheet through the shell modal host | T4.1, Q1 | S | DONE |
| T4.3 | Device pass (§13) | T4.2 | S | READY |

Overall effort: **M**.

## Resolved Decisions (2026-10-01, approved direction)
- Q1: accept the existing Results sheet (T4.2 is UNBLOCKED). The user reviews it on device.
- Q2: yes — one sheet per file ("1 of 3"); Open Playlist drops the rest of the queue.
- Q3: yes — add the "more than one match" reason.
- Q4: not now.
- Q5: keep the themed "+" glyph.
- Q6: keep both the Import row and the Music Browser menu entry.
- R4: picker allows any file (`*/*`), then filters by extension (.m3u .m3u8 .pls .xspf); non-playlist picks are reported as skipped.
- Device pass (T4.3) is deferred to the user's end-of-run device pass; it does not block DONE.
