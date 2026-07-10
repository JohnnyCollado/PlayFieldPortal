package com.playfieldportal.feature.artwork.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
