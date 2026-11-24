package com.playfieldportal.core.domain.text

import android.view.KeyEvent
import com.playfieldportal.core.domain.R
import com.playfieldportal.core.domain.achievement.ShibaRank
import com.playfieldportal.core.domain.model.AudioChannel
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.domain.model.IconLegibilityStyle
import com.playfieldportal.core.domain.model.ImageMotion
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ScrollSpeed
import com.playfieldportal.core.domain.model.TextLegibilityStyle
import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.model.UmdSlotMode
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.domain.model.XmbColorScheme
import com.playfieldportal.core.domain.model.XmbListMotion
import com.playfieldportal.core.domain.model.appupdate.AppEdition

/*
 * The text a user reads for each domain enum, as string resources.
 *
 * The enums keep their English `label` / `displayName` / `displayLabel()` for persistence, logs and
 * the tests that pin them; the UI reads these instead so the words follow the app's language. Each
 * resource resolves to exactly that English (DomainLabelsTest checks it). Use
 * `stringResource(x.labelRes)` in a composable, `UiText.res(x.labelRes)` elsewhere.
 */

val ShibaRank.labelRes: Int
    get() = when (this) {
        ShibaRank.PUP -> R.string.dom_shiba_rank_pup
        ShibaRank.RUFFIAN -> R.string.dom_shiba_rank_ruffian
        ShibaRank.LONE_RON_INU -> R.string.dom_shiba_rank_lone_ron_inu
        ShibaRank.INU_MASTER -> R.string.dom_shiba_rank_inu_master
        ShibaRank.LEGENDARY_HACHIKO -> R.string.dom_shiba_rank_legendary_hachiko
    }

val AppEdition.labelRes: Int
    get() = when (this) {
        AppEdition.FULL -> R.string.dom_edition_full
        AppEdition.LITE -> R.string.dom_edition_lite
    }

val AudioChannel.labelRes: Int
    get() = when (this) {
        AudioChannel.NAVIGATION -> R.string.dom_audio_channel_navigation
        AudioChannel.CATEGORY -> R.string.dom_audio_channel_category
        AudioChannel.OPTIONS -> R.string.dom_audio_channel_options
        AudioChannel.BACK -> R.string.dom_audio_channel_back
        AudioChannel.CONFIRM -> R.string.dom_audio_channel_confirm
        AudioChannel.ERROR -> R.string.dom_audio_channel_error
        AudioChannel.NOTIFICATION -> R.string.dom_audio_channel_notification
        AudioChannel.BOOT -> R.string.dom_audio_channel_boot
        AudioChannel.GAMEBOOT -> R.string.dom_audio_channel_gameboot
        AudioChannel.AMBIENCE -> R.string.dom_audio_channel_ambience
    }

val UiMediaSlot.labelRes: Int
    get() = when (this) {
        UiMediaSlot.SOUND_SCROLL -> R.string.dom_media_slot_navigation
        UiMediaSlot.SOUND_CATEGORY -> R.string.dom_media_slot_category
        UiMediaSlot.SOUND_OPTIONS -> R.string.dom_media_slot_options
        UiMediaSlot.SOUND_BACK -> R.string.dom_media_slot_back
        UiMediaSlot.SOUND_CONFIRM -> R.string.dom_media_slot_confirm
        UiMediaSlot.SOUND_ERROR -> R.string.dom_media_slot_error
        UiMediaSlot.SOUND_NOTIFICATION -> R.string.dom_media_slot_notification
        UiMediaSlot.BOOT_VIDEO -> R.string.dom_media_slot_boot_video
        UiMediaSlot.GAMEBOOT_VIDEO -> R.string.dom_media_slot_gameboot_video
        UiMediaSlot.AMBIENCE_AUDIO -> R.string.dom_media_slot_ambience_audio
    }

val IconDisplayMode.labelRes: Int
    get() = when (this) {
        IconDisplayMode.ICON0 -> R.string.dom_icon_display_icon0
        IconDisplayMode.BOX_ART -> R.string.dom_icon_display_box_art
        IconDisplayMode.PHYSICAL_MEDIA -> R.string.dom_icon_display_physical_media
        IconDisplayMode.BOX_3D -> R.string.dom_icon_display_box_3d
    }

