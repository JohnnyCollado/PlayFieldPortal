package com.playfieldportal.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The file-URI templates behind deep-link launches. The DOA4 strings are the exact ones that booted
 * the game in X360 Mobile on a real device (`x360mobile://launch?uri=...`).
 */
class LaunchUrisTest {

    private val doa4 = "/storage/emulated/0/PFP/Roms/xbox360/Dead or Alive 4 (Asia) (En,Ja,Fr,De,Es,It,Zh,Ko).iso"

    @Test
    fun `a path becomes a file uri with only what must be escaped escaped`() {
        assertEquals(
            "file:///storage/emulated/0/PFP/Roms/xbox360/Dead%20or%20Alive%204%20(Asia)%20(En,Ja,Fr,De,Es,It,Zh,Ko).iso",
            LaunchUris.fileUriOf(doa4),
        )
    }

    @Test
    fun `characters that would break a uri are escaped`() {
        assertEquals("file:///roms/100%25%20%23hash%3F.iso", LaunchUris.fileUriOf("/roms/100% #hash?.iso"))
    }

    @Test
    fun `the encoded form is safe as a query parameter value`() {
        assertEquals(
            "file%3A%2F%2F%2Fstorage%2Femulated%2F0%2FPFP%2FRoms%2Fxbox360%2FDead%2520or%2520Alive%25204%2520" +
                "%28Asia%29%2520%28En%2CJa%2CFr%2CDe%2CEs%2CIt%2CZh%2CKo%29.iso",
            LaunchUris.queryEncode(LaunchUris.fileUriOf(doa4)),
        )
    }
}
