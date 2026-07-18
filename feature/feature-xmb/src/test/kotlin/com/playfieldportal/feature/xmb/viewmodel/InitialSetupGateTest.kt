package com.playfieldportal.feature.xmb.viewmodel

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the upgrader gate for the first-run wizard: any pre-existing configuration means the
 * install is an upgrade and the wizard must be seeded as seen instead of shown.
 */
class InitialSetupGateTest {

    @Test fun `a fresh install has no existing config`() {
        assertFalse(XMBViewModel.hasExistingSetupConfig(preferencesOf()))
    }

    @Test fun `any root folder counts as existing config`() {
        listOf(
            "library_rom_root_tree_uris",
            "library_rom_root_tree_uri",
            "music_root_tree_uris",
            "video_root_tree_uris",
            "photo_root_tree_uris",
            "artwork_folder_tree_uri",
        ).forEach { key ->
            assertTrue(
                key,
                XMBViewModel.hasExistingSetupConfig(
                    preferencesOf(stringPreferencesKey(key) to "content://tree/primary%3AStuff")
                ),
            )
        }
    }

    @Test fun `a blank stored root does not count`() {
        val prefs = preferencesOf(stringPreferencesKey("music_root_tree_uris") to "  ")
        assertFalse(XMBViewModel.hasExistingSetupConfig(prefs))
    }

    @Test fun `a completed library setup counts as existing config`() {
        val prefs = preferencesOf(booleanPreferencesKey("library_setup_complete") to true)
        assertTrue(XMBViewModel.hasExistingSetupConfig(prefs))
    }
}
