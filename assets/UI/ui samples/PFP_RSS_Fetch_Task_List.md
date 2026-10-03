# RSS Fetch: Itemized Task List for Implementation

**What this is:** a step-by-step build list for the RSS Fetch (Channels) feature, written so that a helper (a developer or another Claude session) can work through it without this conversation.
**Read first:**
- `assets/UI/ui samples/PFP_Channels_Implementation_Plan.md`: the decisions and their reasons.
- `PFP_RSS_Channels_Storefront_Design.md`: the original spec, for the feed, security and download details.
- The mockup: https://claude.ai/artifact/XAeFZHf1gGHHF46QubWmKq (11 artboards; 1 dp = 2.305 px on the 1920×1080 test devices).

**Baseline:** `final-polish` @ `f51f1226`, Room **v55**. Branch from `main` once `final-polish` has merged, for example `rss-fetch`.

---

## Ground rules (apply to every task)

1. **Tests first.** In every task, write the listed test file(s) against the intended API before writing production code, then implement until they pass.
2. **Never run Gradle yourself.** The user runs every build, test and install. At the end of each task, give the exact commands, one per `bash` code block.
   - **Library modules** (`feature-*`, `core-*`) have no flavors: `:<module>:testDebugUnitTest`.
   - **The app has flavors:** `:app:assembleLiteDebug`, `:app:installLiteDebug`, `:app:installFullDebug`.
3. **Controller input goes only through the nav core:** `GamepadAction` → `XMBViewModel.dispatchGamepadAction` → `pendingChannelsAction` → `ChannelsViewModel.onAction`. No `onKeyEvent` and no raw keycodes in Channels.
4. **Sounds come only from existing `MenuSound` values** (`SCROLL, SYSTEM_BROWSE, SELECT, CONFIRM, BACK, NOTIFICATION, ERROR`). A press that changes nothing plays nothing.
5. **Options menus use `PspContextMenuOverlay` + `PspMenuRow` + `PspMenuNav`.** No custom menus outside the App Drawer.
6. **Modals use `PfpModalHost` / `PfpModalSpec`.** Text entry is `PfpModalSpec.TextEntry`, which brings the controller virtual keyboard with it.
7. **Results go to the tray** via `BackgroundTaskCenter`. Inline is only for live progress and input validation.
8. **Touch mode:** when `showTouchControls` is on, show `XmbHeaderPill` (Back, Search), the kebab for Options and `TouchPromptBar` in the footer. Never show controller prompts and touch prompts together.
9. **Colours come only from `deriveStorefrontColors()`** (plus the new fields from T1.1). No hard-coded hex values in composables.
10. **User-facing names:** the screen is **RSS Fetch**, each feed is a **Channel**, a finished download shows **✓ FETCHED**, and buttons say **Download**. Code keeps `channels` / `Channel*` names.
11. **Never auto-download, auto-scan, auto-launch or delete game files.**
12. Match the surrounding code style: header comment blocks, KDoc density, and `internal` pure logic files paired with tests.

Each task ends in a compilable state. Tick the boxes as you go.

---

## Phase 0: Scaffold and entry point

### T0.1 Create the `feature-channels` module
- [ ] Create `feature/feature-channels/build.gradle.kts` by copying `feature/feature-appbar/build.gradle.kts`.
  - Set `namespace = "com.playfieldportal.feature.channels"`.
  - Add `implementation(project(":core:core-navigation"))`.
  - Drop `accompanist.drawablepainter`.
- [ ] Add `include(":feature:feature-channels")` to `settings.gradle.kts`, alongside the other features (lines 43–70).
- [ ] Add `implementation(project(":feature:feature-channels"))` to `feature/feature-xmb/build.gradle.kts`, next to `feature-appbar`. The shell hosts feature screens this way.
- [ ] Add the same line to `app/build.gradle.kts`, for both flavors.
- [ ] Create the package `com.playfieldportal.feature.channels` with an empty `di/ChannelsModule.kt` (`@Module @InstallIn(SingletonComponent::class)`).

**Done when** `:feature:feature-channels:assembleDebug` and `:app:assembleLiteDebug` build.

