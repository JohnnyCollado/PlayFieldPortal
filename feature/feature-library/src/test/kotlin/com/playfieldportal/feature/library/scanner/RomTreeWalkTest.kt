package com.playfieldportal.feature.library.scanner

import android.net.Uri
import com.playfieldportal.core.data.saf.SafChild
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SAF ROM walk must say when it could not see everything. A subfolder whose listing failed
 * (the SD card still mounting after a reboot) used to read as an empty folder, so its games were
 * flagged missing until a manual rescan.
 */
class RomTreeWalkTest {

    private fun file(name: String) = SafChild(
        documentId = "1234-5678:roms/$name", uri = mockk<Uri>(relaxed = true), name = name,
        mime = "application/octet-stream", isDirectory = false, lastModified = null, sizeBytes = null,
    )

    private fun dir(id: String) = SafChild(
        documentId = id, uri = mockk<Uri>(relaxed = true), name = id.substringAfterLast('/'),
        mime = "vnd.android.document/directory", isDirectory = true, lastModified = null, sizeBytes = null,
    )

    @Test
    fun `every listing readable is a complete walk`() = runTest {
        val listings = mapOf(
            "root" to listOf(file("Crash.cue"), dir("root/Multi")),
            "root/Multi" to listOf(file("Dino Crisis.cue")),
        )
        val walk = walkRomTree("root", recursive = true, allowed = setOf("cue")) { listings[it] }

        assertTrue(walk.complete)
        assertEquals(setOf("Crash.cue", "Dino Crisis.cue"), walk.files.mapTo(mutableSetOf()) { it.name })
    }

    @Test
    fun `an unreadable subfolder makes the walk incomplete but keeps what was seen`() = runTest {
        val listings = mapOf(
            "root" to listOf(file("Crash.cue"), dir("root/Multi")),
            // "root/Multi" is absent: its listing failed.
        )
        val walk = walkRomTree("root", recursive = true, allowed = setOf("cue")) { listings[it] }

        assertFalse(walk.complete)
        assertEquals(listOf("Crash.cue"), walk.files.map { it.name })
    }

    @Test
    fun `an unreadable root is an incomplete walk`() = runTest {
        val walk = walkRomTree("root", recursive = true, allowed = setOf("cue")) { null }
        assertFalse(walk.complete)
        assertTrue(walk.files.isEmpty())
    }

    @Test
    fun `an empty folder is still a complete walk`() = runTest {
        val walk = walkRomTree("root", recursive = true, allowed = setOf("cue")) { emptyList() }
        assertTrue(walk.complete)
    }

    @Test
    fun `hidden files are skipped and game folders are not descended into`() = runTest {
        val listings = mapOf(
            "root" to listOf(file(".hidden.cue"), dir("root/Demon's Souls.ps3dir")),
        )
        val walk = walkRomTree("root", recursive = true, allowed = setOf("ps3dir")) { listings[it] }

        assertTrue("the game folder itself is never listed", walk.complete)
        assertTrue(walk.files.isEmpty())
        assertEquals(listOf("Demon's Souls.ps3dir"), walk.folderGames.map { it.name })
    }
}
