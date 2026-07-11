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
