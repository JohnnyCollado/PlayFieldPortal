package com.playfieldportal.feature.artwork.portable

import android.net.Uri
import com.playfieldportal.core.data.saf.SafChild
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Marking a library's artwork folders `.nomedia` when the library opens, so folders nothing has
 * written to since the marker was introduced are hidden from the gallery too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ArtworkFolderMarkingTest {

    /** An in-memory folder tree: document id → its children. */
    private val tree = mutableMapOf<String, MutableList<SafChild>>()
    private val marked = mutableListOf<String>()

    private fun dir(parent: String, name: String): String {
        val id = "$parent/$name"
        tree.getOrPut(parent) { mutableListOf() } += child(id, name, isDirectory = true)
        tree.getOrPut(id) { mutableListOf() }
        return id
    }

    private fun file(parent: String, name: String) {
        tree.getOrPut(parent) { mutableListOf() } += child("$parent/$name", name, isDirectory = false)
    }

    private fun markAll(refuse: Set<String> = emptySet()): Int = ArtworkFolderMarking.markAll(
        rootDocId = ROOT,
        children = { tree[it].orEmpty() },
        mark = { id -> (id !in refuse).also { ok -> if (ok) marked += id } },
    )

    @Test
    fun `every folder under Artwork, Import and games is marked`() {
        val artwork = dir(ROOT, "Artwork")
        val psx = dir(artwork, "psx")
        val shots = dir(psx, "screenshots")
        val pfp = dir(psx, "pfp")
        val icon0 = dir(pfp, "icon0")
        val import = dir(ROOT, "Import")
        val esde = dir(import, "ES-DE")
        val games = dir(ROOT, "games")
        file(shots, "Crash Bandicoot (USA).png")

        val created = markAll()

        assertEquals(setOf(artwork, psx, shots, pfp, icon0, import, esde, games), marked.toSet())
        assertEquals(8, created)
    }

    @Test
    fun `a folder that already has its marker is left alone`() {
        val artwork = dir(ROOT, "Artwork")
        val psx = dir(artwork, "psx")
        file(artwork, ".nomedia")

        markAll()

        assertEquals(listOf(psx), marked)
    }

    @Test
    fun `the library root and folders that are not the library's are never marked`() {
        dir(ROOT, "Music")
        dir(ROOT, "Artwork")
        file(ROOT, "pfp-artwork-library.json")

        markAll()

        assertEquals(listOf("$ROOT/Artwork"), marked)
    }

    @Test
    fun `top-level names match in any case`() {
        dir(ROOT, "artwork")
        dir(ROOT, "IMPORT")

        markAll()

        assertEquals(setOf("$ROOT/artwork", "$ROOT/IMPORT"), marked.toSet())
    }

    @Test
    fun `hidden folders are not walked`() {
        val artwork = dir(ROOT, "Artwork")
        val hidden = dir(artwork, ".thumbnails")
        dir(hidden, "inner")

        markAll()

        assertEquals(listOf(artwork), marked)
    }

    @Test
    fun `a folder the provider will not mark is not counted, and the walk goes on`() {
        val artwork = dir(ROOT, "Artwork")
        val psx = dir(artwork, "psx")
        val shots = dir(psx, "screenshots")

        val created = markAll(refuse = setOf(psx))

        assertEquals(setOf(artwork, shots), marked.toSet())
        assertEquals(2, created)
    }

    @Test
    fun `a folder reachable twice is marked once`() {
        val artwork = dir(ROOT, "Artwork")
        val psx = dir(artwork, "psx")
        // A provider that surfaces one folder under two parents, or loops back on itself.
        tree.getValue(psx) += child(artwork, "Artwork", isDirectory = true)

        markAll()

        assertEquals(listOf(artwork, psx), marked.sorted())
    }

    private fun child(id: String, name: String, isDirectory: Boolean) = SafChild(
        documentId = id,
        uri = Uri.parse("content://test/document/${Uri.encode(id)}"),
        name = name,
        mime = if (isDirectory) "vnd.android.document/directory" else "application/octet-stream",
        isDirectory = isDirectory,
        lastModified = null,
        sizeBytes = 0L,
    )

    private companion object {
        const val ROOT = "root"
    }
}
