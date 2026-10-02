package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.MusicTrack
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
    if (byTouch) add(XMBContextMenuItem("play", "Play"))
    add(XMBContextMenuItem("play_background", "Play in Background"))
    add(XMBContextMenuItem("add_to_playlist", "Add to Playlist", opensMenu = true))
    // Reversible (the track stays in the library), so not red and no confirm.
    if (inPlaylist) add(XMBContextMenuItem("remove_from_playlist", "Remove from this Playlist"))
    add(XMBContextMenuItem("track_info", "View Information"))
    add(XMBContextMenuItem("remove_track", "Remove from Library", isDestructive = true))
}

/** A music playlist row. */
internal fun musicPlaylistMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("open_playlist", "Open"))
    add(XMBContextMenuItem("add_tracks", "Add Tracks"))
    add(XMBContextMenuItem("rename_playlist", "Rename Playlist"))
    add(XMBContextMenuItem("delete_playlist", "Delete Playlist", isDestructive = true))
}

/**
 * A video file. The one repeat row is "View Details" (Play, Resume and Details all opened the same
 * screen). Favorite is one silent row whose value says the state.
 */
internal fun videoFileMenuItems(isFavorite: Boolean, inPlaylist: Boolean, byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("video_details", "View Details"))
    add(XMBContextMenuItem("video_favorite", "Favorite", value = if (isFavorite) "On" else "Off", silent = true))
    add(XMBContextMenuItem("video_add_playlist", "Add to Playlist", opensMenu = true))
    if (inPlaylist) add(XMBContextMenuItem("video_remove_playlist", "Remove from this Playlist"))
    add(XMBContextMenuItem("video_remove", "Remove from Library", isDestructive = true))
}

/** A video playlist row. */
internal fun videoPlaylistMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("open_video_playlist", "Open"))
    add(XMBContextMenuItem("rename_video_playlist", "Rename Playlist"))
    add(XMBContextMenuItem("delete_video_playlist", "Delete Playlist", isDestructive = true))
}

/** A video library card. */
internal fun videoLibraryMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("video_lib_open", "Open"))
    add(XMBContextMenuItem("video_lib_scan", "Scan Library"))
    add(XMBContextMenuItem("video_lib_manage", "Manage in Settings"))
}

/**
 * A photo row. Viewing options (zoom, rotate) live in the viewer's own menu; the list row opens
 * (touch only), sets the wallpaper, or removes.
 */
internal fun photoFileMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("photo_open", "Open"))
    add(XMBContextMenuItem("photo_set_wallpaper", "Set as Launcher Wallpaper"))
    add(XMBContextMenuItem("photo_remove", "Remove from Library", isDestructive = true))
}

/** A photo Album card. */
internal fun photoAlbumMenuItems(byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("photo_lib_open", "Open"))
    add(XMBContextMenuItem("photo_lib_scan", "Scan Album"))
    add(XMBContextMenuItem("photo_lib_manage", "Manage in Settings"))
}

/**
 * A section's memory card ("Music" / "Videos" / "Photos"): section-wide actions only. There is no
 * Open row at all, since the press that opens this menu already opens the card.
 */
internal fun mediaCardMenuItems(sectionNoun: String): List<XMBContextMenuItem> = listOf(
    XMBContextMenuItem("media_card_scan", "Scan $sectionNoun"),
    XMBContextMenuItem("media_card_manage", "Manage in Settings"),
)

/**
 * What is known about a track, one fact per line (the video and photo viewers' View Information
 * shape); unknown values are left out.
 */
internal fun musicTrackInfoLines(track: MusicTrack): List<String> = buildList {
    track.artist?.let { add("Artist: $it") }
    track.album?.let { add("Album: $it") }
    track.durationMs?.let {
        val sec = it / 1000
        add("Duration: ${if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)}")
    }
    track.mimeType?.let { add("Type: $it") }
    track.sizeBytes?.let { add("Size: ${formatBytes(it)}") }
    track.relativePath?.let { add("Location: $it") }
    add("File: ${track.displayName}")
}

/** The Music root's Now Playing row: toggle playback, the visualizer picker, or stop. */
internal fun nowPlayingMenuItems(isPlaying: Boolean): List<XMBContextMenuItem> = listOf(
    XMBContextMenuItem("music_playpause", if (isPlaying) "Pause" else "Resume"),
    XMBContextMenuItem("music_visualizer", "Visualizer"),
    XMBContextMenuItem("music_close", "Stop and Close"),
)

/** The full-screen music player's options. */
internal fun musicPlayerOptionsMenuItems(): List<XMBContextMenuItem> = listOf(
    XMBContextMenuItem("music_visualizer", "Visualizer"),
    XMBContextMenuItem("music_background", "Play in Background"),
    XMBContextMenuItem("music_close", "Stop and Close"),
)
