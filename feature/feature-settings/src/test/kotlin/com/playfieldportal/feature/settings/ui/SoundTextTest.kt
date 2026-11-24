package com.playfieldportal.feature.settings.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.AudioChannel
import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.AudioSettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings ▸ Sound reads its row names from string resources rather than from the domain enums.
 *
 * In English the resource must equal the enum's own `displayName`, so moving the text changed
 * nothing a user can see. In another language the same row resolves to that language's text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `every level channel keeps its English name`() {
        AudioChannel.entries.forEach { channel ->
            assertEquals(channel.displayName, context.getString(channel.labelRes()))
        }
    }

    @Test
    fun `every assignable sound keeps its English name`() {
        AudioSettingsViewModel.SOUND_SLOTS.forEach { slot ->
            assertEquals(slot.displayName, context.getString(checkNotNull(slot.soundLabelRes()) { slot }))
        }
    }

    @Test
    fun `a slot that is not on the Sound screen has no sound name`() {
        UiMediaSlot.ofKind(UiMediaKind.VIDEO).forEach { slot -> assertNull(slot.soundLabelRes()) }
    }

    @Test
    @Config(qualifiers = "de")
    fun `German resolves a translated row name`() {
        assertEquals("Gesamtlautstärke", context.getString(R.string.sound_master_volume))
        assertNotEquals(AudioChannel.BACK.displayName, context.getString(AudioChannel.BACK.labelRes()))
    }

    @Test
    @Config(qualifiers = "ja")
    fun `Japanese resolves a translated row name`() {
        assertEquals("マスター音量", context.getString(R.string.sound_master_volume))
    }

    @Test
    @Config(qualifiers = "de")
    fun `a feature name stays as written in every language`() {
        assertEquals("Game Boot", context.getString(AudioChannel.GAMEBOOT.labelRes()))
    }
}
