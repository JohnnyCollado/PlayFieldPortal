package com.playfieldportal.feature.settings.ui

import androidx.annotation.StringRes
import com.playfieldportal.core.domain.model.AudioChannel
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.feature.settings.R

/**
 * Translatable names for the rows of Settings ▸ Sound.
 *
 * The domain enums live in a module with no Android resources, so they cannot name a string
 * resource themselves. The screen asks here instead: an enum value goes in, a resource id comes
 * out, and the text is resolved in the user's language at the point it is drawn. The enums'
 * own `displayName` stays as the English reference that `SoundTextTest` checks these against.
 */
@StringRes
internal fun AudioChannel.labelRes(): Int = when (this) {
    AudioChannel.NAVIGATION -> R.string.sound_channel_navigation
    AudioChannel.CATEGORY -> R.string.fb_sound_channel_category
    AudioChannel.OPTIONS -> R.string.fb_sound_channel_options
    AudioChannel.BACK -> R.string.sound_channel_back
    AudioChannel.CONFIRM -> R.string.sound_channel_confirm
    AudioChannel.ERROR -> R.string.sound_channel_error
    AudioChannel.NOTIFICATION -> R.string.sound_channel_notification
    AudioChannel.BOOT -> R.string.sound_channel_boot
    AudioChannel.GAMEBOOT -> R.string.sound_channel_gameboot
    AudioChannel.AMBIENCE -> R.string.sound_channel_ambience
}

/** The row name of an assignable sound, or null for a slot the Sound screen does not list. */
@StringRes
internal fun UiMediaSlot.soundLabelRes(): Int? = when (this) {
    UiMediaSlot.SOUND_SCROLL -> R.string.sound_slot_navigation
    UiMediaSlot.SOUND_CATEGORY -> R.string.fb_sound_slot_category
    UiMediaSlot.SOUND_OPTIONS -> R.string.fb_sound_slot_options
    UiMediaSlot.SOUND_BACK -> R.string.sound_slot_back
    UiMediaSlot.SOUND_CONFIRM -> R.string.sound_slot_confirm
    UiMediaSlot.SOUND_ERROR -> R.string.sound_slot_error
    UiMediaSlot.SOUND_NOTIFICATION -> R.string.sound_slot_notification
    UiMediaSlot.AMBIENCE_AUDIO -> R.string.sound_slot_ambience
    UiMediaSlot.BOOT_VIDEO, UiMediaSlot.GAMEBOOT_VIDEO -> null
}
