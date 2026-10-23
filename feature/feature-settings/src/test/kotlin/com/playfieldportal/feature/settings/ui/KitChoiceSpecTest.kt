package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.achievements.provider.localsteam.AppIdSource
import com.playfieldportal.feature.settings.viewmodel.KitChoice
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Game List's "no achievement list" prompt only offers what it shows.
 *
 * Survey U34 (docs/audits/codebase-survey-2026-10-22.md): with the installer off, the message
 * offered "install the emulator kit" while that option was hidden.
 */
class KitChoiceSpecTest {

    private fun choice(installer: Boolean) = KitChoice(
        gameId = 1L, appId = "620", folderName = "Portal 2", source = AppIdSource.MARKER,
        treeUri = mockk(relaxed = true), installerEnabled = installer,
    )

    private fun spec(installer: Boolean) = kitChoiceSpec(choice(installer), {}, {}, {}) as PfpModalSpec.Choice

    @Test
    fun `with the installer on, the kit is offered in words and as an option`() {
        val s = spec(installer = true)
        assertEquals(listOf("Link only", "Install kit & link"), s.options.map { it.label })
        assertTrue(s.message.contains("install the emulator kit into it first"))
    }

    @Test
    fun `with the installer off, the message says where to turn it on instead`() {
        val s = spec(installer = false)
        assertEquals(listOf("Link only"), s.options.map { it.label })
        assertFalse(s.message.contains("install the emulator kit into it first"))
        assertTrue(s.message.contains("Settings ▸ Achievements ▸ Local Windows"))
    }
}