### T0.2 Restore the storefront file as reference
- [ ] Restore `feature/feature-appbar/src/main/kotlin/com/playfieldportal/feature/appbar/storefront/StorefrontAppDrawer.kt` from `f51f1226^` into `feature/feature-channels/src/main/kotlin/com/playfieldportal/feature/channels/ui/StorefrontReference.kt`.
- [ ] Change its package to `...feature.channels.ui` and mark it `@file:Suppress("unused")`.
- [ ] Delete the parts that reference the App Drawer: `StorefrontAppDrawerScreen`, the `AppDrawerContent` callbacks, `AppMiniMenu`, `UninstallConfirmDialog`, and the `InstalledApp` / `AppFilter` uses. Keep the visual composables as copy sources: `StorefrontHeader`, `CategoryRail`, `ContentHeader`, `AppGrid`, `AppGridItem`, `ControllerCommandBar` and `EmptyDrawerMessage`.
- [ ] Update the stale pointer comment at `feature/feature-appbar/.../AppDrawerScreen.kt:77` to point to the new location.

**Done when** the module still compiles. T1.x splits this file into the real composables and then deletes it (T1.9).

### T0.3 Hidden-placement key for feature rows
- [ ] **Test first:** add a `HiddenPlacementTest` case asserting that `HiddenPlacement.featureKey("channels") == "feature:channels"`.
- [ ] Add `fun featureKey(id: String) = "feature:$id"` to the companion in `core/core-domain/.../model/HiddenPlacement.kt`.

### T0.4 RSS Fetch row in the App Store column
- [ ] **Tests first** in `feature/feature-xmb/src/test/.../viewmodel/` (extend `XmbListsTest`, or add `RssFetchRowTest`):
  - The App Store root list starts with the RSS Fetch row, then custom cards, then apps, then Add Apps.
  - Other app categories (Video, Network, …) have no RSS Fetch row.
  - With a `HiddenPlacement(featureKey("channels"), CATEGORY, "app_store")`, the row is absent.
  - The row's id is `RSS_FETCH_ITEM_ID`, its title is "RSS Fetch", and its subtitle is "Games from your Channels".
- [ ] In `XMBViewModel.kt`, in the non-gaming `else` branch (~line 3016), prepend `rssFetchItem()` when `category.id == "app_store"` and the row is not hidden there (use the existing `isHiddenAt`).
  - Add `RSS_FETCH_ITEM_ID` next to `ADD_APPS_ITEM_ID`.
  - Add a `rssFetchItem()` factory next to `addAppsItem()`.
  - Use a new icon key `ic_rss_fetch`. **Ask the user for the icon art before drawing one.** Until it arrives, reuse `ic_network`.
- [ ] The row's context menu (Y) gets **Hide**, which writes the placement above with the labels "RSS Fetch" / "App Store". Follow how the app row Hide is built in `GameContextMenuItems.kt` / `XmbConfirm.kt`.
- [ ] Settings ▸ Hidden Items (`feature-settings/.../AppVisibilitySettingsScreen.kt`) must list and restore the hidden row. Check whether it renders `feature:` keys. If it only understands `app:` and `game:`, add a branch and a test for it.

**Done when** the row shows, hides and is restored on device.

### T0.5 Shell wiring and an empty screen
- [ ] **Tests first**, in the XMBViewModel tests:
  - Selecting `RSS_FETCH_ITEM_ID` sets `activeChannels`.
  - While `activeChannels != null`, every `GamepadAction` except `HOME` lands in `pendingChannelsAction`.
  - The virtual keyboard still captures first.
  - `closeChannels()` clears both fields.
- [ ] Add to `XMBUiState`: `activeChannels: ChannelsLaunch? = null`, `pendingChannelsAction: GamepadAction? = null`.
  - `ChannelsLaunch` is a data class in feature-channels holding an optional `channelId` (so a tray notification can deep-link).
- [ ] Add a branch to the `dispatchGamepadAction` ladder right after the `gamePickerCategoryId` branch (~line 6198). Copy its shape, including `consumeChannelsAction()` / `closeChannels()`.
- [ ] Include `activeChannels` in `hasBlockingOverlay` (~line 1184).
- [ ] In `onItemSelected` (~line 9880), map `RSS_FETCH_ITEM_ID` to `openChannels()`.
- [ ] In `XMBShell.kt`, host `ChannelsScreen(pendingGamepadAction, onGamepadActionConsumed, onClose, showTouchControls, launch)` the same way the Game Picker is hosted (~line 1417).
- [ ] `ChannelsScreen` for now: storefront background, the header with breadcrumb `App Store › RSS Fetch`, and a footer prompt **B Back**. BACK closes it and plays `MenuSound.BACK`.

