package com.playfieldportal.core.domain.text

import com.playfieldportal.core.domain.R
import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.UiMediaLimits

/**
 * The wording theme-kit's [MotionLimits] and [UiMediaLimits] reject a picked file with, as
 * translatable text. theme-kit is shared with the desktop Theme Studio and stays plain English, so
 * the Android app maps each of its messages back to a string resource here.
 */
object LimitMessages {

    private val known: Map<String, Int> = mapOf(
        MotionLimits.MSG_UNSUPPORTED_FORMAT to R.string.dom_limit_motion_unsupported_format,
        MotionLimits.MSG_TOO_LARGE_RESOLUTION to R.string.dom_limit_motion_too_large_resolution,
        MotionLimits.MSG_TOO_LONG to R.string.dom_limit_motion_too_long,
        MotionLimits.MSG_TOO_LARGE_BYTES to R.string.dom_limit_motion_too_large_bytes,
        MotionLimits.MSG_UNDECODABLE to R.string.dom_limit_motion_undecodable,
        UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO to R.string.dom_limit_media_unsupported_audio,
        UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO to R.string.dom_limit_media_unsupported_video,
        UiMediaLimits.MSG_TOO_LARGE_BYTES_SOUND to R.string.dom_limit_media_too_large_sound,
        UiMediaLimits.MSG_TOO_LARGE_BYTES_VIDEO to R.string.dom_limit_media_too_large_video,
        UiMediaLimits.MSG_UNDECODABLE to R.string.dom_limit_media_undecodable,
        UiMediaLimits.MSG_NO_DURATION to R.string.dom_limit_media_no_duration,
    )

    // UiMediaLimits.tooLong(spec): "That clip is too long — <cap> or less", the cap being "0.5 s" / "10 s".
    private val tooLong = Regex("""That clip is too long — (.+) or less""")

    /**
     * [message] (a rejection from [MotionLimits.validate], [UiMediaLimits.validate] or one of their
     * `MSG_*` constants) as text in the user's language. A message this does not know — from any
     * other source — is shown as it came.
     */
    fun textFor(message: String): UiText {
        known[message]?.let { return UiText.res(it) }
        tooLong.matchEntire(message)?.let { return UiText.res(R.string.dom_limit_media_too_long, it.groupValues[1]) }
        return UiText.raw(message)
    }
}
