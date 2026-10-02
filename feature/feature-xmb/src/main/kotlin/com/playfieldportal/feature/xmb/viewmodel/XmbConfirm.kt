package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.feature.appbar.AppMenuIds

/**
 * A question the XMB asks before an action from a context menu goes ahead. It is drawn by the
 * shell's shared confirm modal, which opens on Cancel, so no kind is ever answered by a stray press.
 */
sealed interface XmbConfirm {
    /** Take a game out of the library (its file stays). */
    data class RemoveGame(val gameId: Long, val title: String) : XmbConfirm

    /** Take a game whose file is already gone out of the library, with nothing left to restore. */
    data class RemoveMissing(val gameId: Long, val title: String) : XmbConfirm

    /** Delete a custom card; its games stay in the library. */
    data class DeleteCard(val collectionId: Long, val title: String) : XmbConfirm

    /** Take a game out of a category that also holds it through custom cards ([cardNames]). */
    data class RemoveFromCategory(
        val gameId: Long,
        val categoryId: String,
        val categoryName: String,
        val title: String,
        val cardNames: List<String>,
    ) : XmbConfirm

    /** Take an Android-library game out of the library (the app stays installed). */
    data class RemoveAndroidGame(val gameId: Long, val title: String) : XmbConfirm

    /** Remove a console's Memory Card; its games leave the library with it, its ROM files stay. */
    data class RemoveCard(val platformId: String, val title: String) : XmbConfirm

    /** Take a track, video or photo out of its library (the file stays). */
    data class RemoveTrack(val trackId: String, val title: String) : XmbConfirm
    data class RemoveVideo(val videoId: String, val title: String) : XmbConfirm
    data class RemovePhoto(val photoId: String, val title: String) : XmbConfirm

    /** Delete a playlist; what it held stays in the library. */
    data class DeleteMusicPlaylist(val playlistId: Long, val title: String) : XmbConfirm
    data class DeleteVideoPlaylist(val playlistId: Long, val title: String) : XmbConfirm

    /** Uninstall an app from the device; Android asks once more on its own dialog. */
    data class Uninstall(val packageName: String, val title: String) : XmbConfirm

    /** Empty the notification history. */
    data object ClearNotifications : XmbConfirm
}

/** What the confirm modal shows for one [XmbConfirm]. */
data class XmbConfirmCopy(
    val title: String,
    val message: String,
    val confirmLabel: String,
    /** Drawn red. Every kind opens on Cancel regardless; Remove from Category is the one not red. */
    val destructive: Boolean,
)

/** The words for [confirm]: what is lost, and what is explicitly safe. */
fun xmbConfirmCopy(confirm: XmbConfirm): XmbConfirmCopy = when (confirm) {
    is XmbConfirm.RemoveGame -> XmbConfirmCopy(
        title = "Remove \"${confirm.title}\" from Library?",
        message = "The file is not deleted. A later scan adds it back if it is still in a scanned folder.",
        confirmLabel = "Remove",
        destructive = true,
    )
    // The file is already gone, so there is no "put it back" once the entry goes too.
    is XmbConfirm.RemoveMissing -> XmbConfirmCopy(
        title = "Permanently remove \"${confirm.title}\"?",
        message = "Its file is already gone, so this entry cannot be restored.",
        confirmLabel = "Remove permanently",
        destructive = true,
    )
    // A console card's Remove takes its games with it; this must never read as that.
    is XmbConfirm.DeleteCard -> XmbConfirmCopy(
        title = "Delete \"${confirm.title}\"?",
        message = "Its games stay in your library.",
        confirmLabel = "Delete Custom Card",
        destructive = true,
    )
    // Leaving a category drops the game from its custom cards too, which Hidden Items cannot
    // restore. Not red: the removal itself is reversible, the prompt is about the cards.
    is XmbConfirm.RemoveFromCategory -> XmbConfirmCopy(
        title = "Remove from ${confirm.categoryName}?",
        message = "\"${confirm.title}\" also leaves: ${confirm.cardNames.joinToString(", ")}.",
        confirmLabel = "Remove",
        destructive = false,
    )
    is XmbConfirm.RemoveAndroidGame -> XmbConfirmCopy(
        title = "Remove \"${confirm.title}\" from Library?",
        message = "The app stays installed. Find Games can add it back.",
        confirmLabel = "Remove",
        destructive = true,
    )
    is XmbConfirm.RemoveCard -> XmbConfirmCopy(
        title = "Remove \"${confirm.title}\" card?",
        message = "Its games leave your library too. ROM files stay on disk, and a scan can bring them back.",
        confirmLabel = "Remove Card",
        destructive = true,
    )
    is XmbConfirm.RemoveTrack -> removeFromLibraryCopy(confirm.title, "a music folder")
    is XmbConfirm.RemoveVideo -> removeFromLibraryCopy(confirm.title, "a video library")
    is XmbConfirm.RemovePhoto -> removeFromLibraryCopy(confirm.title, "an album")
    is XmbConfirm.DeleteMusicPlaylist -> deletePlaylistCopy(confirm.title, "tracks")
    is XmbConfirm.DeleteVideoPlaylist -> deletePlaylistCopy(confirm.title, "videos")
    is XmbConfirm.Uninstall -> XmbConfirmCopy(
        title = "Uninstall \"${confirm.title}\"?",
        message = "The app and its data are removed from this device.",
        confirmLabel = "Uninstall",
        destructive = true,
    )
    XmbConfirm.ClearNotifications -> XmbConfirmCopy(
        title = "Clear all notifications?",
        message = "Running tasks are not affected.",
        confirmLabel = "Clear All",
        destructive = true,
    )
}

