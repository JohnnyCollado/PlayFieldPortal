# RSS Fetch (Channels Storefront): Implementation Plan

**Source spec:** `PFP_RSS_Channels_Storefront_Design.md` (September 2026)
**Mockup:** https://claude.ai/artifact/XAeFZHf1gGHHF46QubWmKq (8 artboards at 1920×1080, 1 dp = 2.305 px)
**Written:** 2026-10-02, against `final-polish` @ `f51f1226`
**Status:** Plan only. No code has been changed. Itemized build list: `PFP_RSS_Fetch_Task_List.md`.

---

## 0. Name (decided 2026-10-02)

The screen is called **RSS Fetch**. The name plays on the Shiba mascot fetching games, and it says what the screen does: it fetches games from RSS feeds.

| Where | Text |
| --- | --- |
| XMB row (App Store column) | **RSS Fetch**, with the subtitle "Games from your Channels" |
| Breadcrumb root | `App Store › RSS Fetch › <Channel> › <Collection>` |
| Each subscribed feed | **Channel** (unchanged): Add Channel, Channel Options, Remove Channel |
| Tile badge after completion | **✓ FETCHED** |
| Progress copy | "Fetching… 43%" in tray rows and badges |
| Actions | Buttons still say **Download**, so the action is unambiguous |
| Code | The module stays `feature-channels` and types keep `Channel*` names. Only user-facing strings change. |

Name check: there is an unrelated web service called "FetchRSS", a feed generator. The word order differs and we don't use its branding, but the user knows about it.

---

## 1. Where the spec and the repo disagree

The spec was written against an older snapshot. These are the corrections the plan uses.

| Spec says | Repo today | Plan |
| --- | --- | --- |
| Target branch `ui-helper-buttons` | No such branch exists. Only `main` and `final-polish`. | Branch from `main` after `final-polish` lands (for example `channels-storefront`). |
| Room 40 → 41 | `PFPDatabase` is **v55**. The latest migration is `MIGRATION_54_55`. | **`MIGRATION_55_56`**, schema JSON and a `Migration55To56Test`. |
| `StorefrontAppDrawer.kt` is preserved in feature-appbar | **Deleted** as dead code in `f51f1226`. The `AppDrawerScreen.kt:77` comment still points to it. | Restore it from `f51f1226^`, **move** it into `feature-channels` and refactor it there (§3). Do not restore it to feature-appbar. |
| "Use `ControllerPromptBar`" | `ControllerPromptBar` is the low-level row. Full-screen surfaces now pair it with touch pills (`XmbHeaderPill`, kebab) and `TouchPromptBar`, and show one family at a time (ARCHITECTURE.md §touch). | Controller mode uses a context-sensitive `ControllerPromptBar`. Touch mode uses `TouchPromptBar` plus header pills. |
| Screen receives `GamepadAction`s | Input goes `GamepadInputHandler` → `XMBViewModel.dispatchGamepadAction` → priority ladder → `pending…Action` → screen. | Add `activeChannels` and `pendingChannelsAction` to `XMBUiState`, a ladder branch and `hasBlockingOverlay`, the same way as `gamePickerCategoryId`. |
| Ktor is shared | The only unqualified `HttpClient` lives in feature-artwork, and features must not depend on each other. | feature-channels provides its own `@ChannelsHttpClient`, with no default redirect following. Redirects go through `RedirectPolicy`. |
| Completion shows a Scan Now / Later dialog | House rule: operation **results** go to the notification tray (`BackgroundTaskCenter`, NOTIFICATION cue), not in-screen pop-ups. | Completion becomes a tray row with a **Scan Now** action. The item's Details button becomes "Scan Now". There is no modal pop-up. Decided. |
| Rail holds Latest / New / Downloads / All Channels **and** a Channel's collections, while L/R switches Channel | These conflict: L/R would have nothing to switch between if the Channels are rail rows. | A **tab row** of Channels under the header (Home · each Channel · Downloads). LB/RB step the tabs, and the rail shows the active tab's sections. See §2. Approved. |
| `TaskKind` for transfers | `SCAN, ARTWORK, METADATA, ACHIEVEMENT, DOWNLOAD` | Use `DOWNLOAD` for transfers. Add `FEED` for manual refreshes that should show in the tray. |

