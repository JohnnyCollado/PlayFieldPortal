package com.playfieldportal.core.data.database.seeder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Only app-private paths are re-homed onto this install's filesDir.
 *
 * Survey L21 (docs/audits/codebase-survey-2026-10-22.md): any path containing "/files/" was
 * rewritten, so artwork on shared storage (…/Android/data/<pkg>/files/…, or a user folder called
 * "files") was pointed at a file that does not exist and then cleared.
 */
class RehomeInternalPathTest {

    private val filesDir = "/data/user/0/com.playfieldportal.launcher/files"
    private val everything: (String) -> Boolean = { true }
    private val nothing: (String) -> Boolean = { false }

    @Test
    fun `an app-private path from another package moves onto this filesDir`() {
        assertEquals(
            "$filesDir/artwork/1/hero.png",
            rehomeInternalPath("/data/user/0/com.playfieldportal.launcher.debug/files/artwork/1/hero.png", filesDir, everything),
        )
        assertEquals(
            "$filesDir/artwork/1/hero.png",
            rehomeInternalPath("/data/data/com.old/files/artwork/1/hero.png", filesDir, everything),
        )
        assertEquals(
            "$filesDir/wallpaper.png",
            rehomeInternalPath("file:///data/user/0/com.old/files/wallpaper.png", filesDir, everything),
        )
    }

    @Test
    fun `an app-private path whose file is gone is cleared`() {
        assertNull(rehomeInternalPath("/data/data/com.old/files/artwork/1/hero.png", filesDir, nothing))
    }

    @Test
    fun `shared-storage paths and content uris are left alone even when they contain files`() {
        listOf(
            "/storage/emulated/0/Android/data/com.playfieldportal.launcher/files/art.png",
            "/storage/emulated/0/files/x.png",
            "content://media/external/files/12",
        ).forEach { assertEquals(it, rehomeInternalPath(it, filesDir, nothing)) }
    }
}
