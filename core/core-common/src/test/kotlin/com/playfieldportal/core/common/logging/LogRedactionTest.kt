package com.playfieldportal.core.common.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactionTest {

    @Test
    fun `screenscraper credentials and account name never survive`() {
        val line = "GET https://api.screenscraper.fr/api2/jeuInfos.php?devid=PFP&devpassword=hunter2" +
            "&ssid=johnny&sspassword=s3cret&crc=AABBCCDD&romnom=Game.gba"
        val out = LogRedaction.redact(line)
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("s3cret"))
        assertFalse(out.contains("johnny"))
        // Non-sensitive params survive for debuggability.
        assertTrue(out.contains("crc=AABBCCDD"))
        assertTrue(out.contains("romnom=Game.gba"))
    }

    /** The shape that leaked on device: Ktor puts the full request URL in a timeout's message. */
    @Test
    fun `a request URL inside an exception message loses its credentials`() {
        val line = "io.ktor.client.network.sockets.ConnectTimeoutException: Connect timeout has expired " +
            "[url=https://api.screenscraper.fr/api2/jeuRecherche.php?devid=PFP&devpassword=hunter2" +
            "&softname=PFP&output=json&ssid=johnny&sspassword=s3cret&recherche=Tactics+Ogre, connect_timeout=unknown ms]"
        val out = LogRedaction.redact(line)
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("s3cret"))
        assertFalse(out.contains("johnny"))
        assertTrue(out.contains("recherche=Tactics+Ogre, connect_timeout=unknown ms]"))
    }

    @Test
    fun `a request URL echoed inside JSON loses only its secrets`() {
        val body = """{"header":{"commandRequested":"https:\/\/api.screenscraper.fr\/api2\/jeuRecherche.php""" +
            """?devid=PFP&devpassword=hunter2&ssid=johnny&sspassword=s3cret"},"response":{"jeux":[]}}"""
        val out = LogRedaction.redact(body)
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("s3cret"))
        assertFalse(out.contains("johnny"))
        assertTrue(out.endsWith(""""},"response":{"jeux":[]}}"""))
    }

    @Test
    fun `Steam and IGDB query credentials are scrubbed`() {
        val out = LogRedaction.redact(
            "GET https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=ABCDEF123&steamid=7656 " +
                "POST https://id.twitch.tv/oauth2/token?client_id=igdb-id&client_secret=shhh"
        )
        assertFalse(out.contains("ABCDEF123"))
        assertFalse(out.contains("igdb-id"))
        assertFalse(out.contains("shhh"))
        assertTrue(out.contains("steamid=7656"))
    }

    @Test
    fun `the app's own ScreenScraper game ids stay readable`() {
        val line = "SS catalog lookup for title-matched ssId=555 (gameId=12, not persisted)"
        assertEquals(line, LogRedaction.redact(line))
    }

    @Test
    fun `api keys and tokens are scrubbed`() {
        val out = LogRedaction.redact("request failed apikey=abc123 token=xyz789 client_secret=shhh")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("xyz789"))
        assertFalse(out.contains("shhh"))
    }

    @Test
    fun `authorization headers are scrubbed`() {
        val out = LogRedaction.redact("-> Authorization: Bearer eyJhbGciOi.payload.sig")
        assertFalse(out.contains("eyJhbGciOi"))
        val out2 = LogRedaction.redact("Client-ID: my-igdb-client")
        assertFalse(out2.contains("my-igdb-client"))
    }

    @Test
    fun `emails are masked`() {
        val out = LogRedaction.redact("signed in as somebody@example.com ok")
        assertEquals("signed in as REDACTED@EMAIL ok", out)
    }

    @Test
    fun `ordinary lines pass through unchanged`() {
        val line = "Scan: 120 files, 66 linked, 0 unmatched — https://cdn.thegamesdb.net/boxart/front/1-1.jpg"
        assertEquals(line, LogRedaction.redact(line))
    }
}
