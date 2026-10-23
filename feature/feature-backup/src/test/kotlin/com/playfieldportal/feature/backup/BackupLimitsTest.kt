package com.playfieldportal.feature.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup format rules that must hold before Backup & Restore is switched back on.
 * All from survey section "Latent" (docs/audits/codebase-survey-2026-10-22.md).
 */
class BackupLimitsTest {

    // A backup bundles every artwork file with no limit, while restore read with the default
    // 512 entries / 128 MB — so an ordinary library's only backup could not be restored. Both
    // sides now use one limit, and creation refuses what restore would.
    @Test
    fun `an ordinary library fits the backup limits`() {
        // ~3,000 games with five art files each, plus a 128 MB ambience track.
        assertTrue(BackupLimits.fits(entries = 15_000, totalBytes = 1_500L * 1024 * 1024, largestEntryBytes = 128L * 1024 * 1024))
    }

    @Test
    fun `an archive restore would refuse is refused when it is made`() {
        assertFalse(BackupLimits.fits(entries = BackupLimits.ZIP.maxEntries + 1, totalBytes = 1, largestEntryBytes = 1))
        assertFalse(BackupLimits.fits(entries = 1, totalBytes = BackupLimits.ZIP.maxTotalBytes + 1, largestEntryBytes = 1))
        assertFalse(BackupLimits.fits(entries = 1, totalBytes = 1, largestEntryBytes = BackupLimits.ZIP.maxEntryBytes + 1))
    }

    // db_seeded_v1 sat inside a comment line, so it was never in the backed-up list.
    @Test
    fun `the seed flag is backed up`() {
        assertTrue("db_seeded_v1" in BackupManager.BACKED_UP_KEY_NAMES)
    }

    // Restore called prefs.clear(): every key the backup does not list — one-time migration
    // flags, device-only state — was erased, so finished migrations ran again.
    @Test
    fun `applying a snapshot touches only backed-up keys`() {
        val migrationFlag = booleanPreferencesKey("library_consolidated_v22")
        val sgdbKey = stringPreferencesKey("sgdb_api_key")
        val prefs = mutablePreferencesOf(migrationFlag to true, sgdbKey to "old-key")

        BackupManager.applySettingsSnapshot(prefs, SettingsSnapshot(emptyMap()))

        assertEquals(true, prefs[migrationFlag])
        assertNull("a backed-up key the snapshot lacks goes back to its default", prefs[sgdbKey])
    }
}
