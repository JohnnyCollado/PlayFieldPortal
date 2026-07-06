package com.playfieldportal.core.data.discord

import com.playfieldportal.core.domain.discord.DiscordSanitize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscordSanitizeTest {

    @Test
    fun `text strips control and bidi-override chars, collapses whitespace, and trims`() {
        val rlo = Char(0x202E)  // right-to-left override (bidi spoofing)
        val nul = Char(0x0000)  // control char
        val raw = "  Do${rlo}om$nul \tII \n"
        assertEquals("Doom II", DiscordSanitize.text(raw, DiscordSanitize.ACTIVITY_MAX))
    }

    @Test
    fun `text preserves legitimate unicode`() {
        val name = "Player " + Char(0x00E9) + "lite"   // "Player élite"
        assertEquals(name, DiscordSanitize.text(name, DiscordSanitize.NAME_MAX))
    }

    @Test
    fun `text clamps to the max length`() {
        val long = "a".repeat(500)
        assertEquals(DiscordSanitize.ACTIVITY_MAX, DiscordSanitize.text(long, DiscordSanitize.ACTIVITY_MAX).length)
    }

    @Test
    fun `avatarUrl accepts Discord CDN hosts over https`() {
        assertEquals(
            "https://cdn.discordapp.com/avatars/1/abc.png",
            DiscordSanitize.avatarUrl("https://cdn.discordapp.com/avatars/1/abc.png"),
        )
        assertEquals(
            "https://media.discordapp.net/x.png",
            DiscordSanitize.avatarUrl("https://media.discordapp.net/x.png"),
        )
    }

    @Test
    fun `avatarUrl rejects non-https, foreign hosts, and blanks`() {
        assertNull(DiscordSanitize.avatarUrl("http://cdn.discordapp.com/a.png"))       // not https
        assertNull(DiscordSanitize.avatarUrl("https://evil.example.com/a.png"))        // wrong host
        assertNull(DiscordSanitize.avatarUrl("https://cdn.discordapp.com.evil.com/a")) // host spoof
        assertNull(DiscordSanitize.avatarUrl(""))
        assertNull(DiscordSanitize.avatarUrl(null))
    }
}
