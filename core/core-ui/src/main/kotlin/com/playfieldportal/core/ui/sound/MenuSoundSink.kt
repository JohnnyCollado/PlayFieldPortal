package com.playfieldportal.core.ui.sound

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * An ambient one-shot menu-sound sink for Compose layers that navigate without a ViewModel of
 * their own.
 *
 * Nearly every navigable surface in the app routes its input through a ViewModel, which injects
 * [MenuSoundPlayer] directly — that stays the right shape and nothing here changes it. The
 * settings layer is the exception: its cursor, its clamps and its row activation all live in
 * composition ([com.playfieldportal.feature.settings.ui.SettingsScaffold] and the row families it
 * owns), shared by ~40 screens with 20-odd ViewModels between them. Injecting the player into all
 * of those to voice one cursor would be the wrong seam; the navigation lives in one place, so the
 * sound does too.
 *
 * Deliberately a narrow sink rather than the player itself: it hands composition exactly "play
 * this cue", with no mute, level, reload or preview surface, so a row can never grow its own audio
 * policy. [MenuSoundPlayer] remains the only thing that knows where a sample comes from.
 */
fun interface MenuSoundSink {
    fun play(sound: MenuSound)
}

/**
 * The ambient sink. Defaults to silence so Compose previews and UI tests compose the real row
 * families without a Hilt graph — a test that cares about sound provides a recording sink, and one
 * that does not hears nothing rather than crashing.
 *
 * Provided once, for the whole app, from MainActivity. `static` because the sink is a process-wide
 * singleton wrapper: it is written once at startup and never changes, so there is nothing for
 * Compose to gain by tracking reads of it.
 */
val LocalMenuSounds = staticCompositionLocalOf<MenuSoundSink> { MenuSoundSink { } }
