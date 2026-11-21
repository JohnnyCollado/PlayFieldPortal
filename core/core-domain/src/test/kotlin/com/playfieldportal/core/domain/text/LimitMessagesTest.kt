package com.playfieldportal.core.domain.text

import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.UiMediaLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LimitMessagesTest {

    private fun en(message: String) = EnglishStrings.of(LimitMessages.textFor(message))

    @Test
    fun `every fixed rejection reads the same in English`() {
        listOf(
            MotionLimits.MSG_UNSUPPORTED_FORMAT, MotionLimits.MSG_TOO_LARGE_RESOLUTION, MotionLimits.MSG_TOO_LONG,
            MotionLimits.MSG_TOO_LARGE_BYTES, MotionLimits.MSG_UNDECODABLE,
            UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO, UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO,
            UiMediaLimits.MSG_TOO_LARGE_BYTES_SOUND, UiMediaLimits.MSG_TOO_LARGE_BYTES_VIDEO,
            UiMediaLimits.MSG_UNDECODABLE, UiMediaLimits.MSG_NO_DURATION,
        ).forEach { message ->
            assertIs<UiText.Res>(LimitMessages.textFor(message), message)
            assertEquals(message, en(message))
        }
    }

    @Test
    fun `the too-long message carries its cap as an argument`() {
        val message = "That clip is too long — 0.5 s or less"
        val text = assertIs<UiText.Res>(LimitMessages.textFor(message))
        assertEquals(listOf<Any>("0.5 s"), text.args)
        assertEquals(message, en(message))
    }

    @Test
    fun `an unknown message is shown as it came`() {
        assertEquals(UiText.raw("whatever"), LimitMessages.textFor("whatever"))
    }
}