**Done when** RSS Fetch opens from App Store and Back returns to the XMB with the Back cue.

**Commands for the user:**
```bash
./gradlew :core:core-domain:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest
```
```bash
./gradlew :app:installLiteDebug
```

---

## Phase 1: Storefront and navigation (fake data)

### T1.1 Badge colours in `StorefrontColors`
- [ ] **Tests first** in `core/core-ui/src/test/.../theme/StorefrontColorsTest.kt`. For each of the 12 `XmbColorScheme` presets, resolve the palette and assert:
  - contrast(`needsCardText`, `badgeFill` composited over `tileNormal`) ≥ 4.0
  - contrast(`tileSelectedEdge`, `badgeFill`) ≥ 4.0
- [ ] Add these fields to `StorefrontColors`, derived in `storefrontColorsFor` from `lightChrome`:

| Field | Dark chrome | Light chrome |
| --- | --- | --- |
| `badgeFill` | `Black @ 0.60` | `White @ 0.90` |
| `badgeOnFill` | White | `#111111` |
| `needsCardText` | `#FFC46B` | `#8A5300` |
| `needsCardEdge` | `#FFC46B` | `#C98A1C` |
| `needsCardDot` | `#FFC46B` | `#FFC46B` |
| `needsCardDotRing` | `Black @ 0.60` | `Black @ 0.60` |

- [ ] Add the new fields to `DefaultStorefrontColors` too.

### T1.2 Fake data and domain types
- [ ] In `core-domain` (`model/channels/`), add: `Channel`, `ChannelCollection`, `ChannelItem`, `ChannelEnclosure`, `ChannelDownload`, `ChannelDownloadState` (QUEUED, PREFLIGHT, DOWNLOADING, PAUSED, VALIDATING, FINALIZING, COMPLETED, FAILED, CANCELED), `ChannelItemStatus` (NONE, NEW, DOWNLOADING(pct), FETCHED, UPDATE, FAILED, NEEDS_CARD) and `ChannelRepository` (`observeChannels`, `observeCollections(channelId)`, `observeItems(collectionId)`, `observeDownloads`). Field lists are in spec §29–30.
- [ ] In feature-channels, add `FakeChannelRepository` (main source set, `internal`, bound in `ChannelsModule` until T2.6). Seed it with the mockup data:
  - **Channels:** Homebrew Hub, Public Domain Shelf, My Server. These are fixture names only and are never shipped as content.
  - **Collections:** ALL ITEMS, NES, SUPER NINTENDO, GAME BOY ADVANCE, PLAYSTATION, UNMAPPED.
  - **Items:** the 10 NES tiles from artboard 2 and the 10 Unmapped tiles from artboard 9.

### T1.3 Navigation reducer (pure logic)
- [ ] **Tests first:** `ChannelsNavLogicTest.kt` covers every row of the table below, plus:
  - per-collection cursor memory
  - a removed item clamps to its nearest survivor
  - an empty collection: RIGHT and SELECT are silent no-ops
  - touch browse followed by the first controller press restores the cursor silently
  - a modal or menu open blocks all background moves
  - search is scoped to the active collection
- [ ] Create `ChannelsNavLogic.kt` (`internal`, same shape as `GamePickerLogic.kt`):
  - `data class ChannelsNavState(tabs, tabIndex, zone: RAIL|GRID|DETAILS, railIndexByTab, itemIdByCollection, detailsAction, usingTouch, searchQuery, searchOpen)`
  - Reducers: `move(action)`, `stepTab(delta)`, `enter()`, `back(): ChannelsNavState?` (null means close the screen), `touchBrowse(index)`, `tapRail(i)`, `tapTile(i)`.
  - Grid moves use `core-navigation`'s `GridMove`, 5 columns.
  - `channelsSound(before, after): MenuSound?` diffs the two states the same way `gamePickerSound` does.