val IconLegibilityStyle.labelRes: Int
    get() = when (this) {
        IconLegibilityStyle.NONE -> R.string.dom_icon_legibility_none
        IconLegibilityStyle.OFFSET_SHADOW -> R.string.dom_icon_legibility_offset_shadow
        IconLegibilityStyle.OFFSET_SHADOW_LIGHT -> R.string.dom_icon_legibility_offset_shadow_light
        IconLegibilityStyle.CONTOUR_DARK -> R.string.dom_icon_legibility_contour_dark
        IconLegibilityStyle.CONTOUR_LIGHT -> R.string.dom_icon_legibility_contour_light
        IconLegibilityStyle.CONTOUR_AUTO -> R.string.dom_icon_legibility_contour_auto
    }

val ImageMotion.labelRes: Int
    get() = when (this) {
        ImageMotion.ANIMATED -> R.string.dom_image_motion_animated
        ImageMotion.REDUCED -> R.string.dom_image_motion_reduced
        ImageMotion.STATIC -> R.string.dom_image_motion_static
    }

val TextLegibilityStyle.labelRes: Int
    get() = when (this) {
        TextLegibilityStyle.NONE -> R.string.dom_text_legibility_none
        TextLegibilityStyle.SHADOW -> R.string.dom_text_legibility_shadow
        TextLegibilityStyle.OUTLINE -> R.string.dom_text_legibility_outline
        TextLegibilityStyle.PLATE -> R.string.dom_text_legibility_plate
        TextLegibilityStyle.AUTO -> R.string.dom_text_legibility_auto
    }

val TouchGesture.labelRes: Int
    get() = when (this) {
        TouchGesture.TAP -> R.string.dom_touch_gesture_tap
        TouchGesture.LONG_PRESS -> R.string.dom_touch_gesture_long_press
        TouchGesture.SWIPE_LEFT -> R.string.dom_touch_gesture_swipe_left
        TouchGesture.SWIPE_RIGHT -> R.string.dom_touch_gesture_swipe_right
        TouchGesture.SWIPE_DOWN -> R.string.dom_touch_gesture_swipe_down
    }

val UmdSlotMode.labelRes: Int
    get() = when (this) {
        UmdSlotMode.OFF -> R.string.dom_umd_slot_off
        UmdSlotMode.INSERTED -> R.string.dom_umd_slot_inserted
        UmdSlotMode.INSERTED_AND_RECENT -> R.string.dom_umd_slot_inserted_and_recent
    }

val XmbListMotion.labelRes: Int
    get() = when (this) {
        XmbListMotion.REWIND -> R.string.dom_list_motion_rewind
        XmbListMotion.GLIDE -> R.string.dom_list_motion_glide
    }

val ConfirmBackLayout.labelRes: Int
    get() = when (this) {
        ConfirmBackLayout.STANDARD -> R.string.dom_confirm_back_standard
        ConfirmBackLayout.REVERSED -> R.string.dom_confirm_back_reversed
    }

val XYLayout.labelRes: Int
    get() = when (this) {
        XYLayout.STANDARD -> R.string.dom_xy_layout_standard
        XYLayout.SWAPPED -> R.string.dom_xy_layout_swapped
    }

val ControllerDisplayType.labelRes: Int
    get() = when (this) {
        ControllerDisplayType.XBOX -> R.string.dom_controller_type_xbox
        ControllerDisplayType.NINTENDO -> R.string.dom_controller_type_nintendo
        ControllerDisplayType.PLAYSTATION -> R.string.dom_controller_type_playstation
    }

val ScrollSpeed.labelRes: Int
    get() = when (this) {
        ScrollSpeed.RELAXED -> R.string.dom_scroll_speed_relaxed
        ScrollSpeed.STANDARD -> R.string.dom_scroll_speed_standard
        ScrollSpeed.FAST -> R.string.dom_scroll_speed_fast
    }

val XmbColorScheme.labelRes: Int
    get() = when (this) {
        XmbColorScheme.ORIGINAL -> R.string.dom_color_scheme_original
        XmbColorScheme.CLASSIC_BLUE -> R.string.dom_color_scheme_classic_blue
        XmbColorScheme.SUNSET_ORANGE -> R.string.dom_color_scheme_sunset_orange
        XmbColorScheme.FRESH_GREEN -> R.string.dom_color_scheme_fresh_green
        XmbColorScheme.ROYAL_PURPLE -> R.string.dom_color_scheme_royal_purple
        XmbColorScheme.CRIMSON_RED -> R.string.dom_color_scheme_crimson_red
        XmbColorScheme.SILVER_MONO -> R.string.dom_color_scheme_silver
        XmbColorScheme.SAKURA_PINK -> R.string.dom_color_scheme_sakura_pink
        XmbColorScheme.GOLDEN_AMBER -> R.string.dom_color_scheme_golden_amber
        XmbColorScheme.AQUA_TEAL -> R.string.dom_color_scheme_aqua_teal
        XmbColorScheme.MIDNIGHT_NAVY -> R.string.dom_color_scheme_midnight_navy
        XmbColorScheme.CHARCOAL -> R.string.dom_color_scheme_charcoal
    }

