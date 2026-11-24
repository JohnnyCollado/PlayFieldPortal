package com.playfieldportal.feature.settings.ui

import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The real installed version, from PackageManager — a library module's BuildConfig can't
    // know the app's versionName/versionCode (the old hardcoded copy here went stale).
    val context = LocalContext.current
    val packageInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: stringResource(R.string.sets_about_unknown)
    val versionCode = packageInfo?.longVersionCode?.toString() ?: stringResource(R.string.sets_about_unknown)
    // Flavor from the installed package id: the lite build's applicationId carries a ".lite"
    // segment (see app/build.gradle.kts productFlavors). Full ships the Discord Social SDK; lite
    // omits it — so the edition is a real, user-visible difference worth surfacing here.
    val isLite = remember(context) { context.packageName.contains(".lite") }

    // Value rows are focusable, so the cursor walks the list and focus-driven scrolling brings
    // each row into view — no manual scroll interception needed.
    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_about_title),
        onBack   = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            SettingsGroup("Play Field Portal")

            SettingsValueRow(label = stringResource(R.string.sets_about_label_version),      value = versionName)
            SettingsValueRow(label = stringResource(R.string.sets_about_label_build),        value = versionCode)
            SettingsValueRow(label = stringResource(R.string.sets_about_label_edition),      value = if (isLite) stringResource(R.string.sets_edition_lite) else stringResource(R.string.sets_edition_full))
            SettingsValueRow(label = stringResource(R.string.sets_about_label_min_android),  value = "Android 10 (API 29)")
            SettingsValueRow(label = stringResource(R.string.sets_about_label_target),       value = "Android 15 (API 35)")

            SettingsGroup(stringResource(R.string.sets_about_group_open_source))

            SettingsValueRow(label = "Jetpack Compose",   value = stringResource(R.string.sets_about_value_compose))
            SettingsValueRow(label = "Room",              value = stringResource(R.string.sets_about_value_room))
            SettingsValueRow(label = "Hilt",              value = stringResource(R.string.sets_about_value_hilt))
            SettingsValueRow(label = "Ktor",              value = stringResource(R.string.sets_about_value_ktor))
            SettingsValueRow(label = "Coil",              value = stringResource(R.string.sets_about_value_coil))
            SettingsValueRow(label = "Media3",            value = stringResource(R.string.sets_about_value_media3))
            SettingsValueRow(label = "WorkManager",       value = stringResource(R.string.sets_about_value_workmanager))
            SettingsValueRow(label = "Timber",            value = stringResource(R.string.sets_about_value_timber))

            SettingsGroup(stringResource(R.string.sets_about_group_credits))

            SettingsValueRow(label = stringResource(R.string.sets_about_label_art_media), value = "ScreenScraper · SteamGridDB")
            SettingsValueRow(label = stringResource(R.string.sets_about_label_metadata),        value = "ScreenScraper · TheGamesDB · IGDB")
            SettingsValueRow(label = stringResource(R.string.sets_about_label_inspired),     value = "Sony PSP XMB")
            SettingsValueRow(label = stringResource(R.string.sets_about_label_see_also),        value = stringResource(R.string.sets_about_value_see_also))

            SettingsGroup(stringResource(R.string.sets_about_group_legal))

            SettingsRow(
                label    = stringResource(R.string.sets_about_legal_independent),
                sublabel = stringResource(R.string.sets_about_legal_trademarks),
            )
        }
    }
}

@CombinedPreviews
@Composable
fun AboutSettingsScreenPreview() {
    PfpPreview {
        AboutSettingsScreen(onBack = {})
    }
}
