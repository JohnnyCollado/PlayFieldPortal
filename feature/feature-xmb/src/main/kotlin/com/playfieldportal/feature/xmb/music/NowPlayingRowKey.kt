package com.playfieldportal.feature.xmb.music

/**
 * Identity of the Music root's "Now Playing" row, for deciding when that root must be rebuilt.
 *
 * [MusicPlayerController] re-publishes its state twice a second from the position ticker, so the
 * XMB cannot rebuild on every emission. It compares this key instead: null when nothing is loaded,
 * and otherwise everything the row actually renders — so it changes when the song changes (or when
 * a rescan gives the same file new metadata or art) and stays put across position ticks,
 * play/pause and seeks, which the row does not show.
 */
fun nowPlayingRowKey(state: MusicPlaybackState): String? =
    state.track?.let { "${it.id}|${it.displayTitle}|${it.artist}|${it.artUri}" }

/**
 * Identity of the media notification's *contents*, for the same reason: [MusicPlaybackService]
 * mirrors the same flow, and rebuilding a notification twice a second is work no one sees. On top
 * of the row's fields this carries what the notification shows and the row does not — the album,
 * the duration and the transport state.
 *
 * Position is deliberately absent. It moves on every tick but changes nothing the notification
 * draws: the posted `PlaybackState` carries the position and playback speed, and the system
 * extrapolates the seek bar from those between updates.
 */
fun nowPlayingNotificationKey(state: MusicPlaybackState): String =
    listOf(
        nowPlayingRowKey(state),
        state.track?.album,
        state.durationMs,
        state.isPlaying,
    ).joinToString("|")
