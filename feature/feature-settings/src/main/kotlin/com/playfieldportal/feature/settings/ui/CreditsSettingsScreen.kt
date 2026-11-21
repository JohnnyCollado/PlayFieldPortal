package com.playfieldportal.feature.settings.ui

import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.stringResource
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
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_credits_title),
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
            SettingsGroup(stringResource(R.string.sets_credits_group_xmb))

            CreditParagraph(
                stringResource(R.string.sets_credits_xmb_inspired)
            )
            CreditParagraph(
                stringResource(R.string.sets_credits_xmb_trademarks)
            )

            Spacer(Modifier.height(16.dp))
            SettingsGroup(stringResource(R.string.sets_credits_group_icon))

            CreditParagraph(
                stringResource(R.string.sets_credits_icon_designed)
            )
            CreditLine(stringResource(R.string.sets_credits_label_design), "johakovi")
            link("Reddit", "u/silverloc96", CreditLinks.JOHAKOVI_REDDIT)

            Spacer(Modifier.height(16.dp))
            SettingsGroup(stringResource(R.string.sets_credits_group_artwork))

            CreditParagraph(
                stringResource(R.string.sets_credits_artwork_from)
            )
            CreditParagraph(
                stringResource(R.string.sets_credits_artwork_rights)
            )
            CreditLine(stringResource(R.string.sets_credits_label_project), "XMB Menu for ES-DE")
            CreditLine(stringResource(R.string.sets_credits_label_authors), "Anthony Caccese · InitialDin")
            link(stringResource(R.string.sets_credits_label_source), "github.com/anthonycaccese/xmb-menu-es-de", CreditLinks.XMB_THEME)

            Spacer(Modifier.height(16.dp))
            SettingsGroup(stringResource(R.string.sets_credits_group_buttons))

            CreditParagraph(
                stringResource(R.string.sets_credits_buttons_from)
            )
            CreditParagraph(
                stringResource(R.string.sets_credits_buttons_unmodified)
            )
            CreditLine(stringResource(R.string.sets_credits_label_author), "Zacksly")
            link(stringResource(R.string.sets_credits_label_website), "zacksly.itch.io", CreditLinks.ZACKSLY_SITE)
            link(stringResource(R.string.sets_credits_label_support), "patreon.com/zacksly", CreditLinks.ZACKSLY_PATREON)
            CreditLine(
                stringResource(R.string.sets_credits_label_packs),
                "PS5 Button Icons and Controls · Xbox Series Button Icons and Controls · " +
                    "Switch 2 Button Icons and Controls"
            )
            link(stringResource(R.string.sets_credits_label_license), "CC BY 3.0 — creativecommons.org/licenses/by/3.0", CreditLinks.CC_BY_3)

            Spacer(Modifier.height(16.dp))
            SettingsGroup(stringResource(R.string.sets_credits_group_sounds))

            CreditParagraph(
                stringResource(R.string.sets_credits_sounds_built)
            )
            CreditParagraph(
                stringResource(R.string.sets_credits_sounds_license)
            )
            link(stringResource(R.string.sets_credits_label_source), "Pixabay — pixabay.com", CreditLinks.PIXABAY)
            link(stringResource(R.string.sets_credits_label_license), "Pixabay Content License — pixabay.com/service/license-summary", CreditLinks.PIXABAY_LICENSE)
            link("Luca di Alessandro", "pixabay.com/users/lucadialessandro-25927643", CreditLinks.PIXABAY_LUCA)
            link("SoundReality", "pixabay.com/users/soundreality-31074404", CreditLinks.PIXABAY_SOUNDREALITY)
            link("Musheran", "pixabay.com/users/musheran-40634446", CreditLinks.PIXABAY_MUSHERAN)
            link("Universfield", "pixabay.com/users/universfield-28281460", CreditLinks.PIXABAY_UNIVERSFIELD)

            Spacer(Modifier.height(16.dp))
            SettingsGroup(stringResource(R.string.sets_credits_group_metadata))

            CreditParagraph(
                stringResource(R.string.sets_credits_metadata_fetched)
            )
            link(stringResource(R.string.sets_credits_label_primary_scraper), stringResource(R.string.sets_credits_value_screenscraper), CreditLinks.SCREENSCRAPER)
            link(stringResource(R.string.sets_credits_label_artwork), "SteamGridDB — steamgriddb.com", CreditLinks.STEAMGRIDDB)
            CreditLine(stringResource(R.string.sets_credits_label_metadata), "TheGamesDB · IGDB")

            Spacer(Modifier.height(16.dp))
            SettingsGroup(stringResource(R.string.sets_credits_group_achievements))

            CreditParagraph(
                stringResource(R.string.sets_credits_achievements_from)
            )
            link("RetroAchievements", stringResource(R.string.sets_credits_value_retroachievements), CreditLinks.RETROACHIEVEMENTS)
            link("Steam", stringResource(R.string.sets_credits_value_steam), CreditLinks.STEAM)

            Spacer(Modifier.height(16.dp))
            SettingsGroup("Goldberg Steam Emulator (gbe_fork)")

            CreditParagraph(
                stringResource(R.string.sets_credits_goldberg_bundles)
            )
            CreditParagraph(
                stringResource(R.string.sets_credits_goldberg_license)
            )
            CreditLine(stringResource(R.string.sets_credits_label_project), stringResource(R.string.sets_credits_value_gbe))
            link(stringResource(R.string.sets_credits_label_source), "github.com/Detanup01/gbe_fork", CreditLinks.GBE_FORK)
            link(stringResource(R.string.sets_credits_label_original_project), stringResource(R.string.sets_credits_value_goldberg_original), CreditLinks.GOLDBERG)
            link(stringResource(R.string.sets_credits_label_license), "LGPL-3.0 — gnu.org/licenses/lgpl-3.0.html", CreditLinks.LGPL_3)

            Spacer(Modifier.height(16.dp))
            SettingsGroup(stringResource(R.string.sets_credits_group_support))

            CreditParagraph(
                stringResource(R.string.sets_credits_support_thanks)
            )
            link(stringResource(R.string.sets_credits_label_buy_taco), "buymeacoffee.com/johnnycolli", CreditLinks.BUY_ME_A_TACO)

            Spacer(Modifier.height(16.dp))
            SettingsGroup(stringResource(R.string.sets_credits_group_notes))
            CreditParagraph(
                stringResource(R.string.sets_credits_notes_text)
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
