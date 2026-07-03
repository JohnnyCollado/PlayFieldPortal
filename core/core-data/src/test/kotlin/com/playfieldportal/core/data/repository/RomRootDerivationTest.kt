package com.playfieldportal.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Pure string-math derivation used by the single ROM-root scan path — no Android dependencies.
class RomRootDerivationTest {

    @Test
    fun `docIdToRawPath maps primary and removable volumes`() {
        assertEquals("/storage/emulated/0/Roms", RomRootRepository.docIdToRawPath("primary:Roms"))
        assertEquals("/storage/1A2B-3C4D/Games", RomRootRepository.docIdToRawPath("1A2B-3C4D:Games"))
    }

    @Test
    fun `docIdToRawPath returns null for opaque ids`() {
        assertNull(RomRootRepository.docIdToRawPath("primary:"))
        assertNull(RomRootRepository.docIdToRawPath("noColon"))
    }

    @Test
    fun `childDocIdFrom appends the subfolder under the root`() {
        val childDocId = RomRootRepository.childDocIdFrom(
            rootDocId   = "primary:Roms",
            rootRawPath = "/storage/emulated/0/Roms",
            romDirectory = "/storage/emulated/0/Roms/GBA",
        )
        assertEquals("primary:Roms/GBA", childDocId)
    }

    @Test
    fun `childDocIdFrom handles nested subfolders and trailing slashes`() {
        val childDocId = RomRootRepository.childDocIdFrom(
            rootDocId   = "primary:Roms",
            rootRawPath = "/storage/emulated/0/Roms/",
            romDirectory = "/storage/emulated/0/Roms/Nintendo/3ds/",
        )
        assertEquals("primary:Roms/Nintendo/3ds", childDocId)
    }

    @Test
    fun `childDocIdFrom returns the root doc id when the card IS the root`() {
        val childDocId = RomRootRepository.childDocIdFrom(
            rootDocId   = "primary:Roms",
            rootRawPath = "/storage/emulated/0/Roms",
            romDirectory = "/storage/emulated/0/Roms",
        )
        assertEquals("primary:Roms", childDocId)
    }

    @Test
    fun `childDocIdFrom returns null when the card folder is not under the root`() {
        assertNull(
            RomRootRepository.childDocIdFrom(
                rootDocId   = "primary:Roms",
                rootRawPath = "/storage/emulated/0/Roms",
                romDirectory = "/storage/emulated/0/Games/GBA",
            )
        )
        // Sibling that merely shares a name prefix must not match ("/Roms2" vs "/Roms").
        assertNull(
            RomRootRepository.childDocIdFrom(
                rootDocId   = "primary:Roms",
                rootRawPath = "/storage/emulated/0/Roms",
                romDirectory = "/storage/emulated/0/Roms2/GBA",
            )
        )
    }
}
