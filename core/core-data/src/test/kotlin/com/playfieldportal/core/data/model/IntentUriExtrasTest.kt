package com.playfieldportal.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentUriExtrasTest {

    private val winlator =
        "intent:#Intent;action=android.intent.action.MAIN;component=com.winlator/.MainActivity;" +
            "launchFlags=0x10008000;S.shortcut_path=%2Fstorage%2Femulated%2F0%2FWinlator%2FPortal%202.desktop;end"

    @Test
    fun `typed extras are keyed by their prefix, and intent fields are not extras`() {
        val extras = IntentUriExtras.parse("intent:#Intent;action=app.gamenative.LAUNCH_GAME;i.app_id=620;S.game_source=STEAM;B.autoStartGame=true;end")

        assertEquals(mapOf("i.app_id" to "620", "S.game_source" to "STEAM", "B.autoStartGame" to "true"), extras)
    }

    @Test
    fun `a string extra is read and decoded`() {
        assertEquals("/storage/emulated/0/Winlator/Portal 2.desktop", IntentUriExtras.stringExtra(winlator, "shortcut_path"))
        assertNull(IntentUriExtras.stringExtra(winlator, "game_source"))
    }

    @Test
    fun `escapes decode as UTF-8, so a non-ASCII path is one character per letter`() {
        val uri = "intent:#Intent;S.shortcut_path=%2Froms%2FPok%C3%A9mon%20%E2%98%85.desktop;end"

        assertEquals("/roms/Pokémon ★.desktop", IntentUriExtras.stringExtra(uri, "shortcut_path"))
    }

    @Test
    fun `text that is not a valid escape is kept as written`() {
        val uri = "intent:#Intent;S.a=100%;S.b=%zz1;S.c=caf%C3%A9%;S.d=日本;end"
        val extras = IntentUriExtras.parse(uri)

        assertEquals("100%", extras["S.a"])
        assertEquals("%zz1", extras["S.b"])
        assertEquals("café%", extras["S.c"])
        assertEquals("日本", extras["S.d"])
    }

    @Test
    fun `no intent reads as no extra`() {
        assertNull(IntentUriExtras.stringExtra(null, "shortcut_path"))
        assertNull(IntentUriExtras.stringExtra("  ", "shortcut_path"))
    }
}
