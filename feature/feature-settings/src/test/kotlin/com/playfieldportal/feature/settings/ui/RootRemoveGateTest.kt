package com.playfieldportal.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A root folder's Remove button asks first. The button used to remove the folder on the press, and
 * with the cursor now landing straight on a row's buttons that press is one stray Confirm away.
 */
class RootRemoveGateTest {

    @Test
    fun `pressing Remove removes nothing until it is confirmed`() {
        var removed = 0
        val gate = RootRemoveGate()

        gate.request("/storage/emulated/0/PFP/Roms") { removed++ }

        assertEquals(0, removed)
        assertNotNull(gate.spec)
    }

    @Test
    fun `confirming removes the folder once and closes the question`() {
        var removed = 0
        val gate = RootRemoveGate()
        gate.request("/storage/emulated/0/PFP/Roms") { removed++ }

        gate.spec!!.onConfirm()

        assertEquals(1, removed)
        assertNull(gate.spec)
    }

    @Test
    fun `cancelling closes the question and removes nothing`() {
        var removed = 0
        val gate = RootRemoveGate()
        gate.request("/storage/emulated/0/PFP/Roms") { removed++ }

        gate.spec!!.onCancel()

        assertEquals(0, removed)
        assertNull(gate.spec)
    }

    @Test
    fun `the question names the folder, opens on Cancel and says no files are deleted`() {
        val gate = RootRemoveGate()
        gate.request("/storage/emulated/0/PFP/Roms") {}
        val spec = gate.spec!!

        assertTrue(spec.message.contains("/storage/emulated/0/PFP/Roms"))
        assertTrue(spec.message.contains("No files are deleted"))
        assertTrue("a destructive confirm opens on Cancel", spec.openOnCancel)
        assertEquals("Remove", spec.confirmLabel)
    }
}