Everything else in the spec stands: product principles, FeedGuard policies, ready-to-use files only, UIDT on API 34+ with a foreground Worker fallback below it, SAF staging, Room-owned download state, and no automatic download, scan or launch.

---

## 2. My reading of the screen (see mockup)

```
┌ ‹ App Store › RSS Fetch › Homebrew Hub › NES ─────────────────── 🔍 Search ┐  header 56dp
├ [LB]  Home   Homebrew Hub   Public Domain Shelf   My Server   Downloads  [RB] ┤  tabs 36dp
├ COLLECTIONS        │ NES                               48 ITEMS · 6 NEW │
│ ALL ITEMS      212 │ Downloads save to: NES Memory Card   Refreshed 5 min│
│▌NES             48▐│ ─────────────────────────────────────────────────── │
│ SUPER NINTENDO  36 │ [tile][tile][tile][tile][tile]   5 columns, ICON0-  │
│ GAME BOY ADV.   61 │ [tile][tile][tile][tile][tile]   ratio art, title,  │
│ PLAYSTATION     22 │                                  format · size      │
│ UNMAPPED  •     45 │ Open-source RPG Demo   NES · 384 KB · host · date   │  info strip 40dp
├─────────────────────────────────────────────────────────────────────────┤
│ LB Prev Channel  RB Next Channel  B Back  A Details  Y Options  X Search │  footer 48dp
└──────────────────────────────────────────────────────────────────────────┘
```

- **Tabs (LB/RB):** `Home` (Latest, New, All Channels, Add Channel), then one tab per Channel (pinned Channels first, then `sort_order`), then `Downloads` (All, In Progress, Failed, Completed, Partial Files). One model covers the spec's global entries and its per-Channel rail.
- **Focus is always visible in exactly one place.** In rail focus the active row has the edge border and glow, and the grid tiles are slightly dimmed. In grid focus the rail row keeps its fill but loses the border, and the tile takes the cursor (artboards 1 and 2).
- **Tiles** use the ICON0 aspect (126×70) so PSP-era art reads naturally. Art follows the spec's fallback order and ends at a `.EXT` format tile. Status badges: NEW, ✓ FETCHED, progress bar with %, FAILED, UPDATE, CHOOSE CARD.
- **Item Details** replaces the content pane. The rail and tabs dim, and the breadcrumb gains the item. The action row is `Download | View Source | Files (n)`, and the first button follows the spec's §15 state table.
- **Preflight** and **destination picker** use the shared `PfpModalHost` (`Confirm` and `Choice` specs), not storefront chrome, so every modal in PFP looks and sounds the same.
- **Add Channel**: the URL comes in through a `PfpModalSpec.TextEntry` (which already brings the controller virtual keyboard). Preview and mapping then fill the content pane, and ◂ ▸ step each collection's Memory Card the way the settings sliders do.

---

## 3. Refactoring `StorefrontAppDrawer.kt` for the nav core

These are the problems in the restored file, each with the fix.

