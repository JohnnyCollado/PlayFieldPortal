# Play Field Portal — Category Memory Cards, UMD Slot, Sort Tiers & Move: Implementation Plan

Custom categories become useful for power users while staying simple for everyone else: every
gaming category gets its own Memory Card, collections become Custom Memory Cards, each gaming
column gets a UMD slot, sorting works in tiers like Icon Display, and any list can be arranged
by hand.

**Status: implemented; unit tests green, not yet verified on a device.** See §5 for where the
implementation departs from the approved plan.

**Mockups:** https://claude.ai/artifact/FPFoqVMf8RTvy36Nekb7kD (boards 1a–11).

---

## 1. What changed for the user

| Area | Before | After |
| --- | --- | --- |
| Custom gaming category root | Custom cards and loose games mixed, game art at the root | UMD slot, the category's Memory Card, custom cards, Add Games — no game rows |
| Collections | "Collections" | "Custom Memory Cards" everywhere (the data model keeps its names) |
| Deleting a category | Its collections were left pointing at nothing and vanished | Asks: move its custom cards to Game / App Store, or delete them; games always stay |
| Gaming type | A toggle that could be flipped, leaving stale rows | Chosen at creation, then read-only |
| Sort | One in-memory game sort shared by every list | Global sort → per-list sort ("Use Global Setting") → Custom, all persisted |
| Arranging | Pin only; apps could not be unpinned | Pin to Top on/off, and Move in any Custom-sorted list, including a column's root |
| Last played | Never recorded | Stamped when a launch reaches the system |
| Adding to a card | One game at a time, every card in one flat list | Multi-select; the picker lists the current category's cards first |

## 2. Data model (schema v54)

- `list_items(list_key, item_key, position, pinned)` — a list's Custom order and its pinned games.
- `list_settings(list_key, sort_mode)` — a list's own sort; no row means "follow the global sort".
- `umd_slots(column_id → categories ON DELETE CASCADE, game_id, inserted_at)` — the inserted game.
- `app_usage(package_name, last_launched_at, launch_count)` — app recency without Usage Access.
- `category_items.added_at` — when a game was put in a category.
- DataStore `pref_sort_mode_games`, `pref_sort_mode_apps` — the two global sorts.

Keys are plain strings (`ListKeys` in core-domain) so lists with no junction table can be
arranged: `root:<category>`, `card:<platform>`, `all_games`, `favorites`, `catcard:<category>`,
`collection:<id>`, `apps:<section>`; items are `game:<id>`, `app:<pkg>`, `collection:<id>`,
`card:<platform>`, and `row:all_games|favorites|missing|catcard` for rows with no record.

`MIGRATION_53_54` creates the tables and runs `ListStateBackfill`: collections whose category
is gone move to `games`, collection order becomes per-category, and a custom category's pinned
games become pins on its Memory Card list. A backup made before v54 gets the same backfill on
restore.

## 3. Where the logic lives

- **Pure rules** — `ListArrangement`, `UmdSlotResolver`, `ListKeys` (core-domain);
  `XmbLists.kt` (which list is on screen, sort resolution, row arrangement, `moveRow`, the menu
  row builders); `gamesForDisplay` (search, sort, Custom order, pins).
- **Storage** — `ListStateRepository`, `SortPreferences`, `UmdSlotRepository`;
  `GameCategoryRepository.categoryCardGames`; `CategoryRepositoryImpl.delete(id, policy)`;
  `CollectionRepository` (per-category order, rehome, delete-all).
- **State and writes** — `XMBViewModel`: `observeListArrangement`, `publishRootItems`,
  `applyListSort`, `handleArrangeMenuItem`, the Move session and multi-select.
- **Rendering** — `XMBItemList` (`UMD_SLOT`, `CATEGORY_CARD`, the lifted-row and marked-row
  decoration), `ArrangeBars.kt`, `PfpChoiceModal` in the shared modals.

## 4. Tests

| Task | Tests |
| --- | --- |
| T1 schema | `Migration53To54Test`, `ListStateDaoTest` |
| T2 repositories | `ListArrangementTest`, `UmdSlotResolverTest`, `LaunchDispatcherTest` (last played), `AppCategoryRepositoryTest` |
| T3 category card, delete | `GameCategoryRepositoryTest`, `CategoryRepositoryDeleteTest`, `CategoryManagerDeleteTest`, `PfpModalNavTest` |
| T4 custom cards | `CollectionRepositoryTest`, `ArrangeMenusTest`, `GameContextMenuItemsTest` |
| T5–T8 UMD, sort, move, multi-select | `XmbListsTest`, `GamesFilterTest`, `ContextMenuHintStateTest`, `ArrangeMenusTest` |
| T9 backup | `BackupListStateTest`, `BackupKeyCoverageTest` |

Run them with:

```
.\gradlew.bat :core:core-domain:testDebugUnitTest :core:core-data:testDebugUnitTest :core:core-ui:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest :feature:feature-settings:testDebugUnitTest :feature:feature-appbar:testDebugUnitTest :feature:feature-launcher:testDebugUnitTest :feature:feature-backup:testDebugUnitTest
```

## 5. Departures from the approved plan

1. **Pins stay where they were for cards, custom cards and apps.** The plan moved every pin into
   `list_items`. Those three already have pin columns that the Library Manager, backups and
   category moves read and write, so only game rows — which had no home for a pin outside a
   custom category — use `list_items`. The migration therefore copies only pinned games.
2. **Multi-select is entered from the menu, not with Square.** Square already opens the Filter
   menu in every game list. **△ ▸ Select Multiple** starts marking; confirm then marks, △ adds
   the marked games to a card, back ends it.
3. **No Favorites order is seeded.** `games.favorite_sort_order` is never written by the app, so
   there was no user arrangement to preserve.
4. **A category's app cards rehome to App Store, not Game.** The plan named only Main Game; an
   app card in a gaming column would be the wrong kind.
5. **The global app sort is set from an app column's Sort picker** ("Global Sort"), not from a
   Settings screen: there is no All Games menu to carry it, and the picker is where the user is
   already looking.
6. **Date Added inside a card** sorts by when the game was added to that card (or category),
   which the plan implied by adding `category_items.added_at` but did not spell out.
7. **"Recently Used" for apps** counts launches from the XMB (`app_usage`) and, where Usage
   Access is granted, the system's own record. Launches from the App Drawer are seen only
   through the system record.
8. **No test asserts "no user-facing string says Collection".** The strings were renamed by
   hand and the menu tests pin the ones that matter; a blanket source scan was not written.

## 6. Verify on a device

1. Create a gaming category: it opens on the row under the UMD slot, with its Memory Card and
   Add Games.
2. Add games; open the Memory Card: each row says Loose or names its custom card.
3. **△ ▸ Insert as UMD**, then focus the UMD slot: the icon becomes the game's own and its art
   fills the background. **Eject UMD** falls back to the last game played.
4. Make a custom memory card, pin it, unpin it. **X / □ ▸ Custom**, then **△ ▸ Move**.
5. Give one Memory Card its own sort from its **△** menu; check another still follows Global.
6. Delete the category with cards in it: try both answers.
7. **Select Multiple**, mark three games, add them to a card.
8. Back up, restore, and check the order, sorts and UMD slot came back.
