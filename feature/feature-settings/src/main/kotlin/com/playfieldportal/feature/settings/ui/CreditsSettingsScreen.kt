package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import kotlinx.coroutines.launch

@Composable
fun CreditsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pure info screen — no interactive rows for the scaffold's focus navigation to walk, so
    // Up/Down scroll the column directly instead.
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { 120.dp.toPx() }

    SettingsScaffold(
        title = "Settings",
        subtitle = "Credits",
        onBack = onBack,
        modifier = modifier,
        onInterceptAction = { action ->
            when (action) {
                GamepadAction.NAVIGATE_UP   -> { scope.launch { scrollState.animateScrollBy(-stepPx) }; true }
                GamepadAction.NAVIGATE_DOWN -> { scope.launch { scrollState.animateScrollBy(stepPx) }; true }
                else -> false
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 48.dp),
        ) {
            SettingsGroup("XMB Design — Sony")

            CreditParagraph(
                "The look and feel of this launcher is inspired by the XMB (XrossMediaBar), the " +
                    "interface Sony created for the PlayStation Portable and PlayStation 3 — the " +
                    "cross-bar layout, the flowing wave, and the navigation model are all homages to it."
            )
            CreditParagraph(
                "\"XrossMediaBar\", \"XMB\", \"PSP\" and \"PlayStation\" are trademarks of Sony " +
                    "Interactive Entertainment Inc. Play Field Portal is an independent, non-commercial " +
                    "fan project — not affiliated with, endorsed by, or sponsored by Sony. Bundled UI " +
                    "artwork and menu sounds come from the community \"XMB Menu for ES-DE\" theme " +
                    "(credited below) and remain the property of their respective authors."
            )

            Spacer(Modifier.height(16.dp))
            SettingsGroup("App Icon & Logo")

            CreditParagraph(
                "The Play Field Portal app icon and logo were designed by johakovi. Huge thanks for the " +
                    "artwork that gives the launcher its identity."
            )
            CreditLine("Design", "johakovi")
            CreditLine("Reddit", "u/silverloc96")

            Spacer(Modifier.height(16.dp))
            SettingsGroup("System & Console Artwork")

            CreditParagraph(
                "The system, console and category icons used throughout this launcher are from " +
                    "the \"XMB Menu for ES-DE\" theme — a community recreation of the PSP XMB."
            )
            CreditParagraph(
                "All rights to this artwork belong to its creators — Anthony Caccese, building on the " +
                    "original work by InitialDin. The icons are used here with gratitude and remain the " +
                    "property of their respective authors."
            )
            CreditLine("Project", "XMB Menu for ES-DE")
            CreditLine("Authors", "Anthony Caccese · InitialDin")
            CreditLine("Source", "github.com/anthonycaccese/xmb-menu-es-de")

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Menu Sounds")

            CreditParagraph(
                "The navigation, select, back and launch sound effects are bundled from the same " +
                    "\"XMB Menu for ES-DE\" theme and remain the property of their respective authors. " +
                    "If they are your work, or you hold the rights, please reach out for attribution " +
                    "or removal."
            )
            CreditLine("Project", "XMB Menu for ES-DE")
            CreditLine("Source", "github.com/anthonycaccese/xmb-menu-es-de")

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Game Artwork & Metadata")

            CreditParagraph(
                "Box art, 3D boxes, cartridge/disc shots, hero banners, logos, icons, manuals, " +
                    "video snaps and game metadata are fetched at your request from third-party " +
                    "providers and remain the property of their respective owners."
            )
            CreditLine("Primary scraper", "ScreenScraper — screenscraper.fr, community-maintained game media database")
            CreditLine("Artwork", "SteamGridDB — steamgriddb.com")
            CreditLine("Metadata", "TheGamesDB · IGDB")

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Achievements — Shiba Coins")

            CreditParagraph(
                "Achievement data for the Shiba Coins system comes from the following services and " +
                    "projects, and remains the property of their respective owners."
            )
            CreditLine("RetroAchievements", "retroachievements.org — community-made achievement sets and unlock data for retro games, via the official RetroAchievements Web API and api-kotlin client")
            CreditLine("Steam", "Powered by Steam — achievement schemas and unlock data via the Steam Web API, using your own key. Steam and the Steam logo are trademarks of Valve Corporation. steampowered.com")

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Goldberg Steam Emulator (gbe_fork)")

            CreditParagraph(
                "Local achievement tracking for Steam-emulated PC games bundles the Goldberg Steam " +
                    "Emulator — specifically gbe_fork, the community fork maintained by Detanup01 and " +
                    "contributors, building on the original Goldberg Emulator by Mr. Goldberg."
            )
            CreditParagraph(
                "The emulator is free software licensed under the GNU Lesser General Public License " +
                    "v3.0 (LGPL-3.0). Play Field Portal bundles an unmodified build of its " +
                    "steam_api64.dll; the complete corresponding source code and the full license text " +
                    "are available at the links below."
            )
            CreditLine("Project", "gbe_fork — Detanup01 and contributors")
            CreditLine("Source", "github.com/Detanup01/gbe_fork")
            CreditLine("Original project", "Goldberg Emulator by Mr. Goldberg — gitlab.com/Mr_Goldberg/goldberg_emulator")
            CreditLine("License", "LGPL-3.0 — gnu.org/licenses/lgpl-3.0.html")

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Notes")
            CreditParagraph(
                "If you are a rights holder and would like attribution changed or any asset removed, " +
                    "please reach out and it will be addressed promptly."
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CreditParagraph(text: String) {
    Text(
        text = text,
        color = SettingsText,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun CreditLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(text = label.uppercase(), color = SettingsAccent, fontSize = 10.sp)
        Text(text = value, color = SettingsText, fontSize = 14.sp)
    }
}
