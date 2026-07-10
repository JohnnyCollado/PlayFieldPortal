package com.playfieldportal.feature.artwork.api

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactSecretsTest {

    @Test
    fun `screenscraper passwords never reach the log`() {
        val line = "REQUEST: https://api.screenscraper.fr/api2/jeuInfos.php?devid=PFP&devpassword=hunter2&ssid=user&sspassword=s3cret&crc=AABBCCDD"
        val redacted = redactSecretQueryParams(line)
        assertEquals(
            "REQUEST: https://api.screenscraper.fr/api2/jeuInfos.php?devid=PFP&devpassword=REDACTED&ssid=user&sspassword=REDACTED&crc=AABBCCDD",
            redacted,
        )
    }

    @Test
    fun `messages without secrets pass through unchanged`() {
        val line = "RESPONSE: 200 OK https://cdn.thegamesdb.net/images/original/boxart/front/1-1.jpg"
        assertEquals(line, redactSecretQueryParams(line))
    }
}
