package com.playfieldportal.studio

import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PtfIcons
import com.playfieldportal.themekit.ThemeIconChoices
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.playfieldportal.themekit.consoleArt
import com.playfieldportal.themekit.mediaArt

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

    private fun centre(png: ByteArray): Int {
        val img = assertNotNull(ImageIO.read(png.inputStream()))
        return img.getRGB(img.width / 2, img.height / 2)
    }

    @Test
    fun `setIconFromTheme sets the slot from a PSP extra through the gate and undo restores it`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val ref = PtfIcons.SlotRef(2, 5)
        vm.update { it.copy(ptfIcons = mapOf(ref to pngBytes(512))) }

        vm.setIconFromTheme("catbar_games", ThemeIconChoices.Source.Ptf(ref))
        vm.awaitIdle()

        val s = vm.state.value
        assertEquals(null, s.dialog)
        val stored = assertNotNull(s.iconOverrides["catbar_games"])
        val size = assertNotNull(EditableSlots.byKey("catbar_games")).templateSizePx
        assertEquals(size, assertNotNull(ImageIO.read(stored.inputStream())).width, "oversize art is downscaled to the slot's template")
        assertEquals(0xFFAA3366.toInt(), centre(stored))
        assertEquals("png", s.iconExtensions["catbar_games"])
        assertTrue("catbar_games" in s.iconBitmaps)

        vm.undo()
        assertTrue("catbar_games" !in vm.state.value.iconOverrides, "one undo reverts the whole pick")
        assertTrue("catbar_games" !in vm.state.value.iconBitmaps)
    }

    @Test
    fun `setIconFromTheme copies another slot's art`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.update { it.copy(iconOverrides = mapOf("item_playlist" to pngBytes(64)), iconExtensions = mapOf("item_playlist" to "png")) }

        vm.setIconFromTheme("catbar_games", ThemeIconChoices.Source.Slot("item_playlist"))
        vm.awaitIdle()

        assertEquals(null, vm.state.value.dialog)
        assertEquals(0xFFAA3366.toInt(), centre(assertNotNull(vm.state.value.iconOverrides["catbar_games"])))
        assertTrue("item_playlist" in vm.state.value.iconOverrides, "the source slot is untouched")
    }

    @Test
    fun `setIconFromTheme with a missing source or unreadable art raises the error dialog`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.update { it.copy(ptfIcons = mapOf(PtfIcons.SlotRef(2, 5) to byteArrayOf(1, 2, 3))) }

        vm.setIconFromTheme("catbar_games", ThemeIconChoices.Source.Ptf(PtfIcons.SlotRef(2, 5)))
        vm.awaitIdle()
        assertIs<StudioDialog.Error>(vm.state.value.dialog)
        assertTrue(vm.state.value.iconOverrides.isEmpty())

        vm.update { it.copy(dialog = null) }
        vm.setIconFromTheme("catbar_games", ThemeIconChoices.Source.Slot("item_playlist"))
        vm.awaitIdle()
        assertIs<StudioDialog.Error>(vm.state.value.dialog)
        assertTrue(vm.state.value.iconOverrides.isEmpty())
    }

    @Test
    fun `main and sub text colours export and come back on open`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val file = File.createTempFile("studio-roundtrip", ".pfptheme")
        try {
            vm.update { it.copy(name = "Text Colours") }
            vm.setTextColor(TextColorChoice.Custom(0xFFFF8800.toInt()))
            vm.setSubTextColor(TextColorChoice.Custom(0xFF00AA88.toInt()))
            vm.exportTo(file) { null }
            vm.awaitIdle()
            val manifest = assertNotNull(PfpThemeCodec.readDetailed(file.readBytes())).bundle.manifest
            assertEquals("#FF8800", manifest.textColor)
            assertEquals("#00AA88", manifest.subTextColor)

            vm.newTheme()
            assertEquals(TextColorChoice.Auto, vm.state.value.subTextColor)
            vm.openFile(file)
            vm.awaitIdle()
            assertEquals(TextColorChoice.Custom(0xFFFF8800.toInt()), vm.state.value.textColor)
            assertEquals(TextColorChoice.Custom(0xFF00AA88.toInt()), vm.state.value.subTextColor)

            // Auto writes nothing: sub text follows the main colour on the device.
            vm.setSubTextColor(TextColorChoice.Auto)
            vm.exportTo(file) { null }
            vm.awaitIdle()
            assertEquals(null, assertNotNull(PfpThemeCodec.readDetailed(file.readBytes())).bundle.manifest.subTextColor)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `physical media art exports under mediaicons and comes back on open`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val pick = File.createTempFile("studio-media", ".png").apply { writeBytes(pngBytes(64)) }
        val file = File.createTempFile("studio-roundtrip", ".pfptheme")
        try {
            vm.update { it.copy(name = "Media Round Trip") }
            // One edit at a time: each runs on the VM's busy queue, and a single wait can land
            // between the two.
            vm.setIconOverride("physmedia_psx", pick)
            vm.awaitIdle()
            vm.setIconOverride("sysicon_psx", pick)
            vm.awaitIdle()
            assertEquals(setOf("physmedia_psx", "sysicon_psx"), vm.state.value.iconOverrides.keys)
            assertEquals(setOf("physmedia_psx", "sysicon_psx"), vm.state.value.iconBitmaps.keys)

            vm.exportTo(file) { null }
            vm.awaitIdle()
            val bundle = assertNotNull(PfpThemeCodec.readDetailed(file.readBytes())).bundle
            assertEquals(setOf("psx"), bundle.consoleArt.keys, "media art must not land in sysicons/")
            assertEquals(setOf("psx"), bundle.mediaArt.keys, "media art lands in mediaicons/")
            assertTrue(bundle.passthrough.isEmpty(), "media art is typed, never passthrough")

            vm.newTheme()
            vm.openFile(file)
            vm.awaitIdle()
            val state = vm.state.value
            assertEquals(null, state.dialog, "open reported: ${state.dialog}")
            assertEquals(setOf("physmedia_psx", "sysicon_psx"), state.iconOverrides.keys)
            assertEquals("png", state.iconExtensions["physmedia_psx"])
            assertTrue("physmedia_psx" in state.iconBitmaps)
            assertTrue(state.passthroughFiles.isEmpty(), "media art is an edit, not an unknown entry: ${state.passthroughFiles.keys}")

            vm.clearIconOverride("physmedia_psx")
            vm.exportTo(file) { null }
            vm.awaitIdle()
            assertTrue(assertNotNull(PfpThemeCodec.readDetailed(file.readBytes())).bundle.passthrough.isEmpty())
        } finally {
            pick.delete()
            file.delete()
        }
    }
}
