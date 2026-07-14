package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.model.Game
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SteamShortcutTest {

    private fun game(intent: String?) = Game(id = 1, title = "G", platformId = "windows", launchIntentUri = intent)

    @Test
    fun `extracts the gamenative steam app id`() {
        val intent = "intent:#Intent;action=app.gamenative.LAUNCH_GAME;launchFlags=0x10000000;" +
            "component=app.gamenative/.MainActivity;i.app_id=3483510;S.game_source=STEAM;end"
        assertEquals("3483510", SteamShortcut.appIdFrom(game(intent)))
    }

    @Test
    fun `ignores gamenative app_id when the source is not steam`() {
        val intent = "intent:#Intent;action=app.gamenative.LAUNCH_GAME;i.app_id=999;S.game_source=WINLATOR;end"
        assertNull(SteamShortcut.appIdFrom(game(intent)))
    }

    @Test
    fun `extracts a steam rungameid uri`() {
        assertEquals("220", SteamShortcut.appIdFrom(game("steam://rungameid/220")))
        assertEquals("400", SteamShortcut.appIdFrom(game("steam://run/400")))
    }

    @Test
    fun `null when there is no launch intent or steam id`() {
        assertNull(SteamShortcut.appIdFrom(game(null)))
        assertNull(SteamShortcut.appIdFrom(game("intent:#Intent;component=com.other/.Main;end")))
    }
}
