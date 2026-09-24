package com.playfieldportal.core.ui.media

import android.content.Context
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.R

/**
 * The bundled default sample for each customizable slot — the ONE mapping from slot to the
 * `res/raw` asset that plays when the user has not assigned anything.
 *
 * Both consumers hang off this single function so the mapping cannot drift between them:
 * [com.playfieldportal.core.ui.sound.MenuSoundPlayer] loads one SoundPool sample per DISTINCT
 * slot (Navigation's three events share `sfx_cursor` by construction, not by three copies), and
 * [bundledDefaultUri] hands ExoPlayer a URI for the same mapping when a screen auditions one.
 *
 * Only [UiMediaKind.SOUND] slots appear here. Neither presentation has an audio SLOT any more,
 * and ambience has one but no bundled file:
 * their built-in sounds are [bootDefaultAudioUri] and [gameBootDefaultAudioUri], which are not
 * user-assignable and so do not belong in this table. Video slots deliberately return null
 * forever: there is no bundled boot or GameBoot video and none should be added.
 */
fun UiMediaSlot.bundledDefaultRes(): Int? = when (this) {
    UiMediaSlot.SOUND_SCROLL -> R.raw.sfx_cursor
    UiMediaSlot.SOUND_BACK -> R.raw.sfx_back
    UiMediaSlot.SOUND_CONFIRM -> R.raw.sfx_confirm
    UiMediaSlot.SOUND_ERROR -> R.raw.sfx_error
    UiMediaSlot.SOUND_NOTIFICATION -> R.raw.sfx_notification
    UiMediaSlot.BOOT_VIDEO,
    UiMediaSlot.GAMEBOOT_VIDEO,
    // Ambience ships silent on purpose: an assignment is what turns the feature on, so a bundled
    // default would mean every install starts playing music nobody asked for.
    UiMediaSlot.AMBIENCE_AUDIO,
    -> null
}

/**
 * [UiMediaSlot.bundledDefaultRes] as a URI string ExoPlayer can open (the numeric resource-id
 * form its RawResourceDataSource resolves). Takes the package name rather than a [Context] so
 * the resolution stays testable in a plain JVM unit test; callers already hold a context.
 */
fun UiMediaSlot.bundledDefaultUri(packageName: String): String? =
    bundledDefaultRes()?.let { rawResourceUri(packageName, it) }

/** The `res/raw` URI form ExoPlayer's RawResourceDataSource resolves. */
private fun rawResourceUri(packageName: String, resId: Int): String =
    "android.resource://$packageName/$resId"

/**
 * The chime the built-in Boot Sequence plays — the bundled `sfx_opening` sample, which is what
 * makes the out-of-the-box boot audible.
 *
 * Deliberately NOT a [UiMediaSlot]: Boot Sequence is ONE thing the user replaces wholesale with
 * their own clip (which brings its own audio), so there is nothing here to assign separately.
 * Symmetric with [gameBootDefaultAudioUri] by design.
 */
fun bootDefaultAudioUri(packageName: String): String =
    rawResourceUri(packageName, R.raw.sfx_opening)

/**
 * The sound the built-in GameBoot sequence is drawn against — the bundled `sfx_launch` sample,
 * exactly 5.000 s, which is what feature-xmb's `GameBootSequence` timeline is beat-matched to
 * (core-ui cannot see that module, hence the prose reference). This is the ONLY thing that still
 * plays `sfx_launch`: the retired Launch Sound slot used to load the same 5-second sample into
 * SoundPool as a menu chirp, which is why every app launch used to be scored.
 *
 * Deliberately NOT a [UiMediaSlot]: GameBoot is ONE thing the user replaces wholesale with their
 * own clip (which brings its own audio), so there is nothing here to assign separately. That is
 * why this is a plain function rather than another row in [bundledDefaultRes].
 */
fun gameBootDefaultAudioUri(packageName: String): String =
    rawResourceUri(packageName, R.raw.sfx_launch)

/**
 * What the Boot Sequence should actually play for audio, given the user's custom boot video and
 * the built-in sequence's own chime:
 *
 *  • a custom boot video keeps its own audio track (null) — playing the bundled chime under
 *    someone's clip would score their video with a sound they never asked for;
 *  • no custom video → the bundled opening chime, so the boot ships audible.
 *
 * Two branches, no slot — identical in shape to [resolveGameBootAudio], because Boot Sequence and
 * GameBoot are the same bargain: replace the whole presentation, bring your own sound.
 */
fun resolveBootAudio(
    customVideoPath: String?,
    defaultUri: String?,
): String? = if (customVideoPath == null) defaultUri else null

/** Convenience overload for callers that already hold a [Context]. */
fun UiMediaSlot.bundledDefaultUri(context: Context): String? =
    bundledDefaultUri(context.packageName)

/**
 * What the GameBoot transition should actually play for audio, given the user's custom GameBoot
 * video and the built-in sequence's own sound:
 *
 *  • a custom GameBoot video keeps its own audio track (null) — playing the built-in sound under
 *    someone's clip would score their video with a sound they never asked for;
 *  • no custom video → the built-in sequence plays with the sound it was timed against.
 *
 * Two branches, no slot: there is no separate GameBoot sound to assign, which is the whole point
 * of GameBoot being one replaceable thing.
 */
fun resolveGameBootAudio(
    customVideoPath: String?,
    defaultUri: String?,
): String? = if (customVideoPath == null) defaultUri else null

/** Convenience overload for callers that already hold a [Context]. */
fun gameBootDefaultAudioUri(context: Context): String = gameBootDefaultAudioUri(context.packageName)

/** Convenience overload for callers that already hold a [Context]. */
fun bootDefaultAudioUri(context: Context): String = bootDefaultAudioUri(context.packageName)
