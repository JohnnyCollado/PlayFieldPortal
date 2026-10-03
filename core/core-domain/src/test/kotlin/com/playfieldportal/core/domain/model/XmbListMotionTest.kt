package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Item List Motion setting (Settings ▸ Interface ▸ Display): Rewind is the default,
 * Glide is the one-spring glide that shipped first, and a stored value that no longer parses
 * falls back to the default instead of failing.
 */
class XmbListMotionTest {

    @Test
    fun `Rewind is the default`() {
        assertEquals(XmbListMotion.REWIND, XmbListMotion.DEFAULT)
        assertEquals(XmbListMotion.REWIND, XmbListMotion.fromName(null))
    }

    @Test
    fun `stored names round-trip and unknown names fall back to the default`() {
        for (motion in XmbListMotion.entries) assertEquals(motion, XmbListMotion.fromName(motion.name))
        assertEquals(XmbListMotion.DEFAULT, XmbListMotion.fromName("BOUNCE"))
        assertEquals(XmbListMotion.DEFAULT, XmbListMotion.fromName(""))
    }

    @Test
    fun `labels are the names the settings row shows`() {
        assertEquals(listOf("Rewind", "Glide"), XmbListMotion.entries.map { it.label })
    }
}
