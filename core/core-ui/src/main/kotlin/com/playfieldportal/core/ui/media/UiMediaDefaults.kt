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
 * [bundledDefaultUri] hands ExoPlayer a URI for the one AUDIO_TRACK slot that has a default.
 *
 * [UiMediaSlot.BOOT_AUDIO] is the only AUDIO_TRACK slot with a default — that is what makes Boot
 * Sound customizable at all (before this, `bootAudioPath == null` meant silence). Video slots
 * deliberately return null forever: there is no bundled boot or GameBoot video and none should
 * be added.
 */
fun UiMediaSlot.bundledDefaultRes(): Int? = when (this) {
    UiMediaSlot.SOUND_SCROLL -> R.raw.sfx_cursor
    UiMediaSlot.SOUND_BACK -> R.raw.sfx_back
    UiMediaSlot.SOUND_CONFIRM -> R.raw.sfx_confirm
    UiMediaSlot.SOUND_ERROR -> R.raw.sfx_error
    UiMediaSlot.SOUND_LAUNCH -> R.raw.sfx_launch
    UiMediaSlot.SOUND_NOTIFICATION -> R.raw.sfx_notification
    UiMediaSlot.BOOT_AUDIO -> R.raw.sfx_opening
    UiMediaSlot.BOOT_VIDEO,
    UiMediaSlot.GAMEBOOT_VIDEO,
    UiMediaSlot.GAMEBOOT_AUDIO,
    -> null
}

/**
 * [UiMediaSlot.bundledDefaultRes] as a URI string ExoPlayer can open (the numeric resource-id
 * form its RawResourceDataSource resolves). Takes the package name rather than a [Context] so
 * the resolution stays testable in a plain JVM unit test; callers already hold a context.
 */
fun UiMediaSlot.bundledDefaultUri(packageName: String): String? =
    bundledDefaultRes()?.let { "android.resource://$packageName/$it" }

/**
 * What the Boot Sequence should actually play for audio, given the user's custom boot video,
 * their custom boot sound, and the slot's bundled default:
 *
 *  • a custom boot sound always wins, over both the clip's own track and the bundled chime;
 *  • a custom boot video with NO custom sound keeps its own audio track (null) — falling back to
 *    the bundled chime here would silently mute every custom boot video and play the opening
 *    under it, which is never what the user meant;
 *  • no custom media at all → the bundled opening chime, so Boot Sound ships audible.
 */
fun resolveBootAudio(
    customVideoPath: String?,
    customAudioPath: String?,
    bundledDefaultUri: String?,
): String? = customAudioPath ?: if (customVideoPath == null) bundledDefaultUri else null

/** Convenience overload for callers that already hold a [Context]. */
fun UiMediaSlot.bundledDefaultUri(context: Context): String? =
    bundledDefaultUri(context.packageName)
