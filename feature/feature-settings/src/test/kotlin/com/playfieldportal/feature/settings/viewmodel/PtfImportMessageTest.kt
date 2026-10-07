package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.PtfThemeImporter
import org.junit.Test
import kotlin.test.assertEquals

class PtfImportMessageTest {

    private fun success(icons: Int, score: Int?) =
        PtfThemeImporter.Result.Success("pfp_1", "Sunset", accentArgb = null, iconCount = icons, tintScore = score)

    @Test
    fun `icons and a matched tint are both reported`() {
        assertEquals("Imported \"Sunset\" — wallpaper and 13 icons, tint matched 72%", PtfImportMessage.of(success(13, 72)))
    }

    @Test
    fun `a tint below the apply score is not mentioned`() {
        assertEquals("Imported \"Sunset\" — wallpaper and 13 icons", PtfImportMessage.of(success(13, 20)))
    }

    @Test
    fun `one icon reads in the singular`() {
        assertEquals("Imported \"Sunset\" — wallpaper and 1 icon, tint matched 100%", PtfImportMessage.of(success(1, 100)))
    }

    @Test
    fun `no icons means wallpaper only`() {
        assertEquals("Imported \"Sunset\" — wallpaper only", PtfImportMessage.of(success(0, null)))
    }

    @Test
    fun `refusals keep their wording`() {
        assertEquals(
            "CXMB (.ctf) themes aren't supported — only official .ptf themes",
            PtfImportMessage.of(PtfThemeImporter.Result.CxmbNotSupported),
        )
        assertEquals("Not a PSP theme (.ptf) file", PtfImportMessage.of(PtfThemeImporter.Result.Failed("Not a PSP theme (.ptf) file")))
    }
}
