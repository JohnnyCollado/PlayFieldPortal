# Play Field Portal — Category Icons from a Local Image: Implementation Plan

User-created categories (Settings ▸ Categories) can use an icon picked from a file on the device —
a still (PNG / JPG / WEBP / BMP / HEIF) or an animated GIF — that behaves exactly like the custom
icons built-in categories already take through Customize XMB Icons: same import gate, same
messages, same Still / Animated loading, same Animated Images (Animated / Reduced / Static) gating.

**Status: planned, nothing implemented (2026-10-01).**

**Mockup (approved):** section `id="p1"` of the overnight mockups page (screens A–F). §4 restates
every behaviour it shows, so the build does not depend on opening it.

> **Working rules for the implementing session**
> - Tests first: each task writes its failing tests (§7) against the not-yet-existing API, then the
>   production code. Code written ahead of its tests is reverted.
> - Do not run Gradle unless the user asks. Hand the user each task's commands in a bash block.
> - Done for a task = its tests green **and** no new Kotlin compiler warnings in the touched modules.
> - Match the surrounding code's style: dense, explanatory comments that say *why*.
> - Do not commit.

---

## 1. Context

Built-in crossbar categories already accept a user image. `CustomIconStore`
(`core/core-data/.../repository/CustomIconStore.kt`) stores one file per slot at
`filesDir/custom-icons/<slotKey>.<ext>`, gated by `CustomIconLimits`
(`core/core-ui/.../icons/CustomIconLimits.kt`), loaded as `CustomIcon.Still` / `CustomIcon.Animated`,
and provided to every render site through `LocalCustomIcons` (XMBShell.kt:561). Built-in categories
reach their slot through `CategoryIconGlyph` → `catbarSlotKeyFor(iconKey)`
(`core/core-ui/.../icons/XmbIconOverrides.kt`). Animation is gated in exactly one place,
`CustomIconSurface` (`LocalImageMotion.shouldAnimate(...)`, `LocalIconFocused`).

User categories are created and edited in `CategoryManagerViewModel` / `CategoryManagerScreen`
(`feature/feature-settings`). Their icon today can only be a key from `CATEGORY_ICON_CATALOG`
(`core/core-ui/.../icons/CategoryIcons.kt`).

## 2. Problem

A user category can only borrow one of the catalog glyphs. Worse, because a user category's
`iconKey` resolves to a shared slot (`ic_favorites` → `catbar_favorites`), its glyph silently
changes whenever the user customises the built-in category it borrowed from — it has no icon of its
own.

## 3. Current Behaviour (from the code)

| Area | What the code does today | Where |
| --- | --- | --- |
| `Category.iconKey` = `"custom"` | **Never written, never read.** Only a comment on the field. `categoryIconFor("custom")` would fall through to the games glyph. | `core-domain/.../model/Category.kt:8`, `CategoryIcons.kt:112` |
| `Category.customIconUri` / `categories.custom_icon_uri` | **Dead column.** Mapped in `CategoryEntity.toDomain/toEntity` and copied for built-ins in `canonicalXmbCategories`, but nothing writes it and nothing renders it. | `CategoryEntity.kt:24`, `XmbLists.kt:81` |
| Category ids | Only one creator: `"custom_" + slug([a-z0-9_]) + "_" + (maxPosition+1)`, timestamp when the slug is blank. Built-ins use fixed ids (`games`, `settings`, …). Name length is unbounded, so ids are too. | `CategoryRepositoryImpl.createCustomCategory` |
| Id reuse | Deleting the last category and recreating the same name yields the **same id**. | same |
| Store key gate | `CustomizableIcons.isValidKey(key)` on import / clear / load — the only thing stopping a key escaping the directory. | `CustomIconStore.kt` |
| `clearAll()` | Deletes the whole `custom-icons/` directory. | same |
| Crossbar glyph | `XMBCategoryBar` calls `CategoryIconGlyph(iconKey = category.iconKey)` — no category id. | `XMBCategoryBar.kt:264` |
| Built-in icon on the bar | `canonicalXmbCategories` rebuilds built-ins from the fallback list and does **not** copy the stored `iconKey`, so Change Icon on a built-in has no effect on the bar. | `XmbLists.kt:72-87` |
| Category Manager picker | `PickIconContent` lists `ICON_OPTIONS`; `chooseIcon` advances create → PICK_TYPE, or calls `setIcon` and returns to DETAIL. | `CategoryManagerScreen.kt:244`, `CategoryManagerViewModel.kt:148` |
| Detail "Change Icon" value | Prints the raw key (`ic_favorites`); the list sublabel prints `Icon: ic_games`. | `CategoryManagerScreen.kt:231, :334` |
| `CategoryManagerUiState.message` | Declared, never set, never rendered. | `CategoryManagerViewModel.kt:65` |
| Customize XMB Icons | Slots come from `CustomizableIcons.group(group)` in three places: `CustomIconSession.focusedSlot`, `onCustomIconSlotMove`, and the overlay's strip. | `XMBViewModel.kt:335-349, :10740`, `CustomIconsOverlay.kt:96` |
| Save as Theme | `PfpThemeStore.saveCurrentLook` iterates `CustomizableIcons.ALL` only. | `PfpThemeStore.kt:429` |
| Theme apply | Loads theme icons gated by `CustomizableIcons.isValidKey`; never touches `custom-icons/`. | `XMBViewModel.loadThemeIconOverrides`, `PfpThemeStore.kt:118` |
| Backup | `custom-icons` is in `BUNDLED_FILE_ROOTS` (whole tree bundled, replaced on restore) and `custom_icons_stamp` is a backed-up long key. Files are committed **before** categories are upserted. | `BackupManager.kt:620, :831`, restore at ~:296 |
| Settings sees user icons | Settings is composed inside XMBShell's `CompositionLocalProvider` (provider at :557, `SettingsNavHost` at :1079, provider closes at :1534), so `LocalCustomIcons` is live in Category Manager. | `XMBShell.kt` |
| Startup sweep precedent | `UiMediaStore.pruneOrphans()` from `MainActivity`; `DatabaseInitializer.initialize()` runs every launch from `PFPApplication`. | `UiMediaStore.kt:208`, `DatabaseInitializer.kt` |

