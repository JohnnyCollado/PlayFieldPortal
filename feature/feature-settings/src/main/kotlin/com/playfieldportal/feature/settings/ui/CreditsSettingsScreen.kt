package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.feature.settings.viewmodel.CreditsViewModel
import kotlinx.coroutines.launch

@Composable
fun CreditsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreditsViewModel = hiltViewModel(),
) {
    // Pure info screen — no interactive rows for the scaffold's focus navigation to walk, so
    // Up/Down scroll the column directly instead. Links are touch-only: a tap opens one.
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { 120.dp.toPx() }
    // Credits has no rows, so the scaffold's own move cue never fires here — the page itself is
    // what moves, and it ticks for the same reason a row would.
    val menuSounds = LocalMenuSounds.current

    SettingsScaffold(
        title = "Settings",
        subtitle = "Credits",
        onBack = onBack,
        modifier = modifier,
        onInterceptAction = { action ->
            when (action) {
                GamepadAction.NAVIGATE_UP   -> {
                    menuSounds.play(MenuSound.SCROLL)
                    scope.launch { scrollState.animateScrollBy(-stepPx) }; true
                }
                GamepadAction.NAVIGATE_DOWN -> {
                    menuSounds.play(MenuSound.SCROLL)
                    scope.launch { scrollState.animateScrollBy(stepPx) }; true
                }
                else -> false
            }
        },
    ) {
        // Credits has no focusable rows — it scrolls as a whole — so this registration is purely
        // what lets the scaffold's header and footer drag it. The screen keeps owning the state
        // itself because onInterceptAction above animates the same one for UP/DOWN.
        LocalSettingsScrollStateRegistrar.current(scrollState)
        val link: @Composable (label: String, value: String, address: String) -> Unit = { label, value, address ->
            CreditLink(label = label, value = value, onOpen = { viewModel.open(creditUrl(address)) })
        }
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
                    "artwork comes from the community \"XMB Menu for ES-DE\" theme (credited below) " +
                    "and remains the property of its respective authors. No Sony audio is bundled: " +
                    "every menu sound is credited separately below."
            )

            Spacer(Modifier.height(16.dp))
            SettingsGroup("App Icon & Logo")

            CreditParagraph(
                "The Play Field Portal app icon and logo were designed by johakovi. Huge thanks for the " +
                    "artwork that gives the launcher its identity."
            )
            CreditLine("Design", "johakovi")
            link("Reddit", "u/silverloc96", CreditLinks.JOHAKOVI_REDDIT)

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
            link("Source", "github.com/anthonycaccese/xmb-menu-es-de", CreditLinks.XMB_THEME)

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Controller Button Icons")

            CreditParagraph(
                "Every on-screen button prompt — the PlayStation, Xbox and Nintendo face buttons, " +
                    "D-pads, bumpers, triggers, sticks and system buttons — is drawn from Zacksly's " +
                    "button icon packs, used under the Creative Commons Attribution 3.0 license."
            )
            CreditParagraph(
                "The artwork is unmodified: only the file names were changed to Android resource " +
                    "names, and just the icons the launcher renders are bundled."
            )
            CreditLine("Author", "Zacksly")
            link("Website", "zacksly.itch.io", CreditLinks.ZACKSLY_SITE)
            link("Support", "patreon.com/zacksly", CreditLinks.ZACKSLY_PATREON)
            CreditLine(
                "Packs",
                "PS5 Button Icons and Controls · Xbox Series Button Icons and Controls · " +
                    "Switch 2 Button Icons and Controls"
            )
            link("License", "CC BY 3.0 — creativecommons.org/licenses/by/3.0", CreditLinks.CC_BY_3)

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Menu Sounds")

            CreditParagraph(
                "The bundled menu sounds — navigation, back, confirm, error, launch, notification " +
                    "and the boot chime — are built from royalty-free audio published on Pixabay, " +
                    "edited for the launcher: trimmed, re-pitched, re-levelled and converted. Thanks " +
                    "to the creators whose work the set is built from."
            )
            CreditParagraph(
                "The Pixabay Content License does not require attribution; it is given here with " +
                    "thanks anyway. If you are one of these creators and would like the credit " +
                    "changed or an asset removed, please reach out."
            )
            link("Source", "Pixabay — pixabay.com", CreditLinks.PIXABAY)
            link("License", "Pixabay Content License — pixabay.com/service/license-summary", CreditLinks.PIXABAY_LICENSE)
            link("Luca di Alessandro", "pixabay.com/users/lucadialessandro-25927643", CreditLinks.PIXABAY_LUCA)
            link("SoundReality", "pixabay.com/users/soundreality-31074404", CreditLinks.PIXABAY_SOUNDREALITY)
            link("Musheran", "pixabay.com/users/musheran-40634446", CreditLinks.PIXABAY_MUSHERAN)
            link("Universfield", "pixabay.com/users/universfield-28281460", CreditLinks.PIXABAY_UNIVERSFIELD)

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Game Artwork & Metadata")

            CreditParagraph(
                "Box art, 3D boxes, cartridge/disc shots, hero banners, logos, icons, manuals, " +
                    "video snaps and game metadata are fetched at your request from third-party " +
                    "providers and remain the property of their respective owners."
            )
            link("Primary scraper", "ScreenScraper — screenscraper.fr, community-maintained game media database", CreditLinks.SCREENSCRAPER)
            link("Artwork", "SteamGridDB — steamgriddb.com", CreditLinks.STEAMGRIDDB)
            CreditLine("Metadata", "TheGamesDB · IGDB")

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Achievements — Shiba Coins")

            CreditParagraph(
                "Achievement data for the Shiba Coins system comes from the following services and " +
                    "projects, and remains the property of their respective owners."
            )
            link("RetroAchievements", "retroachievements.org — community-made achievement sets and unlock data for retro games, via the official RetroAchievements Web API and api-kotlin client", CreditLinks.RETROACHIEVEMENTS)
            link("Steam", "Powered by Steam — achievement schemas and unlock data via the Steam Web API, using your own key. Steam and the Steam logo are trademarks of Valve Corporation. steampowered.com", CreditLinks.STEAM)

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
            link("Source", "github.com/Detanup01/gbe_fork", CreditLinks.GBE_FORK)
            link("Original project", "Goldberg Emulator by Mr. Goldberg — gitlab.com/Mr_Goldberg/goldberg_emulator", CreditLinks.GOLDBERG)
            link("License", "LGPL-3.0 — gnu.org/licenses/lgpl-3.0.html", CreditLinks.LGPL_3)

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Support the Project")

            CreditParagraph(
                "Thank you all for your support. I created this as a love letter to the PSP and the " +
                    "amazing community that loves it as well, so this project will forever remain " +
                    "free. With that said, if you appreciate what I do and would like to show that " +
                    "appreciation in another way, you can buy me a taco. Of course, donations are " +
                    "always optional and never required. Love y'all, and Happy June 15th."
            )
            link("Buy me a Taco", "buymeacoffee.com/johnnycolli", CreditLinks.BUY_ME_A_TACO)

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

/**
 * A [CreditLine] whose address opens in the browser when tapped. Touch only: it is not a cursor
 * stop, so the controller keeps scrolling the page. The address is underlined to read as a link.
 */
@Composable
private fun CreditLink(label: String, value: String, onOpen: () -> Unit) {
    val menuSounds = LocalMenuSounds.current
    val touchInput = LocalSettingsTouchInput.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(onOpen) {
                detectTapGestures(onTap = { touchInput(); menuSounds.play(MenuSound.SELECT); onOpen() })
            }
            .padding(vertical = 6.dp),
    ) {
        Text(text = label.uppercase(), color = SettingsAccent, fontSize = 10.sp)
        Text(text = value, color = SettingsText, fontSize = 14.sp, textDecoration = TextDecoration.Underline)
    }
}
