package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.MusicTrack
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.xmb.R
import com.playfieldportal.feature.xmb.ui.detail.formatBytes

/**
 * The XMB media rows' context menus, from the state they depend on. Kept out of [XMBViewModel] so
 * their contents can be pinned without building it; the `openXxxContextMenu` openers supply the
 * state and open the menu. Ids are the handlers' keys (and `confirmFor`'s) and do not change with
 * the wording.
 *
 * [byTouch] is true for a long-press. A menu opened with the controller is opened from the row's
 * own press, so it leaves out the row that repeats that press (Play, Open, View Details); a
 * long-press has no such press behind it and keeps exactly one, first.
 */

/**
 * A music track: Play (touch), Play in Background, playlist rows, View Information, Remove from
 * Library last.
 */
internal fun musicTrackMenuItems(inPlaylist: Boolean, byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("play", UiText.res(R.string.xmbm_play)))
    add(XMBContextMenuItem("play_background", UiText.res(R.string.xmbm_play_in_background)))
    add(XMBContextMenuItem("add_to_playlist", UiText.res(R.string.xmbm_add_to_playlist), opensMenu = true))
    // Reversible (the track stays in the library), so not red and no confirm.
    if (inPlaylist) add(XMBContextMenuItem("remove_from_playlist", UiText.res(R.string.xmbm_remove_from_this_playlist)))
    add(XMBContextMenuItem("track_info", UiText.res(R.string.xmbm_media_view_information)))
    add(XMBContextMenuItem("remove_track", UiText.res(R.string.xmbm_remove_from_library), isDestructive = true))
}

/** A music playlist row. */
internal fun musicPlaylistMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("open_playlist", UiText.res(R.string.xmbm_open)))
    add(XMBContextMenuItem("add_tracks", UiText.res(R.string.xmbm_media_add_tracks)))
    add(XMBContextMenuItem("rename_playlist", UiText.res(R.string.xmbm_rename_playlist)))
    add(XMBContextMenuItem("delete_playlist", UiText.res(R.string.xmbm_delete_playlist), isDestructive = true))
}

/**
 * A video file. The one repeat row is "View Details" (Play, Resume and Details all opened the same
 * screen). Favorite is one silent row whose value says the state.
 */
internal fun videoFileMenuItems(isFavorite: Boolean, inPlaylist: Boolean, byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("video_details", UiText.res(R.string.xmbm_media_view_details)))
    add(XMBContextMenuItem("video_favorite", UiText.res(R.string.xmbm_favorite), value = onOff(isFavorite), silent = true))
    add(XMBContextMenuItem("video_add_playlist", UiText.res(R.string.xmbm_add_to_playlist), opensMenu = true))
    if (inPlaylist) add(XMBContextMenuItem("video_remove_playlist", UiText.res(R.string.xmbm_remove_from_this_playlist)))
    add(XMBContextMenuItem("video_remove", UiText.res(R.string.xmbm_remove_from_library), isDestructive = true))
}

/** A video playlist row. */
internal fun videoPlaylistMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("open_video_playlist", UiText.res(R.string.xmbm_open)))
    add(XMBContextMenuItem("rename_video_playlist", UiText.res(R.string.xmbm_rename_playlist)))
    add(XMBContextMenuItem("delete_video_playlist", UiText.res(R.string.xmbm_delete_playlist), isDestructive = true))
}

/** A video library card. */
internal fun videoLibraryMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("video_lib_open", UiText.res(R.string.xmbm_open)))
    add(XMBContextMenuItem("video_lib_scan", UiText.res(R.string.xmbm_media_scan_library)))
    add(XMBContextMenuItem("video_lib_manage", UiText.res(R.string.xmbm_manage_in_settings)))
}

/**
 * A photo row. Viewing options (zoom, rotate) live in the viewer's own menu; the list row opens
 * (touch only), sets the wallpaper or the device lock screen, or removes.
 */
internal fun photoFileMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("photo_open", UiText.res(R.string.xmbm_open)))
    add(XMBContextMenuItem("photo_set_wallpaper", UiText.res(R.string.xmbm_media_set_wallpaper)))
    add(XMBContextMenuItem("photo_set_lockscreen", UiText.res(R.string.xmbm_media_set_lockscreen)))
    add(XMBContextMenuItem("photo_remove", UiText.res(R.string.xmbm_remove_from_library), isDestructive = true))
}

/** A photo Album card. */
internal fun photoAlbumMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("photo_lib_open", UiText.res(R.string.xmbm_open)))
    add(XMBContextMenuItem("photo_lib_scan", UiText.res(R.string.xmbm_media_scan_album)))
    add(XMBContextMenuItem("photo_lib_manage", UiText.res(R.string.xmbm_manage_in_settings)))
}

/**
 * A section's memory card ("Music" / "Videos" / "Photos"): section-wide actions only. There is no
 * Open row at all, since the press that opens this menu already opens the card.
 */
internal fun mediaCardMenuItems(sectionNoun: UiText): List<XMBContextMenuItem> = listOf(
    XMBContextMenuItem("media_card_scan", UiText.res(R.string.xmbm_media_scan_section, sectionNoun)),
    XMBContextMenuItem("media_card_manage", UiText.res(R.string.xmbm_manage_in_settings)),
)

/**
 * What is known about a track, one fact per line (the video and photo viewers' View Information
 * shape); unknown values are left out.
 */
internal fun musicTrackInfoLines(track: MusicTrack): List<UiText> = buildList {
    track.artist?.let { add(UiText.res(R.string.xmbm_info_artist, it)) }
    track.album?.let { add(UiText.res(R.string.xmbm_info_album, it)) }
    track.durationMs?.let {
        val sec = it / 1000
        val clock = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
        add(UiText.res(R.string.xmbm_info_duration, clock))
    }
    track.mimeType?.let { add(UiText.res(R.string.xmbm_info_type, it)) }
    track.sizeBytes?.let { add(UiText.res(R.string.xmbm_info_size, formatBytes(it))) }
    track.relativePath?.let { add(UiText.res(R.string.xmbm_info_location, it)) }
    add(UiText.res(R.string.xmbm_info_file, track.displayName))
}

/** The Music root's Now Playing row: toggle playback, the visualizer picker, or stop. */
internal fun nowPlayingMenuItems(isPlaying: Boolean): List<XMBContextMenuItem> = listOf(
    XMBContextMenuItem("music_playpause", UiText.res(if (isPlaying) R.string.xmbm_pause else R.string.xmbm_resume)),
    XMBContextMenuItem("music_visualizer", UiText.res(R.string.xmbm_visualizer)),
    XMBContextMenuItem("music_close", UiText.res(R.string.xmbm_stop_and_close)),
)

/** The full-screen music player's options. */
internal fun musicPlayerOptionsMenuItems(): List<XMBContextMenuItem> = listOf(
    XMBContextMenuItem("music_visualizer", UiText.res(R.string.xmbm_visualizer)),
    XMBContextMenuItem("music_background", UiText.res(R.string.xmbm_play_in_background)),
    XMBContextMenuItem("music_close", UiText.res(R.string.xmbm_stop_and_close)),
)
