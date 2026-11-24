package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.Playlist
import com.playfieldportal.core.domain.model.VideoPlaylist
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.xmb.R

// The rows of the two playlist lists (music, video) and the browser menu entry that open the system
// picker for a playlist file. Pure, so the order and the ids are pinned by a test.

internal const val IMPORT_PLAYLIST_ITEM_ID = "import_playlist"
internal const val IMPORT_VIDEO_PLAYLIST_ITEM_ID = "import_video_playlist"
internal const val BROWSER_IMPORT_MENU_ID = "music_browser_import"

/** Playlist list: one row per playlist, then "Create Playlist" and "Import Playlist". */
internal fun playlistRootItems(playlists: List<Playlist>): List<XMBItem> {
    val rows = playlists.map { pl ->
        XMBItem(
            id         = "pl_${pl.id}",
            title      = pl.name,
            subtitle   = "",
            playlistId = pl.id,
            type       = XMBItemType.PLAYLIST,
        )
    }
    return rows + listOf(
        XMBItem(
            id       = XMBViewModel.CREATE_PLAYLIST_ITEM_ID,
            titleText    = UiText.res(R.string.xmbm_playlist_create_title),
            subtitleText = UiText.res(R.string.xmbm_playlist_create_music_sub),
            type     = XMBItemType.ADD_ACTION,
        ),
        importItem(IMPORT_PLAYLIST_ITEM_ID),
    )
}

/** The video twin of [playlistRootItems]. */
internal fun videoPlaylistItems(playlists: List<VideoPlaylist>): List<XMBItem> {
    val rows = playlists.map { pl ->
        XMBItem(
            id         = "vpl_${pl.id}",
            title      = pl.name,
            subtitle   = "",
            playlistId = pl.id,
            type       = XMBItemType.PLAYLIST,
        )
    }
    return rows + listOf(
        XMBItem(
            id       = XMBViewModel.CREATE_VIDEO_PLAYLIST_ITEM_ID,
            titleText    = UiText.res(R.string.xmbm_playlist_create_title),
            subtitleText = UiText.res(R.string.xmbm_playlist_create_video_sub),
            type     = XMBItemType.ADD_ACTION,
        ),
        importItem(IMPORT_VIDEO_PLAYLIST_ITEM_ID),
    )
}

private fun importItem(id: String) = XMBItem(
    id       = id,
    titleText    = UiText.res(R.string.xmbm_playlist_import_title),
    subtitleText = UiText.res(R.string.xmbm_playlist_import_sub),
    type     = XMBItemType.ADD_ACTION,
)

/** The list-menu entry that imports a playlist: only the Playlists list has one to offer. */
internal fun browserImportMenuItems(view: MusicBrowserView?): List<XMBContextMenuItem> =
    if (view == MusicBrowserView.Playlists) {
        listOf(XMBContextMenuItem(BROWSER_IMPORT_MENU_ID, UiText.res(R.string.xmbm_playlist_import_title)))
    } else emptyList()
