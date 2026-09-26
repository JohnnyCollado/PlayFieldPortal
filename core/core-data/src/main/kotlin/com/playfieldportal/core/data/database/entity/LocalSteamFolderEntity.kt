package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One Steam-emu game folder the user pointed PFP at, keyed by the Steam app id it resolves to.
 *
 * **Why this table exists.** Local Steam discovery used to find a game folder by walking the
 * windows library's scan surfaces on every pass. Windows games no longer live there — they enter
 * the library through OS pins, launcher exports and `.pfpgame` restores while their install
 * folders sit wherever the user's Wine/Winlator setup put them. So the folder is PICKED once and
 * recorded here, and every later sync resolves it with a primary-key read instead of a deep SAF
 * tree walk.
 *
 * **Why the key is the app id alone.** `findByAppId` is the one question every sync asks, and the
 * app id is the only thing a provider link carries. Two installs of the SAME app id (a second
 * language build, a backup copy) therefore collapse onto one row — the later pick wins. That is
 * the deliberate trade: a composite `(app_id, tree_uri, folder_doc_id)` key would allow both at
 * the cost of a lookup that can return more than one row and a caller that has to choose between
 * them, which is a choice no sync can make correctly.
 *
 * **Nothing here is authoritative about progress.** [progressDocId] being null means "no progress
 * file resolved yet", which is "not played", never "nothing earned" — an unreachable folder, a
 * revoked grant and an unreadable file are all unknown (see `LocalSteamSource`).
 */
@Serializable
@Entity(tableName = "local_steam_folders")
data class LocalSteamFolderEntity(

    /** The Steam app id this folder resolves to — the id a LOCAL_STEAM provider link carries. */
    @PrimaryKey
    @ColumnInfo(name = "app_id")
    val appId: String,

    /** The game folder's own display name, for pickers and batch reports. */
    @ColumnInfo(name = "folder_name")
    val folderName: String,

    /** The granted SAF tree the folder was reached through; held with a persistable grant. */
    @ColumnInfo(name = "tree_uri")
    val treeUri: String,

    /** The game folder itself, inside [treeUri]. */
    @ColumnInfo(name = "folder_doc_id")
    val folderDocId: String,

    /**
     * `steam_settings` — the write target for a generated kit. Null when the folder has a Steam
     * DLL but no settings folder yet: it still identifies, and the kit step creates the folder.
     */
    @ColumnInfo(name = "settings_dir_doc_id")
    val settingsDirDocId: String? = null,

    /** The folder holding `steam_settings` — the DLL folder, and the save-redirect base. */
    @ColumnInfo(name = "settings_parent_doc_id")
    val settingsParentDocId: String,

    /** The resolved `achievements.json`. Null means not played yet, never "no unlocks". */
    @ColumnInfo(name = "progress_doc_id")
    val progressDocId: String? = null,

    /** Whether the emu's own `steam_settings/achievements.json` schema exists. */
    @ColumnInfo(name = "has_schema")
    val hasSchema: Boolean = false,

    /** How the app id was established: `MARKER` / `STORED_IDENTITY` / `TITLE_MATCH`. */
    @ColumnInfo(name = "appid_source")
    val appIdSource: String,

    /** The last time the folder was successfully read. */
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
)
