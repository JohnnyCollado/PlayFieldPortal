package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.steam.SteamCommunityApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSteamHiddenDescriptionsTest {

    private val communityApi = mockk<SteamCommunityApi>()
    private val enricher = LocalSteamHiddenDescriptions(communityApi)

    private fun coin(
        id: String,
        title: String,
        description: String = "",
        hidden: Boolean = true,
        earned: Boolean = true,
    ) = SyncedCoin(
        providerAchievementId = id,
        title = title,
        description = description,
        tier = ShibaTier.BRONZE,
        globalRarity = 10.0,
        iconUrl = null,
        isHidden = hidden,
        isEarned = earned,
        earnedHardcore = earned,
        earnedAt = if (earned) 1L else null,
    )

    private fun page(vararg rows: Pair<String, String>) = Response.success(
        rows.joinToString("") { (title, desc) ->
            """<div class="achieveRow"><h3>$title</h3><h5>$desc</h5></div>"""
        }.toResponseBody(),
    )

    @Test
    fun `fills an earned hidden coin's blank description from an owner page`() = runTest {
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns
            page("A Moment's Respite" to "Rest at the DigiBase for the first time.")

        val out = enricher.enrich("440", listOf(coin("h1", "A Moment's Respite")))

        assertEquals("Rest at the DigiBase for the first time.", out.single().description)
    }

    @Test
    fun `stops at the first owner once every needed description is found`() = runTest {
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns
            page("Secret" to "You found it.")

        enricher.enrich("440", listOf(coin("h1", "Secret")))

        // Full coverage from owner #1 must not fan out to the rest of the roster.
        coVerify(exactly = 1) { communityApi.achievementsPage(any(), "440", any()) }
    }

    @Test
    fun `falls through a private owner to the next that has the description`() = runTest {
        coEvery { communityApi.achievementsPage("76561198028121353", "440", any()) } returns
            Response.error(403, "".toResponseBody())
        coEvery { communityApi.achievementsPage("76561198001237877", "440", any()) } returns
            page("Secret" to "Revealed by the second owner.")

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("Revealed by the second owner.", out.single().description)
    }

    @Test
    fun `never overwrites a description that is already present`() = runTest {
        val out = enricher.enrich("440", listOf(coin("h1", "Visible", description = "Already here")))

        assertEquals("Already here", out.single().description)
        coVerify(exactly = 0) { communityApi.achievementsPage(any(), any(), any()) }
    }

    @Test
    fun `skips coins that are hidden but not earned`() = runTest {
        val out = enricher.enrich("440", listOf(coin("h1", "Locked secret", earned = false)))

        assertEquals("", out.single().description)
        coVerify(exactly = 0) { communityApi.achievementsPage(any(), any(), any()) }
    }

    @Test
    fun `a page without a declared length is skipped, not read unbounded`() = runTest {
        // contentLength() == -1 (no Content-Length header) must fail the size guard, so .string()
        // is never called and the owner is skipped — even though the body would reveal the title.
        val unsizedBody = mockk<ResponseBody> {
            every { contentLength() } returns -1
            every { string() } returns """<div class="achieveRow"><h3>Secret</h3><h5>Leaked.</h5></div>"""
        }
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns Response.success(unsizedBody)

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("", out.single().description)
        verify(exactly = 0) { unsizedBody.string() }
    }

    @Test
    fun `a markup change on every owner leaves the coins untouched`() = runTest {
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns page()

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("", out.single().description)
    }
}