| Zone | Action | Result | Sound |
| --- | --- | --- | --- |
| Rail or Grid | PREV/NEXT_CATEGORY | Change tab, **clamped** (no wrap). Keep the zone, restore the tab's collection and cursor. | SYSTEM_BROWSE; none at the ends |
| Rail | UP / DOWN | Move collection; the grid previews it | SCROLL; none at the ends |
| Rail | RIGHT / SELECT | Enter the grid on the remembered item, else the nearest survivor, else the first | SELECT |
| Rail | BACK | `back()` returns null and the screen closes | BACK |
| Rail | OPEN_CONTEXT_MENU | Collection menu (on the Home tab: Channel menu) | SELECT |
| Rail or Grid | CHANGE_SORT | Open search | SELECT |
| Grid | D-pad | GridMove | SCROLL; none at the edges |
| Grid | LEFT in column 0 | Back to the rail row | BACK |
| Grid | SELECT | Details (never downloads) | SELECT |
| Grid | BACK | Rail | BACK |
| Grid | OPEN_CONTEXT_MENU | Item menu | SELECT |
| Details | LEFT / RIGHT | Move between action buttons | SCROLL |
| Details | BACK | Grid, on the same tile | BACK |
| Details | PREV/NEXT_CATEGORY | Ignored | none |

### T1.4 `ChannelsViewModel`
- [ ] **Tests first:** `ChannelsViewModelTest`, using the fake repository and a recording `MenuSoundPlayer`:
  - `onAction` applies the reducer and plays exactly the cue from `channelsSound`.
  - While a menu or modal is open, presses go to it and not to the reducer.
  - `back()` returning null emits a close event.
- [ ] Inject `ChannelRepository` and `MenuSoundPlayer`. Expose `uiState: StateFlow<ChannelsUiState>`: tabs, rail rows with counts and the needs-card marker, visible items, selection, content header (label, count, "Downloads save to", refresh text), info strip, prompts, menu, modal and touch flag.
- [ ] `ChannelsScreen` collects `pendingGamepadAction` in a `LaunchedEffect`, calls `viewModel.onAction`, then calls consumed. It forwards `HOME` to nothing, because the shell already owns HOME.

### T1.5 Composables (split out of `StorefrontReference.kt`)

All of these take state and callbacks only. Sizes come from the reference file: header 56dp, tabs 36dp, rail 180dp, footer 48dp, tile corner 2dp. See artboards 1, 2 and 9.
- [ ] **`ChannelStorefront`:** the scaffold. Uses the reference file's background gradient and dividers.
- [ ] **`ChannelHeader`:** breadcrumb `App Store › RSS Fetch › <Channel> › <Collection>[ › <Item>]`, plus Search.
  - Tapping a breadcrumb segment goes up the same way BACK does.
  - In touch mode: an `XmbHeaderPill` Back on the left, plus Search and kebab pills.
- [ ] **`ChannelTabRow`:** 36dp. Active tab is bold with a 2dp underline in `chromeDivider`, inactive tabs use `textSecondary` at 0.8. LB/RB glyphs sit at the ends in controller mode only. Tapping a tab selects it.
- [ ] **`ChannelCollectionRail`:** `CategoryRail` with:
  - a section label (COLLECTIONS / CHANNELS / DOWNLOADS)
  - the cursor border and glow only while the zone is RAIL
  - the needs-card dot (`needsCardDot` with a 3px `needsCardDotRing`)
- [ ] **`ChannelContentHeader`:**
  - Line 1: label and "N ITEMS · M NEW" (Unmapped shows "· M NEED A CARD").
  - Line 2: "Downloads save to: <card | Ask each time>" and "Refreshed …", with an inline spinner while refreshing.
- [ ] **`ChannelItemGrid`:** `LazyVerticalGrid`, 5 fixed columns.
  - **Centred scroll** on the selection; do not reuse the reference's `animateScrollToItem` top-pinning.
  - Tiles are dimmed slightly while the zone is RAIL.
  - Touch-browse reporting is kept from the reference.
- [ ] **`ChannelItemTile`:**
  - **Art:** ICON0 aspect (126:70), Coil `AsyncImage`. The fallback is a `.EXT` format tile.
  - **Text:** title on 1 line, then "Platform · size".
  - **Badges:** NEW (fill `chromeDivider`), UPDATE (outline), FAILED (`destructive`), ✓ FETCHED, a % plus progress bar, and CHOOSE CARD (`needsCardText` / `needsCardEdge` on `badgeFill`).
  - **Selected tile:** the reference's edge plus inner border.
  - **Touch:** tap opens Details, long-press opens the menu.