| # | Problem in the preserved file | Fix |
| --- | --- | --- |
| 1 | Input handling is split: a `LaunchedEffect(pendingGamepadAction)` toggles a `remember`ed `searchActive` and forwards the rest. | All input goes to `ChannelsViewModel.onAction(action)`. Navigation state is one immutable `ChannelsNavState` with pure reducers (`move`, `enterGrid`, `back`, `stepTab`…) in `ChannelsNavLogic.kt`, the same shape as `GamePickerLogic`. |
| 2 | The rail can't be focused by a controller. Rows are only `clickable`, and filters change only through LB/RB. | Two zones, `RAIL` and `GRID`, plus `DETAILS`, with the spec's §9 movement rules. |
| 3 | No sounds at all. | `channelsSound(before, after): MenuSound?` is diffed in the VM, as `gamePickerSound` does (§4). |
| 4 | Search uses `BasicTextField` and the system IME (`keyboard?.show()`). | `rememberVirtualKeyboardEdit` + `VirtualKeyboardTextInput` + `Modifier.virtualKeyboardField`, as in `AppDrawerScreen.kt:281`. The query is scoped to the active collection. |
| 5 | `animateScrollToItem(selected)` pins the selected tile to the top. | Centred scroll, matching the XMB and picker. The grid move uses `core-navigation`'s `GridMove`. |
| 6 | `AppMiniMenu` is a storefront-only menu: it takes a selection index but has no navigation of its own, and its rows are clickable only. | **Deleted.** The App Drawer is the only place that keeps its own menu. Every Channels options menu (Channel, Collection, Item and Download) uses the shared **`PspContextMenuOverlay`** (core-ui): the right-edge 300dp panel, wave colour at 0.75, and the glow-band cursor. Rows are `PspMenuRow` with `header`, `value` and `opensMenu`. Presses go through `PspMenuNav`, which brings SCROLL, SELECT/CONFIRM and BACK with edge-silence. A submenu row like "Save To ›" opens the Memory Card list in the same menu. |
| 7 | Material `AlertDialog` (uninstall confirm). | Deleted. Every confirm is a `PfpModalSpec`. |
| 8 | The footer is a static list, shown in touch mode as well. | Prompts come from state (zone, item state, modal), so every handled button is advertised and nothing else is. With `showTouchControls`, the footer becomes `TouchPromptBar`, the header gets `XmbHeaderPill` Back and Search, and Options becomes the kebab. |
| 9 | Touch-browse tracking is inline in the grid. | Keep the nearest-to-centre logic, but feed it to the VM as `touchBrowse(index)`. The next controller press restores the cursor there silently, as `GamePickerState.touchBrowse` does. |
| 10 | Reads `AppDrawerViewModel`, `InstalledApp` and `AppFilter`. | Split into stateless composables (`ChannelStorefront`, `ChannelCollectionRail`, `ChannelItemGrid`, `ChannelItemTile`, `ChannelContentHeader`, …) that take `ChannelsUiState` and callbacks. Colours stay on `deriveStorefrontColors()`. |

**Shell wiring (feature-xmb):**
- `XMBUiState.activeChannels: ChannelsLaunch?` and `pendingChannelsAction`.
- A ladder branch that sits with `gamePickerCategoryId`, after the virtual keyboard and before `activeContextMenu`.
- `XMBShell` hosts `ChannelsScreen(pendingGamepadAction, onGamepadActionConsumed, onClose, showTouchControls)`.
- **Entry point (decided):** a main XMB row **RSS Fetch** in the **App Store** column (`app_store`), built as a `channelsItem()` sentinel row like `addAppsItem()` and placed first in the column, above the store apps.
- **Hide-able:** the row's context menu (Y) gets **Hide**, which writes a `HiddenPlacement` with a new key `HiddenPlacement.featureKey("channels")` (= `"feature:channels"`; the key stays stable if the display name changes), `HideLocationType.CATEGORY`, `locationId = "app_store"`, labels "RSS Fetch" / "App Store". The App Store branch filters it with the existing `isHiddenAt(...)`, and it comes back from Settings ▸ Hidden Items like any hidden app. Hiding only removes the row: Channels, items and in-flight downloads keep running, and tray notifications still open the screen.
- Tests first: `XmbListsTest`/`XMBViewModel` cases for "App Store shows Channels first", "hidden Channels row is filtered", "Hidden Items lists and restores it".
- HOME stays with the shell (notification panel).

**Navigation core use:**
- Rail and grid use the pure reducer with `GridMove`, like the Game Picker, because the grid is uniform.
- The Details action row and the Add Channel mapping list use a `NavigationEngine("channels-details")` adapter like `GameDetailNav`. Node keys look like `channels:details:download` and `channels:map:<collectionId>`. The engine provides `markReady()` after first layout, `pushModal` while a `PfpModalHost` is open, and `dispatchTouch` for taps.
- A modal or menu blocks all background navigation. `modal.intercept(action)` gets the press first.

