package com.playfieldportal.core.ui.icons

import com.playfieldportal.core.ui.R
import com.playfieldportal.themekit.SYSICON_PLATFORM_IDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Keeps `SYSICON_PLATFORM_IDS` (the theme-kit console-slot registry) and the R8-safe static
 * `when` in [systemIconRes] in lockstep. The `when` must stay static — it is what stops R8's
 * resource shrinker stripping the `sysicon_*` drawables — so the test, not reflection, is the
 * guard: every registered platform id must resolve to dedicated art, never the fallback.
 *
 * Pure JVM: R.drawable.* fields are compile-time constants, so no Robolectric is needed.
 */
class SystemIconsTest {

    @Test
    fun `platform id list is non-empty and unique`() {
        assertTrue(SYSICON_PLATFORM_IDS.isNotEmpty())
        assertEquals(
            SYSICON_PLATFORM_IDS.size,
            SYSICON_PLATFORM_IDS.toSet().size,
            "duplicate platform id in SYSICON_PLATFORM_IDS",
        )
    }

    @Test
    fun `every registered platform id resolves to dedicated art, not the fallback`() {
        for (id in SYSICON_PLATFORM_IDS) {
            val res = systemIconRes(id)
            assertNotEquals(
                R.drawable.sysicon_default,
                res,
                "platform '$id' resolves to sysicon_default — add a when branch or remove it from the registry",
            )
        }
    }

    @Test
    fun `ids are matched case-insensitively`() {
        for (id in SYSICON_PLATFORM_IDS) {
            assertEquals(
                systemIconRes(id),
                systemIconRes(id.uppercase()),
                "lookup must lowercase before matching: $id",
            )
        }
    }

    @Test
    fun `unregistered ids fall back to the default art`() {
        assertEquals(R.drawable.sysicon_default, systemIconRes(null))
        assertEquals(R.drawable.sysicon_default, systemIconRes("not_a_platform"))
        assertEquals(R.drawable.sysicon_default, systemIconRes("default"))
    }
}
