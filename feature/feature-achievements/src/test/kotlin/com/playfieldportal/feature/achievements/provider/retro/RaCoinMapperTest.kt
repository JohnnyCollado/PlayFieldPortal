package com.playfieldportal.feature.achievements.provider.retro

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.retroachivements.api.data.pojo.game.GetGameInfoAndUserProgress

/**
 * Mapping coverage for the RA remote path — restores what the deleted RetroAchievementsApiTest gave
 * (tier from points, hardcore vs softcore earned state, badge URL, rarity, empty -> NotFound),
 * exercised directly against api-kotlin POJOs without the Retrofit/NetworkResponse plumbing.
 */
class RaCoinMapperTest {

    @Test
    fun `maps achievements to tiered coins with earned state and badge url`() {
        val gold = achievement(
            id = "10", points = 50, numAwarded = 5, badgeName = "12345",
            dateEarnedHardcore = "2024-01-02 03:04:05",
        )
        val bronze = achievement(id = "11", points = 5, numAwarded = 90, badgeName = "67890")
        val game = response(casualPlayers = 100, achievements = linkedMapOf("10" to gold, "11" to bronze))

        val result = RaCoinMapper.map(game, "789")

        assertTrue(result is ProviderSyncResult.Success)
        result as ProviderSyncResult.Success
        assertEquals("789", result.providerGameId)
        val byId = result.coins.associateBy { it.providerAchievementId }

        val g = byId.getValue("10")
        assertEquals(ShibaTier.GOLD, g.tier)
        assertEquals(5.0, g.globalRarity, 1e-6)
        assertTrue(g.isEarned)
        assertTrue(g.earnedHardcore)
        assertEquals("https://media.retroachievements.org/Badge/12345.png", g.iconUrl)

        val b = byId.getValue("11")
        assertEquals(ShibaTier.BRONZE, b.tier)
        assertEquals(90.0, b.globalRarity, 1e-6)
        assertFalse(b.isEarned)
        assertFalse(b.earnedHardcore)
        assertNull(b.earnedAt)
    }

    @Test
    fun `softcore-only unlock earns the coin but not the crown`() {
        val soft = achievement(
            id = "20", points = 25, numAwarded = 10, badgeName = "1",
            dateEarned = "2024-05-06 07:08:09",
        )
        val game = response(casualPlayers = 50, achievements = linkedMapOf("20" to soft))

        val coin = (RaCoinMapper.map(game, "1") as ProviderSyncResult.Success).coins.single()
        assertEquals(ShibaTier.SILVER, coin.tier)
        assertTrue(coin.isEarned)
        assertFalse(coin.earnedHardcore)
    }

    @Test
    fun `empty achievement set maps to NotFound`() {
        val game = response(casualPlayers = 10, achievements = linkedMapOf())
        assertEquals(ProviderSyncResult.NotFound, RaCoinMapper.map(game, "1"))
    }

    // ── POJO builders ─────────────────────────────────────────────────────────
    // api-kotlin's models have no default values, so these fill every field; only the ones the
    // mapper reads (id, points, numAwarded, badgeName, dateEarned[Hardcore], casual players) vary.

    private fun achievement(
        id: String,
        points: Long,
        numAwarded: Long,
        badgeName: String,
        dateEarned: String? = null,
        dateEarnedHardcore: String? = null,
    ) = GetGameInfoAndUserProgress.Response.Achievement(
        id = id,
        numAwarded = numAwarded,
        numAwardedHardcore = 0,
        title = "Coin $id",
        description = "desc $id",
        points = points,
        trueRatio = 0,
        author = "",
        dateModified = "",
        dateCreated = "",
        badgeName = badgeName,
        displayOrder = 0,
        memAddr = "",
        type = null,
        dateEarnedHardcore = dateEarnedHardcore,
        dateEarned = dateEarned,
    )

    private fun response(
        casualPlayers: Long,
        achievements: Map<String, GetGameInfoAndUserProgress.Response.Achievement>,
    ) = GetGameInfoAndUserProgress.Response(
        id = 1,
        title = "",
        consoleId = 1,
        forumTopicId = 0,
        flags = null,
        imageIcon = "",
        imageTitle = "",
        imageInGame = "",
        imageBoxArt = "",
        publisher = "",
        developer = "",
        genre = "",
        released = null,
        releasedAtGranularity = null,
        isFinal = false,
        richPresencePatch = "",
        playersTotal = 0,
        userTotalPlaytime = 0,
        guideUrl = null,
        consoleName = "",
        parentGameId = null,
        numDistinctPlayers = 0,
        numAchievements = achievements.size,
        achievements = HashMap(achievements),
        numAwardedToUser = 0,
        numAwardedToUserHardcore = 0,
        numDistinctPlayersCasual = casualPlayers,
        numDistinctPlayersHardcore = 0,
        userCompletion = "",
        userCompletionHardcore = "",
    )
}
