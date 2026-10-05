package com.playfieldportal.feature.settings.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Credits screen shows bare addresses; each one must open as an https link and nothing else. */
class CreditLinksTest {

    @Test
    fun `a bare address opens over https`() {
        assertEquals("https://github.com/Detanup01/gbe_fork", creditUrl("github.com/Detanup01/gbe_fork"))
    }

    @Test
    fun `an address that already has https is kept`() {
        assertEquals("https://www.buymeacoffee.com/johnnycolli", creditUrl("https://www.buymeacoffee.com/johnnycolli"))
    }

    @Test
    fun `plain http is upgraded to https`() {
        assertEquals("https://pixabay.com", creditUrl("http://pixabay.com"))
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        assertEquals("https://zacksly.itch.io", creditUrl("  zacksly.itch.io "))
    }

    @Test
    fun `a Reddit user name opens their profile`() {
        assertEquals("https://www.reddit.com/user/silverloc96", creditUrl("u/silverloc96"))
    }

    @Test
    fun `every link on the Credits screen opens over https`() {
        CreditsLinkTargets.forEach { target ->
            val url = creditUrl(target)
            assertTrue(url.startsWith("https://"), "$target -> $url")
            assertTrue(' ' !in url, "$target -> $url")
        }
    }
}
