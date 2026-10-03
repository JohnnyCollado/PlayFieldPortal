package com.playfieldportal.feature.achievements.provider.steam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SteamHuntersApiTest {

    // Captured 2026-10-02 from https://steamhunters.com/api/apps/524220/achievements (NieR:Automata).
    private val nier = javaClass.getResource("/steamhunters/nier_automata_524220.json")!!.readText()

    @Test
    fun `the captured NieR response decodes every entry with its apiName and description`() {
        val entries = SteamHuntersAchievements.decode(nier)

        assertEquals(47, entries.size)
        assertTrue(entries.all { !it.apiName.isNullOrBlank() && !it.description.isNullOrBlank() })
        val bunker = entries.first { it.apiName == "ACH_VISITED_BUNKER" }
        assertEquals("Resuscitated Body", bunker.name)
        assertEquals("Stare into space from the Bunker.", bunker.description)
    }

    @Test
    fun `unknown fields are ignored and a missing description decodes as null`() {
        val entries = SteamHuntersAchievements.decode(
            """[{"apiName":"A","name":"Alpha","points":3,"obtainability":0,"brandNew":{"x":1}}]""",
        )

        assertEquals("A", entries.single().apiName)
        assertEquals(null, entries.single().description)
    }

    @Test
    fun `a game Steam Hunters does not know decodes to an empty list`() {
        assertEquals(emptyList(), SteamHuntersAchievements.decode("[]"))
    }
}
