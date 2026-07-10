# Portable Media Library — PFP Implementation Plan (mirrors the Feature Spec)

Status: **M-A + M-B IMPLEMENTED (July 2026)** — layout v2 + `artwork_records` (DB v26) +
resolvers + in-place v1 migrator + importer/relink/manual rewire; `RoutingArtworkStore` sends
scrapes and manual picks into the portable library when a folder is linked (internal fallback
otherwise), with §22 priority (scrapes never overwrite valid/locked/user assets, user picks
lock their slot) and deleteAll never touching user files. Next: M-C (Scan & recovery).
Supersedes the folder-layout portions of `portable-artwork-plan.md`; everything else in that
plan (ScreenScraper, storage seam, identity columns) stands.

Baseline: the ES-DE importer + per-game-folder portable library shipped on branch
`artwork-refactor` and is validated on-device. This plan maps every section of the Portable
Artwork Library spec onto that system — reusing it wherever it already satisfies the goal, and
pivoting only the destination layout plus the four approved amendments:

- **A1** Manifest is a root summary only; the DB is the per-game map; Scan rebuilds it.
- **A2** Checksums are computed only by background Verify/Scan, never inline on writes.
- **A3** Naming modes ship as an enum with only `MATCH_ROM_FILENAME` enabled in V1.
- **A4** Import machinery (sources/matcher/planner/worker/UI) carries over unchanged.

---

## §1 Goal → achieved by a destination-layout swap on existing seams

All writes already flow `caller → ArtworkStore / ArtworkImportExecutor → PortableArtworkLibrary
→ DocumentsContract`. Only `PortableArtworkLibrary`'s path scheme and the executor's naming
change; graders of each goal:

| Spec goal | How the current system delivers it |
|---|---|
| Easy to browse / back up / move | ES-DE-shaped folders, human filenames (this plan) |
| Usable by ES-DE directly | layout **is** `downloaded_media` shape; export (§15) adds the wrapper |
| Independent of app storage | `ArtworkFolderRepository` SAF tree (shipped) |
| Predictable folders | `ArtworkPathResolver` (new, trivial) over PFP platform ids = ES-DE canonical names |
| Collision-safe | ROM-filename naming + write-time collision check against `artwork_records` |

## §2 User Experience → existing screens, two additions

Shipped: **Artwork ▸ Artwork Folder & Import** (folder link/re-link/forget, import, report).
Additions: *Export / Reorganize Artwork* row (M-E), *Artwork Naming Format* row (display-only
single option, A3), *Artwork Library Status* row (counts/bytes from `artwork_records` — one
indexed query, instant). First-scrape folder prompt: reuse the existing picker flow, triggered
when a scrape starts with no folder linked (M-B).

## §3 Folder structure → `PortableArtworkLibrary` path scheme v2

`{platform}/{mediaType}/{PortableName}.{ext}` with `import/` unchanged as the drop zone and
`pfp-media-library.json` at root. Platform dir names are PFP platform ids (already ES-DE
canonical); `PlatformFolderHintResolver.esDeFolderName()` maps the few divergences (`x360` →
`xbox360`) on export. `entryDirDocId(platform, slug)` becomes `mediaDirDocId(platform,
mediaType)` — the docId cache now holds ~10 dirs per platform instead of one per game
(smaller, hotter cache). Hard rule inherited from the fan-out tradeoff: **no directory
enumeration on any UI path — `artwork_records` is the only artwork resolver.** Scans/existence
checks use one cursor per media dir per run.

## §4 Portable names vs internal IDs → already split

`games.artwork_key` (shipped, v25) stays the stable internal join; the four game columns keep
carrying resolved `content://` URIs (UI untouched, Coil unchanged). New `PortableNameResolver`
replaces `ArtworkNaming.slug()` for **filenames only**: ROM filename stem preserved verbatim
(case, spaces, tags) minus characters illegal on FAT/SAF, Windows-reserved names suffixed —
the existing hostile-input test table carries over. `normalizeForMatch`/`simplifyTitle` and
the matcher are untouched (A4). Retiring `slug()` is safe: normalization v1 never shipped to
users; manifest bumps to formatVersion 2.

## §5 Naming modes → enum reserved, one value live (A3)

`ArtworkNamingMode { MATCH_ROM_FILENAME }` stored in DataStore next to the storage-mode key;
settings row shows it non-interactively ("Match ROM Filename — recommended"). Display-name /
clean-title / compatibility profiles stay out of V1: they are collision generators and imply a
full-library SAF rename plus DB relink on switch.

## §6 Collisions → detected at write time via the records table

