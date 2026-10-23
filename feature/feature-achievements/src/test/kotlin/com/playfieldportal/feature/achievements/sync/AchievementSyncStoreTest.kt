package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.AchievementTrackingDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A check that finds no achievement set is a complete answer, so it stamps the detail time.
 *
 * Survey L8 (docs/audits/codebase-survey-2026-10-22.md): `recordNoSet` wrote `last_checked_at`
 * only. "Is new" and the 24-hour staleness gate both key on `last_detail_at`, so a game with no set
 * was re-fetched on every sync run and every time its Game page opened, forever.
 */
class AchievementSyncStoreTest {

    private val dao = mockk<AchievementTrackingDao>(relaxed = true)
    private val store = AchievementSyncStore(dao)

    @Test
    fun `no set records the detail time so the game is not new or stale next run`() = runTest {
        store.recordNoSet(AchievementIdentity(AchievementProvider.STEAM, "440"), now = 1_000L)

        coVerify { dao.recordDetail("STEAM", "440", 1_000L, null) }
    }
}
