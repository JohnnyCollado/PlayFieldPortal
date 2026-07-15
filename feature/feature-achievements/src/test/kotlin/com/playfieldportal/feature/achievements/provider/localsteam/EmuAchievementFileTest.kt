package com.playfieldportal.feature.achievements.provider.localsteam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmuAchievementFileTest {

    // The verbatim progress file gbe_fork wrote on the test device (MARVEL Cosmic Invasion,
    // appid 2753970) after a session that earned three achievements — reality, not a mock.
    private val fixture = javaClass.getResource("/localsteam/gse_achievements_progress.json")!!.readText()

    @Test
    fun `parses the live device fixture`() {
        val parsed = EmuAchievementFile.parse(fixture).associateBy { it.apiName }

        assertEquals(35, parsed.size)
        assertEquals(3, parsed.values.count { it.earned })

        val ready = parsed.getValue("ReadyForBattle")
        assertTrue(ready.earned)
        assertEquals(1784120702L, ready.earnedAtEpochSeconds)

        // Earned with a progress counter mid-flight: earned state wins, counters are ignored.
        val combo = parsed.getValue("HitsCombo")
        assertTrue(combo.earned)
        assertEquals(1784121020L, combo.earnedAtEpochSeconds)

        // Locked entries carry earned_time=0, which must map to "never", not epoch 1970.
        val locked = parsed.getValue("StoppedThanos")
        assertEquals(false, locked.earned)
        assertNull(locked.earnedAtEpochSeconds)
    }

    @Test
    fun `tolerates the old Goldberg zero-one earned flag`() {
        val parsed = EmuAchievementFile.parse(
            """{"ACH_A": {"earned": 1, "earned_time": 5}, "ACH_B": {"earned": 0, "earned_time": 0}}""",
        ).associateBy { it.apiName }

        assertTrue(parsed.getValue("ACH_A").earned)
        assertEquals(false, parsed.getValue("ACH_B").earned)
    }

    @Test
    fun `hostile or malformed input yields empty and never throws`() {
        val hostile = listOf(
            "", "not json at all", "42", "\"just a string\"", "[1,2,3]",
            """{"ACH": "not an object"}""",
            """{"ACH": {"earned": "maybe", "earned_time": "soon"}}""",
            "{\"unterminated\": {",
            "x".repeat(EmuAchievementFile.MAX_BYTES + 1),
        )
        for (input in hostile) {
            val parsed = EmuAchievementFile.parse(input)
            assertTrue(parsed.none { it.earned }, "no earned state from: ${input.take(30)}")
        }
    }

    @Test
    fun `entries missing fields default to locked`() {
        val parsed = EmuAchievementFile.parse("""{"ACH": {}}""").single()
        assertEquals("ACH", parsed.apiName)
        assertEquals(false, parsed.earned)
        assertNull(parsed.earnedAtEpochSeconds)
    }
}