`rom_path` is unique per library, so same-platform stem collisions only arise from
case-folding (FAT is case-insensitive) and disc-tag stripping (excluded by A3/§7). Before any
write: one indexed query on `artwork_records (platform, mediaType, portableName
COLLATE NOCASE)`. Collision → keep full tags (they are never stripped in V1) and if still
colliding, suffix ` (2)` and record it in the run report. Never overwrite another game's file.

## §7 Multi-disc → duplicate-row sharing now, title grouping later

Shipped: same-stem duplicate rows (.cue + .bin) match as one game; under the new schema both
rows get `artwork_records` pointing at the **same file** — the spec's "multiple game records
reference one portable file" requirement, satisfied by rows not copies. True multi-disc
grouping (FF7 Disc 1–3 as one title) is a *library* feature PFP doesn't have; until it exists,
per-disc names (lossless, collision-free) are the correct default and the disc-tag-stripped
shared name is deferred with the grouping feature.

## §8 Media-type mapping → one table, both directions

`EsDeImportSource.MEDIA_TYPE_TO_KIND` (shipped) becomes the single bidirectional
`ArtworkKind ↔ portable dir` map in `ArtworkPathResolver`: covers, fanart, marquees,
screenshots, miximages, physicalmedia, titlescreens + 3dboxes/videos/manuals reserved. Slot
resolution (UI columns from records, priority per slot): `iconUri` ← `pfp/icons` else covers;
`heroUri` ← miximages else fanart; `artworkUri` ← fanart; `logoUri` ← marquees. Standard dirs
never hold PFP-derived images.

## §9 PFP namespace → reserved dirs under each platform

`{platform}/pfp/{icons,backgrounds,overlays,banners}/` — path resolver knows them; V1 writes
nothing there (no generator exists yet); importer/scan ignore-lists them for other frontends'
sake and ours.

## §10 Manifest → root summary only (A1)

`pfp-media-library.json`: formatVersion 2, library UUID, createdAt, appVersion, naming mode,
entry-count hint. Rewritten once per completed operation (import run, scrape batch, scan) —
never per file. The per-game map is `artwork_records`; §18 Scan is the rebuild path after
reinstall (filenames are ROM filenames = matcher pass 1, so rebuild needs no sidecar data).
Existing `ArtworkLibraryManifest` class is reshaped, defensive parsing (size cap,
ignoreUnknownKeys) kept.

## §11 Database → `artwork_records` (v26), migrating `artwork_index`

Room migration v25→v26: create `artwork_records(id PK, game_id, platform_id, artwork_type,
portable_name, relative_path, document_uri, source, source_provider, size_bytes,
width/height NULL, checksum NULL, created_at, updated_at, user_assigned, locked)`; copy
`artwork_index` rows across (location/doc mapping is mechanical), drop `artwork_index`.
Checksum/width/height filled only by background Verify (A2). Per-entry `metadata.json` retires
— provenance now lives here and rides backups; the importer stops writing it (one fewer write
per game).

## §12 Scraper writes → M-B, the one big refactor

`PortableArtworkStore` implements the existing `ArtworkStore` interface over
`PortableArtworkLibrary`; Hilt binding switches on `artwork_storage_mode` (internal fallback
stays for unlinked installs — behavior identical to today). The 12-step pipeline maps to the
already-shipped write discipline: temp/cache download → magic-byte validation (`PayloadCheck`)
→ collision check (§6) → kernel-copy into the media dir → record upsert → column refresh.
"Update manifest" is batched per scrape run (A1); no checksum inline (A2).

## §13 Manual picks → same seam, locked records

`GameDetailViewModel` already calls `ArtworkStore.saveVersionedFromUri`; the portable backend
routes it into the correct media dir under the game's portable name and writes the record with
`user_assigned = true, locked = true`. The "Copy artwork into portable library" toggle is
default-ON and honestly labeled (the source image is not needed afterward). Locked enforcement
already exists in the executor; M-B extends it to the scrape paths (§22).

## §14 Import → shipped; add in-place adoption (M-D)

The ES-DE importer is live and device-validated (detection, 3-pass matcher + cue/bin and
index-prefix fixes, preview, ambiguous review, move/copy, resumable worker, reports). Layout
pivot makes move mode cheaper (1 document op per file — names preserved, no rename). New
option when a picked folder already matches the library shape: **"Use this folder as the PFP
Artwork Library"** — validate writable grant, adopt in place, then §18 Scan links everything;
zero bytes copied.

## §15 Export → M-E, nearly free

The library *is* ES-DE-shaped. Export = new worker copying selected media types into a
user-picked `downloaded_media/` tree: platform names via `esDeFolderName()`, `pfp/` skipped
unless requested, kernel-copy, conflict/missing report reusing the `ImportSummary` shape and
report screen. Never mutates the live library. Profiles beyond ES-DE = future enum values.

