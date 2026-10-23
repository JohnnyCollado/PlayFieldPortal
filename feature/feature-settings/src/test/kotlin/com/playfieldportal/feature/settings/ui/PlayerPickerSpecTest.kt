package com.playfieldportal.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The default-player pickers (Music, Video) as the shared Choice modal.
 *
 * Survey P6 (docs/audits/codebase-survey-2026-10-22.md): they were Material dialogs with no
 * controller routing, so a pad could not pick a player. The shared modal is driven by pad and touch.
 */
class PlayerPickerSpecTest {

    private val options = listOf(
        PlayerOption("builtin", "Play Field Portal"),
        PlayerOption(null, "System Default"),
        PlayerOption("org.videolan.vlc", "VLC"),
    )

    @Test
    fun `it opens on the current player and lists every option`() {
        val spec = playerPickerSpec("Default Video Player", options, current = "org.videolan.vlc", emptyNote = null, onChoose = {}, onClose = {})

        assertEquals("Default Video Player", spec.title)
        assertEquals(listOf("Play Field Portal", "System Default", "VLC"), spec.options.map { it.label })
        assertEquals(2, spec.initialSelected)
    }

    @Test
    fun `confirming picks the selected player and cancel just closes`() {
        val chosen = mutableListOf<String?>()
        var closed = 0
        val spec = playerPickerSpec("Default Music Player", options, current = null, emptyNote = null, onChoose = { chosen += it }, onClose = { closed++ })

        assertEquals(1, spec.initialSelected)
        spec.onConfirm(1)
        spec.onCancel()

        assertEquals(listOf<String?>(null), chosen)
        assertEquals(1, closed)
    }

    @Test
    fun `with no installed players the message says so`() {
        val spec = playerPickerSpec("Default Music Player", options.take(2), current = "builtin", emptyNote = "No other music players found on this device.", onChoose = {}, onClose = {})

        assertEquals("No other music players found on this device.", spec.message)
        assertEquals(0, spec.initialSelected)
    }
}
