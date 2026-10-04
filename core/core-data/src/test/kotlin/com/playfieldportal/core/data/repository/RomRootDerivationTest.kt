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

    // ── Grants made through an app's own documents provider ─────────────────────
    // Real URIs from a device: XenDroid's provider ids are absolute paths, X360 Mobile's are opaque.

    private val xenDroidTree =
        "content://xendroid.compose.DocumentsProvider/tree/%2Fstorage%2Femulated%2F0%2FAndroid%2Fdata%2Fxendroid.compose%2Ffiles%2Fcompose"
    private val x360MobileTree = "content://emu.x360mobile.com.documents/tree/v%3Aroot"

    @Test
    fun `rawPathOfTree maps shared-storage trees as before`() {
        assertEquals(
            "/storage/emulated/0/Roms",
            RomRootRepository.rawPathOfTree("content://com.android.externalstorage.documents/tree/primary%3ARoms"),
        )
        assertEquals(
            "/storage/1A2B-3C4D/Games/PS2",
            RomRootRepository.rawPathOfTree("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AGames%2FPS2"),
        )
    }

    @Test
    fun `rawPathOfTree reads a provider whose ids are absolute paths`() {
        assertEquals(
            "/storage/emulated/0/Android/data/xendroid.compose/files/compose",
            RomRootRepository.rawPathOfTree(xenDroidTree),
        )
    }

    @Test
    fun `rawPathOfTree never invents a storage path for an opaque provider id`() {
        // "v:root" is not a storage volume; it used to become "/storage/v/root".
        assertNull(RomRootRepository.rawPathOfTree(x360MobileTree))
    }

    @Test
    fun `rawPathOfDocument only maps volume ids for shared storage`() {
        assertEquals("/storage/emulated/0/a", RomRootRepository.rawPathOfDocument("com.android.externalstorage.documents", "primary:a"))
        assertNull(RomRootRepository.rawPathOfDocument("emu.x360mobile.com.documents", "v:root/a"))
        assertEquals("/data/x", RomRootRepository.rawPathOfDocument("some.provider", "/data/x"))
        // Without an authority the caller's id is taken as shared storage, as it always was.
        assertEquals("/storage/emulated/0/a", RomRootRepository.rawPathOfDocument(null, "primary:a"))
    }

    @Test
    fun `displayNameOfTree is readable for every kind of grant`() {
        assertEquals(
            "/storage/emulated/0/Android/data/xendroid.compose/files/compose",
            RomRootRepository.displayNameOfTree(xenDroidTree),
        )
        assertEquals("root", RomRootRepository.displayNameOfTree(x360MobileTree))
        assertEquals(
            "profiles",
            RomRootRepository.displayNameOfTree("content://emu.x360mobile.com.documents/tree/v%3Aroot%2Fprofiles"),
        )
        assertEquals(
            "/storage/emulated/0",
            RomRootRepository.displayNameOfTree("content://com.android.externalstorage.documents/tree/primary%3A"),
        )
        assertEquals("not a uri", RomRootRepository.displayNameOfTree("not a uri"))
    }
}
