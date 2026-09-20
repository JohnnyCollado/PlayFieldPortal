package com.playfieldportal.core.data.saf

import android.net.Uri
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The signature and the per-file reuse key: the two pieces of logic that decide whether a scan
 * does any real work. Both are pure — Robolectric is here only for a working [Uri].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SafTreeSignatureTest {

    // ── Tree signature ──────────────────────────────────────────────────────

    @Test fun `an unchanged tree keeps its signature regardless of walk order`() {
        val tree = listOf(entry("a.mp4", 100, 10), entry("b.mp4", 200, 20))
        assertEquals(safTreeSignature(tree), safTreeSignature(tree.reversed()))
    }

    @Test fun `adding a file changes the signature`() {
        val before = listOf(entry("a.mp4", 100, 10))
        assertNotEquals(safTreeSignature(before), safTreeSignature(before + entry("b.mp4", 200, 20)))
    }

    @Test fun `removing a file changes the signature`() {
        val before = listOf(entry("a.mp4", 100, 10), entry("b.mp4", 200, 20))
        assertNotEquals(safTreeSignature(before), safTreeSignature(listOf(before.first())))
    }

    @Test fun `a touched file changes the signature`() {
        assertNotEquals(
            safTreeSignature(listOf(entry("a.mp4", 100, 10))),
            safTreeSignature(listOf(entry("a.mp4", 100, 99))),
        )
    }

    // The case mtime alone cannot catch, and the reason size is in the signature at all.
    @Test fun `a resized file changes the signature even with no usable mtime`() {
        assertNotEquals(
            safTreeSignature(listOf(entry("a.mp4", sizeBytes = 100, lastModified = null))),
            safTreeSignature(listOf(entry("a.mp4", sizeBytes = 250, lastModified = null))),
        )
    }

    // ── Per-file reuse key ──────────────────────────────────────────────────

    @Test fun `an unchanged file is reused`() {
        assertTrue(child("a.mp4", 100, 10).matchesCachedFile(10, 100))
    }

    @Test fun `a file whose size moved is re-probed`() {
        assertFalse(child("a.mp4", 250, 10).matchesCachedFile(10, 100))
    }

    @Test fun `a file whose mtime moved is re-probed`() {
        assertFalse(child("a.mp4", 100, 99).matchesCachedFile(10, 100))
    }

    // The regression that made null-mtime providers silently permanent: `null == null` read as
    // "unchanged", so an edited file on a USB/MTP volume was never picked up again.
    @Test fun `a file with no mtime and no size is re-probed rather than assumed unchanged`() {
        assertFalse(child("a.mp4", sizeBytes = null, lastModified = null).matchesCachedFile(null, null))
    }

    // ...but a provider reporting size and no mtime must not be condemned to re-probe forever.
    @Test fun `a file with no mtime is still reused when size agrees`() {
        assertTrue(child("a.mp4", sizeBytes = 100, lastModified = null).matchesCachedFile(null, 100))
    }

    @Test fun `a newly seen file has nothing cached to match`() {
        assertFalse(child("a.mp4", 100, 10).matchesCachedFile(null, null))
    }

    private fun child(name: String, sizeBytes: Long?, lastModified: Long?) = SafChild(
        documentId = "primary:Media/$name",
        uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMedia/$name"),
        name = name,
        mime = "video/mp4",
        isDirectory = false,
        lastModified = lastModified,
        sizeBytes = sizeBytes,
    )

    private fun entry(name: String, sizeBytes: Long?, lastModified: Long?) =
        SafFile(child(name, sizeBytes, lastModified), relativePath = "")
}
