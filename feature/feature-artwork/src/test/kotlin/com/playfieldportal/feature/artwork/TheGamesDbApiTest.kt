package com.playfieldportal.feature.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TheGamesDB response parsing. A ByGameName response carries every hit's images in ONE include
 * block, so once Change Match can pick a hit other than the first, the art must come from that
 * hit's own images — never from whichever game happened to be listed first.
 */
class TheGamesDbApiTest {

    private val response = TgdbGamesResponse(
        code = 200,
        data = TgdbGamesData(
            games = listOf(
                TgdbGame(id = 1L, gameTitle = "Final Fantasy VI", releaseDate = "1994-04-02"),
                TgdbGame(id = 2L, gameTitle = "Final Fantasy VI Advance", releaseDate = "2007-02-05", overview = "GBA port"),
            ),
        ),
        include = TgdbInclude(
            boxart = TgdbBoxartInclude(
                baseUrl = TgdbBaseUrl(large = "https://cdn/large/"),
                data = mapOf(
                    "1" to listOf(TgdbImage(id = 10, type = "boxart", side = "front", filename = "snes-front.jpg")),
                    "2" to listOf(
                        TgdbImage(id = 20, type = "boxart", side = "back", filename = "gba-back.jpg"),
                        TgdbImage(id = 21, type = "boxart", side = "front", filename = "gba-front.jpg"),
                        TgdbImage(id = 22, type = "fanart", filename = "gba-fanart.jpg"),
                        TgdbImage(id = 23, type = "clearlogo", filename = "gba-logo.png"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a game's art comes from its own images, not the first hit's`() {
        val info = TheGamesDbApi.infoFrom(response, response.data!!.games[1])

        assertEquals(2L, info.tgdbId)
        assertEquals("Final Fantasy VI Advance", info.title)
        assertEquals("GBA port", info.description)
        assertEquals(2007, info.releaseYear)
        assertEquals("https://cdn/large/gba-front.jpg", info.artworkUrl)
        assertEquals("https://cdn/large/gba-fanart.jpg", info.heroUrl)
        assertEquals("https://cdn/large/gba-logo.png", info.logoUrl)
    }

    @Test
    fun `a game with no images of its own yields no urls`() {
        val info = TheGamesDbApi.infoFrom(response, TgdbGame(id = 3L, gameTitle = "Unlisted"))

        assertNull(info.artworkUrl)
        assertNull(info.heroUrl)
        assertNull(info.logoUrl)
    }
}