---

## 4. Input and sound map

Sounds are the existing `MenuSound` slots. No new audio. A press that changes nothing is silent: blocked edges, the first press after touch that only restores the cursor, and LB at the first tab.

| Zone | Action | Result | Sound |
| --- | --- | --- | --- |
| any browse | PREV/NEXT_CATEGORY | Previous/next tab (clamped). Keep the zone, restore that tab's collection and cursor. | SYSTEM_BROWSE |
| Rail | UP / DOWN | Move collection, and the grid previews it instantly | SCROLL |
| Rail | RIGHT / SELECT | Enter the grid on the remembered item (nearest survivor, else first) | SELECT |
| Rail | SELECT on Add Channel / All Channels | Open that flow | SELECT |
| Rail | BACK | Close Channels and return to the XMB | BACK |
| Rail | OPEN_CONTEXT_MENU | Collection Options (Home tab: Channel Options) | SELECT |
| Rail / Grid | CHANGE_SORT | Open search (virtual keyboard) | SELECT |
| Grid | D-pad | `GridMove` | SCROLL |
| Grid | LEFT in column 0 | Back to the active rail row | BACK |
| Grid | SELECT | Item Details (never downloads) | SELECT |
| Grid | BACK | Rail | BACK |
| Grid | OPEN_CONTEXT_MENU | Item Options | SELECT |
| Details | LEFT / RIGHT | Move between action buttons | SCROLL |
| Details | SELECT Download | Preflight, then the `Confirm` modal | SELECT |
| Details | SELECT when preflight fails (card gone, extension rejected) | The reason shows inline, or "Choose Memory Card" opens the `Choice` modal | ERROR |
| Preflight | Confirm | Enqueue, and the button becomes View Progress | CONFIRM |
| Details | BACK | Grid (the same tile) | BACK |
| Context menu | (PspMenuNav over `PspContextMenuOverlay`) | Row SCROLL; action CONFIRM; `opensMenu` row SELECT; BACK closes or ascends | built in |
| Search | BACK | Clear and close, back to the previous zone | BACK |
| Downloads tab | SELECT on a row | Item Details for that item | SELECT |
| Tray | download settled | `BackgroundTaskCenter.complete/fail` | NOTIFICATION (center) |

---

## 5. Module and data plan (spec §29–31, re-targeted)

- **New module `:feature:feature-channels`.** It depends on core-domain, core-data, core-ui and core-navigation, and never on another feature. Include it in both the `full` and `lite` flavors (decided).
- **core-domain:** the Channel models, `ChannelDownloadState`, the repository and coordinator interfaces, and `LibraryScanRequester`. feature-library provides the implementation, bound in `:app`.
- **core-data:** entities and DAOs for `channels`, `channel_collections`, `channel_destination_mappings`, `channel_items`, `channel_item_collections`, `channel_enclosures` and `channel_downloads`, plus `MIGRATION_55_56` in `DatabaseModule`. A Memory Card removal nulls mappings in the repository, with no cascade.
- **Scanner:** ignore `*.pfp-part` and any URI held by a non-completed `channel_downloads` row.
- **Manifest (app):** `RUN_USER_INITIATED_JOBS` and `ChannelDownloadJobService`. Workers use the existing `HiltWorkerFactory`.

---

## 6. Phases (tests first in every phase)

I put the storefront first, ahead of the spec's persistence-first order, so the controller and sound behaviour can be checked on the Odin3 and Thor before any networking exists. It runs on a fake in-memory repository behind the same interfaces.

