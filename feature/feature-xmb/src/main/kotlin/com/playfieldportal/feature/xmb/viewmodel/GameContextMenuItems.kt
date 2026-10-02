package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category

/**
 * The XMB game row's context menu, from the state it depends on. Kept out of [XMBViewModel] so
 * its contents can be pinned without building the ViewModel; `openGameContextMenuCore` supplies
 * the state and opens the menu.
 *
 * [hideLabel] is the name of the place this row is shown ("Favorites", a card, a custom memory
 * card), or null where per-location hide is not offered.
 *
 * [pinned] is the row's Pin to Top state in the list it is shown in, or null where the list
 * cannot pin (a column's root, the Missing bucket). [canMove] is true only while that list is
 * Custom sorted. [umd] is what the row can do about its column's UMD slot, and [canSelectMultiple]
 * offers marking several games to add to a custom memory card at once.
 *
 * [emulatorLabel] is the per-game emulator override's name, or "Default" while the game follows
 * its platform; [iconDisplayLabel] is the icon mode in force for the game (its own override, else
 * what it inherits), or null where it is not known yet.
 *
 * Six groups, in the order the approved mockup draws them: Play, Library, Arrange, Customize, PC,
 * Manage. An empty group draws nothing, header included. Ids are the handlers' keys and do not
 * change with the wording.
 */
internal fun gameContextMenuItems(
    item: XMBItem,
    discCount: Int,
    inCollection: Boolean,
    currentCategory: Category?,
    categories: List<Category>,
    inMissingBucket: Boolean,
    hideLabel: String?,
    pinned: Boolean? = null,
    canMove: Boolean = false,
    umd: UmdMenuState = UmdMenuState.NONE,
    canSelectMultiple: Boolean = false,
    emulatorLabel: String = "Default",
    iconDisplayLabel: String? = null,
): List<XMBContextMenuItem> = buildList {
    group("Play") {
        // The explicit path to the edit surface, essential when direct launch makes confirm skip
        // straight into the game. Launch/title/note editing lives in Game Detail; the one-tap
        // actions below (favorites, Fetch Artwork, emulator) don't need the full screen.
        add(XMBContextMenuItem("game_details", "View Game Details"))
        // Multi-disc sets: pick which disc is preferred — the only way to reach a non-primary
        // disc when direct launch skips Game Detail's picker. It only records the choice; the
        // launch stays an explicit confirm on the row.
        if (discCount > 1) add(XMBContextMenuItem("choose_disc", "Choose Disc", opensMenu = true))
        // Android games can never have achievements — no Shiba Coins entry for them.
        if (item.platformId != XMBViewModel.ANDROID_PLATFORM_ID) {
            add(XMBContextMenuItem("view_shiba_coins", "View Shiba Coins"))
        }
        // Emulator choice only applies to ROM-backed games; package-backed gaming apps
        // launch via their package/shortcut handle.
        if (!item.isAndroidApp) {
            add(XMBContextMenuItem("change_emulator", "Change Emulator", value = emulatorLabel, opensMenu = true))
        }
        // The column's UMD slot: put this game in it, or take it out.
        addAll(umdMenuItems(umd))
    }

    group("Library") {
        // No "Edit App Details" here: package-backed GAME entries (PC shortcuts, Android
        // gaming apps) are games — art/title/note editing lives in Game Detail and the
        // game rows below, never the slim standard-app editor.
        // One name and one id whichever way it is set; the value says the state.
        add(XMBContextMenuItem(
            id     = "favorite_toggle",
            label  = "Favorite",
            value  = if (item.isFavorite) "On" else "Off",
            silent = true,
        ))
        add(XMBContextMenuItem("add_to_collection", "Add to Card", opensMenu = true))
        // Only offer removal when viewing the game from inside a custom memory card.
        if (inCollection) add(XMBContextMenuItem("remove_from_collection", "Remove from Card"))
        // Mark several games, then add them all to a custom memory card in one go.
        if (canSelectMultiple) add(XMBContextMenuItem("select_multiple", "Select Multiple"))
    }

    group("Arrange") {
        // Where the row sits in the list it is shown in.
        addAll(arrangeMenuItems(pinned, canMove))

        // Gaming category options. Games in the Main Game category can only be COPIED into
        // another category (never moved out or removed); custom gaming categories allow
        // move / remove. Move/Add only appear when a real destination exists — a custom gaming
        // category other than the current one (Main Game is never a target).
        if (currentCategory?.isGamingCategory == true) {
            val hasOtherCustomCategory = categories.any {
                it.isGamingCategory && it.id != BuiltInCategory.GAMES && it.id != currentCategory.id
            }
            if (currentCategory.id == BuiltInCategory.GAMES) {
                if (hasOtherCustomCategory) add(XMBContextMenuItem("add_category", "Add to Category", opensMenu = true))
            } else {
                if (hasOtherCustomCategory) add(XMBContextMenuItem("move_category", "Move to Category", opensMenu = true))
                add(XMBContextMenuItem("remove_category", "Remove from Category"))
            }
        }
    }

    group("Customize") {
        add(XMBContextMenuItem("icon_display", "Icon Display", value = iconDisplayLabel, opensMenu = true))
        // Re-scrape this game's artwork in the background. Not for missing-ROM entries: with the
        // file gone there is no hash to match on, only the title.
        if (!inMissingBucket) add(XMBContextMenuItem("fetch_artwork", "Fetch Artwork"))
    }

    // Emulated PC (Local Steam) games can have their Goldberg achievement data installed on
    // demand — gated per-game at dispatch on the installer toggle being on.
    if (item.platformId == XMBViewModel.WINDOWS_PLATFORM_ID) {
        group("PC") {
            add(XMBContextMenuItem("install_goldberg", "Install Goldberg Achievements"))
            // Writes this game's .pfpgame file so a fresh install can bring it back with its
            // artwork (C18 task X.7). Offered on every PC game; the exporter explains a refusal.
            add(XMBContextMenuItem("export_game", "Export Game"))
        }
    }

    group("Manage") {
        add(XMBContextMenuItem("manage_collections", "Manage Custom Cards"))
        add(XMBContextMenuItem("file_location", "Show File Location"))
        // Per-location hide for the spot this game is shown in (recoverable in Hidden Items).
        hideLabel?.let { add(XMBContextMenuItem("hide_here", "Hide from $it")) }
        // Android-library apps are user-curated, so let the user remove one like any game,
        // or demote it to a standard app without losing its art/collections.
        if (inMissingBucket) {
            // The plan's explicit user delete, and the only destructive action anywhere in the
            // missing-ROM flow. Mechanically identical to "Remove from Library" (delete row,
            // file untouched), but labelled for what it means here: this bucket is the entry's
            // last visible trace, so removing it ends the line rather than dropping it from one
            // view. Everything else is recoverable by putting the file back.
            add(XMBContextMenuItem("remove_missing", "Remove permanently", isDestructive = true))
        } else if (item.platformId == XMBViewModel.ANDROID_PLATFORM_ID && item.packageName != null && !inCollection) {
            add(XMBContextMenuItem("unmark_game", "Unmark as Game"))
            add(XMBContextMenuItem("remove_app", "Remove from Library", isDestructive = true))
        } else if (!inCollection) {
            // Every other game gets full delete too (confirmed first). Deleting a scanned ROM
            // entry leaves the file untouched — the next scan re-discovers it.
            add(XMBContextMenuItem("remove_game", "Remove from Library", isDestructive = true))
        }
    }
}
