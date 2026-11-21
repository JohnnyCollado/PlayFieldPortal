package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.settings.R
import com.playfieldportal.themekit.UiMediaLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a media row says, and what the tray says when applying a theme leaves a clip out: the
 * user's own pick wins, then the applied theme's file, then the built-in default; caps are read
 * from the shared UiMediaLimits, never restated.
 */
@RunWith(RobolectricTestRunner::class)
class UiMediaRowTextTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val customVideo = UiText.res(R.string.setd_custom_video)

    @Test
    fun `the user's pick wins, then the theme's file, then the default`() {
        assertEquals("intro.mp4", UiMediaRowText.label(userName = "intro.mp4", userAssigned = true, themeSupplied = true, fallback = customVideo).resolve(context))
        assertEquals("Custom video", UiMediaRowText.label(userName = null, userAssigned = true, themeSupplied = false, fallback = customVideo).resolve(context))
        assertEquals("From theme", UiMediaRowText.label(userName = null, userAssigned = false, themeSupplied = true, fallback = customVideo).resolve(context))
        assertEquals("PFP Default", UiMediaRowText.label(userName = "stale.mp4", userAssigned = false, themeSupplied = false, fallback = customVideo).resolve(context))
    }

    @Test
    fun `video row caps come from the shared limits`() {
        assertEquals("(MP4 or WebM, up to 15 seconds)", UiMediaRowText.videoCap(UiMediaSlot.BOOT_VIDEO).resolve(context))
        assertEquals("(MP4 or WebM, up to 10 seconds)", UiMediaRowText.videoCap(UiMediaSlot.GAMEBOOT_VIDEO).resolve(context))
        assertEquals(UiMediaLimits.BOOT_MAX_MS, 15_000L)
    }

    @Test
    fun `a theme that installs everything reports nothing`() {
        assertNull(UiMediaRowText.droppedReport(emptyMap()))
    }

    @Test
    fun `dropped clips are named in settings terms with the reason`() {
        val report = UiMediaRowText.droppedReport(
            linkedMapOf(
                "boot_video" to UiMediaLimits.tooLong(UiMediaLimits.BOOT_CLIP),
                "sound_scroll" to UiMediaLimits.MSG_NO_DURATION,
            ),
        )?.resolve(context)
        assertEquals(
            "Not installed from this theme:\n" +
                "Boot Video — That clip is too long — 15 s or less\n" +
                "Navigation sound — ${UiMediaLimits.MSG_NO_DURATION}",
            report,
        )
    }
}
