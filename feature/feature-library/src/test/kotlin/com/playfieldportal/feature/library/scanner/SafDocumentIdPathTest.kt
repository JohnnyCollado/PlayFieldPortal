package com.playfieldportal.feature.library.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafDocumentIdPathTest {

    @Test
    fun `primary volume maps to emulated 0`() {
        assertEquals(
            "/storage/emulated/0/ROMs/PSP/game.iso",
            safDocumentIdToRawPath("primary:ROMs/PSP/game.iso"),
        )
    }

    @Test
    fun `removable volume maps under storage uuid`() {
        assertEquals(
            "/storage/1A2B-3C4D/Games/game.chd",
            safDocumentIdToRawPath("1A2B-3C4D:Games/game.chd"),
        )
    }

    @Test
    fun `primary is case-insensitive`() {
        assertEquals(
            "/storage/emulated/0/a.bin",
            safDocumentIdToRawPath("PRIMARY:a.bin"),
        )
    }

    @Test
    fun `document id without a relative part is rejected`() {
        assertNull(safDocumentIdToRawPath("primary:"))
        assertNull(safDocumentIdToRawPath("primary"))
        assertNull(safDocumentIdToRawPath(""))
    }
}
