package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.ShibaTier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShibaCoinsArrangeTest {

    private fun row(
        id: String,
        tier: ShibaTier,
        rarity: Double,
        earned: Boolean,
        earnedAt: Long? = null,
        title: String = id,
        description: String = "",
        hidden: Boolean = false,
    ) = CoinRow(id, tier, title, description, rarity, null, isHidden = hidden, isEarned = earned, earnedAt = earnedAt)

    private val coins = listOf(
        row("bronze", ShibaTier.BRONZE, 60.0, earned = true, earnedAt = 100),
        row("gold", ShibaTier.GOLD, 2.0, earned = false),
        row("silver", ShibaTier.SILVER, 15.0, earned = true, earnedAt = 300),
    )

    @Test
    fun `rarest sorts by ascending rarity`() {
        val ids = coins.arrange(CoinSort.RAREST, CoinFilter.ALL).map { it.id }
        assertEquals(listOf("gold", "silver", "bronze"), ids)
    }

    @Test
    fun `tier sort puts bronze before silver before gold`() {
        val ids = coins.arrange(CoinSort.TIER, CoinFilter.ALL).map { it.id }
        assertEquals(listOf("bronze", "silver", "gold"), ids)
    }

    @Test
    fun `earned sort lists earned first, newest first`() {
        val ids = coins.arrange(CoinSort.EARNED, CoinFilter.ALL).map { it.id }
        assertEquals(listOf("silver", "bronze", "gold"), ids)
    }

    @Test
    fun `filters earned and locked`() {
        assertEquals(listOf("bronze", "silver"), coins.arrange(CoinSort.TIER, CoinFilter.EARNED).map { it.id }.sorted())
        assertEquals(listOf("gold"), coins.arrange(CoinSort.TIER, CoinFilter.LOCKED).map { it.id })
    }

    // ── Search ──────────────────────────────────────────────────────────────────

    private val searchable = listOf(
        row("a", ShibaTier.BRONZE, 50.0, earned = true, title = "First Blood", description = "Defeat an enemy"),
        row("b", ShibaTier.SILVER, 20.0, earned = false, title = "Speedrunner", description = "Finish in under an hour"),
    )

    @Test
    fun `an empty query changes nothing`() {
        assertEquals(
            searchable.arrange(CoinSort.TIER, CoinFilter.ALL).map { it.id },
            searchable.arrange(CoinSort.TIER, CoinFilter.ALL, query = "").map { it.id },
        )
    }

    @Test
    fun `query matches the title`() {
        assertEquals(listOf("b"), searchable.arrange(CoinSort.TIER, CoinFilter.ALL, query = "Speed").map { it.id })
    }

    @Test
    fun `query matches the description`() {
        assertEquals(listOf("a"), searchable.arrange(CoinSort.TIER, CoinFilter.ALL, query = "enemy").map { it.id })
    }

    @Test
    fun `query is case-insensitive and trimmed`() {
        assertEquals(listOf("b"), searchable.arrange(CoinSort.TIER, CoinFilter.ALL, query = "  SPEEDRUNNER  ").map { it.id })
    }

    @Test
    fun `query that matches nothing returns nothing`() {
        assertTrue(searchable.arrange(CoinSort.TIER, CoinFilter.ALL, query = "zzz").isEmpty())
    }

    // A redacted coin is redacted in search too: its real words must not be findable, or search
    // becomes a way to read every hidden coin in the game.
    private val secret = listOf(
        row(
            id = "secret",
            tier = ShibaTier.GOLD,
            rarity = 1.0,
            earned = false,
            title = "Kill the final boss",
            description = "Beat the game on hard",
            hidden = true,
        ),
    )

    @Test
    fun `a redacted hidden coin is not matched by its real title or description`() {
        assertTrue(secret.arrange(CoinSort.TIER, CoinFilter.ALL, query = "boss").isEmpty())
        assertTrue(secret.arrange(CoinSort.TIER, CoinFilter.ALL, query = "hard").isEmpty())
    }

    @Test
    fun `a redacted hidden coin is matched by hidden`() {
        assertEquals(listOf("secret"), secret.arrange(CoinSort.TIER, CoinFilter.ALL, query = "hidden").map { it.id })
    }

    @Test
    fun `a revealed hidden coin is matched by its real title`() {
        val ids = secret.arrange(CoinSort.TIER, CoinFilter.ALL, query = "boss", revealedIds = setOf("secret")).map { it.id }
        assertEquals(listOf("secret"), ids)
    }

    @Test
    fun `an earned hidden coin is no longer redacted in search`() {
        val earnedSecret = secret.map { it.copy(isEarned = true) }
        assertEquals(listOf("secret"), earnedSecret.arrange(CoinSort.TIER, CoinFilter.ALL, query = "boss").map { it.id })
        assertFalse(earnedSecret.first().isHideable)
    }
}