- [ ] **`ChannelInfoStrip`:** 40dp. Shows item or collection details, matching artboards 1, 2 and 9.
- [ ] **`ChannelPromptBar`:** the prompt list comes from state. See the footers in artboards 1, 2, 5 and 7, and use `TouchPromptBar` in touch mode.
- [ ] **`EmptyCollectionMessage`:** "No items in this collection", "No Channels yet: Add Channel", or "Couldn't refresh: showing saved items".

### T1.6 Context menus
- [ ] **Tests first:** a menu-builder test for each menu, checking rows, headers, `value`, `opensMenu` and cues.
- [ ] Build `PspMenuRow` lists for these menus:
  - **Item** (artboard 3): View Details, Download, Save To `value=<card>` ›, View Source, then an "Item" header with Mark as Read/Unread and Hide Item, then (when relevant) Retry Download and Remove Download Record.
  - **Collection:** Save To ›, Refresh Collection, Mark All Read, Hide Collection, Collection Information.
  - **Channel:** Refresh Channel, Rename, Pin Channel, Manage Collections, Mark All Read, Feed Information, Remove Channel (destructive).
  - **Download:** Pause, Resume, Cancel, Retry, View Item, Scan Now, Remove Download Record, Clean Partial File.
- [ ] Route presses through `PspMenuNav.handle`, and render with `PspContextMenuOverlay`.

### T1.7 Item Details
- [ ] **Tests first:** test the primary-action state table: Download / View Progress / Resume / Retry / Scan Now / View in Library / Choose Memory Card.
- [ ] Build `ChannelItemDetails` (artboard 5):
  - **Left:** 16:9 art.
  - **Right:** title, "Channel · Collection · date · author", the description as plain text (never a WebView), and the fact grid (File, Format, Size, Checksum, Source, Save to, Status).
  - **Bottom:** the action row `[primary] View Source  Files (n)`.
  - **Around it:** the rail and tabs dim to 0.45 alpha.
  - **Focus:** a `NavigationEngine("channels-details")` adapter modelled on `GameDetailNav`, with node keys `channels:details:<action>`, `markReady()` after first layout, and `dispatchTouch` for taps.
- [ ] Download → the preflight modal (T4.x). In Phase 1, show a `PfpModalSpec.Notice` saying "Downloads arrive in a later build".

### T1.8 Search
- [ ] **Tests first:**
  - The query filters only the active collection.
  - BACK clears the query and closes search.
  - Changing tab keeps search open (spec §10).
- [ ] Use `rememberVirtualKeyboardEdit` + `VirtualKeyboardTextInput` + `Modifier.virtualKeyboardField`, following `AppDrawerScreen.kt:281–302`. The field sits in the header, 220dp wide, with `searchField` / `searchBorder` colours.

### T1.9 Clean-up and device check
- [ ] Delete `StorefrontReference.kt` once nothing is copied from it any more.
- [ ] Previews: add `@CombinedPreviews` for the storefront in rail focus, grid focus, Unmapped, Details and touch mode.

**Done when** the whole controller loop works on the Odin3 and Thor with the right cues, and touch mode matches artboard 4.

**Commands for the user:**
```bash
./gradlew :core:core-ui:testDebugUnitTest :feature:feature-channels:testDebugUnitTest
```
```bash
./gradlew :app:installLiteDebug
```

---

## Phase 2: Feeds and persistence

### T2.1 Room v56
- [ ] **Tests first:** `Migration55To56Test`, written the same way as the existing `MigrationXToYTest` files:
  - the tables and indexes exist
  - foreign keys behave
  - a Memory Card removal leaves mappings intact until the repository nulls them
- [ ] Add these entities and DAOs in `core-data`:
  - `ChannelEntity`
  - `ChannelCollectionEntity`, unique on `(channel_id, source_key)`
  - `ChannelDestinationMappingEntity`, with **no cascade** to Memory Cards
  - `ChannelItemEntity`, unique on `(channel_id, guid)`
  - `ChannelItemCollectionEntity`, composite primary key
  - `ChannelEnclosureEntity`
  - `ChannelDownloadEntity`
