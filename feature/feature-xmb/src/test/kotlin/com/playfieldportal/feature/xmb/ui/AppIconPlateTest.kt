package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An app inside a custom memory card shows its own launcher icon like anywhere else: no dark plate
 * behind it. Only the letter fallback (icon could not be loaded) keeps a plate, for contrast.
 * Custom artwork still gets its PSP tile — that is a different branch (PspIcon0Icon).
 */
class AppIconPlateTest {

    @Test
    fun `a loaded launcher icon has no plate behind it`() {
        assertNull(appIconPlate(hasLauncherIcon = true))
    }

    @Test
    fun `the letter fallback keeps a plate so the letter reads`() {
        assertNotNull(appIconPlate(hasLauncherIcon = false))
    }
}
