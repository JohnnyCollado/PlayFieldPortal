package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.PtfThemeImporter
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.text.UiText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PtfImportMessageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun msg(result: PtfThemeImporter.Result): String = PtfImportMessage.of(result).resolve(context)


    private fun success(icons: Int, score: Int?) =
        PtfThemeImporter.Result.Success("pfp_1", "Sunset", accentArgb = null, iconCount = icons, tintScore = score)

    @Test
    fun `icons and a matched tint are both reported`() {
        assertEquals("Imported \"Sunset\" — wallpaper and 13 icons, tint matched 72%", msg(success(13, 72)))
    }

    @Test
    fun `a tint below the apply score is not mentioned`() {
        assertEquals("Imported \"Sunset\" — wallpaper and 13 icons", msg(success(13, 20)))
    }

    @Test
    fun `one icon reads in the singular`() {
        assertEquals("Imported \"Sunset\" — wallpaper and 1 icon, tint matched 100%", msg(success(1, 100)))
    }

    @Test
    fun `no icons means wallpaper only`() {
        assertEquals("Imported \"Sunset\" — wallpaper only", msg(success(0, null)))
    }

    @Test
    fun `refusals keep their wording`() {
        assertEquals(
            "CXMB (.ctf) themes aren't supported — only official .ptf themes",
            msg(PtfThemeImporter.Result.CxmbNotSupported),
        )
        assertEquals("Not a PSP theme (.ptf) file", msg(PtfThemeImporter.Result.Failed("Not a PSP theme (.ptf) file")))
    }
}
