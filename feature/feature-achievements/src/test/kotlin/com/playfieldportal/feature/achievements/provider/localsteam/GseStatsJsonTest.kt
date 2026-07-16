package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaStat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GseStatsJsonTest {

    private val parser = Json

    @Test
    fun `whole defaults become int stats with string-typed values`() {
        val out = GseStatsJson.serialize(listOf(SteamSchemaStat(name = "stat_kills", defaultValue = 5.0)))

        val entry = parser.parseToJsonElement(out).jsonArray.single().jsonObject
        assertEquals("stat_kills", entry.getValue("name").jsonPrimitive.content)
        assertEquals("int", entry.getValue("type").jsonPrimitive.content)
        // gbe_fork's example carries every value as a string, whole numbers without a decimal.
        assertTrue(entry.getValue("default").jsonPrimitive.isString)
        assertEquals("5", entry.getValue("default").jsonPrimitive.content)
        assertEquals("0", entry.getValue("global").jsonPrimitive.content)
    }

    @Test
    fun `fractional defaults become float stats`() {
        val out = GseStatsJson.serialize(listOf(SteamSchemaStat(name = "stat_speed", defaultValue = 3.5)))

        val entry = parser.parseToJsonElement(out).jsonArray.single().jsonObject
        assertEquals("float", entry.getValue("type").jsonPrimitive.content)
        assertEquals("3.5", entry.getValue("default").jsonPrimitive.content)
    }

    @Test
    fun `an empty stat list serializes to an empty array`() {
        assertTrue(parser.parseToJsonElement(GseStatsJson.serialize(emptyList())).jsonArray.isEmpty())
    }
}
