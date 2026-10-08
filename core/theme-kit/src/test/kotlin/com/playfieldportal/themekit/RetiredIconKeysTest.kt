package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Favorites is not a crossbar category: it is the Game column's Favorites card, `sysicon_favorites`.
 * The old `catbar_favorites` slot is retired. Bundles written before still carry
 * `icons/catbar_favorites.*`, so the reader moves that art onto the slot that now draws it, and
 * the writer never emits the old key again.
 */
class RetiredIconKeysTest {

    private val manifest = """{"manifest":"pfptheme","schemaVersion":4,"name":"Old","accentColor":"#FF72B1"}""".toByteArray()
    private val legacyArt = "legacy-favorites-png".toByteArray()
    private val currentArt = "current-favorites-png".toByteArray()

    @Test
    fun `catbar_favorites is no longer a slot and retires onto sysicon_favorites`() {
        assertFalse(CustomizableIcons.isValidKey("catbar_favorites"))
        assertFalse(IconSlots.isValidKey("catbar_favorites"))
        assertEquals("sysicon_favorites", IconSlots.RETIRED["catbar_favorites"])
        IconSlots.RETIRED.values.forEach { assertTrue(CustomizableIcons.isValidKey(it), it) }
    }

    @Test
    fun `an old bundle's favorites crossbar icon is read as the Favorites card`() {
        val bundle = assertNotNull(
            PfpThemeCodec.read(ThemeFixtures.zip("manifest.json" to manifest, "icons/catbar_favorites.png" to legacyArt)),
        )
        assertContentEquals(legacyArt, assertNotNull(bundle.icons["sysicon_favorites"]).bytes)
        assertFalse("catbar_favorites" in bundle.icons)
        assertTrue(bundle.passthrough.none { it.name == "icons/catbar_favorites.png" }, "migrated, not passed through")
    }

    @Test
    fun `the bundle's own Favorites card wins over the retired icon, whatever the entry order`() {
        val orders = listOf(
            listOf("icons/catbar_favorites.png" to legacyArt, "sysicons/favorites.png" to currentArt),
            listOf("sysicons/favorites.png" to currentArt, "icons/catbar_favorites.png" to legacyArt),
        )
        for (entries in orders) {
            val bytes = ThemeFixtures.zip("manifest.json" to manifest, *entries.toTypedArray())
            val bundle = assertNotNull(PfpThemeCodec.read(bytes))
            assertContentEquals(currentArt, assertNotNull(bundle.icons["sysicon_favorites"]).bytes)
        }
    }

    @Test
    fun `a re-written old bundle carries the art under sysicons only`() {
        val old = assertNotNull(
            PfpThemeCodec.read(ThemeFixtures.zip("manifest.json" to manifest, "icons/catbar_favorites.png" to legacyArt)),
        )
        val names = entryNames(PfpThemeCodec.write(old))
        assertTrue("sysicons/favorites.png" in names, "$names")
        assertTrue(names.none { "catbar_favorites" in it }, "$names")
    }

    @Test
    fun `a bundle's contents list the retired icon under the slot it fills`() {
        val file = kotlin.io.path.createTempFile(suffix = ".pfptheme").toFile()
        try {
            file.writeBytes(ThemeFixtures.zip("manifest.json" to manifest, "icons/catbar_favorites.png" to legacyArt))
            assertEquals(setOf("sysicon_favorites"), assertNotNull(PfpThemeCodec.contents(file)).iconKeys)
        } finally {
            file.delete()
        }
    }

    private fun entryNames(zip: ByteArray): List<String> = buildList {
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                add(entry.name)
                z.copyTo(ByteArrayOutputStream())
            }
        }
    }
}
