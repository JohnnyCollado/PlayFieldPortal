package com.playfieldportal.core.domain.text

import android.view.KeyEvent
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
import com.playfieldportal.core.domain.model.displayLabel
import com.playfieldportal.core.domain.model.keycodeDisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every enum's string resource must resolve to exactly the English the enum itself carries: the
 * enums keep their English for persistence and tests, the UI reads the resources, and these checks
 * keep the two from drifting apart.
 */
class DomainLabelsTest {

    private fun en(id: Int) = EnglishStrings.string(id)

    @Test
    fun `the strings file and the compiled resources agree`() {
        assertEquals(EnglishStrings.declaredNames, EnglishStrings.compiledNames)
    }

    @Test
    fun `labels with their own label property resolve to it`() {
        ShibaRank.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
        AppEdition.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
        IconDisplayMode.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
        IconLegibilityStyle.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
        ImageMotion.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
        TextLegibilityStyle.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
        TouchGesture.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
        UmdSlotMode.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
        XmbListMotion.entries.forEach { assertEquals(it.label, en(it.labelRes), "$it") }
    }

    @Test
    fun `labels with a displayName resolve to it`() {
        AudioChannel.entries.forEach { assertEquals(it.displayName, en(it.labelRes), "$it") }
        UiMediaSlot.entries.forEach { assertEquals(it.displayName, en(it.labelRes), "$it") }
    }

    @Test
    fun `labels with a displayLabel function resolve to it`() {
        ConfirmBackLayout.entries.forEach { assertEquals(it.displayLabel(), en(it.labelRes), "$it") }
        XYLayout.entries.forEach { assertEquals(it.displayLabel(), en(it.labelRes), "$it") }
        ControllerDisplayType.entries.forEach { assertEquals(it.displayLabel(), en(it.labelRes), "$it") }
        ScrollSpeed.entries.forEach { assertEquals(it.displayLabel(), en(it.labelRes), "$it") }
        XmbColorScheme.entries.forEach { assertEquals(it.displayLabel(), en(it.labelRes), "$it") }
        GamepadAction.entries.forEach { assertEquals(it.displayLabel(), en(it.labelRes), "$it") }
    }

    @Test
    fun `error codes resolve to their title and help text`() {
        PfpErrorCode.entries.forEach {
            assertEquals(it.title, en(it.labelRes), "${it.id} title")
            assertEquals(it.why, en(it.whyRes), "${it.id} why")
            assertEquals(it.whatYouCanDo, en(it.whatYouCanDoRes), "${it.id} action")
        }
    }

    @Test
    fun `button names match the English keycode names`() {
        val keys = listOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            9999,
        )
        keys.forEach { assertEquals(it.keycodeDisplayName(), EnglishStrings.of(keycodeLabel(it)), "key $it") }
    }
}
