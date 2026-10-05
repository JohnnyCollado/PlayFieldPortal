package com.playfieldportal.themekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What a saved theme would supply, read from the zip's entry list alone — the "Apply this theme?"
 * confirmation needs it before anything is applied, and must not inflate a 60 MB motion entry.
 */
class PfpThemeContentsTest {

    private fun bundleWith(vararg names: String): File {
        val file = File.createTempFile("contents", ".pfptheme").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return file
    }

    private val itemKey = IconSlots.ALL.first().key
    private val console = CustomizableIcons.ALL.first { it.group == IconSlot.Group.CONSOLE }
    private val mediaSlot = ThemeMediaSlots.ALL.first()

    @Test
    fun `lists the registered icon and media keys the theme carries`() {
        val file = bundleWith(
            "manifest.json",
            "wallpaper.png",
            "motion.mp4",
            "icons/$itemKey.png",
            "sysicons/${console.key.removePrefix(CustomizableIcons.SYSICON_PREFIX)}.gif",
            mediaSlot.entryName(mediaSlot.extensions.first()),
        )

        val contents = PfpThemeCodec.contents(file)!!

        assertEquals(setOf(itemKey, console.key), contents.iconKeys)
        assertEquals(setOf(mediaSlot.key), contents.mediaKeys)
    }

    @Test
    fun `entries no slot claims, or with an extension the slot refuses, are not counted`() {
        val file = bundleWith(
            "manifest.json",
            "icons/not_a_real_slot.png",
            "icons/$itemKey.bmp",
            mediaSlot.entryName("txt"),
            "icons/../escape.png",
        )

        val contents = PfpThemeCodec.contents(file)!!

        assertEquals(emptySet<String>(), contents.iconKeys)
        assertEquals(emptySet<String>(), contents.mediaKeys)
    }

    @Test
    fun `something that is not a zip has no contents`() {
        val file = File.createTempFile("broken", ".pfptheme").apply { deleteOnExit(); writeText("nope") }
        assertNull(PfpThemeCodec.contents(file))
        assertNull(PfpThemeCodec.contents(File("does/not/exist.pfptheme")))
    }
}
