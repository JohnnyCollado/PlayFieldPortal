package com.playfieldportal.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `.cue` (or `.gdi`, `.m3u`, …) names sibling track files, and an emulator like DuckStation opens
 * them through the same SAF tree. A one-document grant covers the sheet only, so the launch fails
 * on the first track. The folder grant is the fix, kept to multi-file discs and to a tree the user
 * configured: a Library ROM root, or a Memory Card's own folder.
 */
class DiscFolderGrantTest {

    private val card = "content://com.android.externalstorage.documents/tree/primary%3APFP%2FRoms"
    private val cue = "$card/document/primary%3APFP%2FRoms%2Fpsx%2FParasite%20Eve%20II%20(USA)%20(Disc%201)" +
        "%2FParasite%20Eve%20II%20(USA)%20(Disc%201).cue"

    @Test
    fun `a cue inside a configured tree gets that tree`() {
        assertEquals(card, DiscFolderGrant.treeFor(cue, listOf(card)))
    }

    @Test
    fun `every multi-file sheet format qualifies, in any case`() {
        listOf("gdi", "m3u", "ccd", "mds", "toc", "CUE").forEach { ext ->
            val uri = "$card/document/primary%3APFP%2FRoms%2Fgame.$ext"
            assertEquals(ext, card, DiscFolderGrant.treeFor(uri, listOf(card)))
        }
    }

    @Test
    fun `a single-file rom keeps its one-document grant`() {
        listOf("chd", "iso", "bin", "pbp", "zip").forEach { ext ->
            val uri = "$card/document/primary%3APFP%2FRoms%2Fgame.$ext"
            assertNull(ext, DiscFolderGrant.treeFor(uri, listOf(card)))
        }
    }

    @Test
    fun `a tree that is not a configured card is never granted`() {
        val other = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val uri = "$other/document/primary%3ADownload%2Fgame.cue"
        assertNull(DiscFolderGrant.treeFor(uri, listOf(card)))
    }

    @Test
    fun `a plain document or FileProvider uri has no tree to grant`() {
        assertNull(DiscFolderGrant.treeFor("content://com.android.externalstorage.documents/document/primary%3Agame.cue", listOf(card)))
        assertNull(DiscFolderGrant.treeFor("content://com.playfieldportal.launcher.fileprovider/root/storage/game.cue", listOf(card)))
        assertNull(DiscFolderGrant.treeFor("not a uri", listOf(card)))
    }

    @Test
    fun `a cue under one of several configured trees gets the one it is in`() {
        val sd = "content://com.android.externalstorage.documents/tree/408C-3861%3AEmulation%2Froms"
        assertEquals(card, DiscFolderGrant.treeFor(cue, listOf(sd, card)))
    }

    @Test
    fun `a tree stored with a trailing slash still matches`() {
        assertEquals(card, DiscFolderGrant.treeFor(cue, listOf("$card/")))
    }
}