## §16 Change location → M-F, reusing migrator machinery

Move / copy / relink between trees with the original plan's §2.7 discipline: non-destructive
copy phase → verify (size compare via one cursor per dir) → atomic flip of records +
columns → old library untouched until the user confirms. Same-provider moves use
`moveDocument`. Relink-without-moving = adopt (§14) + Scan.

## §17 Recovery → extend what's shipped

`hasLiveGrant()` + re-link row exist. Add: startup grant check surfacing a banner on the
import screen and a badge on Artwork settings; Coil failures already fall back to the
no-artwork placeholder; records/columns are never deleted for a dead grant. Cached-thumbnail
fallback is deferred (Coil's disk cache already gives an effective version of it).

## §18 Scan → M-C, streaming, matcher-reusing

One worker: per platform, per media dir, one cursor stream → diff against `artwork_records`
(new / missing / changed-size / duplicate portable names) → untracked files run through the
existing `ArtworkImportMatcher` for relinking → column refresh → manifest summary rewrite →
report row (same report screen). Constant memory, cancellable, no image decoding.

## §19 Orphans → records query + optional quarantine

Orphan = record whose `game_id` no longer exists, or scanned file with no record and no match.
Default keep; optional move to `_orphaned/` (path resolver knows it; scan/import skip it);
delete only from an explicit, itemized review. Sizes come from records — instant.

## §20 SAF requirements → already satisfied, names mapped

Spec abstraction ↔ ours: `ArtworkLibraryStorage` = `PortableArtworkLibrary`;
`DocumentTreeReader` = `SafChildren` (core-data); `DocumentTreeWriter` = the library's
create/copy/move/write ops; `ArtworkPathResolver`/`ArtworkFilenameResolver` = new resolver
pair; `ArtworkFileValidator` = `PayloadCheck`/`ImageFormat`. Guarantees already enforced: tree
picker + persisted read/write grant, no broad storage permission, no fake paths, no
atomic-move assumption (copy+delete fallback), all IO off-main.

## §21 Background processing → established pattern

`ArtworkImportWorker` pattern (plan-as-file handoff, throttled notifier + `setProgress`,
honest cancel, persisted report) is the template for scrape batches (M-B), Scan (M-C), Export
(M-E), Move (M-F). Cancellation is cooperative and file-op-safe today; stays that way.

## §22 Conflict policy → enforced through records

Priority ladder (user > locked > existing portable > imported > scraped > default) becomes
a single `canOverwrite(record?, incomingSource)` check in the portable store, consulted by
scraper, importer, and manual paths alike. Default remains missing-only everywhere. Advanced
replace options (same-source replace, prefer-higher-res, keep-both) are post-V1; the record
fields they need (source, size, width/height) exist from day one.

## §23 V1 scope → status

| Item | Status |
|---|---|
| User-selected root, persisted SAF, import, reports, background workers, conflict basics | **Shipped** |
| Platform + media-type folders, ROM-filename naming, records table | **M-A** |
| Scraper + manual picks → portable | **M-B** |
| Library scan, missing-folder recovery | **M-C** |
| In-place adoption | **M-D** |
| ES-DE export | **M-E** |
| Location change + legacy internal migration, orphans | **M-F** |
| Deferred (unchanged from spec) | watching, cloud sync, shares, other profiles, gamelist.xml, video conversion |

## §24 Milestones

- **M-A — Layout pivot + records (next).** `PortableNameResolver` + `ArtworkPathResolver`
  (+ test tables), library path scheme v2, DB v26, importer/executor rewire, manifest v2,
  device migration: wipe-and-reimport if the existing library was copy-imported, else a
  one-shot relocator driven by the old metadata.json `romFileName`s. *Gate: re-import on the
  Thor, all 6 consoles, then a second run showing full skip (idempotence).* 
- **M-B — Portable scrape + manual picks.** `PortableArtworkStore` binding, §22 policy check,
  first-scrape folder prompt, scrape batches as workers. *Gate: scrape into folder, verify
  ES-DE reads it.*
- **M-C — Scan & recovery.** *Gate: delete a file + drop an untracked file → scan reports and
  relinks both.*
- **M-D — Adoption mode.** *Gate: point PFP at a pre-existing ES-DE `downloaded_media`; zero
  copies; artwork resolves.*
- **M-E — ES-DE export.** *Gate: exported tree loads in ES-DE.*
- **M-F — Move/copy/relink + legacy migration + orphans.**

Each milestone leaves the app fully working; M-A is the only one that touches existing
behavior, and its blast radius is the four files behind the storage seam.
