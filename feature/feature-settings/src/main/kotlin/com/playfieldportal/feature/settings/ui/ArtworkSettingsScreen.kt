package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.components.PfpModalSpec
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.domain.text.labelRes
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.artwork.api.ArtworkScrapePreferences
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.ArtworkSettingsViewModel

@Composable
fun ArtworkSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtworkSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Artwork Folder & Import lives on its own sub-screen (same pattern as Library Manager's
    // internal sections) — BACK returns here.
    var showImport by remember { mutableStateOf(false) }
    if (showImport) {
        ArtworkImportScreen(onBack = { showImport = false }, modifier = modifier)
        return
    }

    var sgdbKeyDraft by remember(state.apiKeyMasked) { mutableStateOf("") }
    var tgdbKeyDraft by remember(state.hasTgdbKey) { mutableStateOf("") }
    var igdbClientIdDraft by remember(state.igdbClientId) { mutableStateOf("") }
    var igdbClientSecretDraft by remember { mutableStateOf("") }
    var ssUsernameDraft by remember(state.ssUsername) { mutableStateOf("") }
    var ssPasswordDraft by remember { mutableStateOf("") }

    // Debug builds only. Any MIME type: pickers often report .properties files as octet-stream.
    val credentialsFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.loadDebugCredentials(it) }
    }

    val modal = rememberSettingsModal(
        if (state.confirmRescrapeAll) {
            PfpModalSpec.Confirm(
                key = "rescrape_all",
                title = stringResource(R.string.setd_rescrape_title),
                message = pluralStringResource(R.plurals.setd_rescrape_message, state.status.total, state.status.total),
                confirmLabel = stringResource(R.string.setd_rescrape_confirm),
                onConfirm = { viewModel.confirmRescrapeAll() },
                onCancel = { viewModel.cancelRescrapeAll() },
            )
        } else {
            null
        },
    )

    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.setd_artwork_title),
        onBack   = onBack,
        modifier = modifier,
        modalOpen = modal.open,
        onInterceptAction = modal.intercept,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {

            // ── Debug: credentials file ───────────────────────────────────────
            // Never in release: BuildConfig.DEBUG is a compile-time false there, so the row is
            // stripped and the ViewModel refuses the load as well.
            if (com.playfieldportal.feature.settings.BuildConfig.DEBUG && state.debugCredentialsAvailable) {
                SettingsGroup("Debug")

                SettingsRow(
                    label    = "Load credentials file",
                    sublabel = "Fill every artwork and achievement credential from a .properties file " +
                        "(steamgriddb.apiKey, thegamesdb.apiKey, igdb.*, screenscraper.*, retroachievements.*, steam.*)",
                    onClick  = { credentialsFilePicker.launch(arrayOf("*/*")) },
                )

                state.debugCredentialsStatus?.let {
                    SettingsRow(
                        label    = it,
                        sublabel = "Tap to dismiss",
                        onClick  = { viewModel.dismissDebugCredentialsStatus() },
                    )
                }
            }

            // ── Artwork library / import ──────────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_group_artwork_library))

            SettingsRow(
                label    = stringResource(R.string.setd_artwork_folder_row),
                sublabel = if (state.artworkFolderUnavailable)
                    stringResource(R.string.setd_artwork_folder_unavailable_sublabel)
                else stringResource(R.string.setd_artwork_folder_sublabel),
                labelTrailing = if (state.artworkFolderUnavailable) ({
                    KnowledgeBadge(stringResource(R.string.setd_unavailable), WarningAmber)
                }) else null,
                onClick  = { showImport = true },
            )

            // ── Artwork status ────────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_group_artwork_status))

            SettingsValueRow(label = stringResource(R.string.setd_status_total),      value = state.status.total.toString())
            SettingsValueRow(label = stringResource(R.string.setd_status_complete),         value = state.status.complete.toString())
            SettingsValueRow(label = stringResource(R.string.setd_status_missing),          value = state.status.missing.toString())
            SettingsValueRow(label = stringResource(R.string.setd_status_stale),  value = state.status.stale.toString())
            SettingsRow(
                label    = stringResource(R.string.setd_refresh_status),
                sublabel = if (state.isLoadingStatus) stringResource(R.string.setd_refresh_checking) else stringResource(R.string.setd_refresh_sublabel),
                onClick  = if (state.isLoadingStatus) null else ({ viewModel.refreshStatus() }),
            )

            // ── Scraping ──────────────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_group_scrape))

            if (state.isScraping) {
                Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 10.dp)) {
                    LinearProgressIndicator(
                        progress = {
                            if (state.scrapeTotal > 0) state.scrapeCurrent.toFloat() / state.scrapeTotal else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = SettingsAccent,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text  = stringResource(
                            R.string.setd_scrape_progress,
                            state.scrapeCurrent,
                            state.scrapeTotal,
                            state.scrapeTitle.asString(),
                        ),
                        color = SettingsText,
                    )
                    if (state.scrapeSource.isNotEmpty() || state.scrapeAsset.isNotEmpty()) {
                        Text(
                            text = buildString {
                                if (state.scrapeSource.isNotEmpty()) append(state.scrapeSource)
                                if (state.scrapeSource.isNotEmpty() && state.scrapeAsset.isNotEmpty()) append(" › ")
                                if (state.scrapeAsset.isNotEmpty()) append(state.scrapeAsset)
                            },
                            color = SettingsAccent,
                        )
                    }
                    Text(
                        text  = stringResource(R.string.setd_scrape_counts, state.scrapeSucceeded, state.scrapeFailed),
                        color = SettingsSubtext,
                    )
                    Text(
                        text  = stringResource(R.string.setd_scrape_background),
                        color = SettingsSubtext,
                    )
                }
                SettingsRow(
                    label    = stringResource(R.string.setd_scrape_cancel),
                    sublabel = stringResource(R.string.setd_scrape_cancel_sublabel),
                    onClick  = { viewModel.cancelScrape() },
                )
            } else {
                SettingsRow(
                    label    = stringResource(R.string.setd_scrape_all),
                    sublabel = stringResource(R.string.setd_scrape_all_sublabel),
                    onClick  = { viewModel.requestRescrapeAll() },
                    destructive = true,
                )
                SettingsRow(
                    label    = stringResource(R.string.setd_scrape_missing),
                    sublabel = stringResource(R.string.setd_scrape_missing_sublabel),
                    onClick  = { viewModel.scrapeMissingOnly() },
                )
            }

            state.summary?.let { summary ->
                SettingsRow(
                    label    = summary.asString(),
                    sublabel = stringResource(R.string.setd_tap_to_dismiss),
                    onClick  = { viewModel.dismissSummary() },
                )
            }

            // ── Source priority ───────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_group_source_priority))

            if (state.ssEnabled) {
                SettingsValueRow(label = stringResource(R.string.setd_source_primary),    value = stringResource(R.string.setd_source_screenscraper_hash))
                SettingsValueRow(label = stringResource(R.string.setd_source_fallback, 1), value = "TheGamesDB")
                SettingsValueRow(label = stringResource(R.string.setd_source_fallback, 2), value = "IGDB")
                SettingsValueRow(label = stringResource(R.string.setd_source_fallback, 3), value = stringResource(R.string.setd_source_steamgriddb_artwork))
            } else {
                SettingsValueRow(label = stringResource(R.string.setd_source_primary),    value = "TheGamesDB")
                SettingsValueRow(label = stringResource(R.string.setd_source_fallback, 1), value = "IGDB")
                SettingsValueRow(label = stringResource(R.string.setd_source_fallback, 2), value = stringResource(R.string.setd_source_steamgriddb_artwork))
            }

            // ── Art preferences ───────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_group_art_preferences))

            SettingsValueRow(
                label    = stringResource(R.string.setd_icon_display),
                sublabel = stringResource(R.string.setd_icon_display_sublabel),
                value    = stringResource(state.iconDisplayMode.labelRes),
                onClick  = { viewModel.cycleIconDisplayMode() },
            )

            // "Animated images" lives in Display now, as Motion: one setting for everything that moves.
            SettingsToggleRow(
                label    = stringResource(R.string.setd_animated_icons),
                sublabel = stringResource(R.string.setd_animated_icons_sublabel),
                checked  = state.animatedIcons,
                onToggle = { viewModel.setAnimatedIcons(it) },
            )

            SettingsSliderRow(
                label     = stringResource(R.string.setd_snap_delay),
                sublabel  = stringResource(R.string.setd_snap_delay_sublabel, formatSnapDelay(state.icon1LingerDelaySeconds)),
                value     = state.icon1LingerDelaySeconds,
                onValueChange = viewModel::setIcon1LingerDelaySeconds,
                valueRange = 1f..5f,
                steps     = 7,
                enabled  = state.animatedIcons,
                valueFormatter = { formatSnapDelay(it) },
            )

            SettingsToggleRow(
                label    = stringResource(R.string.setd_crop_preview),
                sublabel = stringResource(R.string.setd_crop_preview_sublabel),
                checked  = state.cropPreviewEnabled,
                onToggle = { viewModel.setCropPreviewEnabled(it) },
            )

            SettingsToggleRow(
                label    = stringResource(R.string.setd_prefer_sgdb_heroes),
                sublabel = stringResource(R.string.setd_prefer_sgdb_heroes_sublabel),
                checked  = state.preferSteamGridDbHeroes,
                onToggle = { viewModel.setPreferSteamGridDbHeroes(it) },
            )

            SettingsToggleRow(
                label    = stringResource(R.string.setd_download_heroes),
                sublabel = stringResource(R.string.setd_download_heroes_sublabel),
                checked  = state.downloadHeroes,
                onToggle = { viewModel.setDownloadHeroes(it) },
            )

            SettingsToggleRow(
                label    = stringResource(R.string.setd_download_logos),
                sublabel = stringResource(R.string.setd_download_logos_sublabel),
                checked  = state.downloadLogos,
                onToggle = { viewModel.setDownloadLogos(it) },
            )

            if (state.ssEnabled) {
                SettingsToggleRow(
                    label    = stringResource(R.string.setd_download_manuals),
                    sublabel = stringResource(R.string.setd_download_manuals_sublabel),
                    checked  = state.downloadManuals,
                    onToggle = { viewModel.setDownloadManuals(it) },
                )
                SettingsToggleRow(
                    label    = stringResource(R.string.setd_download_video_snaps),
                    sublabel = stringResource(R.string.setd_download_video_snaps_sublabel),
                    checked  = state.downloadVideoSnaps,
                    onToggle = { viewModel.setDownloadVideoSnaps(it) },
                )
                SettingsRow(
                    label    = stringResource(R.string.setd_artwork_region, regionLabel(state.artworkRegion)),
                    sublabel = stringResource(R.string.setd_artwork_region_sublabel),
                    onClick  = { viewModel.cycleArtworkRegion() },
                )
            }

            // ── SteamGridDB API key ───────────────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_group_sgdb_api))

            SettingsTextFieldRow(
                label         = stringResource(if (state.hasApiKey) R.string.setd_api_key_saved else R.string.setd_api_key),
                value         = sgdbKeyDraft,
                onValueChange = { sgdbKeyDraft = it },
                placeholder   = stringResource(if (state.hasApiKey) R.string.setd_key_replace_placeholder else R.string.setd_sgdb_key_placeholder),
                isPassword    = true,
                helper        = stringResource(R.string.setd_sgdb_key_helper),
            )

            if (sgdbKeyDraft.isNotBlank()) {
                SettingsRow(
                    label   = stringResource(R.string.setd_save_api_key),
                    onClick = {
                        viewModel.saveApiKey(sgdbKeyDraft)
                        sgdbKeyDraft = ""
                    },
                )
            }

            if (state.hasApiKey) {
                SettingsRow(
                    label    = stringResource(R.string.setd_remove_api_key),
                    sublabel = stringResource(R.string.setd_remove_api_key_sublabel),
                    onClick  = { viewModel.clearApiKey() },
                )
            }

            // ── TheGamesDB API key ────────────────────────────────────────────
            // Stored encrypted like the SteamGridDB key. Without one TheGamesDB is skipped by the
            // scraper and not offered in the Artwork Studio.
            SettingsGroup(stringResource(R.string.setd_group_tgdb_api))

            SettingsTextFieldRow(
                label         = stringResource(if (state.hasTgdbKey) R.string.setd_api_key_saved else R.string.setd_api_key),
                value         = tgdbKeyDraft,
                onValueChange = { tgdbKeyDraft = it },
                placeholder   = stringResource(if (state.hasTgdbKey) R.string.setd_key_replace_placeholder else R.string.setd_tgdb_key_placeholder),
                isPassword    = true,
                helper        = stringResource(R.string.setd_tgdb_key_helper),
            )

            if (tgdbKeyDraft.isNotBlank()) {
                SettingsRow(
                    label   = stringResource(R.string.setd_save_tgdb_key),
                    onClick = {
                        viewModel.saveTgdbKey(tgdbKeyDraft)
                        tgdbKeyDraft = ""
                    },
                )
            }

            if (state.hasTgdbKey) {
                SettingsRow(
                    label    = stringResource(R.string.setd_remove_tgdb_key),
                    sublabel = stringResource(R.string.setd_remove_tgdb_key_sublabel),
                    onClick  = { viewModel.clearTgdbKey() },
                )
            }

            // ── IGDB credentials (optional) ───────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_group_igdb))

            SettingsTextFieldRow(
                label         = stringResource(if (state.hasIgdbCredentials) R.string.setd_igdb_client_id_saved else R.string.setd_igdb_client_id),
                value         = igdbClientIdDraft,
                onValueChange = { igdbClientIdDraft = it },
                placeholder   = if (state.hasIgdbCredentials) "••••••••" else stringResource(R.string.setd_igdb_client_id_placeholder),
            )
            SettingsTextFieldRow(
                label         = stringResource(R.string.setd_igdb_client_secret),
                value         = igdbClientSecretDraft,
                onValueChange = { igdbClientSecretDraft = it },
                placeholder   = stringResource(R.string.setd_igdb_client_secret_placeholder),
                isPassword    = true,
                helper        = stringResource(R.string.setd_igdb_helper),
            )

            state.igdbCredentialStatus?.let {
                SettingsRow(label = it.asString(), sublabel = stringResource(R.string.setd_tap_to_dismiss), onClick = { viewModel.dismissCredentialStatus() })
            }

            if (igdbClientIdDraft.isNotBlank() && igdbClientSecretDraft.isNotBlank()) {
                SettingsRow(
                    label   = stringResource(R.string.setd_test_credentials),
                    onClick = { viewModel.testIgdbCredentials(igdbClientIdDraft, igdbClientSecretDraft) },
                )
                SettingsRow(
                    label   = stringResource(R.string.setd_save_credentials),
                    onClick = {
                        viewModel.saveIgdbCredentials(igdbClientIdDraft, igdbClientSecretDraft)
                        igdbClientIdDraft = ""
                        igdbClientSecretDraft = ""
                    },
                )
            }

            if (state.hasIgdbCredentials) {
                SettingsRow(
                    label    = stringResource(R.string.setd_clear_igdb),
                    sublabel = stringResource(R.string.setd_clear_igdb_sublabel),
                    onClick  = { viewModel.clearIgdbCredentials() },
                )
            }

            // ── ScreenScraper provider status ────────────────────────────────
            // The WebAPI rejects every call without a devid/devpassword pair. This build ships an
            // obfuscated built-in pair (there is no user-entered override), so the provider works
            // out of the box; the optional user account below only raises the rate limit/quota.
            SettingsGroup("ScreenScraper")

            SettingsValueRow(
                label    = stringResource(R.string.setd_ss_status),
                sublabel = if (state.ssEnabled) {
                    stringResource(R.string.setd_ss_status_active_sublabel)
                } else {
                    stringResource(R.string.setd_ss_status_inactive_sublabel)
                },
                value    = stringResource(if (state.ssEnabled) R.string.setd_ss_active else R.string.setd_ss_inactive),
            )

            state.unprotectedSecretWarning?.let {
                SettingsRow(
                    label    = it.asString(),
                    sublabel = stringResource(R.string.setd_tap_to_dismiss),
                    onClick  = { viewModel.dismissUnprotectedSecretWarning() },
                )
            }

            // ── ScreenScraper user account (optional) ─────────────────────────
            // Only raises the rate limit and daily quota; scraping works without it.
            SettingsGroup(stringResource(R.string.setd_group_ss_account))

            SettingsTextFieldRow(
                label         = if (state.hasSsCredentials) stringResource(R.string.setd_ss_username_saved, state.ssUsername) else stringResource(R.string.setd_ss_username),
                value         = ssUsernameDraft,
                onValueChange = { ssUsernameDraft = it },
                placeholder   = stringResource(if (state.hasSsCredentials) R.string.setd_ss_tap_to_replace else R.string.setd_ss_username_placeholder),
            )
            SettingsTextFieldRow(
                label         = stringResource(R.string.setd_ss_password),
                value         = ssPasswordDraft,
                onValueChange = { ssPasswordDraft = it },
                placeholder   = stringResource(if (state.hasSsCredentials) R.string.setd_key_replace_placeholder else R.string.setd_ss_password_placeholder),
                isPassword    = true,
                helper        = stringResource(R.string.setd_ss_password_helper),
            )

            state.ssCredentialStatus?.let {
                SettingsRow(label = it.asString(), sublabel = stringResource(R.string.setd_tap_to_dismiss), onClick = { viewModel.dismissSsCredentialStatus() })
            }

            if (ssUsernameDraft.isNotBlank() && ssPasswordDraft.isNotBlank()) {
                if (state.ssEnabled) {
                    SettingsRow(
                        label   = stringResource(R.string.setd_test_account),
                        onClick = { viewModel.testSsCredentials(ssUsernameDraft, ssPasswordDraft) },
                    )
                }
                SettingsRow(
                    label   = stringResource(R.string.setd_save_account),
                    onClick = {
                        viewModel.saveSsCredentials(ssUsernameDraft, ssPasswordDraft)
                        ssUsernameDraft = ""
                        ssPasswordDraft = ""
                    },
                )
            }

            if (state.hasSsCredentials) {
                SettingsRow(
                    label    = stringResource(R.string.setd_clear_ss_account),
                    sublabel = stringResource(R.string.setd_clear_ss_account_sublabel),
                    onClick  = { viewModel.clearSsCredentials() },
                )
            }

            // ── Cache ─────────────────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_group_cache))

            SettingsValueRow(
                label    = stringResource(R.string.setd_cache_size),
                sublabel = stringResource(R.string.setd_cache_size_sublabel),
                value    = state.diskCacheSizeMb,
            )

            SettingsRow(
                label    = stringResource(R.string.setd_clear_ss_cache),
                sublabel = stringResource(R.string.setd_clear_ss_cache_sublabel),
                onClick  = { viewModel.clearSsUrlCache() },
            )

            SettingsRow(
                label    = stringResource(R.string.setd_clear_all_artwork),
                sublabel = stringResource(R.string.setd_clear_all_artwork_sublabel),
                onClick  = { viewModel.clearCache() },
            )
        }
    }

    modal.Content()
}

private fun formatSnapDelay(seconds: Float): String =
    if (seconds % 1f == 0f) "${seconds.toInt()}s" else "${seconds}s"

/** The menu label for a stored ScreenScraper region code. */
@Composable
private fun regionLabel(region: String?): String =
    ArtworkScrapePreferences.ARTWORK_REGIONS.firstOrNull { it.first == region }?.second
        ?: region?.uppercase()
        ?: stringResource(R.string.setd_region_no_preference)
