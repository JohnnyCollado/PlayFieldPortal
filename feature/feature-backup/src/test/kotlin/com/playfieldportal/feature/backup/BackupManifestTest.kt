package com.playfieldportal.feature.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `BackupManifest round-trips through JSON`() {
        val original = BackupManifest(
            formatVersion  = BACKUP_FORMAT_VERSION,
            appVersionCode = 42,
            appVersionName = "1.2.3",
            createdAt      = 1_700_000_000_000L,
            gameCount      = 150,
            sessionCount   = 300,
            categoryCount  = 7,
        )

        val encoded = json.encodeToString(BackupManifest.serializer(), original)
        val decoded = json.decodeFromString(BackupManifest.serializer(), encoded)

        assertEquals(original.formatVersion,  decoded.formatVersion)
        assertEquals(original.appVersionCode, decoded.appVersionCode)
        assertEquals(original.appVersionName, decoded.appVersionName)
        assertEquals(original.createdAt,      decoded.createdAt)
        assertEquals(original.gameCount,      decoded.gameCount)
        assertEquals(original.sessionCount,   decoded.sessionCount)
        assertEquals(original.categoryCount,  decoded.categoryCount)
    }

    @Test
    fun `BackupManifest default formatVersion matches BACKUP_FORMAT_VERSION constant`() {
        val manifest = BackupManifest(
            appVersionCode = 1,
            appVersionName = "0.1",
            createdAt      = 0L,
            gameCount      = 0,
            sessionCount   = 0,
            categoryCount  = 0,
        )
        assertEquals(BACKUP_FORMAT_VERSION, manifest.formatVersion)
    }

    @Test
    fun `SettingsSnapshot round-trips through JSON`() {
        val original = SettingsSnapshot(
            entries = mapOf("wave_mode" to "FULL", "thermal_aware" to "true", "api_key" to "abc123")
        )
        val encoded = json.encodeToString(SettingsSnapshot.serializer(), original)
        val decoded = json.decodeFromString(SettingsSnapshot.serializer(), encoded)
        assertEquals(original.entries, decoded.entries)
    }

    @Test
    fun `BackupEntry names are stable strings`() {
        assertEquals("manifest.json",       BackupEntry.MANIFEST)
        assertEquals("games.json",          BackupEntry.GAMES)
        assertEquals("categories.json",     BackupEntry.CATEGORIES)
        assertEquals("category_items.json", BackupEntry.CATEGORY_ITEMS)
        assertEquals("play_sessions.json",  BackupEntry.PLAY_SESSIONS)
        assertEquals("settings.json",       BackupEntry.SETTINGS)
    }
}