val GamepadAction.labelRes: Int
    get() = when (this) {
        GamepadAction.NAVIGATE_UP -> R.string.dom_action_navigate_up
        GamepadAction.NAVIGATE_DOWN -> R.string.dom_action_navigate_down
        GamepadAction.NAVIGATE_LEFT -> R.string.dom_action_navigate_left
        GamepadAction.NAVIGATE_RIGHT -> R.string.dom_action_navigate_right
        GamepadAction.SELECT -> R.string.dom_action_select
        GamepadAction.BACK -> R.string.dom_action_back
        GamepadAction.OPEN_CONTEXT_MENU -> R.string.dom_action_open_context_menu
        GamepadAction.CHANGE_SORT -> R.string.dom_action_change_sort
        GamepadAction.PREV_CATEGORY -> R.string.dom_action_prev_category
        GamepadAction.NEXT_CATEGORY -> R.string.dom_action_next_category
        GamepadAction.HOME -> R.string.dom_action_home
        GamepadAction.SHIFT -> R.string.dom_action_shift
        GamepadAction.CAPS_LOCK -> R.string.dom_action_caps_lock
        GamepadAction.CATEGORY_MENU -> R.string.dom_action_category_menu
    }

/** The error's title (the first line of its Notes sheet). */
val PfpErrorCode.labelRes: Int
    get() = when (this) {
        PfpErrorCode.LN_1001 -> R.string.dom_error_ln_1001_title
        PfpErrorCode.LN_1002 -> R.string.dom_error_ln_1002_title
        PfpErrorCode.LN_2001 -> R.string.dom_error_ln_2001_title
        PfpErrorCode.LN_4001 -> R.string.dom_error_ln_4001_title
        PfpErrorCode.LN_4002 -> R.string.dom_error_ln_4002_title
        PfpErrorCode.LN_4003 -> R.string.dom_error_ln_4003_title
        PfpErrorCode.LN_9001 -> R.string.dom_error_ln_9001_title
        PfpErrorCode.SC_1001 -> R.string.dom_error_sc_1001_title
        PfpErrorCode.SC_1002 -> R.string.dom_error_sc_1002_title
        PfpErrorCode.SC_2001 -> R.string.dom_error_sc_2001_title
        PfpErrorCode.SC_2002 -> R.string.dom_error_sc_2002_title
        PfpErrorCode.SC_2003 -> R.string.dom_error_sc_2003_title
        PfpErrorCode.SC_2004 -> R.string.dom_error_sc_2004_title
        PfpErrorCode.SC_9001 -> R.string.dom_error_sc_9001_title
        PfpErrorCode.AR_1001 -> R.string.dom_error_ar_1001_title
        PfpErrorCode.AR_2001 -> R.string.dom_error_ar_2001_title
        PfpErrorCode.AR_2002 -> R.string.dom_error_ar_2002_title
        PfpErrorCode.AR_2003 -> R.string.dom_error_ar_2003_title
        PfpErrorCode.AR_3001 -> R.string.dom_error_ar_3001_title
        PfpErrorCode.AR_3002 -> R.string.dom_error_ar_3002_title
        PfpErrorCode.AR_3003 -> R.string.dom_error_ar_3003_title
        PfpErrorCode.AR_9001 -> R.string.dom_error_ar_9001_title
        PfpErrorCode.AC_1001 -> R.string.dom_error_ac_1001_title
        PfpErrorCode.AC_2001 -> R.string.dom_error_ac_2001_title
        PfpErrorCode.AC_3001 -> R.string.dom_error_ac_3001_title
        PfpErrorCode.AC_3002 -> R.string.dom_error_ac_3002_title
        PfpErrorCode.AC_3003 -> R.string.dom_error_ac_3003_title
        PfpErrorCode.AC_3004 -> R.string.dom_error_ac_3004_title
        PfpErrorCode.MD_2001 -> R.string.dom_error_md_2001_title
        PfpErrorCode.MD_9001 -> R.string.dom_error_md_9001_title
        PfpErrorCode.BK_1001 -> R.string.dom_error_bk_1001_title
        PfpErrorCode.BK_2001 -> R.string.dom_error_bk_2001_title
        PfpErrorCode.BK_2002 -> R.string.dom_error_bk_2002_title
        PfpErrorCode.SY_9001 -> R.string.dom_error_sy_9001_title
    }

