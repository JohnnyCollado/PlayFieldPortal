# Disc Region Scanning — SAF sibling resolution

Source: direct request (device logcat, 2026-09-05, psx rescan). Effort: S–M.
Branch base: `redesign-app-picker`.

This is an implementation handoff. It is written to be executed without the conversation that
produced it. Every line reference was verified against the working tree on 2026-09-05; re-check any
that has drifted, but do not assume a helper exists that is not named here.

## Problem

A psx rescan emits a stack of `SecurityException` warnings from `DiscRegionReader`:

```
Region read failed for /storage/emulated/0/Roms/psx/Parasite Eve II (Disc 2)/….cue
java.lang.SecurityException: Permission Denial: reading … ExternalStorageProvider uri
content://com.android.externalstorage.documents/document/primary%3ARoms%2Fpsx%2F…%2Fparasite%20eve%20ii%20(disc%202).bin
requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs
```

Four defects, only the first of which is visible as an exception. Net effect: **`.cue`/`.gdi` region
detection has never worked on either code path** — SAF throws, raw-path fails silently.

That matters downstream. `DiscSetBuilder.derive` (`DiscSetBuilder.kt:126-137`) only splits a folder
into two disc sets when *every* member carries a known region; an unknown region falls back to
merging. So distinct regional dumps sitting in one folder are being silently merged into a single
set — exactly the Parasite Eve II (USA) / Parasite Eve II pair in the log.

### Defect 1 — non-tree document URI (the exception)

`DiscRegionReader.kt:148`:

```kotlin
return DocumentsContract.buildDocumentUri(authority, "$parent/$siblingName")
```

`buildDocumentUri` yields a bare `content://auth/document/<id>` with no `tree/` segment, so it
carries none of the ROM root's persisted grant. The `.cue` itself opens fine at `:126` precisely
because *that* URI is tree-derived.

This is the only `buildDocumentUri` call in the repo — the other ~30 sites all use
`buildDocumentUriUsingTree`. The correct precedent for this exact problem already exists at
`DiscImageOpener.kt:138-150` (`cueSiblingUri`).

### Defect 2 — lowercased sibling names

In the logged URI the directories keep their case (`Parasite%20Eve%20II%20(Disc%202)`) but the
filename is lowercased. That is `DiscSheets.kt:23` / `:40`, and it is deliberate — the file header
states the contract:

> Names are normalised to lowercase basenames — the callers match them against sibling files.

Correct for `DiscCompanionSuppressor` and `DiscImageResolver`, which use the result as a
case-insensitive membership set. `DiscRegionReader` is the odd consumer: it needs a real filename to
**open**. On ext4 that name does not exist, so fixing defect 1 alone converts the SecurityException
into a silent 404.

The raw-path branch has the same bug and has been failing completely silently: `:109` builds
`File(parent, "parasite eve ii (disc 2).bin")`, `!image.isFile` at `:80` returns null, no log line.

### Defect 3 — doc id with no `/`

`parent = docId.substringBeforeLast('/')` at `:147` returns the **whole string** when the id has no
slash (a sheet at volume root, `primary:game.cue`), producing `primary:game.cue/game.bin`.
`siblingDocumentId` (`DiscImageOpener.kt:272`) already handles this with a colon fallback.

### Defect 4 — the region memo never caches a null

Each file appears **twice** in the trace, from two different call sites: `derive:137` (step A,
region split) and `derive:204` (step D, enrichment) — four reads for two files inside 15 ms.

`DiscSetBuilder.kt:108-110`:

```kotlin
val regionByPath = HashMap<String, GameRegion?>()
fun regionOf(game: Game): GameRegion? = game.romPath?.let { path ->
    regionByPath.getOrPut(path) { regionReader.read(game) ?: game.region }
}
```

Kotlin's `getOrPut` branches on `value == null`, not `containsKey`. In a map whose value type is
nullable, a memoized `null` is indistinguishable from an absent key, so the lambda re-runs on every
call. The comment at `:105-107` — "read once per path within the batch" — is false for precisely the
unknown-region case.