| Phase | Scope | Tests written first | Exit |
| --- | --- | --- | --- |
| **0 Restore** | Restore the file from `f51f1226^` into the new module. Add the module, the shell flag, the hide-able App Store row and an empty screen. | none (scaffold) | Channels opens and closes from App Store, and Hide / Hidden Items restore works. Back returns to the XMB. |
| **1 Storefront nav** | `ChannelsNavLogic` and `channelsSound`. Tabs, rail, grid, info strip, details shell, item and collection menus, search via the virtual keyboard, touch mode, context-sensitive prompts. Uses a fake repository with fixture Channels. | `ChannelsNavLogicTest`: spec §37.6 plus the §4 sound table, including edge-silence and touch restore. | The full controller loop works on device with the right cues. |
| **2 Feeds** | `RssAtomParser` (streaming XmlPullParser, DTD rejected, caps), `FeedGuard` policies, `ChannelFeedClient`, refresh merge, conditional GET, Room v56. | Parser, FeedGuard and Migration55To56 fixtures (§37.1, 37.3, 37.5). | A real feed can be tested, saved and refreshed, and the storefront reads from Room. |
| **3 Routing** | `PlatformCategoryResolver`, the mapping UI (Add Channel and Manage Collections), compatibility checks, per-download override and Remember for this collection. | Routing tests (§37.2). | One Channel routes several collections to several cards. |
| **4 Downloads** | Download state machine, the `DownloadScheduler` split (UIDT and foreground Worker), Ktor streaming with Range/If-Range, SAF `.pfp-part` staging, rename fallback, SHA-256, tray progress, and the Downloads tab. | Download tests (§37.4) using a fake SAF target and a MockEngine. | A file survives a kill or pause and finalizes into the card. |
| **5 Library handoff** | `LibraryScanRequester`, tray Scan Now, the Details "Scan Now" state, scanner exclusions, Partial Files cleanup. | Scanner-exclusion and requester tests. | An explicit scan lands the game in the right card. |
| **6 Hardening** | Redirect and large-file tests, process-death tests, accessibility descriptions, error copy, a 5,000-item feed performance pass. | §37.7 instrumentation. | The §39 acceptance list passes. |

Per memory, you run the builds and tests. Each phase ends with the exact `:feature:feature-channels:testDebugUnitTest` / `:app:installLiteDebug` commands.

---

## 7. Questions for you before Phase 0

1. ~~Channel tab row~~ **Approved 2026-10-02.**
2. ~~Options menus~~ **Decided 2026-10-02:** shared `PspContextMenuOverlay` context menus everywhere outside the App Drawer (artboard 3 updated).
3. ~~Download complete~~ **Decided 2026-10-02:** tray row with a Scan Now action; the Details button becomes "Scan Now". No modal.
4. ~~New badge colours~~ **Decided 2026-10-02: option A (amber), with two fixes** (artboards 9 and 11).
   - **Rail dot:** amber `#FFC46B` with a 3px `rgba(0,0,0,.6)` ring, so it shows on every scheme's selected row. Without the ring it is 1.05:1 on Silver.
   - **Badge fill follows the chrome direction.** Dark chrome uses `rgba(0,0,0,.6)` with amber text `#FFC46B` and border. Light chrome (Sunset Orange, Fresh Green, Silver, Golden Amber, Aqua Teal) uses light glass `rgba(255,255,255,.90)` with dark-amber text `#8A5300` and a `#C98A1C` border.
   - **Applies to all fill-based tile badges:** UPDATE (edge text/outline), ✓ FETCHED and progress %. This fixes UPDATE's ~3:1 on pale schemes.
   - **Measured contrast:** CHOOSE CARD is 12.1:1 dark and 5.7:1 light. UPDATE is ≥5.6:1 everywhere except Silver, which is 4.2:1 (bold 19sp label).
   - **Code:** add `badgeFill`, `badgeOnFill`, `needsCardText`, `needsCardEdge` and `needsCardDot` to `StorefrontColors`, derived in `storefrontColorsFor` from `lightChrome`, with a `StorefrontColorsTest` case per preset asserting ≥4.0:1.
5. ~~Lite flavor~~ **Decided 2026-10-02:** ship in both `full` and `lite`, entered from a hide-able App Store row.
6. ~~LB/RB at the ends~~ **Decided 2026-10-02:** clamp. LB on the first tab and RB on the last tab do nothing and play no sound.
