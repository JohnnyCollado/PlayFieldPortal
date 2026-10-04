package com.playfieldportal.feature.achievements.provider.x360

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One title's achievements merged across every GPD that has it — X360 Mobile and XenDroid can
 * both be linked, and each can hold more than one profile. An achievement earned anywhere counts.
 */
class X360AchievementsTest {

    private fun gpd(fixture: GpdFixture) = assertNotNull(XdbfGpd.parse(fixture.build()))

    @Test
    fun `a single GPD passes through in achievement id order`() {
        val merged = X360Achievements.merge(
            listOf(gpd(GpdFixture().achievement(id = 7).achievement(id = 2).achievement(id = 5))),
        )
        assertEquals(listOf(2, 5, 7), merged.map { it.achievement.id })
    }

    @Test
    fun `earned in either GPD counts as earned, with the earliest unlock time`() {
        val early = GpdFixture.filetimeOf(1_700_000_000_000L)
        val late = GpdFixture.filetimeOf(1_790_000_000_000L)
        val a = gpd(
            GpdFixture()
                .achievement(id = 1, flags = 0x9 or XdbfGpd.FLAG_ACHIEVED, unlockFiletime = late)
                .achievement(id = 2),
        )
        val b = gpd(
            GpdFixture()
                .achievement(id = 1, flags = 0x9 or XdbfGpd.FLAG_ACHIEVED, unlockFiletime = early)
                .achievement(id = 2, flags = 0x9 or XdbfGpd.FLAG_ACHIEVED, unlockFiletime = late),
        )

        val merged = X360Achievements.merge(listOf(a, b)).associateBy { it.achievement.id }

        assertEquals(1_700_000_000_000L, merged.getValue(1).achievement.unlockedAtEpochMillis)
        assertTrue(merged.getValue(2).achievement.unlocked)
        assertEquals(1_790_000_000_000L, merged.getValue(2).achievement.unlockedAtEpochMillis)
    }

    @Test
    fun `an achievement only one GPD knows about is kept`() {
        val merged = X360Achievements.merge(
            listOf(gpd(GpdFixture().achievement(id = 1)), gpd(GpdFixture().achievement(id = 9))),
        )
        assertEquals(listOf(1, 9), merged.map { it.achievement.id })
    }

    @Test
    fun `the icon comes from whichever GPD holds that image`() {
        // Xenia only stores an achievement's image once it is earned, so the earning GPD has it.
        val a = gpd(GpdFixture().achievement(id = 1, imageId = 11))
        val b = gpd(
            GpdFixture()
                .achievement(id = 1, imageId = 11, flags = 0x9 or XdbfGpd.FLAG_ACHIEVED)
                .image(11, GpdFixture.PNG),
        )

        val merged = X360Achievements.merge(listOf(a, b)).single()

        assertContentEquals(GpdFixture.PNG, merged.icon)
    }

    @Test
    fun `a locked achievement with no stored image has no icon`() {
        val merged = X360Achievements.merge(listOf(gpd(GpdFixture().achievement(id = 1)))).single()
        assertNull(merged.icon)
        assertFalse(merged.achievement.unlocked)
    }

    @Test
    fun `no GPDs merge to nothing`() {
        assertTrue(X360Achievements.merge(emptyList()).isEmpty())
    }
}
