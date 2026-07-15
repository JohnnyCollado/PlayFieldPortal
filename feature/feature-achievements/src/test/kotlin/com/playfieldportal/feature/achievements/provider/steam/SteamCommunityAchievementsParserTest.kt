package com.playfieldportal.feature.achievements.provider.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCommunityAchievementsParserTest {

    private fun row(title: String, description: String) = """
        <div class="achieveRow ">
          <div class="achieveImgHolder"><img src="https://cdn.example/apps/1/abc.jpg"/></div>
          <div class="achieveTxtHolder">
            <div class="achieveUnlockTime">Unlocked Jan 26 @ 9:41pm</div>
            <h3 class="ellipsis">$title</h3>
            <h5>$description</h5>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun `parses titles and descriptions from achievement rows`() {
        val html = "<div id=\"personalAchieve\">" +
            row("A Moment&#39;s Respite", "Take a break at the caf&amp;e.") +
            row("Card Battle Debut", "Win a <b>card battle</b>.") +
            "</div>"

        val parsed = SteamCommunityAchievementsParser.parse(html)

        assertEquals("Take a break at the caf&e.", parsed[SteamCommunityAchievementsParser.normalizeTitle("A Moment's Respite")])
        assertEquals("Win a card battle.", parsed[SteamCommunityAchievementsParser.normalizeTitle("Card Battle Debut")])
    }

    @Test
    fun `a duplicated title with different descriptions is dropped as ambiguous`() {
        val html = row("Twin", "First meaning") + row("Twin", "Second meaning")
        val parsed = SteamCommunityAchievementsParser.parse(html)
        assertNull(parsed[SteamCommunityAchievementsParser.normalizeTitle("Twin")])
    }

    @Test
    fun `unrecognized markup yields an empty map, never throws`() {
        assertTrue(SteamCommunityAchievementsParser.parse("<html><body>login required</body></html>").isEmpty())
        assertTrue(SteamCommunityAchievementsParser.parse("").isEmpty())
    }

    @Test
    fun `descriptions are length-capped`() {
        val long = "x".repeat(2_000)
        val parsed = SteamCommunityAchievementsParser.parse(row("Big", long))
        assertEquals(500, parsed.getValue(SteamCommunityAchievementsParser.normalizeTitle("Big")).length)
    }
}
