package com.playfieldportal.studio

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Drives the REAL ViewModel through the user flow: set a custom icon → export → New →
 * Open the exported file — the custom icon must come back. Regression test for icons
 * silently disappearing between export and reopen.
 */
class ViewModelIconRoundTripTest {

    private fun pngBytes(size: Int = 32): ByteArray {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until size) for (y in 0 until size) img.setRGB(x, y, 0xFFAA3366.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(10_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    @Test
    fun `custom icon survives export then open`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val icon = pngBytes()

        // Simulate a finished icon import (bytes are what matters for export).
        vm.update {
            it.copy(
                name = "Icon Round Trip",
                iconOverrides = mapOf("catbar_games" to icon, "item_playlist" to icon),
            )
        }

        val file = File.createTempFile("studio-roundtrip", ".pfptheme")
        try {
            vm.exportTo(file) { null } // no rendered preview needed for this test
            vm.awaitIdle()
            assertTrue(file.length() > 0, "export wrote nothing; status=${vm.state.value.statusMessage} dialog=${vm.state.value.dialog}")

            vm.newTheme()
            assertEquals(emptyMap(), vm.state.value.iconOverrides)

            vm.openFile(file)
            vm.awaitIdle()

            val state = vm.state.value
            assertEquals(null, state.dialog, "open reported: ${state.dialog}")
            assertEquals("Icon Round Trip", state.name)
            assertEquals(
                setOf("catbar_games", "item_playlist"),
                state.iconOverrides.keys,
                "custom icons dropped on reopen",
            )
        } finally {
            file.delete()
        }
    }
}