/** Why the error happened. */
val PfpErrorCode.whyRes: Int
    get() = when (this) {
        PfpErrorCode.LN_1001 -> R.string.dom_error_ln_1001_why
        PfpErrorCode.LN_1002 -> R.string.dom_error_ln_1002_why
        PfpErrorCode.LN_2001 -> R.string.dom_error_ln_2001_why
        PfpErrorCode.LN_4001 -> R.string.dom_error_ln_4001_why
        PfpErrorCode.LN_4002 -> R.string.dom_error_ln_4002_why
        PfpErrorCode.LN_4003 -> R.string.dom_error_ln_4003_why
        PfpErrorCode.LN_9001 -> R.string.dom_error_ln_9001_why
        PfpErrorCode.SC_1001 -> R.string.dom_error_sc_1001_why
        PfpErrorCode.SC_1002 -> R.string.dom_error_sc_1002_why
        PfpErrorCode.SC_2001 -> R.string.dom_error_sc_2001_why
        PfpErrorCode.SC_2002 -> R.string.dom_error_sc_2002_why
        PfpErrorCode.SC_2003 -> R.string.dom_error_sc_2003_why
        PfpErrorCode.SC_2004 -> R.string.dom_error_sc_2004_why
        PfpErrorCode.SC_9001 -> R.string.dom_error_sc_9001_why
        PfpErrorCode.AR_1001 -> R.string.dom_error_ar_1001_why
        PfpErrorCode.AR_2001 -> R.string.dom_error_ar_2001_why
        PfpErrorCode.AR_2002 -> R.string.dom_error_ar_2002_why
        PfpErrorCode.AR_2003 -> R.string.dom_error_ar_2003_why
        PfpErrorCode.AR_3001 -> R.string.dom_error_ar_3001_why
        PfpErrorCode.AR_3002 -> R.string.dom_error_ar_3002_why
        PfpErrorCode.AR_3003 -> R.string.dom_error_ar_3003_why
        PfpErrorCode.AR_9001 -> R.string.dom_error_ar_9001_why
        PfpErrorCode.AC_1001 -> R.string.dom_error_ac_1001_why
        PfpErrorCode.AC_2001 -> R.string.dom_error_ac_2001_why
        PfpErrorCode.AC_3001 -> R.string.dom_error_ac_3001_why
        PfpErrorCode.AC_3002 -> R.string.dom_error_ac_3002_why
        PfpErrorCode.AC_3003 -> R.string.dom_error_ac_3003_why
        PfpErrorCode.AC_3004 -> R.string.dom_error_ac_3004_why
        PfpErrorCode.MD_2001 -> R.string.dom_error_md_2001_why
        PfpErrorCode.MD_9001 -> R.string.dom_error_md_9001_why
        PfpErrorCode.BK_1001 -> R.string.dom_error_bk_1001_why
        PfpErrorCode.BK_2001 -> R.string.dom_error_bk_2001_why
        PfpErrorCode.BK_2002 -> R.string.dom_error_bk_2002_why
        PfpErrorCode.SY_9001 -> R.string.dom_error_sy_9001_why
    }

