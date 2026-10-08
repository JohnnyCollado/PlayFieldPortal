package com.playfieldportal.studio.preview

import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import com.playfieldportal.themekit.SharedIconArt
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Slots that draw the same built-in picture must be one [SharedIconArt] group, so an icon job
 * (PTF import, default art) cannot reach some of them and miss the rest. Compared byte for byte on
 * the Studio's copies of the launcher art, which mirror core-ui and feature-xmb's files.
 */
class StudioIconSetSharedArtTest {

    /**
     * Consoles that ship one picture for related hardware: the Sega family's bundled art, and the
     * aliases in [StudioIconSet.consoleResource] (Xbox borrows the Xbox 360 icon, the CPS boards
     * share the CP System badge). They are separate consoles a theme gives separate art, so they
     * share nothing on purpose.
     */
    private val sameArtDifferentConsoles: List<Set<String>> = listOf(
        setOf("sysicon_megadrive", "sysicon_sega32x", "sysicon_segacd"),
        setOf("sysicon_x360", "sysicon_xbox"),
        setOf("sysicon_cps1", "sysicon_cps2", "sysicon_cps3"),
    )

    private fun defaultResource(key: String): String? =
        StudioIconSet.RESOURCE_SLOTS[key] ?: StudioIconSet.consoleResource(key)

    private fun hash(path: String): String {
        val bytes = assertNotNull(StudioIconSet::class.java.classLoader.getResourceAsStream(path), path).use { it.readBytes() }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Raster slots grouped by identical default art; physical media (generic disc and cart art) is not slot art. */
    private fun identicalArt(): List<Set<String>> = CustomizableIcons.ALL
        .filter { it.group != IconSlot.Group.PHYSICAL_MEDIA }
        .mapNotNull { slot -> defaultResource(slot.key)?.takeIf { it.endsWith(".png") }?.let { slot.key to hash(it) } }
        .groupBy({ it.second }, { it.first })
        .values.map { it.toSet() }
        .filter { it.size > 1 }

    @Test
    fun `slots that draw the same picture are one shared-art group`() {
        val groups = SharedIconArt.ALL.map { it.toSet() } + sameArtDifferentConsoles
        val ungrouped = identicalArt().filter { it !in groups }
        assertEquals(emptyList(), ungrouped, "identical default art outside SharedIconArt")
    }

    @Test
    fun `every shared-art group draws one picture`() {
        for (group in SharedIconArt.ALL) {
            val hashes = group.map { key -> hash(assertNotNull(defaultResource(key), key)) }.toSet()
            assertEquals(1, hashes.size, "$group draws different pictures")
        }
    }

    @Test
    fun `the memory-card items all take the memory-card png`() {
        SharedIconArt.MEMORY_CARD.filter { !it.startsWith(CustomizableIcons.SYSICON_PREFIX) }.forEach {
            assertEquals("xmb/item_memcard.png", StudioIconSet.RESOURCE_SLOTS[it], it)
        }
    }

    @Test
    fun `favorites has no crossbar art of its own`() {
        assertTrue("catbar_favorites" !in StudioIconSet.RESOURCE_SLOTS)
        assertEquals("xmb/sysicon_favorites.png", StudioIconSet.consoleResource("sysicon_favorites"))
    }
}
