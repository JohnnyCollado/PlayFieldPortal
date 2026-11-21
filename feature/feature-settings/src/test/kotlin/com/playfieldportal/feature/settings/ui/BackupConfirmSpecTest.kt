package com.playfieldportal.feature.settings.ui

import android.content.res.Resources
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.feature.backup.BackupInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Backup & Restore confirms: destructive, opening on Cancel, and naming what they act on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupConfirmSpecTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val info = BackupInfo("pfp-2026.pfpbackup", Uri.parse("content://tree/doc1"), 1L)

    @Test
    fun `the confirm is destructive, opens on cancel and names the backup`() {
        val spec = deleteBackupConfirmSpec(resources, info, onConfirm = {}, onCancel = {})

        assertTrue(spec.destructive)
        assertTrue(spec.openOnCancel)
        assertTrue(spec.message.contains("pfp-2026.pfpbackup"))
    }

    @Test
    fun `the restore confirm is destructive, opens on cancel and says what it replaces`() {
        val named = restoreBackupConfirmSpec(resources, "pfp-2026.pfpbackup", onConfirm = {}, onCancel = {})
        val picked = restoreBackupConfirmSpec(resources, null, onConfirm = {}, onCancel = {})

        assertTrue(named.destructive)
        assertTrue(named.openOnCancel)
        assertTrue(named.message.contains("pfp-2026.pfpbackup"))
        assertTrue(named.message.contains("replace"))
        assertTrue(picked.destructive)
        assertTrue(picked.openOnCancel)
    }

    @Test
    fun `the restore runs on Confirm and not on Cancel`() {
        var restored = 0
        var cancelled = 0
        val spec = restoreBackupConfirmSpec(resources, null, onConfirm = { restored++ }, onCancel = { cancelled++ })

        spec.onCancel()
        assertEquals(0 to 1, restored to cancelled)

        spec.onConfirm()
        assertEquals(1 to 1, restored to cancelled)
    }

    @Test
    fun `the delete runs on Confirm and not on Cancel`() {
        var deleted = 0
        var cancelled = 0
        val spec = deleteBackupConfirmSpec(resources, info, onConfirm = { deleted++ }, onCancel = { cancelled++ })

        spec.onCancel()
        assertEquals(0 to 1, deleted to cancelled)

        spec.onConfirm()
        assertEquals(1 to 1, deleted to cancelled)
    }
}