- [ ] Bump `PFPDatabase` to `version = 56`. Add `MIGRATION_55_56` with explicit `CREATE TABLE` / `CREATE INDEX` statements, register it in `DatabaseModule.addMigrations`, and commit the exported `56.json` schema.
- [ ] Never use destructive fallback.

### T2.2 FeedGuard policies
- [ ] **Tests first:** `FeedGuardTest`, covering every row of spec §37.3.
- [ ] Create these files in `feed/guard/`:
  - **`FeedUrlPolicy`:** HTTPS only; no credentials in the URL; loopback and private IPs rejected.
  - **`RedirectPolicy`:** at most 5 hops, and every hop is revalidated.
  - **`FeedDocumentPolicy`:** XML capped at 5 MB, at most 5,000 items, at most 8 enclosures per item, and field length caps.
  - **`EnclosurePolicy`**
  - **`FilenamePolicy`** (spec §22.3)
  - **`ArtifactPolicy`** (spec §22.4)
  - **`ArchivePolicyPlaceholder`:** rejects every archive.

### T2.3 Parser
- [ ] **Tests first:** `RssAtomParserTest`, built on fixture XML files in `src/test/resources/feeds/`, covering every case in spec §37.1.
- [ ] Write a streaming `XmlPullParser` that rejects DOCTYPE/DTD and turns HTML into plain text. It outputs `ParsedChannel(metadata, items[categories, enclosures], warnings)`.

### T2.4 Category resolution
- [ ] **Tests first:** `PlatformCategoryResolverTest`, checking the priority order in spec §18.1, including the domain/scheme `https://playfieldportal.app/platform`.
- [ ] Reuse the existing platform alias map. Do not add a second one. Search core-domain or core-data for the alias table used by the scanner or identity code.

### T2.5 Feed client
- [ ] Provide `@ChannelsHttpClient` in `ChannelsModule`: Ktor with the OkHttp engine, `followRedirects = false` (RedirectPolicy handles hops), and timeouts.
- [ ] **Tests first:** `ChannelFeedClientTest` with Ktor `MockEngine`:
  - ETag/If-None-Match and Last-Modified/If-Modified-Since
  - a 304 counts as success
  - the size cap applies while streaming, even without a `Content-Length`

### T2.6 Repository and refresh
- [ ] **Tests first:** `ChannelRepositoryTest` (merge):
  - read, hidden and download state are preserved
  - new items are marked NEW
  - items that disappear are marked unavailable, never deleted
  - a changed enclosure becomes UPDATE
- [ ] Write `ChannelRepositoryImpl` in core-data and `ChannelRefreshCoordinator` (`testFeed`, `refreshChannel`, `refreshAllDueChannels`) with a per-Channel mutex so refreshes never overlap.
  - Opening RSS Fetch refreshes stale Channels.
  - A manual refresh reports to the tray with a new `TaskKind.FEED`.
- [ ] Swap the binding from `FakeChannelRepository` to the real implementation. Keep the fake in the test sources.

### T2.7 Add Channel flow
- [ ] **Tests first:** the Add Channel state machine goes URL → testing → preview → mapping → saved, and handles every subscription error in spec §35.1.
- [ ] **URL entry:** `PfpModalSpec.TextEntry` (title "Add Channel", placeholder "https://…").
- [ ] **Preview and mapping** (artboard 8): fills the content pane.
  - Mapping rows step their Memory Card with ◂ ▸ (SCROLL) through the compatible cards plus "Ask each time".
  - A shows the card list in a `PfpModalSpec` Choice.
  - X saves the Channel.
  - Show the legal notice from spec §36.

**Commands for the user:**
```bash
./gradlew :core:core-data:testDebugUnitTest :feature:feature-channels:testDebugUnitTest
```

---

## Phase 3: Routing

### T3.1 Destination routing
- [ ] **Tests first:** `ChannelRoutingTest`, covering every case in spec §37.2.
- [ ] `resolveDestination(item, enclosure, override?)` checks, in order: the override, then the collection mapping, then "Ask". Extensions are compared case-insensitively against `MemoryCard.supportedExtensions` (stored without the dot).
- [ ] A disabled or removed card becomes "Ask" and the item gets the NEEDS_CARD status.
- [ ] Rule for picking the enclosure: if exactly one file is compatible, preselect it. If there are several, ask, or reuse the previous choice. Never pick art, checksum, torrent, metadata or executable files.
- [ ] **Card picker:** `PfpModalSpec` Choice, with a **Remember for this collection** row that updates the mapping. Without it, the choice is a per-download override only.

