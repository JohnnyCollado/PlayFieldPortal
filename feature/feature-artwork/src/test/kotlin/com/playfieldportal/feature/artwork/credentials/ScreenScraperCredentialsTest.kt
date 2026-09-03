package com.playfieldportal.feature.artwork.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The developer pair is what decides whether ScreenScraper works at all, so "is it configured"
 * has to be a single decision made in one place. These cases are the ones that used to be spread
 * across `BuildConfig.SS_DEV_ID.isNotBlank()` checks at three call sites.
 */
class ScreenScraperCredentialsTest {

    @Test
    fun `both developer fields present yields credentials`() {
        val creds = ScreenScraperCredentials.of("dev", "secret", null, null)

        assertEquals("dev", creds?.devId)
        assertEquals("secret", creds?.devPassword)
        assertNull(creds?.userId)
        assertNull(creds?.userPassword)
    }

    @Test
    fun `missing developer id means the provider is off`() {
        assertNull(ScreenScraperCredentials.of(null, "secret", "user", "pw"))
        assertNull(ScreenScraperCredentials.of("", "secret", "user", "pw"))
    }

    @Test
    fun `missing developer password means the provider is off`() {
        assertNull(ScreenScraperCredentials.of("dev", null, "user", "pw"))
        assertNull(ScreenScraperCredentials.of("dev", "", "user", "pw"))
    }

    @Test
    fun `whitespace-only developer fields count as absent`() {
        assertNull(ScreenScraperCredentials.of("   ", "secret", null, null))
        assertNull(ScreenScraperCredentials.of("dev", "\t\n ", null, null))
    }

    @Test
    fun `developer fields are trimmed`() {
        val creds = ScreenScraperCredentials.of("  dev  ", "  secret  ", null, null)

        assertEquals("dev", creds?.devId)
        assertEquals("secret", creds?.devPassword)
    }

    @Test
    fun `user account is optional and blank is normalized to absent`() {
        val creds = ScreenScraperCredentials.of("dev", "secret", "   ", "")

        assertNull(creds?.userId)
        assertNull(creds?.userPassword)
    }

    @Test
    fun `user account is carried through when present`() {
        val creds = ScreenScraperCredentials.of("dev", "secret", " someone ", " pw ")

        assertEquals("someone", creds?.userId)
        assertEquals("pw", creds?.userPassword)
    }
}