private fun removeFromLibraryCopy(title: String, scannedPlace: String) = XmbConfirmCopy(
    title = "Remove \"$title\" from Library?",
    message = "The file is not deleted. A later scan adds it back if it is still in $scannedPlace.",
    confirmLabel = "Remove",
    destructive = true,
)

private fun deletePlaylistCopy(title: String, items: String) = XmbConfirmCopy(
    title = "Delete \"$title\"?",
    message = "Its $items stay in your library.",
    confirmLabel = "Delete Playlist",
    destructive = true,
)

/**
 * The confirm that [itemId] on [menu] must raise before it runs, or null when the row is safe to
 * run on one press. Mirrors the dispatch order in the ViewModel, so a shared id ("remove" is a
 * console card's Remove Card and an app's Remove From Category) resolves by the menu it is on.
 * Remove From Category is not here: it only asks when the game also sits on custom cards, which
 * needs a repository read.
 */
internal fun confirmFor(menu: XMBContextMenu, itemId: String): XmbConfirm? = when {
    // An app row's own id, so it resolves before any menu that also carries a game or card id.
    itemId == AppMenuIds.UNINSTALL && menu.packageName != null -> XmbConfirm.Uninstall(menu.packageName, menu.title)
    menu.videoFileId != null ->
        if (itemId == "video_remove") XmbConfirm.RemoveVideo(menu.videoFileId, menu.title) else null
    menu.photoFileId != null ->
        if (itemId == "photo_remove") XmbConfirm.RemovePhoto(menu.photoFileId, menu.title) else null
    menu.videoPlaylistId != null ->
        if (itemId == "delete_video_playlist") XmbConfirm.DeleteVideoPlaylist(menu.videoPlaylistId, menu.title) else null
    menu.notificationListMenu ->
        if (itemId == NotificationMenuIds.CLEAR_ALL) XmbConfirm.ClearNotifications else null
    menu.playlistId != null && menu.musicTrackId == null ->
        if (itemId == "delete_playlist") XmbConfirm.DeleteMusicPlaylist(menu.playlistId, menu.title) else null
    menu.collectionRowId != null ->
        if (itemId == "delete_collection") XmbConfirm.DeleteCard(menu.collectionRowId, menu.title) else null
    menu.musicTrackId != null ->
        if (itemId == "remove_track") XmbConfirm.RemoveTrack(menu.musicTrackId, menu.title) else null
    menu.platformId != null ->
        if (itemId == "remove") XmbConfirm.RemoveCard(menu.platformId, menu.title) else null
    menu.gameId != null -> when (itemId) {
        "remove_game" -> XmbConfirm.RemoveGame(menu.gameId, menu.title)
        "remove_missing" -> XmbConfirm.RemoveMissing(menu.gameId, menu.title)
        "remove_app" -> XmbConfirm.RemoveAndroidGame(menu.gameId, menu.title)
        else -> null
    }
    else -> null
}