## 4. Goals (approved behaviour)

1. **Create flow** (name → icon → type) and **Change Icon** both show a **"From Your Device"** group
   above the built-in catalog:
   - No image: **Choose Image…** (sublabel "PNG, JPG, WEBP, BMP, HEIC or GIF · animated GIFs play").
   - Image set: **Your Image** (live preview, plays when focused), **Replace Image…**, **Remove
     Image** (sublabel names the built-in it goes back to, e.g. "Go back to the built-in icon
     (Favorites)").
   - Picker: system `OpenDocument` with `image/*`.
2. **Picking a built-in icon clears the image** — only one active choice ever.
3. **Rejected file**: the existing `CustomIconLimits` message is shown under the group (screen F)
   and the previous icon is kept.
4. On the **crossbar** the image renders through the custom-icon path: size, matte, icon colour
   rules, focus scale and Animated Images behave like the built-in categories' custom icons.
5. **Customize XMB Icons ▸ Category Bar** lists the user's categories after the built-ins; Pick and
   Reset there write / clear the same file.
6. **Save as Theme…** excludes these images; **PFP backups** include them.
7. Category detail **Change Icon** shows a readable value: "Your Image" with a thumbnail, otherwise
   the catalog label ("Favorites", not `ic_favorites`).
8. **Deleting a category deletes its image**; a startup orphan sweep removes leftovers.

## 5. Non-Goals

- **Memory-card collections** (`GameCollection.iconKey`) — not in scope.
- **Built-in categories' Category Manager icon** — they keep using Customize XMB Icons' `catbar_*`
  slots. The "From Your Device" group is not offered for protected categories. The pre-existing
  "Change Icon on a built-in has no effect on the bar" issue (§3) is **not** fixed here.
- **No schema change**: `Category.customIconUri` / `custom_icon_uri` and `iconKey = "custom"` stay
  unused. No Room migration.
- **No change** to `CustomIconLimits` numbers or messages, `CustomIconSurface`, `ImageMotion`,
  `PfpThemeCodec`, `IconSlots`, `CustomizableIcons`, Theme Studio, or `BackupManager`.
- **No HEIC MIME fix** (see §12 risk R2) unless the user approves it separately.
- **No change** to the category bar's layout, move session, or the create flow's step order.

## 6. Architectural Decisions

**AD1 — A second key family in the existing store: `usercat_<categoryId>`.**
The image lives at `filesDir/custom-icons/usercat_<categoryId>.<ext>`, written by
`CustomIconStore.import`. *Why:* one store already solves staging, capped copy, the
`CustomIconLimits` gate, extension swap, Coil eviction, the reload stamp, Still/Animated loading,
`LocalCustomIcons` delivery and backup — reuse is the whole point of "works exactly like".
*Rules out:* a new store, a new directory, a new stamp, a new composition local.

**AD2 — The key family is validated by a strict pattern in a new core-ui object
`UserCategoryIconKeys`, NOT added to `CustomizableIcons` / `IconSlots`.**
`keyFor(categoryId)` returns `"usercat_$categoryId"` only when the id matches
`^custom_[a-z0-9_]{1,120}$` (the exact shape `createCustomCategory` produces, length-capped),
else `null`. `isValidKey(key)` accepts those keys plus one constant `DRAFT_KEY = "usercat_draft"`
(cannot collide: real keys always start `usercat_custom_`). `categoryIdFor(key)` is the inverse.
`CustomIconStore`'s gate becomes `CustomizableIcons.isValidKey(k) || UserCategoryIconKeys.isValidKey(k)`.
*Why:* the pattern admits only `[a-z0-9_]`, so no `/`, `.` or `..` can reach a file name; the
length cap keeps the file name well under the 255-byte limit (an over-long name would otherwise
make the store's `copyTo` fallback throw). Keeping it out of `CustomizableIcons` means theme
export (`saveCurrentLook` iterates `CustomizableIcons.ALL`), theme import gating and Theme Studio
exclude these keys **for free**, which is exactly decision 3. core-ui is the lowest module that
core-data, feature-settings and feature-xmb all depend on, and already hosts `CustomIconLimits`.
*Rules out:* registering per-category slots in theme-kit; free-form keys; any category id that
doesn't match the pattern getting an image (fail closed — the group is simply not offered).

**AD3 — File presence IS the assignment; `iconKey` stays the built-in fallback.**
A category with an image keeps its catalog `iconKey`; the render path checks
`LocalCustomIcons[usercat_<id>]` first and falls back to today's chain. "Remove Image" is just
`store.clear(key)`. *Why:* matches the store's documented contract ("the directory is the source of
truth", same as `UiMediaStore`); a missing / restored-without-file / swept image degrades to a
real glyph, never to the games fallback; no second store needs to stay in sync with Room; no
absolute paths in the DB to rewrite on restore. *Rules out:* writing `iconKey = "custom"` or
`customIconUri`.

**AD4 — Create flow imports to a draft key, then moves it.**
The category has no id until `chooseType`. Picking an image during create imports to
`DRAFT_KEY` immediately (so rejection is reported on the icon step, screen F), and after
`createCustomCategory` returns the id the VM calls a new `CustomIconStore.move(DRAFT_KEY,
keyFor(id))`. Cancelling the create flow clears the draft; the startup sweep removes any draft
left by a killed process. If no built-in was chosen, the category is created with
`FALLBACK_CATEGORY_ICON.key` (`ic_games`) as its fallback `iconKey`.
*Rules out:* holding the SAF `Uri` across steps and importing after create (rejection would land
after the category exists, and the grant may lapse); generating the id early (changes the
repository's id contract).

**AD5 — Category-aware glyph resolution in core-ui.**
`CategoryIconGlyph` gains `categoryId: String? = null`. A pure resolver
`resolveCategoryCustomIcon(iconKey, categoryId, userIcons, themeIcons): CustomIcon?` decides:
user-category image (user tier only) › existing `catbar_*` user pick › theme icon › `null`
(built-in drawable / console art as today). `XMBCategoryBar` passes `category.id`. The Category
Manager catalog rows pass no id, so they are unchanged. *Why:* the drawing still funnels through
`CustomIconSurface`, so Animated Images, the off-screen hold, `LocalIconFocused` and the matte all
apply with zero new motion code. *Rules out:* a parallel bar-only image path.

**AD6 — Deletion clears the image in the repository; the sweep runs once at startup.**
`CategoryRepositoryImpl` gets `CustomIconStore` injected; `delete()` clears `keyFor(id)` after the
row is gone; a new `pruneOrphanCategoryIcons()` passes every stored category id to a new
`CustomIconStore.pruneUserCategoryIcons(liveIds)`, called from `DatabaseInitializer.initialize()`
after `reconcileBuiltInCategories()`. *Why:* every delete path is covered, not only the screen's;
the id-reuse case (§3) can never inherit a stale image; startup is the one moment no restore is
mid-flight (restore commits files before categories, so a sweep during restore would delete
restored images). *Rules out:* sweeping on every stamp change or from the screen.

**AD7 — Category Manager owns its picks through the store; it reads presence from a store flow.**
`CategoryManagerViewModel` gets `CustomIconStore` injected and a new
`CustomIconStore.observeStoredKeys(): Flow<Set<String>>` (the stamp mapped to a directory listing
of valid keys). Rows gain `hasImage`, `iconLabel` and `deviceImageKey` (null for protected / non-
matching ids). Previews in the screen draw `LocalCustomIcons[key]` through `CustomIconSurface`
(already live in Settings, §3). The screen resolves the MIME (`contentResolver.getType`) and passes
it in — the VM stays Context-free. *Rules out:* the VM decoding bitmaps; a second load of the icons.

**AD8 — Customize XMB Icons gets user-category slots appended to Category Bar.**
`CustomIconSession` carries `userCategorySlots: List<UserCategoryIconSlot>` (key, display name =
category name, fallback `iconKey`), snapshotted from the bar's categories when the editor opens.
A `CustomIconSession.slots()` function returns `CustomizableIcons.group(group)` plus those slots
for `CATEGORY_BAR`; `focusedSlot`, `onCustomIconSlotMove` and the overlay strip all read it. A user
slot with no image previews through `CategoryIconGlyph(iconKey, categoryId)` — what the bar draws.
Pick / Reset call the existing `onIconPicked` / `onResetSlot` unchanged. **Reset All keeps its
current meaning (every user pick) and therefore also clears category images** — see §12 Q1.
*Rules out:* new `IconSlot.Group`, changes to `IconSlot`/theme-kit, a second picker.

## 7. Test Plan (written first, per task)

Commands: library modules use `testDebugUnitTest`; `:core:theme-kit` is pure JVM and uses `test`.

### P0 — must exist before the feature can be called correct

| ID | Test file | Asserts | Task |
| --- | --- | --- | --- |
| K1 | `core/core-ui/src/test/.../icons/UserCategoryIconKeysTest.kt` (new) | `keyFor("custom_retro_shelf_9") == "usercat_custom_retro_shelf_9"`; `keyFor` is null for built-in ids (`games`, `settings`, `app_store`), legacy `music_apps`, `""`, upper case, `custom_../x`, `custom_a/b`, `custom_a.b`, ids over the cap; `isValidKey` true for every `keyFor` output and `DRAFT_KEY`, false for `usercat_`, `usercat_games`, `usercat_custom_../x`, `usercat_custom_x.png`, `catbar_games`; `categoryIdFor(keyFor(id)) == id`; `categoryIdFor(DRAFT_KEY) == null` | 1.1 |
| S1 | `core/core-data/src/test/.../repository/CustomIconStoreTest.kt` (extend) | importing to `usercat_custom_x_1` writes `usercat_custom_x_1.png`, bumps the stamp, evicts the dest path; `load()` returns it as `CustomIcon.Still` | 1.1 |
| S2 | same | extend `invalid slot keys write nothing` with `usercat_games`, `usercat_custom_../x`, `usercat_`, `usercat_custom_A` — nothing written, no stamp | 1.1 |
| S3 | same | a rejected re-import (`video/mp4`) to a usercat key that already holds a PNG returns `ok=false`, message `CustomIconLimits.MSG_UNSUPPORTED_FORMAT`, and the old file is untouched | 1.1 |
| S4 | same | `move(DRAFT_KEY, "usercat_custom_x_1")`: dest exists, draft gone, dest evicted, stamp bumped, returns true; with no draft returns false and writes nothing; an invalid dest key returns false and keeps the draft | 1.2 |
| S5 | same | `pruneUserCategoryIcons(setOf("custom_keep_1"))` deletes `usercat_custom_gone_2.*` and the draft, keeps `usercat_custom_keep_1.*`, never touches `catbar_*` / `sysicon_*` files, returns true and bumps the stamp; a second call returns false and does not bump | 1.2 |
| R1 | `core/core-data/src/test/.../repository/CategoryRepositoryDeleteTest.kt` (extend) | deleting `custom_ff_5` calls `customIconStore.clear("usercat_custom_ff_5")` after `deleteById`; deleting a protected built-in never calls `clear` | 1.3 |
| G1 | `core/core-ui/src/test/.../icons/CategoryIconResolutionTest.kt` (new) | user-category image wins over a `catbar_favorites` user pick and a theme icon for the same `iconKey`; with no category image the existing chain applies (catbar user pick › theme › null); `categoryId = null` gives exactly today's result; a usercat key present only in the **theme** map is ignored; console-art keys still return null (ConsoleIcon path) | 2.1 |
| V1 | `feature/feature-settings/src/test/.../viewmodel/CategoryManagerDeviceIconTest.kt` (new) | Change Icon: `onDeviceImagePicked(uri, mime)` calls `store.import("usercat_<id>", uri, mime)`; success → step DETAIL, message null; rejection → step stays PICK_ICON, `message` = the store's message, `setIcon` never called | 3.1 |
| V2 | same | Change Icon: `chooseIcon("ic_favorites")` calls `setIcon` and `store.clear("usercat_<id>")` | 3.1 |
| V3 | same | Create: image pick imports to `DRAFT_KEY` and advances to PICK_TYPE; `chooseType` creates with `ic_games` when no built-in was chosen (or the chosen key), then `store.move(DRAFT_KEY, "usercat_<newId>")` | 3.1 |
| V4 | same | Create cancelled (`onBack` from PICK_ICON to LIST) calls `store.clear(DRAFT_KEY)` | 3.1 |

### P1 — guards the decisions

| ID | Test file | Asserts | Task |
| --- | --- | --- | --- |
| K2 | `core/theme-kit/src/test/.../CustomizableIconsTest.kt` (extend) | no `usercat_` key is a theme slot: `CustomizableIcons.ALL.none { it.key.startsWith("usercat_") }`, `IconSlots.isValidKey("usercat_custom_x_1")` and `CustomizableIcons.isValidKey(...)` false (theme-kit can't import core-ui, so assert on the literal prefix) | 1.1 |
| T1 | `core/core-data/src/test/.../repository/PfpThemeStoreV3Test.kt` (extend) | with `custom-icons/usercat_custom_x_1.png` present, `saveCurrentLook` produces a bundle whose `icons` / `sysicons` contain no `usercat_` key | 1.1 |
| S6 | `CustomIconStoreTest.kt` | `observeStoredKeys()` emits the valid key set; emits again after an import and after a clear | 1.2 |
| R2 | `CategoryRepositoryDeleteTest.kt` (or new `CategoryRepositoryIconSweepTest.kt`) | `pruneOrphanCategoryIcons()` passes every stored category id to `store.pruneUserCategoryIcons` | 1.3 |
| V5 | `CategoryManagerDeviceIconTest.kt` | rows: `hasImage` true iff `usercat_<id>` is in the observed set; `iconLabel` is "Your Image" when it is, else the catalog label (`ic_favorites` → "Favorites", legacy `ic_ps1` → "PlayStation"); `deviceImageKey` null for a protected category | 3.1 |
| V6 | same | Change Icon `removeDeviceImage()` calls `store.clear("usercat_<id>")` and returns to DETAIL; the device-image actions are no-ops for a protected category | 3.1 |
| X1 | `feature/feature-xmb/src/test/.../viewmodel/CustomIconSessionSlotsTest.kt` (new) | `CATEGORY_BAR` slots = the built-in catbar slots in their order, then one per user category in bar order; built-ins and non-matching ids are not added; other groups unchanged; `focusedSlot` at index `builtIns.size` is the first user slot | 4.1 |

### P2 — edge cases

| ID | Test file | Asserts | Task |
| --- | --- | --- | --- |
| V7 | `CategoryManagerDeviceIconTest.kt` | Create: picking a built-in after an image clears the draft | 3.1 |
| V8 | same | a failed `move` after create leaves the category created and sets `message` | 3.1 |
| X2 | `CustomIconSessionSlotsTest.kt` | a user slot's display name is the category name; its `iconKey` is the category's | 4.1 |

Existing tests that must stay green after constructor changes:
`CategoryRepositoryDeleteTest`, `CategoryRepositoryReorderTest` (Task 1.3),
`CategoryManagerDeleteTest`, `CategoryManagerBackNavigationTest` (Task 3.1), `CustomIconLimitsTest`,
`DefaultSlotGlyphTest`, `PfpThemeStoreV3Test`.

## 8. Rejected Alternatives

- **Use the existing `iconKey = "custom"` + `customIconUri`.** Never implemented (§3); would lose the
  built-in fallback, split one choice across Room and the file system (non-atomic), store
  absolute paths that need rewriting on restore (`BackupManager` rewrites only game artwork
  paths), and need its own decode/motion path.
- **Register user categories as `IconSlot`s in `CustomizableIcons`.** Theme-kit is the theme bundle
  contract and is shared with Theme Studio; a device-local, dynamic set does not belong there and
  would leak into `saveCurrentLook` and theme import gating.
- **A separate `CategoryIconStore` / directory.** Duplicates every solved problem in
  `CustomIconStore` and needs its own backup root and stamp.
- **Key by an opaque hash of the id.** Safe, but unreadable on disk and harder to sweep; the strict
  pattern is equally safe because the id alphabet is already `[a-z0-9_]`.
- **Sweep on every stamp change / on screen open.** Races the restore (files land before categories).

## 9. Data / Persistence / Compatibility

- No Room migration. No new preference keys. `custom_icons_stamp` keeps its contract.
- Backups: carried by the existing `custom-icons` bundle root — **no code change**. A restore brings
  the images and the categories (same ids) together. An older archive without images restores
  categories that simply fall back to their `iconKey`.
- Restoring onto a device whose categories differ: unmatched images are removed by the next
  startup sweep.
- Theme apply never touches `custom-icons/`; theme export never reads `usercat_` keys.
- Downgrade: an older build's `CustomIconStore.load` skips unknown keys, so `usercat_*` files are
  ignored, not crashed on.

## 10. Implementation Phases

- **Phase 1 — Storage.** Key family, store gate, store lifecycle ops, deletion and sweep.
- **Phase 2 — Rendering.** Category-aware glyph on the crossbar.
- **Phase 3 — Category Manager.** VM behaviour, then the screen (A, B, C, F of the mockup).
- **Phase 4 — Customize XMB Icons.** User categories in Category Bar (screen E).

Each task leaves the repository building with tests green. Phases 2 and 4 are independent of
Phase 3.

## 11. Execution Tasks

### Task 1.1 — Key family and store gate

**Depends on:** None

**Objective:** Introduce `UserCategoryIconKeys` and let `CustomIconStore` import, clear and load
`usercat_*` keys, with theme export provably excluding them.

**Scope:** New `core/core-ui/.../icons/UserCategoryIconKeys.kt` (`PREFIX`, `DRAFT_KEY`, `keyFor`,
`isValidKey`, `categoryIdFor`, KDoc explaining why the pattern is load-bearing). Extend the gate in
`CustomIconStore.import` / `clear` / `load` to `CustomizableIcons.isValidKey(k) ||
UserCategoryIconKeys.isValidKey(k)` via one private `isStorableKey`. Update the class KDoc to name
the second key family and that it is device-local (never themed). Tests K1, S1, S2, S3, K2, T1.

**Do Not Change:** `CustomizableIcons`, `IconSlots`, `PfpThemeStore` (T1 only proves exclusion),
`CustomIconLimits`, `clearAll`, the import pipeline itself, the stamp.

**Expected Files:** Add `UserCategoryIconKeys.kt`, `UserCategoryIconKeysTest.kt`. Modify
`CustomIconStore.kt`, `CustomIconStoreTest.kt`, `CustomizableIconsTest.kt`, `PfpThemeStoreV3Test.kt`.

**Acceptance Criteria:**
- [ ] K1, S1, S2, S3, K2, T1 written first and failing, then green.
- [ ] All existing `CustomIconStoreTest` / `PfpThemeStoreV3Test` cases still green.
- [ ] No new compiler warnings in core-ui, core-data, theme-kit.

**Change Budget:** 2 production files (1 new); 4 test files. Test-file count exceeds the default
because each guard lives in its owning module's existing test.

**Tests:**
```bash
./gradlew :core:core-ui:testDebugUnitTest --tests "*UserCategoryIconKeysTest*"
./gradlew :core:core-data:testDebugUnitTest --tests "*CustomIconStoreTest*" --tests "*PfpThemeStoreV3Test*"
./gradlew :core:theme-kit:test --tests "*CustomizableIconsTest*"
```

**Stop Condition:** Stop when the criteria hold. Do not add move / prune / observe (1.2).

**If Blocked:** STOP and report what you attempted, what blocked you, which file caused it, and the
decision needed. Do not invent architecture.

---

### Task 1.2 — Store lifecycle operations

**Depends on:** 1.1

**Objective:** Add the three store operations the feature needs: `move`, `observeStoredKeys`,
`pruneUserCategoryIcons`.

**Scope:** In `CustomIconStore`:
- `suspend fun move(fromKey: String, toKey: String): Boolean` — both keys storable; removes any
  `toKey` file under another extension, renames (copy fallback inside `runCatching`), evicts the
  dest path, bumps the stamp; false and no write when there is no source or a key is invalid.
- `fun observeStoredKeys(): Flow<Set<String>>` — `pfpDataStore.data` → stamp →
  `distinctUntilChanged` → IO directory listing of storable keys with known extensions.
- `suspend fun pruneUserCategoryIcons(liveCategoryIds: Set<String>): Boolean` — deletes every
  `usercat_*` file whose `categoryIdFor` is null (draft or malformed) or not in the set; bumps the
  stamp only when something was removed (mirror `UiMediaStore.pruneOrphans`' comment style).
Tests S4, S5, S6.

**Do Not Change:** `import` / `clear` / `load` / `clearAll` behaviour, `CustomIconLimits`, any caller.

**Expected Files:** Modify `CustomIconStore.kt`, `CustomIconStoreTest.kt`.

**Acceptance Criteria:**
- [ ] S4, S5, S6 failing first, then green; existing store tests green.
- [ ] `prune` never deletes a `CustomizableIcons` key.
- [ ] No new compiler warnings in core-data.

**Change Budget:** 1 production file, 1 test file.

**Tests:**
```bash
./gradlew :core:core-data:testDebugUnitTest --tests "*CustomIconStoreTest*"
```

**Stop Condition:** Stop when the criteria hold. Do not wire callers.

**If Blocked:** STOP and report (as above).

---

### Task 1.3 — Delete clears the image; startup orphan sweep

**Depends on:** 1.2

**Objective:** Deleting a category removes its image, and every launch removes orphaned category
images and drafts.

**Scope:** Inject `CustomIconStore` into `CategoryRepositoryImpl`. In `delete()`, after
`deleteById` and only for non-protected ids, `keyFor(id)?.let { store.clear(it) }`. Add
`suspend fun pruneOrphanCategoryIcons()` (all ids from `categoryDao.getAll()` →
`store.pruneUserCategoryIcons`). Call it from `DatabaseInitializer.initialize()` after
`reconcileBuiltInCategories()`, wrapped so a failure is logged, never fatal; comment why startup is
the only safe moment (restore order, §3). Update the two repository tests' constructors. Tests R1, R2.

**Do Not Change:** Collection re-homing, list-state deletion, `PROTECTED_BUILTINS`,
`createCustomCategory`'s id scheme, `BackupManager`, `MainActivity`.

**Expected Files:** Modify `CategoryRepositoryImpl.kt`, `DatabaseInitializer.kt`,
`CategoryRepositoryDeleteTest.kt`, `CategoryRepositoryReorderTest.kt` (constructor only).

**Acceptance Criteria:**
- [ ] R1, R2 failing first, then green; all existing `CategoryRepository*Test` green.
- [ ] Protected categories never trigger `clear`.
- [ ] No new compiler warnings in core-data.

**Change Budget:** 2 production files, 2 test files.

**Tests:**
```bash
./gradlew :core:core-data:testDebugUnitTest --tests "*CategoryRepository*"
```

**Stop Condition:** Stop when the criteria hold.

**If Blocked:** STOP and report (as above). In particular, if Hilt reports a cycle injecting
`CustomIconStore` into `CategoryRepositoryImpl`, stop — do not move the call into the view model.

---

### Task 2.1 — Category-aware glyph on the crossbar

**Depends on:** 1.1

**Objective:** A user category's image draws on the crossbar through the existing custom-icon
path, with the built-in glyph as fallback.

**Scope:** Add the pure `resolveCategoryCustomIcon(...)` (AD5) beside `CategoryIconGlyph` and make
`CategoryIconGlyph` use it, with a new trailing-default `categoryId: String? = null` parameter;
keep the console-art branch exactly as is when no category image exists. `XMBCategoryBar` passes
`categoryId = category.id`. Update `CategoryIconGlyph`'s KDoc for the new first tier. A comment-only
correction of `Category.iconKey`'s "or \"custom\"" comment is allowed. Test G1.

**Do Not Change:** `CustomIconSurface`, `ConsoleIcon`, `ImageMotion`, the bar's sizes / alpha /
motion locals, `catbarSlotKeyFor`, `CategoryManagerScreen`.

**Expected Files:** Modify `CategoryIconGlyph.kt`, `XMBCategoryBar.kt`; add
`CategoryIconResolutionTest.kt`.

**Acceptance Criteria:**
- [ ] G1 failing first, then green.
- [ ] With no image, every existing call renders identically (resolver with `categoryId = null`
      returns today's value).
- [ ] No new compiler warnings in core-ui, feature-xmb.

**Change Budget:** 2 production files, 1 test file (+1 comment-only line allowed).

**Tests:**
```bash
./gradlew :core:core-ui:testDebugUnitTest --tests "*CategoryIconResolutionTest*"
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*XmbCategoryBar*" --tests "*DefaultSlotGlyphTest*"
```

**Device check:** none for this task — the crossbar is verified in Task 3.2's device pass, once an
image can be picked.

**Stop Condition:** Stop when the criteria hold.

**If Blocked:** STOP and report (as above).

---

### Task 3.1 — Category Manager view-model behaviour

**Depends on:** 1.2, 1.3

**Objective:** The VM supports choose / replace / remove of a device image in both flows, and rows
expose `hasImage`, `iconLabel`, `deviceImageKey`.

**Scope:** Inject `CustomIconStore`; combine `observeStoredKeys()` into `uiState`. New
`CategoryRow` fields; pure `iconValueLabel(iconKey, hasImage)` using `categoryIconFor(...).label`.
New state: `pendingHasImage`, and use the existing `message` (cleared on the next pick, on
`chooseIcon`, and when leaving PICK_ICON). New actions `onDeviceImagePicked(uri, mime)`,
`removeDeviceImage()`. `chooseIcon` clears the image (change flow) or the draft (create flow).
`chooseType` creates with `pendingIconKey ?: FALLBACK_CATEGORY_ICON.key`, then
`move(DRAFT_KEY, keyFor(newId))` when `pendingHasImage`. `onBack` leaving the create flow clears the
draft. All device-image actions are no-ops when `deviceImageKey` is null. Update the two existing
VM tests' constructors. Tests V1–V8.

UX decisions fixed here: a successful pick in **Change Icon** returns to DETAIL (like `chooseIcon`);
in **create** it advances to PICK_TYPE. **Remove Image** in Change Icon returns to DETAIL; in create
it stays on PICK_ICON.

**Do Not Change:** Rename / visibility / delete / move-on-bar behaviour, `ICON_OPTIONS`,
`CategoryRepositoryImpl`'s API, the step enum's existing values.

**Expected Files:** Modify `CategoryManagerViewModel.kt`, `CategoryManagerDeleteTest.kt`,
`CategoryManagerBackNavigationTest.kt` (constructor only); add `CategoryManagerDeviceIconTest.kt`.

**Acceptance Criteria:**
- [ ] V1–V8 failing first, then green; existing CategoryManager tests green.
- [ ] No Android `Context` in the VM.
- [ ] No new compiler warnings in feature-settings.

**Change Budget:** 1 production file, 3 test files.

**Tests:**
```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*CategoryManager*"
```

**Stop Condition:** Stop when the criteria hold. No UI work.

**If Blocked:** STOP and report (as above).

---

### Task 3.2 — Category Manager screen

**Depends on:** 3.1, 2.1

**Objective:** Build screens A, B, C and F of the approved mockup.

**Scope:** In `PickIconContent`, when the target row (create, or `detail.deviceImageKey != null`)
allows it: a "From Your Device" group above the catalog — Choose Image… / Your Image (preview via
`LocalCustomIcons[key]` → `CustomIconSurface`, `LocalIconFocused` = row focused) / Replace Image… /
Remove Image (sublabel names the fallback label) — an `OpenDocument` launcher with `image/*` whose
result resolves the MIME through `LocalContext.current.contentResolver.getType(uri)` and calls
`onDeviceImagePicked`; `state.message` rendered under the group. The built-in group header reads
"Built-in · <category name>". In `CategoryDetailContent`, Change Icon shows `iconLabel` with a
thumbnail (image or `CategoryIconGlyph`), using `SettingsRow`'s `trailing` since
`SettingsValueRow` has no slot. The list sublabel uses `iconLabel` instead of the raw key (see §12 Q3).

**Do Not Change:** `SettingsScaffold` / `SettingsRow` APIs, modals, the type step, focus-restore
keys, any other settings screen.

**Expected Files:** Modify `CategoryManagerScreen.kt` (only).

**Acceptance Criteria:**
- [ ] `:feature:feature-settings` compiles with no new warnings; Task 3.1 tests still green.
- [ ] Device check (user drives): create a category with a PNG; with a multi-frame GIF (plays only
      while focused under Reduced, never under Static); Replace; Remove → built-in returns; pick a
      built-in after an image → image gone; reject a >10 s or >512 px GIF → message shown, old
      icon kept; Change Icon value reads "Your Image" / "Favorites".

**Change Budget:** 1 production file. No new tests (logic is covered by 3.1); state why in the
handback.

**Tests:**
```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*CategoryManager*"
./gradlew :app:installFullDebug
```

**Stop Condition:** Stop when the criteria hold; then stop for the user's device check.

**If Blocked:** STOP and report (as above). Anything visual not in the mockup needs the user's yes.

---

### Task 4.1 — User categories in Customize XMB Icons

**Depends on:** 1.1, 2.1

**Objective:** Category Bar in Customize XMB Icons lists the user's bar categories after the
built-ins; Pick / Reset on them write / clear `usercat_<id>`.

**Scope:** New `UserCategoryIconSlot` and pure `userCategoryIconSlots(categories)` +
`CustomIconSession.slots()` (in a small new file in `feature-xmb/.../viewmodel/` or beside
`CustomIconSession`). `openCustomIcons` snapshots `uiState.categories`; `focusedSlot` and
`onCustomIconSlotMove` use `slots()`. `CustomIconsOverlay` builds its strip from `session.slots()`;
`SlotPreview` falls back to `CategoryIconGlyph(iconKey, categoryId)` for a user slot; the status
line reads "Your pick" / "Default" as today. Tests X1, X2.

**Do Not Change:** `onIconPicked`, `onResetSlot`, `onResetAll` (semantics per AD8 / §12 Q1),
Save as Theme, `IconSlot`, `CustomizableIcons`, the other three groups, the overlay's controls.

**Expected Files:** Modify `XMBViewModel.kt` (session + three call sites), `CustomIconsOverlay.kt`;
add `CustomIconSessionSlotsTest.kt` (and the small slots file if not placed in `XMBViewModel.kt`).

**Acceptance Criteria:**
- [ ] X1, X2 failing first, then green.
- [ ] Built-in Category Bar order and the other groups are unchanged.
- [ ] No new compiler warnings in feature-xmb.
- [ ] Device check (user): Pick on a user category in the overlay changes the bar and the Category
      Manager preview; Reset restores the built-in; Save as Theme bundle has no category image.

**Change Budget:** 2–3 production files, 1 test file.

**Tests:**
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*CustomIconSessionSlotsTest*"
```

**Stop Condition:** Stop when the criteria hold; then stop for the user's device check.

**If Blocked:** STOP and report (as above).

## 12. Risks and Open Questions

- **Q1 — Reset All.** `clearAll()` deletes the whole directory, so Reset All in Customize XMB Icons
  will also remove category images. The plan keeps that (they are user picks shown in the same
  editor). If the user wants Reset All to spare them, Task 4.1 grows a `clearAll` filter.
- **Q2 — Create with only an image.** The category's fallback `iconKey` becomes `ic_games` (the
  catalog's own fallback). Alternative: fall back by type (Games vs App Store) after PICK_TYPE.
- **Q3 — List sublabel.** The plan also replaces `Icon: ic_games` on the category list with the
  readable label (same function as decision 5). Drop it from 3.2 if unwanted.
- **Q4 — Hidden user categories** are not in the overlay (it snapshots the bar). They still get
  images through Category Manager.
- **R1 — Built-in Change Icon is already inert on the bar** (`canonicalXmbCategories` ignores the
  stored `iconKey`). Out of scope; the new group is never offered for built-ins.
- **R2 — HEIC.** `CustomIconStore` maps only `image/heif`; `.heic` files usually report
  `image/heic`, so they are rejected as unsupported although the message lists HEIC. Pre-existing;
  affects built-in custom icons too. A one-line mapping fix is available if the user approves.
- **R3 — Oversized files via the stream path** (provider gives no size) report "Couldn't read that
  image" rather than "File is too large". Pre-existing; unchanged.
- **R4 — Id reuse** (`custom_<slug>_<maxPos+1>`) is safe only because delete clears the image
  (Task 1.3) and the sweep backs it up.
- **R5 — Id length.** Categories whose generated id exceeds the cap (very long names) are not
  offered "From Your Device". Fail-closed by design.

## 13. Execution Task Index

| ID | Task | Depends On | Status |
| --- | --- | --- | --- |
| 1.1 | Key family (`UserCategoryIconKeys`) and store gate; theme-export exclusion guards | None | DONE |
| 1.2 | Store lifecycle ops: `move`, `observeStoredKeys`, `pruneUserCategoryIcons` | 1.1 | DONE |
| 1.3 | Delete clears the category image; startup orphan sweep | 1.2 | DONE |
| 2.1 | Category-aware glyph resolution on the crossbar | 1.1 | DONE |
| 3.1 | Category Manager VM: device image pick / replace / remove, readable icon value | 1.2, 1.3 | DONE |
| 3.2 | Category Manager screen (mockup A, B, C, F) + device check | 3.1, 2.1 | DONE |
| 4.1 | User categories in Customize XMB Icons ▸ Category Bar + device check | 1.1, 2.1 | DONE |

## Resolved Decisions (2026-10-01, approved direction)
- Q1: keep — Reset All in Customize XMB Icons also clears category images.
- Q2: keep — image-only categories fall back to `ic_games`.
- Q3: yes — the list-row sublabel also shows the readable icon name.
- Q4: keep — hidden categories are not listed in the overlay.
- Picker filter: use the same six-MIME list as the Customize XMB Icons overlay (not `image/*`).
- `image/heic` accepted alongside `image/heif` (done in Task 1.1).
- Device-check steps in tasks are deferred to the user's end-of-run device pass; they do not block DONE.
