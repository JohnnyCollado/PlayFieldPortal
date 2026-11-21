package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.achievements.provider.localsteam.AppIdSource
import com.playfieldportal.feature.settings.viewmodel.KitChoice
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Game List's "no achievement list" prompt only offers what it shows.
 *
 * Survey U34 (docs/audits/codebase-survey-2026-10-22.md): with the installer off, the message
 * offered "install the emulator kit" while that option was hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KitChoiceSpecTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun choice(installer: Boolean) = KitChoice(
        gameId = 1L, appId = "620", folderName = "Portal 2", source = AppIdSource.MARKER,
        treeUri = mockk(relaxed = true), installerEnabled = installer,
    )

    /** The spec is built in a composition because its texts come from string resources. */
    private fun spec(installer: Boolean): PfpModalSpec.Choice {
        lateinit var built: PfpModalSpec.Choice
        composeRule.setContent { built = kitChoiceSpec(choice(installer), {}, {}, {}) as PfpModalSpec.Choice }
        composeRule.waitForIdle()
        return built
    }

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
