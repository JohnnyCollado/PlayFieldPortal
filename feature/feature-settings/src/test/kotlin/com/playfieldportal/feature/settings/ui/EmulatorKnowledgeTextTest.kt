package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.playfieldportal.core.data.kb.PlatformGain
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.FieldChange
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wording of the import review and the gained-file-types note. Nothing about an entry may be hidden or abbreviated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmulatorKnowledgeTextTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The wording is composed with string resources, so it is read inside a composition. */
    private fun composed(block: @Composable () -> String): String {
        var text = ""
        composeRule.setContent { text = block() }
        composeRule.waitForIdle()
        return text
    }

    @Test
    fun `a new entry lists every package name`() {
        val entry = EmulatorKbEmulator(
            id = "x", name = "X", packageNames = listOf("org.a", "org.b", "org.c"), platformIds = listOf("psp"),
        )

        val text = composed { newEntrySummary(entry) }

        assertTrue(text.startsWith("org.a, org.b, org.c · PlayStation Portable"), text)
    }

    @Test
    fun `a long diff is shown in full`() {
        val diff = (1..7).map { FieldChange("Field $it", "old $it", "new $it") }

        val text = composed { diffText(diff) }

        assertEquals(14, text.lines().size)
        assertFalse(text.contains("more"))
        assertTrue(text.contains("+ Field 7: new 7"))
    }

    @Test
    fun `a diff says what the intent type does and names the consoles`() {
        val text = composed {
            diffText(
                listOf(
                    FieldChange("Intent type", "ACTION_VIEW", "COMPONENT"),
                    FieldChange("Platforms", "psp", "psp, switch"),
                ),
            )
        }

        assertTrue(text.contains("- Intent type: Opens the ROM file"), text)
        assertTrue(text.contains("+ Intent type: Opens a specific screen"), text)
        assertTrue(text.contains("+ Platforms: PlayStation Portable, Nintendo Switch"), text)
    }

    @Test
    fun `gained file types use console names and the review's extension format`() {
        val text = gainedSummary(listOf(PlatformGain("psp", listOf("chd", "cso")), PlatformGain("switch", listOf("nsz"))))

        assertEquals("PlayStation Portable: chd, cso; Nintendo Switch: nsz.", text)
    }
}
