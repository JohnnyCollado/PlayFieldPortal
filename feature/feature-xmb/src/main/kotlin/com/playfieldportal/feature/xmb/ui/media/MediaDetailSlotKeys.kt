package com.playfieldportal.feature.xmb.ui.media

// Theme-kit icon slots (Media controls, Game Detail) for the screens' button glyphs. Kept pure so
// the pairing is unit-tested; the composables only look the key up.

/** A media transport button — one themeable `media_*` slot each. */
enum class TransportAction { PLAY, PAUSE, PREVIOUS, NEXT, BACK_10, FORWARD_10 }

fun transportSlotKey(action: TransportAction): String = when (action) {
    TransportAction.PLAY -> "media_play"
    TransportAction.PAUSE -> "media_pause"
    TransportAction.PREVIOUS -> "media_prev"
    TransportAction.NEXT -> "media_next"
    TransportAction.BACK_10 -> "media_back10"
    TransportAction.FORWARD_10 -> "media_fwd10"
}

/** The play/pause button shows the action pressing it performs. */
fun playPauseAction(isPlaying: Boolean): TransportAction =
    if (isPlaying) TransportAction.PAUSE else TransportAction.PLAY

/** A Game Detail action — one themeable `detail_*` slot each. */
enum class GameDetailAction { PLAY, FAVORITE, ARTWORK, MANUAL, MORE }

fun detailSlotKey(action: GameDetailAction): String = when (action) {
    GameDetailAction.PLAY -> "detail_play"
    GameDetailAction.FAVORITE -> "detail_favorite"
    GameDetailAction.ARTWORK -> "detail_artwork"
    GameDetailAction.MANUAL -> "detail_manual"
    GameDetailAction.MORE -> "detail_more"
}

/**
 * Plan A6: favorite is one slot for both states, so custom art can't swap between filled and
 * outline — the un-favorited state draws it dimmed instead.
 */
fun favoriteOverrideAlpha(isFavorite: Boolean): Float = if (isFavorite) 1f else 0.4f
