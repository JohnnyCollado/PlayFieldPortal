package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.playfieldportal.core.ui.components.PfpModalSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The default-player pickers (Music, Video) as the shared Choice modal.
 *
 * Survey P6 (docs/audits/codebase-survey-2026-10-22.md): they were Material dialogs with no
 * controller routing, so a pad could not pick a player. The shared modal is driven by pad and touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerPickerSpecTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The spec is built in a composition because its button labels come from string resources. */
    private fun build(
        title: String,
        options: List<PlayerOption>,
        current: String?,
        emptyNote: String? = null,
        onChoose: (String?) -> Unit = {},
        onClose: () -> Unit = {},
    ): PfpModalSpec.Choice {
        lateinit var spec: PfpModalSpec.Choice
        composeRule.setContent { spec = playerPickerSpec(title, options, current, emptyNote, onChoose, onClose) }
        composeRule.waitForIdle()
        return spec
    }

    private val options = listOf(
        PlayerOption("builtin", "Play Field Portal"),
        PlayerOption(null, "System Default"),
        PlayerOption("org.videolan.vlc", "VLC"),
    )

    @Test
    fun `it opens on the current player and lists every option`() {
        val spec = build("Default Video Player", options, current = "org.videolan.vlc")

        assertEquals("Default Video Player", spec.title)
        assertEquals(listOf("Play Field Portal", "System Default", "VLC"), spec.options.map { it.label })
        assertEquals(2, spec.initialSelected)
        assertEquals("Use This Player", spec.confirmLabel)
        assertEquals("Close", spec.cancelLabel)
    }

    @Test
    fun `confirming picks the selected player and cancel just closes`() {
        val chosen = mutableListOf<String?>()
        var closed = 0
        val spec = build("Default Music Player", options, current = null, onChoose = { chosen += it }, onClose = { closed++ })

        assertEquals(1, spec.initialSelected)
        spec.onConfirm(1)
        spec.onCancel()

        assertEquals(listOf<String?>(null), chosen)
        assertEquals(1, closed)
    }

    @Test
    fun `with no installed players the message says so`() {
        val spec = build("Default Music Player", options.take(2), current = "builtin", emptyNote = "No other music players found on this device.")

        assertEquals("No other music players found on this device.", spec.message)
        assertEquals(0, spec.initialSelected)
    }
}
