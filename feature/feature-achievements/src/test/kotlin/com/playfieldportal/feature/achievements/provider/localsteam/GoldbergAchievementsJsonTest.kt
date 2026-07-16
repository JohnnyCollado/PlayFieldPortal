package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaAchievement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldbergAchievementsJsonTest {

    private val parser = Json

    @Test
    fun `maps schema fields into the goldberg shape`() {
        val out = GoldbergAchievementsJson.serialize(
            listOf(
                SteamSchemaAchievement(
                    name = "ACH_1",
                    displayName = "First \"Steps\"",
                    description = "Do a thing & win",
                    hidden = 0,
                    icon = "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/1173810/aaa111.jpg",
                    icongray = "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/1173810/aaa111_gray.jpg",
                ),
            ),
        )

        // Re-parses as valid JSON and preserves special characters through serialization.
        val entry = parser.parseToJsonElement(out).jsonArray.single().jsonObject
        assertEquals("ACH_1", entry.getValue("name").jsonPrimitive.content)
        assertEquals("First \"Steps\"", entry.getValue("displayName").jsonPrimitive.content)
        assertEquals("Do a thing & win", entry.getValue("description").jsonPrimitive.content)
        assertEquals(0, entry.getValue("hidden").jsonPrimitive.content.toInt())
        // Full CDN urls collapse to a folder-relative images/<file> path the emu expects.
        assertEquals("images/aaa111.jpg", entry.getValue("icon").jsonPrimitive.content)
        assertEquals("images/aaa111_gray.jpg", entry.getValue("icongray").jsonPrimitive.content)
    }

    @Test
    fun `a hidden achievement with no description becomes an empty string`() {
        val out = GoldbergAchievementsJson.serialize(
            listOf(
                SteamSchemaAchievement(
                    name = "ACH_HIDDEN",
                    displayName = "Secret",
                    description = null,
                    hidden = 1,
                    icon = "https://x/bbb.jpg",
                    icongray = "https://x/bbbg.jpg",
                ),
            ),
        )

        val entry = parser.parseToJsonElement(out).jsonArray.single().jsonObject
        assertEquals("", entry.getValue("description").jsonPrimitive.content)
        assertEquals(1, entry.getValue("hidden").jsonPrimitive.content.toInt())
    }

    @Test
    fun `an achievement with no icon yields an empty image path`() {
        val out = GoldbergAchievementsJson.serialize(
            listOf(SteamSchemaAchievement(name = "ACH_NOICON", displayName = "No icon", icon = null, icongray = null)),
        )

        val entry = parser.parseToJsonElement(out).jsonArray.single().jsonObject
        assertEquals("", entry.getValue("icon").jsonPrimitive.content)
        assertEquals("", entry.getValue("icongray").jsonPrimitive.content)
    }

    @Test
    fun `an empty schema serializes to an empty array`() {
        val out = GoldbergAchievementsJson.serialize(emptyList())
        assertTrue(parser.parseToJsonElement(out).jsonArray.isEmpty())
    }
}
