package com.playfieldportal.feature.backup

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackupManager carries preferences by an explicit key list, so a key that is not named there
 * simply does not survive a restore — with no error, no log line and nothing to notice until a
 * user finds a setting missing on a new device. This test is the tripwire.
 *
 * Every key below was verified against its declaration site. Keys deliberately NOT carried are
 * listed at the bottom with the reason, so an omission reads as a decision rather than an
 * oversight.
 */
class BackupKeyCoverageTest {

    private val covered = BackupManager.BACKED_UP_KEY_NAMES

    private fun assertCovered(vararg keys: String) {
        keys.forEach { key ->
            assertTrue("$key is not in BackupManager's backed-up key list", key in covered)
        }
    }

    @Test
    fun `font colour and text legibility keys are backed up`() {
        assertCovered(
            "display_text_color",
            "display_text_color_exact",
            "display_text_legibility",
            "display_text_contrast_notice_suppressed",
        )
    }

    @Test
    fun `icon and text appearance settings are backed up`() {
        assertCovered(
            "display_icon_legibility",
            "display_solid_unfocused_icons",
            "display_text_shadow",
            "pref_animated_icons",
            "pref_icon_display_mode",
            "pref_icon1_linger_delay_seconds",
        )
    }

    @Test
    fun `XMB geometry is backed up`() {
        assertCovered(
            "display_xmb_scale",
            "display_bar_top_fraction",
            "display_xmb_layout_adjust",
        )
    }

    @Test
    fun `the one-colour theme cascade is backed up`() {
        assertCovered(
            "theme_accent_override",
            "theme_icon_color",
            "theme_applied_name",
            "theme_layout_spec",
        )
    }

    @Test
    fun `launch, artwork and achievement behaviour is backed up`() {
        assertCovered(
            "pref_direct_game_launch",
            "achievements_enabled",
            "artwork_import_move_files",
            "pref_dl_manuals",
            "pref_dl_video_snaps",
        )
    }

    @Test
    fun `discord presence and voice preferences are backed up`() {
        assertCovered(
            "discord_generic_activity",
            "discord_share_activity",
            "discord_voice_push_to_talk",
            "discord_voice_ptt_overlay",
            "discord_voice_ptt_keycode",
            "discord_voice_noise_cancellation",
            "discord_voice_echo_cancellation",
            "discord_voice_agc",
            "discord_voice_mic_sensitivity",
            "discord_voice_input_volume",
            "discord_voice_audio_balance",
        )
    }

    @Test
    fun `the wallpaper and scheme the font colour is measured against are backed up`() {
        // A picked colour is only meaningful against the backdrop it was chosen for, so a restore
        // that carries one without the other is a half-restore.
        assertCovered("display_custom_wallpaper", "display_color_scheme")
    }

    @Test
    fun `keys excluded on purpose stay excluded`() {
        // Migration and seed markers describe THIS install's schema progress. Restoring them onto
        // a fresh device would convince it that migrations already ran.
        val migrationMarkers = listOf(
            "debug_seeded_v1", "themes_seeded_v1", "library_consolidated_v22", "data_prep_version",
        )
        // Points at an extracted theme-icons directory that is not in BUNDLED_FILE_ROOTS, so
        // restoring the stamp would send observers to files that aren't there.
        val danglingStamp = listOf("theme_icons_stamp")
        // Device- or session-bound, not settings.
        val sessionState = listOf("achievements_sync_last", "session_blob")
        // Derived, not chosen. The wallpaper luminance survey embeds the absolute path it was
        // computed from, so a restored copy names the SOURCE device's filesDir and is rejected as
        // stale the first time it is read. StartupDataPrep recomputes it from the restored
        // wallpaper on the next cold start, which is both cheaper and correct — carrying it would
        // be dead weight that is discarded on arrival.
        val derivedCaches = listOf("display_wallpaper_luma")

        (migrationMarkers + danglingStamp + sessionState + derivedCaches).forEach { key ->
            assertTrue(
                "$key is carried by BackupManager — if that is now intended, move it out of this list",
                key !in covered,
            )
        }
    }
}
