package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.appbar.AppMenuIds
import com.playfieldportal.feature.xmb.R

/**
 * A question the XMB asks before an action from a context menu goes ahead. It is drawn by the
 * shell's shared confirm modal, which opens on Cancel, so no kind is ever answered by a stray press.
 */
sealed interface XmbConfirm {
    /** Take a game out of the library (its file stays). */
    data class RemoveGame(val gameId: Long, val title: UiText) : XmbConfirm

    /** Take a game whose file is already gone out of the library, with nothing left to restore. */
    data class RemoveMissing(val gameId: Long, val title: UiText) : XmbConfirm

    /** Delete a custom card; its games stay in the library. */
    data class DeleteCard(val collectionId: Long, val title: UiText) : XmbConfirm

    /** Take a game out of a category that also holds it through custom cards ([cardNames]). */
    data class RemoveFromCategory(
        val gameId: Long,
        val categoryId: String,
        val categoryName: String,
        val title: UiText,
        val cardNames: List<String>,
    ) : XmbConfirm

    /** Take an app-style row (a standard app, or one unmarked as a game) out of the library. */
    data class RemoveAppRow(val gameId: Long, val title: UiText) : XmbConfirm

    /** Take an Android-library game out of the library (the app stays installed). */
    data class RemoveAndroidGame(val gameId: Long, val title: UiText) : XmbConfirm

    /** Remove a console's Memory Card; its games leave the library with it, its ROM files stay. */
    data class RemoveCard(val platformId: String, val title: UiText) : XmbConfirm

    /** Take a track, video or photo out of its library (the file stays). */
    data class RemoveTrack(val trackId: String, val title: UiText) : XmbConfirm
    data class RemoveVideo(val videoId: String, val title: UiText) : XmbConfirm
    data class RemovePhoto(val photoId: String, val title: UiText) : XmbConfirm

    /** Delete a playlist; what it held stays in the library. */
    data class DeleteMusicPlaylist(val playlistId: Long, val title: UiText) : XmbConfirm
    data class DeleteVideoPlaylist(val playlistId: Long, val title: UiText) : XmbConfirm

    /** Uninstall an app from the device; Android asks once more on its own dialog. */
    data class Uninstall(val packageName: String, val title: UiText) : XmbConfirm

    /** Empty the notification history. */
    data object ClearNotifications : XmbConfirm
}

/** What the confirm modal shows for one [XmbConfirm]. */
data class XmbConfirmCopy(
    val title: UiText,
    val message: UiText,
    val confirmLabel: UiText,
    /** Drawn red. Every kind opens on Cancel regardless; Remove from Category is the one not red. */
    val destructive: Boolean,
)

/** The words for [confirm]: what is lost, and what is explicitly safe. */
fun xmbConfirmCopy(confirm: XmbConfirm): XmbConfirmCopy = when (confirm) {
    is XmbConfirm.RemoveGame -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_remove_library_title, confirm.title),
        message = UiText.res(R.string.xmbm_confirm_remove_game_msg),
        confirmLabel = UiText.res(R.string.xmbm_confirm_remove),
        destructive = true,
    )
    // The file is already gone, so there is no "put it back" once the entry goes too.
    is XmbConfirm.RemoveMissing -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_remove_missing_title, confirm.title),
        message = UiText.res(R.string.xmbm_confirm_remove_missing_msg),
        confirmLabel = UiText.res(R.string.xmbm_confirm_remove_permanently),
        destructive = true,
    )
    // A console card's Remove takes its games with it; this must never read as that.
    is XmbConfirm.DeleteCard -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_delete_card_title, confirm.title),
        message = UiText.res(R.string.xmbm_confirm_delete_card_msg),
        confirmLabel = UiText.res(R.string.xmbm_confirm_delete_card_button),
        destructive = true,
    )
    // Leaving a category drops the game from its custom cards too, which Hidden Items cannot
    // restore. Not red: the removal itself is reversible, the prompt is about the cards.
    is XmbConfirm.RemoveFromCategory -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_remove_category_title, confirm.categoryName),
        message = UiText.res(
            R.string.xmbm_confirm_remove_category_msg,
            confirm.title,
            UiText.Joined(confirm.cardNames.map(UiText::raw), ", "),
        ),
        confirmLabel = UiText.res(R.string.xmbm_confirm_remove),
        destructive = false,
    )
    is XmbConfirm.RemoveAndroidGame -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_remove_library_title, confirm.title),
        message = UiText.res(R.string.xmbm_confirm_remove_android_msg),
        confirmLabel = UiText.res(R.string.xmbm_confirm_remove),
        destructive = true,
    )
    // The Android card holds installed apps — no ROM files, no scan (survey U27).
    is XmbConfirm.RemoveCard -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_remove_card_title, confirm.title),
        message = if (confirm.platformId == XMBViewModel.ANDROID_PLATFORM_ID) {
            UiText.res(R.string.xmbm_confirm_remove_card_android_msg)
        } else {
            UiText.res(R.string.xmbm_confirm_remove_card_msg)
        },
        confirmLabel = UiText.res(R.string.xmbm_confirm_remove_card_button),
        destructive = true,
    )
    // An app row has no file and no scan to bring it back (survey U28).
    is XmbConfirm.RemoveAppRow -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_remove_library_title, confirm.title),
        message = UiText.res(R.string.xmbm_confirm_remove_app_row_msg),
        confirmLabel = UiText.res(R.string.xmbm_confirm_remove),
        destructive = true,
    )
    is XmbConfirm.RemoveTrack -> removeFromLibraryCopy(confirm.title, R.string.xmbm_confirm_remove_track_msg)
    is XmbConfirm.RemoveVideo -> removeFromLibraryCopy(confirm.title, R.string.xmbm_confirm_remove_video_msg)
    is XmbConfirm.RemovePhoto -> removeFromLibraryCopy(confirm.title, R.string.xmbm_confirm_remove_photo_msg)
    is XmbConfirm.DeleteMusicPlaylist -> deletePlaylistCopy(confirm.title, R.string.xmbm_confirm_delete_music_playlist_msg)
    is XmbConfirm.DeleteVideoPlaylist -> deletePlaylistCopy(confirm.title, R.string.xmbm_confirm_delete_video_playlist_msg)
    is XmbConfirm.Uninstall -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_uninstall_title, confirm.title),
        message = UiText.res(R.string.xmbm_confirm_uninstall_msg),
        confirmLabel = UiText.res(R.string.xmbm_confirm_uninstall_button),
        destructive = true,
    )
    XmbConfirm.ClearNotifications -> XmbConfirmCopy(
        title = UiText.res(R.string.xmbm_confirm_clear_notifs_title),
        message = UiText.res(R.string.xmbm_confirm_clear_notifs_msg),
        confirmLabel = UiText.res(R.string.xmbm_confirm_clear_all_button),
        destructive = true,
    )
}

private fun removeFromLibraryCopy(title: UiText, message: Int) = XmbConfirmCopy(
    title = UiText.res(R.string.xmbm_confirm_remove_library_title, title),
    message = UiText.res(message),
    confirmLabel = UiText.res(R.string.xmbm_confirm_remove),
    destructive = true,
)

private fun deletePlaylistCopy(title: UiText, message: Int) = XmbConfirmCopy(
    title = UiText.res(R.string.xmbm_confirm_delete_card_title, title),
    message = UiText.res(message),
    confirmLabel = UiText.res(R.string.xmbm_confirm_delete_playlist_button),
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
        "remove_app_row" -> XmbConfirm.RemoveAppRow(menu.gameId, menu.title)
        else -> null
    }
    else -> null
}
