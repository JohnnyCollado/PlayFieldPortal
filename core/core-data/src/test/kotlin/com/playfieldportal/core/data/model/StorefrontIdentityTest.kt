package com.playfieldportal.core.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorefrontIdentityTest {

    @Test
    fun `GameNative intents name the store explicitly`() {
        assertEquals(
            "STEAM" to "620",
            StorefrontIdentity.fromLaunchIntentUri(
                "intent:#Intent;component=app.gamenative/app.gamenative.MainActivity;" +
                    "action=app.gamenative.LAUNCH_GAME;i.app_id=620;S.game_source=STEAM;end"
            ),
        )
        assertEquals(
            "GOG" to "1423049311",
            StorefrontIdentity.fromLaunchIntentUri(
                "intent:#Intent;i.app_id=1423049311;S.game_source=GOG;end"
            ),
        )
        assertEquals(
            "EPIC" to "42",
            StorefrontIdentity.fromLaunchIntentUri("intent:#Intent;i.app_id=42;S.game_source=EPIC;end"),
        )
    }

    // The adapter itself defaults game_source to STEAM when the caller leaves it blank, so a
    // GameNative intent without one is a Steam launch — not an unknown store.
    @Test
    fun `a GameNative intent with no game_source falls back to Steam, like the adapter does`() {
        assertEquals(
            "STEAM" to "620",
            StorefrontIdentity.fromLaunchIntentUri("intent:#Intent;i.app_id=620;end"),
        )
    }

    @Test
    fun `steamAppId is a Steam id but localGameId is only the launcher's own`() {
        assertEquals(
            "STEAM" to "1145360",
            StorefrontIdentity.fromLaunchIntentUri(
                "intent:#Intent;action=gamehub.lite.LAUNCH_GAME;S.steamAppId=1145360;B.autoStartGame=true;end"
            ),
        )
        assertNull(
            StorefrontIdentity.fromLaunchIntentUri(
                "intent:#Intent;action=gamehub.lite.LAUNCH_GAME;S.localGameId=local_9f2c;end"
            ),
        )
        assertNull(
            StorefrontIdentity.fromLaunchIntentUri(
                "intent:#Intent;action=gamehub.lite.LAUNCH_GAME;S.localGameId=778899;end"
            ),
        )
    }

    @Test
    fun `an intent with no store identity yields null rather than a guess`() {
        assertNull(StorefrontIdentity.fromLaunchIntentUri(null))
        assertNull(StorefrontIdentity.fromLaunchIntentUri(""))
        assertNull(StorefrontIdentity.fromLaunchIntentUri("not an intent uri at all"))
        // Winlator launches by shortcut path.
        assertNull(
            StorefrontIdentity.fromLaunchIntentUri(
                "intent:#Intent;component=com.winlator/.MainActivity;" +
                    "S.shortcut_path=%2Fstorage%2Femulated%2F0%2Fgame.desktop;end"
            ),
        )
    }

    @Test
    fun `non-numeric and oversized ids are rejected`() {
        assertNull(StorefrontIdentity.fromLaunchIntentUri("intent:#Intent;S.steamAppId=abc;end"))
        assertNull(StorefrontIdentity.fromLaunchIntentUri("intent:#Intent;S.steamAppId=1234567890123;end"))
        assertTrue(StorefrontIdentity.isPlausibleAppId("620"))
        assertFalse(StorefrontIdentity.isPlausibleAppId(""))
        assertFalse(StorefrontIdentity.isPlausibleAppId("local_9f2c"))
        assertFalse(StorefrontIdentity.isPlausibleAppId(null))
    }

    @Test
    fun `only known stores are stored, and always uppercase`() {
        assertEquals("STEAM", StorefrontIdentity.normalizeStore("steam"))
        assertEquals("CUSTOM_GAME", StorefrontIdentity.normalizeStore(" custom_game "))
        assertNull(StorefrontIdentity.normalizeStore("itch"))
        assertNull(StorefrontIdentity.normalizeStore(null))
    }

    @Test
    fun `percent-encoded extra values are decoded`() {
        assertEquals(
            "STEAM" to "620",
            // Uri.encode leaves alphanumerics alone, but an encoded value must still round-trip.
            StorefrontIdentity.fromLaunchIntentUri("intent:#Intent;i.app_id=620;S.game_source=STEAM%20;end"),
        )
    }

    // Extras carry a typed prefix; plain intent fields must never be read as one.
    @Test
    fun `intent fields are not mistaken for extras`() {
        assertNull(
            StorefrontIdentity.fromLaunchIntentUri(
                "intent:#Intent;action=app.gamenative.LAUNCH_GAME;package=app.gamenative;launchFlags=0x10000000;end"
            ),
        )
    }
}
