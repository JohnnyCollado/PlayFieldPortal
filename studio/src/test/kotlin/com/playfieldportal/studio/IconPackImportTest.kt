package com.playfieldportal.studio

import com.playfieldportal.studio.io.IconPackImport
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconGifSupport
import com.playfieldportal.themekit.PfpThemeCodec
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IconPackImportTest {

    private fun png(size: Int = 32): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB), "png", out)
        return out.toByteArray()
    }

    private fun zip(dir: File, entries: List<Pair<String, ByteArray>>): File {
        val file = File(dir, "pack.zip")
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return file
    }

    private fun withTemp(block: (File) -> Unit) {
        val dir = createTempDirectory("icon-pack").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    @Test
    fun `a folder matches png and gif files by slot key, case-insensitively`() = withTemp { dir ->
        File(dir, "catbar_games.png").writeBytes(png())
        File(dir, "SYSICON_PSP.GIF").writeBytes(IconGifTestMedia.animatedGif())
        val scan = IconPackImport.scan(dir)
        assertEquals(setOf("catbar_games", "sysicon_psp"), scan.matched.keys)
        assertTrue(scan.unmatched.isEmpty() && scan.rejected.isEmpty())
        assertNull(scan.error)
    }

    @Test
    fun `a zip matches entries, including inside a wrapper folder`() = withTemp { dir ->
        val z = zip(dir, listOf("pack/catbar_games.png" to png(), "sysicon_psp.png" to png(), "pack/" to ByteArray(0)))
        val scan = IconPackImport.scan(z)
        assertEquals(setOf("catbar_games", "sysicon_psp"), scan.matched.keys)
        assertNull(scan.error)
    }

    @Test
    fun `hostile zip names are rejected and never matched`() = withTemp { dir ->
        val names = listOf(
            "../catbar_games.png", "/catbar_games.png", "C:\\catbar_games.png",
            "..\\catbar_games.png", "a/../../catbar_games.png", "x/catbar_games.png\u0000.png",
        )
        val z = zip(dir, names.map { it to png() })
        val scan = IconPackImport.scan(z)
        assertTrue(scan.matched.isEmpty(), "matched: ${scan.matched.keys}")
        assertEquals(names.size, scan.rejected.size)
    }

    @Test
    fun `unmatched files are reported with a nearest-key suggestion`() = withTemp { dir ->
        File(dir, "catbar_gams.png").writeBytes(png())
        File(dir, "totally_unrelated_name.png").writeBytes(png())
        val scan = IconPackImport.scan(dir)
        assertEquals(2, scan.unmatched.size)
        assertEquals("catbar_games", scan.unmatched.first { it.name == "catbar_gams.png" }.suggestion)
        assertNull(scan.unmatched.first { it.name.startsWith("totally") }.suggestion)
    }

    @Test
    fun `every file is accounted for exactly once`() = withTemp { dir ->
        File(dir, "catbar_games.png").writeBytes(png())
        File(dir, "catbar_games.gif").writeBytes(IconGifTestMedia.animatedGif()) // duplicate key
        File(dir, "nope.png").writeBytes(png())
        File(dir, "notes.txt").writeText("hi")
        val scan = IconPackImport.scan(dir)
        assertEquals(4, scan.fileCount)
        assertEquals(4, scan.matched.size + scan.unmatched.size + scan.rejected.size)
        assertEquals(1, scan.matched.size)
    }

    @Test
    fun `an entry past the icon byte cap refuses the archive`() = withTemp { dir ->
        val z = zip(dir, listOf("catbar_games.png" to ByteArray(PfpThemeCodec.MAX_ICON_BYTES + 1)))
        val scan = IconPackImport.scan(z)
        assertNotNull(scan.error)
        assertTrue(scan.matched.isEmpty())
    }

    @Test
    fun `an oversized file in a folder is rejected without being read`() = withTemp { dir ->
        File(dir, "catbar_games.png").writeBytes(ByteArray(PfpThemeCodec.MAX_ICON_BYTES + 1))
        val scan = IconPackImport.scan(dir)
        assertTrue(scan.matched.isEmpty())
        assertEquals(1, scan.rejected.size)
    }

    @Test
    fun `a whole pack import is one undoable edit and reports added and replaced`() = runBlocking<Unit> {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("icon-pack-vm").toFile()
        try {
            vm.setIconOverride("catbar_games", File(dir, "seed.png").also { it.writeBytes(png(16)) })
            vm.awaitIdle()
            File(dir, "seed.png").delete()

            File(dir, "catbar_games.png").writeBytes(png(24))
            File(dir, "sysicon_psp.png").writeBytes(png(24))
            File(dir, "bogus.png").writeBytes(png())
            var report: com.playfieldportal.studio.io.IconPackReport? = null
            vm.importIconPack(dir) { report = it }
            vm.awaitIdle()

            val r = assertNotNull(report)
            assertEquals(listOf("sysicon_psp"), r.added)
            assertEquals(listOf("catbar_games"), r.replaced)
            assertEquals(listOf("bogus.png"), r.unmatched.map { it.name })
            assertNotNull(vm.state.value.iconOverrides["sysicon_psp"])
            assertNotNull(vm.state.value.iconBitmaps["catbar_games"])

            vm.undo()
            val after = vm.state.value
            assertNull(after.iconOverrides["sysicon_psp"], "one undo reverts the whole pack")
            assertNotNull(after.iconOverrides["catbar_games"], "the seed icon from before the import remains")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a gate-failing gif is rejected with its reason while the rest still import`() = runBlocking<Unit> {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("icon-pack-gate").toFile()
        try {
            File(dir, "catbar_games.png").writeBytes(png())
            File(dir, "catbar_settings.gif").writeBytes(IconGifTestMedia.animatedGif(frames = 110, delayCs = 100))
            File(dir, "catbar_music.png").writeBytes(byteArrayOf(1, 2, 3))
            var report: com.playfieldportal.studio.io.IconPackReport? = null
            vm.importIconPack(dir) { report = it }
            vm.awaitIdle()

            val r = assertNotNull(report)
            assertEquals(listOf("catbar_games"), r.added)
            val reasons = r.rejected.associate { it.name to it.reason }
            assertEquals(IconGifSupport.MSG_TOO_LONG, reasons["catbar_settings.gif"])
            assertTrue(reasons["catbar_music.png"]!!.contains("not a readable image"))
            assertNull(vm.state.value.iconOverrides["catbar_settings"])
            assertNull(vm.state.value.dialog)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `keys used by the template export are valid slots`() {
        // The export writes <slot.key>.png for IconSlots.ALL; every one must match on re-import.
        assertTrue(com.playfieldportal.themekit.IconSlots.ALL.all { CustomizableIcons.byKey(it.key) != null })
    }
}
