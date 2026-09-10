package com.playfieldportal.feature.artwork.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkFileNamingTest {

    // Fixed names must never change — they are the on-disk contract with existing installs.
    @Test
    fun `fixed names match the pre-seam layout`() {
        assertEquals("icon.jpg",       ArtworkFileNaming.fixedName(ArtworkKind.ICON))
        assertEquals("hero.jpg",       ArtworkFileNaming.fixedName(ArtworkKind.HERO))
        assertEquals("background.jpg", ArtworkFileNaming.fixedName(ArtworkKind.BACKGROUND))
        assertEquals("logo.png",       ArtworkFileNaming.fixedName(ArtworkKind.LOGO))
    }

    @Test
    fun `versioned names embed kind, timestamp and extension`() {
        assertEquals("background_1234.webp", ArtworkFileNaming.versionedName(ArtworkKind.BACKGROUND, "webp", nowMillis = 1234))
        assertEquals("icon_99.jpg", ArtworkFileNaming.versionedName(ArtworkKind.ICON, "jpg", nowMillis = 99))
    }

    @Test
    fun `prune matches versioned files and the legacy fixed name of the same kind`() {
        assertTrue(ArtworkFileNaming.isPruneCandidate(ArtworkKind.ICON, "icon_1718000000.jpg"))
        assertTrue(ArtworkFileNaming.isPruneCandidate(ArtworkKind.ICON, "icon.jpg"))
        assertTrue(ArtworkFileNaming.isPruneCandidate(ArtworkKind.HERO, "hero_1.png"))
    }

    @Test
    fun `prune never matches other kinds`() {
        assertFalse(ArtworkFileNaming.isPruneCandidate(ArtworkKind.ICON, "hero_1718000000.jpg"))
        assertFalse(ArtworkFileNaming.isPruneCandidate(ArtworkKind.ICON, "background.jpg"))
        assertFalse(ArtworkFileNaming.isPruneCandidate(ArtworkKind.HERO, "logo.png"))
        // "iconography.jpg" style prefix collisions: the separator underscore is required.
        assertFalse(ArtworkFileNaming.isPruneCandidate(ArtworkKind.ICON, "iconography.jpg"))
    }

    // ── Ordinals (C16 task 0.1) ───────────────────────────────────────────────

    @Test
    fun `position zero keeps the historic bare name so existing installs never move`() {
        assertEquals("screenshot.jpg", ArtworkFileNaming.fixedName(ArtworkKind.SCREENSHOT, 0))
        assertEquals("video.mp4",      ArtworkFileNaming.fixedName(ArtworkKind.VIDEO, 0))
        assertEquals("screenshot",     ArtworkFileNaming.baseName(ArtworkKind.SCREENSHOT, 0))
    }

    @Test
    fun `later positions get a two-digit ordinal before the extension`() {
        assertEquals("screenshot_01.jpg", ArtworkFileNaming.fixedName(ArtworkKind.SCREENSHOT, 1))
        assertEquals("screenshot_12.jpg", ArtworkFileNaming.fixedName(ArtworkKind.SCREENSHOT, 12))
        assertEquals("video_03.mp4",      ArtworkFileNaming.fixedName(ArtworkKind.VIDEO, 3))
    }

    @Test
    fun `portable stems carry the same ordinal rule`() {
        assertEquals("Final Fantasy X (USA)", ArtworkFileNaming.withOrdinal("Final Fantasy X (USA)", 0))
        assertEquals("Final Fantasy X (USA)_02", ArtworkFileNaming.withOrdinal("Final Fantasy X (USA)", 2))
        assertEquals(2, ArtworkFileNaming.ordinalOf("Final Fantasy X (USA)_02"))
        assertEquals("Final Fantasy X (USA)", ArtworkFileNaming.stripOrdinal("Final Fantasy X (USA)_02"))
        // A collision-suffixed base still round-trips.
        assertEquals("Zelda (2)_01", ArtworkFileNaming.withOrdinal("Zelda (2)", 1))
        assertEquals("Zelda (2)", ArtworkFileNaming.stripOrdinal("Zelda (2)_01"))
    }

    @Test
    fun `an ordinal is exactly two digits so a versioned timestamp is never mistaken for one`() {
        assertEquals(0, ArtworkFileNaming.ordinalOf("screenshot_1718000000000"))
        assertEquals(0, ArtworkFileNaming.ordinalOf("screenshot_1"))
        assertEquals(0, ArtworkFileNaming.ordinalOf("screenshot"))
    }

    @Test
    fun `sort order is recoverable from the filename for multi-asset kinds`() {
        assertEquals(0, ArtworkFileNaming.sortOrderFromFileName(ArtworkKind.SCREENSHOT, "screenshot.jpg"))
        assertEquals(4, ArtworkFileNaming.sortOrderFromFileName(ArtworkKind.SCREENSHOT, "screenshot_04.jpg"))
        assertEquals(1, ArtworkFileNaming.sortOrderFromFileName(ArtworkKind.VIDEO, "video_01.mp4"))
        // Not this kind's file at all.
        assertNull(ArtworkFileNaming.sortOrderFromFileName(ArtworkKind.SCREENSHOT, "video_01.mp4"))
        assertNull(ArtworkFileNaming.sortOrderFromFileName(ArtworkKind.SCREENSHOT, "screenshot_1718000000.jpg"))
    }

    @Test
    fun `single-art kinds never read an ordinal out of a name`() {
        assertEquals(0, ArtworkFileNaming.sortOrderFromFileName(ArtworkKind.ICON, "icon.jpg"))
        assertNull(ArtworkFileNaming.sortOrderFromFileName(ArtworkKind.ICON, "icon_01.jpg"))
        assertFalse(ArtworkFileNaming.supportsMultiple(ArtworkKind.ICON1))
        assertTrue(ArtworkFileNaming.supportsMultiple(ArtworkKind.VIDEO))
        assertTrue(ArtworkFileNaming.supportsMultiple(ArtworkKind.SCREENSHOT))
    }

    // AD-1: saving screenshot #2 must not delete screenshot #1's bytes.
    @Test
    fun `a save at one position never prunes a sibling ordinal`() {
        assertFalse(ArtworkFileNaming.isPruneCandidate(ArtworkKind.SCREENSHOT, "screenshot.jpg", sortOrder = 1))
        assertFalse(ArtworkFileNaming.isPruneCandidate(ArtworkKind.SCREENSHOT, "screenshot_02.jpg", sortOrder = 1))
        assertTrue(ArtworkFileNaming.isPruneCandidate(ArtworkKind.SCREENSHOT, "screenshot_01.jpg", sortOrder = 1))

        // Position 0 owns the legacy versioned namespace, but not its siblings' ordinals.
        assertTrue(ArtworkFileNaming.isPruneCandidate(ArtworkKind.SCREENSHOT, "screenshot.jpg", sortOrder = 0))
        assertTrue(ArtworkFileNaming.isPruneCandidate(ArtworkKind.SCREENSHOT, "screenshot_1718000000.jpg", sortOrder = 0))
        assertFalse(ArtworkFileNaming.isPruneCandidate(ArtworkKind.SCREENSHOT, "screenshot_01.jpg", sortOrder = 0))
    }
}
