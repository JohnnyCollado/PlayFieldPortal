package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.feature.xmb.music.MusicPlaybackState

/**
 * What the fullscreen browser's now-playing strip shows: the loaded song, named, plus enough to draw
 * it — so a player dismissed with B is one tap away instead of a hunt back through the library.
 *
 * A snapshot rather than a live view of playback, deliberately: it is written from the same collector
 * that feeds `XMBUiState.musicPlayback`, and holds only what the strip renders, so the twice-a-second
 * position ticks never reach it and the strip never repaints while a song plays on.
 */
data class MusicBrowserNowPlaying(
    val title: String,
    val artist: String?,
    val artUri: String?,
    val isPlaying: Boolean,
)

/**
 * The strip's contents for a playback snapshot, or null when nothing is loaded — which is what hides
 * the strip entirely, rather than showing an empty bar over a browser with nothing to return to.
 *
 * Pure, so "which song, and is it playing" is pinned by a plain test with no ViewModel to build.
 * Anything derived from position is deliberately absent; see [MusicBrowserNowPlaying].
 */
internal fun browserNowPlaying(playback: MusicPlaybackState): MusicBrowserNowPlaying? =
    playback.track?.let { track ->
        MusicBrowserNowPlaying(
            title = track.displayTitle,
            artist = track.artist,
            artUri = track.artUri,
            isPlaying = playback.isPlaying,
        )
    }