Cost: a doubled 256 KB head read per PSX game, for the exact population where detection is already
failing. Tasks 1–3 make successful reads memoize (non-null values cache fine), but `.chd` and
genuinely unknown discs stay doubled, and `DiscSetBuilder.kt:128` expects those to be common.

## Goal

`.cue`- and `.gdi`-backed games detect their region on both the SAF and raw-path branches, each disc
image is read at most once per scan batch, and a genuinely unreadable sheet degrades to a single
quiet warning rather than a repeated stack trace.

## Decisions already made

Settled with the project owner. If one turns out to be impractical, stop and report rather than
substituting your own.

- **Hoist the sibling-id helper to `core-data`** and dedupe the feature-achievements copy, rather
  than adding a third implementation.
- **Keep the failure at `Timber.w` but drop the stack trace** — a missing `.bin` is a data problem,
  not a code problem, and the trace repeats per game per scan.

## Security note

`DiscRegionReader` has no path-traversal guard. Today the parser's `substringAfterLast('/')` provides
one incidentally. Cue sheets are attacker-controllable content (cf. `95b5c38`, "Harden untrusted
input handling"), so the case-preserving parser **must** keep basename stripping, and the reader
should apply the explicit guard that `DiscImageOpener.kt:154` already uses.

---

## Task 1 — Case-preserving sheet parsers

**File:** `feature/feature-library/src/main/kotlin/com/playfieldportal/feature/library/scanner/DiscSheets.kt`

Do **not** change the existing signatures — `DiscCompanionSuppressor.kt:46-47` and
`DiscImageResolver.kt:77,164` depend on the lowercase-set contract. Add raw variants and define the
existing functions on top of them, so there is still one parse per format:

```kotlin
/** FILE entries in sheet order, original case, basenames only. */
fun cueSheetReferencesRaw(lines: List<String>): List<String>

fun cueSheetReferences(lines: List<String>): Set<String> =
    cueSheetReferencesRaw(lines).map { it.lowercase() }.toSet()

fun gdiSheetTrackNamesRaw(lines: List<String>): List<String>

fun gdiSheetTrackNames(lines: List<String>): Set<String> =
    gdiSheetTrackNamesRaw(lines).map { it.lowercase() }.toSet()
```

Requirements:

- The raw variants keep the `substringAfterLast('/')` / `substringAfterLast('\\')` stripping. That is
  a security control, not cosmetics — do not drop it while removing the `.lowercase()`.
- `List` rather than `Set` is deliberate: `DiscRegionReader` calls `.firstOrNull()` and genuinely
  means "first data track". Today that works only by `mutableSetOf`'s insertion order.
- Existing behavior must be bit-identical: `mutableSetOf` is a `LinkedHashSet` and
  `.map { … }.toSet()` is too, so both dedup and iteration order are preserved for the two existing
  consumers.
- Update the file header comment — it currently asserts every caller wants lowercase, which stops
  being true.

## Task 2 — Hoist the SAF sibling helpers into core-data

**To:** `core/core-data/src/main/kotlin/com/playfieldportal/core/data/saf/SafChildren.kt`
(`safScanStartDocId` already lives there, so doc-id helpers are in keeping; a new `SafDocumentIds.kt`
in the same package is fine if you prefer the separation)

Move both helpers out of `DiscImageOpener.kt` and make them public:

```kotlin
/** The document id of a file sitting next to [documentId]; null if the id has no parent. */
fun safSiblingDocumentId(documentId: String, siblingName: String): String?

/** A sheet-referenced name must be a bare sibling — path-traversal guard on untrusted sheets. */
fun isSafeSiblingName(name: String): Boolean
```

- `safSiblingDocumentId` keeps the existing logic verbatim from `DiscImageOpener.kt:272-278`: split
  on the last `/`, else the last `:`, else null.
- `isSafeSiblingName` is `DiscImageOpener.kt:154-155` unchanged.
- Both feature modules already depend on `core:core-data` (`feature-library/build.gradle.kts`,
  `feature-achievements/build.gradle.kts:54`), and neither depends on the other — core-data is the
  only legal shared home.
- Update `DiscImageOpener.cueSiblingUri` (`:138-150`) to call the hoisted versions and delete its
  private copies.
- Move `feature-achievements/src/test/.../match/SiblingDocumentIdTest.kt` to core-data's test source
  set alongside the helper, adjusting the package. Do not leave it behind testing a deleted symbol.

## Task 3 — Fix `DiscRegionReader`

**File:** `feature/feature-library/src/main/kotlin/com/playfieldportal/feature/library/scanner/DiscRegionReader.kt`

**3a. Replace `siblingDocumentUri` (`:144-149`)** with the tree-scoped build:

```kotlin
private fun siblingDocumentUri(sheetUri: Uri, siblingName: String): Uri? {
    if (!isSafeSiblingName(siblingName)) return null
    val docId = runCatching { DocumentsContract.getDocumentId(sheetUri) }.getOrNull() ?: return null
    val siblingId = safSiblingDocumentId(docId, siblingName) ?: return null
    return runCatching { DocumentsContract.buildDocumentUriUsingTree(sheetUri, siblingId) }.getOrNull()
}
```

`buildDocumentUriUsingTree` reads the tree id from path segment 1, so passing the sheet's own
tree-derived document URI is correct and inherits the grant. Drop the now-unused `authority` lookup.

**3b. Switch to the case-preserving names** at `:108`, `:113`, `:128`, `:133` — use
`cueSheetReferencesRaw` / `gdiSheetTrackNamesRaw`. This is what actually fixes the raw-path branch
(`resolveRawImageFile`), which has been silently returning null for every mixed-case `.bin`.

**3c. Guard the raw path too.** `resolveRawImageFile` (`:105-118`) builds `File(file.parentFile, name)`
straight from sheet content. Apply `isSafeSiblingName` there as well — the basename stripping in
Task 1 covers it, but the explicit check documents the intent and survives future parser edits.

**3d. Logging (`:87`).** Keep the level at `w`, drop the throwable:

```kotlin
Timber.w("Region read failed for %s: %s", game.romPath, e.message)
```

A broken sheet stays visible; scans stop emitting a 25-frame trace per game.

## Task 4 — Fix the region memo

**File:** `feature/feature-library/src/main/kotlin/com/playfieldportal/feature/library/scanner/DiscSetBuilder.kt`

Make the memo key on presence rather than nullness (`:108-110`):

```kotlin
val regionByPath = HashMap<String, GameRegion?>()
fun regionOf(game: Game): GameRegion? = game.romPath?.let { path ->
    if (regionByPath.containsKey(path)) regionByPath[path]
    else (regionReader.read(game) ?: game.region).also { regionByPath[path] = it }
}
```

This is the whole change — do not restructure `derive`, and do not try to collapse the `:137` and
`:204` call sites. Two calls against a working memo is correct and cheap; the bug is that the memo
does not hold a null.

Leave the `?: game.region` fallback alone. It is what keeps a transient read failure from wiping an
already-stored region, and it stays correct under the fix.

## Task 5 — Tests

**`feature/feature-library/src/test/.../DiscSheetsTest.kt`** — extend, don't rewrite. Existing cases
pin the lowercase contract and must keep passing unchanged. Add:

- `cueSheetReferencesRaw` preserves case: `FILE "Parasite Eve II (Disc 2).bin" BINARY` →
  `["Parasite Eve II (Disc 2).bin"]`, and `cueSheetReferences` on the same input still yields the
  lowercase set.
- Raw variants still strip directory components: `FILE "sub/track02.bin" BINARY` → `["track02.bin"]`,
  and a parent-directory style reference reduces to its basename.
- Raw preserves sheet order across multiple `FILE` lines (the `.firstOrNull()` contract).
- The same three for `gdiSheetTrackNamesRaw`.

**core-data test source set** — the relocated `SiblingDocumentIdTest`, plus a case for the colon
fallback (`primary:game.cue` + `game.bin` → `primary:game.bin`) if not already covered, since that
is defect 3.

**`isSafeSiblingName`** — rejects a parent-directory reference, a name with a forward slash, a name
with a backslash, and the empty string; accepts a plain name with spaces and parentheses.

**`feature-library/src/test/.../DiscSetBuilderTest.kt`** — the memo fix is directly testable, since
`derive` already takes an injectable `RegionReader` (`DiscSetBuilder.kt:74,88`). Add a counting fake:

- A `RegionReader` returning `null` that increments a per-path counter is invoked **exactly once per
  path** across a `derive` call covering both the step-A and step-D sites. This test fails on today's
  code — confirm that before fixing, or it is not pinning anything.
- A reader returning a non-null region is likewise called once (guards against a fix that breaks the
  working path).
- The `?: game.region` fallback still applies: a null read on a game with a stored region leaves that
  region intact.

`DiscRegionReader` itself needs a `Context`/`ContentResolver` and stays uncovered; the extraction
above is what makes the risky parts testable without it.

---

## Files touched

| File | Change |
|---|---|
| `feature-library/.../scanner/DiscSheets.kt` | add `*Raw` parsers; existing functions delegate; update header comment |
| `core-data/.../saf/SafChildren.kt` | **new** `safSiblingDocumentId`, `isSafeSiblingName` |
| `feature-library/.../scanner/DiscRegionReader.kt` | tree-scoped sibling URI; raw names; traversal guard; log without trace |
| `feature-achievements/.../match/DiscImageOpener.kt` | delete private helpers, call the core-data ones |
| `feature-library/.../scanner/DiscSetBuilder.kt` | `regionOf` memo keys on `containsKey`, not nullness |
| `feature-achievements/src/test/.../SiblingDocumentIdTest.kt` | move to core-data test source set |
| `feature-library/src/test/.../DiscSheetsTest.kt` | add raw-variant cases |
| `feature-library/src/test/.../DiscSetBuilderTest.kt` | add region-memo call-count cases |

**Do not touch:** `DiscCompanionSuppressor.kt`, `DiscImageResolver.kt`, `DiscSetReconciler.kt`,
`LibraryScanner.kt`, and everything in `DiscSetBuilder.kt` other than the three-line `regionOf` memo.
Their behavior is correct; they simply never received a non-null region for sheet-based games. Region
detection starting to work is the whole point — do not "fix" the merge or split logic to compensate
for what it used to see.

## Verification

Ask the project owner before running Gradle — builds are not run unprompted in this repo.

```bash
./gradlew :feature:feature-library:testDebugUnitTest :core:core-data:testDebugUnitTest :feature:feature-achievements:testDebugUnitTest
```

On device, the owner drives navigation; request screenshots only when they say they are ready.

1. **The warnings are gone.** Rescan the psx console with the Parasite Eve II folders present. No
   `SecurityException` from `DiscRegionReader` in logcat.

   ```bash
   "C:\Users\johnn\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s DiscRegionReader:* LibraryScanner:*
   ```

2. **Region is actually detected**, not merely quiet. This is the real check — defect 2 would leave a
   silent null. Confirm the `.cue`-backed psx rows come back with a non-null region (inspect the
   `games` table, or add a temporary `Timber.d` of the detected region during the run and remove it
   before committing).
3. **Regional split works.** A folder holding a USA disc and a non-USA disc of the same title should
   now resolve as two disc sets rather than one merged set (`DiscSetBuilder.kt:126-137`).
4. **Raw-path parity.** A console configured by raw path rather than SAF detects region for the same
   `.cue` — that branch was silently broken and must now work too.
5. **Sheet at volume root.** A `.cue` directly under the granted root (document id with no `/`)
   resolves its `.bin` rather than producing `primary:game.cue/game.bin`.
6. **Soft failure still soft, and read once.** Temporarily rename a referenced `.bin`: the scan logs
   **one** warning line per game with no stack trace — not two, which is what today's broken memo
   produces — the game keeps a null region, and the scan completes normally.
7. **No achievement regression.** `DiscImageOpener` still opens a SAF `.cue`-backed game — that path
   worked before and shares the hoisted helpers now.