---

## Phase 4: Downloads

### T4.1 State machine and runner
- [ ] **Tests first:** `DownloadSessionRunnerTest`, using a fake SAF target and Ktor `MockEngine`, covering every case in spec §37.4. Include:
  - a 206 resume appends
  - an unexpected 200 restarts from zero
  - a changed ETag restarts
  - pause keeps the partial file
  - cancel deletes it
  - a size or checksum mismatch never finalizes
- [ ] `DownloadSessionRunner`: Range + If-Range, Room progress writes (throttled to about 1 per 500 ms), and SHA-256 on every completed file.

### T4.2 SAF target
- [ ] **Tests first:** `SafDownloadTargetTest` checks:
  - rename returns a new URI and that URI is persisted
  - when rename is unsupported, it copies then deletes
  - lost permission becomes a recoverable error
- [ ] Write the `.pfp-part` sibling file, persist its URI before streaming, then validate, rename and persist the final URI.

### T4.3 Schedulers
- [ ] `DownloadScheduler` interface with two implementations:
  - **`UidtDownloadScheduler`** for API 34+: a `JobService` with `setUserInitiated(true)`, plus the manifest permission `RUN_USER_INITIATED_JOBS`.
  - **`ForegroundWorkerScheduler`** for API 29–33: a `@HiltWorker` `CoroutineWorker` that calls `setForeground` and adds a cancel action.
- [ ] Register both in `app/src/main/AndroidManifest.xml`. Workers use the existing `HiltWorkerFactory`.

### T4.4 Preflight and Downloads tab
- [ ] **Tests first:** preflight covers all nine checks in spec §16. Each failure shows its reason inline and plays ERROR. Confirming plays CONFIRM.
- [ ] **Preflight modal** (artboard 6): `PfpModalSpec.Confirm` with the fact rows.
- [ ] **Downloads tab** (artboard 7): rail sections All, In Progress, Failed, Completed, Partial Files. Rows show the art thumb, title, "Channel › Collection → Card", a progress bar, bytes/%, speed/ETA (only when reliable) and a state chip. A opens the item, Y opens the Download menu.
- [ ] **Tray:** `BackgroundTaskCenter.start/progress` with `TaskKind.DOWNLOAD` and "Fetching… 43%".
  - On completion, `complete(...)` with a **Scan Now** action and the title "Fetched <title>".
  - On failure, `fail(...)` with the reason.
  - There is no in-screen pop-up.

---

## Phase 5: Library handoff

### T5.1 Scan requester and exclusions
- [ ] **Tests first:**
  - The scanner skips `*.pfp-part` files.
  - The scanner skips URIs held by non-COMPLETED `channel_downloads` rows.
  - Scan Now requests only the destination card.
- [ ] Add `interface LibraryScanRequester { suspend fun requestMemoryCardScan(platformId: String) }` to core-domain. feature-library implements it, and `:app` binds it.
- [ ] Wire Scan Now to three places: the tray action, the Details primary button ("Scan Now" for an item that is fetched but not yet scanned), and the Download menu.
- [ ] Partial Files: list orphaned `.pfp-part` files with a Clean action, behind a confirm modal.

---

## Phase 6: Hardening

- [ ] Instrumentation tests from spec §37.7: real SAF tree, UIDT on API 34+, Worker below 34, restart during a download.
- [ ] Performance: a 5,000-item fixture feed scrolls smoothly, and parsing stays under 2 s on the Thor.
- [ ] Accessibility: `contentDescription` on tiles (title, status, size), rail rows and badges.
- [ ] Final error copy for every case in spec §35.
- [ ] Walk through the acceptance list in spec §39 and the plan's decision list, and confirm each one on device.

---

## Do not
- Modify the App Drawer, or reuse its menu.
- Add new sounds, icons or chrome without the user's approval. Describe it first, or ask for their art.
- Follow redirects automatically, render feed HTML, or extract archives.
- Add a "download anyway" bypass for rejected files.
- Run Gradle yourself.
