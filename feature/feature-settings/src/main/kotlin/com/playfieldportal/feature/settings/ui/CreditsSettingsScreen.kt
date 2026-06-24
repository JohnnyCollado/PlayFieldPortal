package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CreditsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "Settings", subtitle = "Credits", onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                    "fan project — not affiliated with, endorsed by, or sponsored by Sony, and it ships " +
                    "none of Sony's code, fonts or proprietary assets."
            )

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
            SettingsGroup("Game Artwork & Metadata")

            CreditParagraph(
                "Box art, hero banners, logos and icons are fetched at your request from third-party " +
                    "providers and remain the property of their respective owners."
            )
            CreditLine("Artwork", "SteamGridDB")
            CreditLine("Metadata", "IGDB · TheGamesDB")

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