/** What the user can do about it. */
val PfpErrorCode.whatYouCanDoRes: Int
    get() = when (this) {
        PfpErrorCode.LN_1001 -> R.string.dom_error_ln_1001_action
        PfpErrorCode.LN_1002 -> R.string.dom_error_ln_1002_action
        PfpErrorCode.LN_2001 -> R.string.dom_error_ln_2001_action
        PfpErrorCode.LN_4001 -> R.string.dom_error_ln_4001_action
        PfpErrorCode.LN_4002 -> R.string.dom_error_ln_4002_action
        PfpErrorCode.LN_4003 -> R.string.dom_error_ln_4003_action
        PfpErrorCode.LN_9001 -> R.string.dom_error_ln_9001_action
        PfpErrorCode.SC_1001 -> R.string.dom_error_sc_1001_action
        PfpErrorCode.SC_1002 -> R.string.dom_error_sc_1002_action
        PfpErrorCode.SC_2001 -> R.string.dom_error_sc_2001_action
        PfpErrorCode.SC_2002 -> R.string.dom_error_sc_2002_action
        PfpErrorCode.SC_2003 -> R.string.dom_error_sc_2003_action
        PfpErrorCode.SC_2004 -> R.string.dom_error_sc_2004_action
        PfpErrorCode.SC_9001 -> R.string.dom_error_sc_9001_action
        PfpErrorCode.AR_1001 -> R.string.dom_error_ar_1001_action
        PfpErrorCode.AR_2001 -> R.string.dom_error_ar_2001_action
        PfpErrorCode.AR_2002 -> R.string.dom_error_ar_2002_action
        PfpErrorCode.AR_2003 -> R.string.dom_error_ar_2003_action
        PfpErrorCode.AR_3001 -> R.string.dom_error_ar_3001_action
        PfpErrorCode.AR_3002 -> R.string.dom_error_ar_3002_action
        PfpErrorCode.AR_3003 -> R.string.dom_error_ar_3003_action
        PfpErrorCode.AR_9001 -> R.string.dom_error_ar_9001_action
        PfpErrorCode.AC_1001 -> R.string.dom_error_ac_1001_action
        PfpErrorCode.AC_2001 -> R.string.dom_error_ac_2001_action
        PfpErrorCode.AC_3001 -> R.string.dom_error_ac_3001_action
        PfpErrorCode.AC_3002 -> R.string.dom_error_ac_3002_action
        PfpErrorCode.AC_3003 -> R.string.dom_error_ac_3003_action
        PfpErrorCode.AC_3004 -> R.string.dom_error_ac_3004_action
        PfpErrorCode.MD_2001 -> R.string.dom_error_md_2001_action
        PfpErrorCode.MD_9001 -> R.string.dom_error_md_9001_action
        PfpErrorCode.BK_1001 -> R.string.dom_error_bk_1001_action
        PfpErrorCode.BK_2001 -> R.string.dom_error_bk_2001_action
        PfpErrorCode.BK_2002 -> R.string.dom_error_bk_2002_action
        PfpErrorCode.SY_9001 -> R.string.dom_error_sy_9001_action
    }

/**
 * The name of a controller button or key, the localisable twin of `Int.keycodeDisplayName()`.
 * [keyCode] is an Android `KeyEvent.KEYCODE_*`.
 */
fun keycodeLabel(keyCode: Int): UiText = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> UiText.res(R.string.dom_key_a)
    KeyEvent.KEYCODE_BUTTON_B -> UiText.res(R.string.dom_key_b)
    KeyEvent.KEYCODE_BUTTON_X -> UiText.res(R.string.dom_key_x)
    KeyEvent.KEYCODE_BUTTON_Y -> UiText.res(R.string.dom_key_y)
    KeyEvent.KEYCODE_DPAD_UP -> UiText.res(R.string.dom_key_dpad_up)
    KeyEvent.KEYCODE_DPAD_DOWN -> UiText.res(R.string.dom_key_dpad_down)
    KeyEvent.KEYCODE_DPAD_LEFT -> UiText.res(R.string.dom_key_dpad_left)
    KeyEvent.KEYCODE_DPAD_RIGHT -> UiText.res(R.string.dom_key_dpad_right)
    KeyEvent.KEYCODE_DPAD_CENTER -> UiText.res(R.string.dom_key_dpad_center)
    KeyEvent.KEYCODE_BUTTON_L1 -> UiText.res(R.string.dom_key_l1)
    KeyEvent.KEYCODE_BUTTON_R1 -> UiText.res(R.string.dom_key_r1)
    KeyEvent.KEYCODE_BUTTON_L2 -> UiText.res(R.string.dom_key_l2)
    KeyEvent.KEYCODE_BUTTON_R2 -> UiText.res(R.string.dom_key_r2)
    KeyEvent.KEYCODE_BUTTON_START -> UiText.res(R.string.dom_key_start)
    KeyEvent.KEYCODE_BUTTON_SELECT -> UiText.res(R.string.dom_key_select)
    KeyEvent.KEYCODE_ENTER -> UiText.res(R.string.dom_key_enter)
    KeyEvent.KEYCODE_BACK -> UiText.res(R.string.dom_key_back)
    else -> UiText.res(R.string.dom_key_unknown, keyCode)
}
