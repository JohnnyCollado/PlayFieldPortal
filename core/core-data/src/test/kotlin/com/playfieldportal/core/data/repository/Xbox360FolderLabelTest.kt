package com.playfieldportal.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a granted Xbox 360 data folder reads on screen. Both emulators publish their data through
 * their own documents providers, whose folder ids are not shared-storage ids — so the generic
 * "volume:path" reading turned X360 Mobile's root into "/storage/v/root" and left XenDroid's
 * percent-encoded. The URIs below are the ones granted on a real device.
 */
class Xbox360FolderLabelTest {

    @Test
    fun `XenDroid's provider ids are real paths and read as that path`() {
        assertEquals(
            "/storage/emulated/0/Android/data/xendroid.compose/files/compose",
            Xbox360DataLibrary.folderLabel(
                "content://xendroid.compose.DocumentsProvider/tree/%2Fstorage%2Femulated%2F0%2FAndroid%2Fdata%2Fxendroid.compose%2Ffiles%2Fcompose",
                Xbox360Emulator.XENDROID,
            ),
        )
    }

    @Test
    fun `X360 Mobile's opaque root reads as the emulator's data`() {
        assertEquals(
            "X360 Mobile data",
            Xbox360DataLibrary.folderLabel("content://emu.x360mobile.com.documents/tree/v%3Aroot", Xbox360Emulator.X360_MOBILE),
        )
    }

    @Test
    fun `a subfolder of an opaque provider keeps its own name`() {
        assertEquals(
            "X360 Mobile · profiles",
            Xbox360DataLibrary.folderLabel(
                "content://emu.x360mobile.com.documents/tree/v%3Aroot%2Fprofiles",
                Xbox360Emulator.X360_MOBILE,
            ),
        )
    }

    @Test
    fun `a shared-storage grant reads as its path`() {
        assertEquals(
            "/storage/emulated/0/Android/data/xendroid.compose",
            Xbox360DataLibrary.folderLabel(
                "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fxendroid.compose",
                Xbox360Emulator.XENDROID,
            ),
        )
        assertEquals(
            "/storage/1A2B-3C4D/Xenia",
            Xbox360DataLibrary.folderLabel(
                "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AXenia",
                Xbox360Emulator.XENDROID,
            ),
        )
    }

    @Test
    fun `something unparseable still reads as the emulator's data`() {
        assertEquals("XenDroid data", Xbox360DataLibrary.folderLabel("not a uri", Xbox360Emulator.XENDROID))
    }
}
