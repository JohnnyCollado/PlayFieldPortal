package com.playfieldportal.core.domain.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.discord.MicSensitivity
import com.playfieldportal.core.data.repository.Xbox360Emulator
import com.playfieldportal.core.data.video.VideoIntentResolver
import com.playfieldportal.core.domain.model.Video
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** core-data's enum labels resolve to the English the enums carry; the English default is what a test sees. */
@RunWith(RobolectricTestRunner::class)
class DataLabelsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `microphone sensitivity labels match the enum`() {
        MicSensitivity.entries.forEach { assertEquals(it.label, context.getString(it.labelRes), "$it") }
    }

    @Test
    fun `xbox 360 emulator names match the enum`() {
        Xbox360Emulator.entries.forEach { assertEquals(it.label, context.getString(it.labelRes), "$it") }
    }

    @Test
    fun `a video whose file has gone says so in words`() {
        val message = VideoIntentResolver(context).validate(
            video = Video(id = "1", libraryId = "1", uri = "content://nowhere/1", displayName = "a.mp4"),
            playerPackage = null,
        )
        // Robolectric's content resolver reports no type for an unregistered uri: the file is "gone".
        assertEquals("This video file could not be found or access was lost. Try re-scanning the library.", message)
    }
}
