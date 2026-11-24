package com.playfieldportal.feature.settings.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Turning on the Goldberg installer always asks for a save backup first, wherever it is turned on.
 *
 * Survey U32 (docs/audits/codebase-survey-2026-10-22.md): Library Manager called itself "the same
 * switch" as Achievements ▸ Local Windows but turned it on with no warning.
 */
@RunWith(RobolectricTestRunner::class)
class GoldbergWarningSpecTest {

    @Test
    fun `the warning asks for a backup, opens on Cancel and enables only on confirm`() {
        var enabled = false
        var cancelled = false
        val spec = goldbergWarningSpec(
            ApplicationProvider.getApplicationContext<Context>().resources,
            onConfirm = { enabled = true }, onCancel = { cancelled = true })

        assertEquals("Back up your save files first", spec.title)
        assertEquals("I've backed up — enable", spec.confirmLabel)
        assertTrue(spec.openOnCancel)
        spec.onCancel(); assertTrue(cancelled); assertEquals(false, enabled)
        spec.onConfirm(); assertTrue(enabled)
    }
}
