package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.steam.SteamCommunityApi
import com.playfieldportal.feature.achievements.provider.steam.SteamHuntersApi
import com.playfieldportal.feature.achievements.provider.steam.SteamHuntersDescriptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Response
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalSteamHiddenDescriptionsTest {

    private val communityApi = mockk<SteamCommunityApi>()
    // Steam Hunters knows nothing by default, so the roster cases run exactly as they did before it.
    private val huntersApi = mockk<SteamHuntersApi> {
        coEvery { achievements(any()) } answers { hunters() }
    }
    private val enricher = LocalSteamHiddenDescriptions(communityApi, SteamHuntersDescriptions(huntersApi))

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

    // A body with no Content-Length, as a gzip response arrives after OkHttp decompresses it.
    private fun unsized(text: String): ResponseBody = Buffer().writeUtf8(text).asResponseBody(null, -1)

    // A Steam Hunters body; a null description omits the field, as a sparse entry would.
    private fun hunters(vararg rows: Pair<String, String?>): Response<ResponseBody> = Response.success(
        rows.joinToString(",", "[", "]") { (apiName, desc) ->
            val description = desc?.let { """"description":"$it",""" }.orEmpty()
            """{"apiName":"$apiName","name":"$apiName",$description"steamPercentage":1.0}"""
        }.toResponseBody(),
    )

    // --- Roster (unchanged rules) ---

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
    fun `the roster is never asked about a hidden coin that is not earned`() = runTest {
        val out = enricher.enrich("440", listOf(coin("h1", "Locked secret", earned = false)))

        assertEquals("", out.single().description)
        coVerify(exactly = 0) { communityApi.achievementsPage(any(), any(), any()) }
    }

    @Test
    fun `a page with no declared length is still read when it fits the cap`() = runTest {
        // OkHttp strips Content-Length when it un-gzips, so an unsized page is the normal case.
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns
            Response.success(unsized("""<div class="achieveRow"><h3>Secret</h3><h5>Found it.</h5></div>"""))

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("Found it.", out.single().description)
    }

    @Test
    fun `an oversized page is skipped, not read whole`() = runTest {
        val page = """<div class="achieveRow"><h3>Secret</h3><h5>Leaked.</h5></div>""" + " ".repeat(4_100_000)
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns Response.success(unsized(page))

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("", out.single().description)
    }

    @Test
    fun `a markup change on every owner leaves the coins untouched`() = runTest {
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns page()

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("", out.single().description)
    }

    // --- Steam Hunters first ---

    @Test
    fun `fills earned and unearned hidden coins from Steam Hunters by apiName`() = runTest {
        coEvery { huntersApi.achievements("367520") } returns hunters(
            "ENDING_B" to "Defeat the Hollow Knight with Hornet by your side.",
            "ENDING_C" to "Defeat the Radiance.",
        )

        val out = enricher.enrich(
            "367520",
            listOf(coin("ENDING_B", "Dream No More"), coin("ENDING_C", "Embrace the Void", earned = false)),
        ).associateBy { it.providerAchievementId }

        assertEquals("Defeat the Hollow Knight with Hornet by your side.", out.getValue("ENDING_B").description)
        assertEquals("Defeat the Radiance.", out.getValue("ENDING_C").description)
    }

    @Test
    fun `matches on apiName, not on title`() = runTest {
        // The coin's TITLE equals a Steam Hunters apiName, but its id does not: nothing is taken.
        coEvery { huntersApi.achievements("367520") } returns hunters("OTHER_ID" to "Wrong one.")

        val out = enricher.enrich("367520", listOf(coin("ENDING_B", "OTHER_ID", earned = false)))

        assertEquals("", out.single().description)
    }

    @Test
    fun `leaves visible and already described coins alone`() = runTest {
        coEvery { huntersApi.achievements("367520") } returns hunters(
            "VISIBLE" to "From Steam Hunters.",
            "DESCRIBED" to "From Steam Hunters.",
            "BLANK" to "Filled.",
        )

        val out = enricher.enrich(
            "367520",
            listOf(
                coin("VISIBLE", "Visible", hidden = false),
                coin("DESCRIBED", "Described", description = "Already here"),
                coin("BLANK", "Blank"),
            ),
        ).associateBy { it.providerAchievementId }

        assertEquals("", out.getValue("VISIBLE").description)
        assertEquals("Already here", out.getValue("DESCRIBED").description)
        assertEquals("Filled.", out.getValue("BLANK").description)
    }

    @Test
    fun `a blank or missing Steam Hunters description is not taken`() = runTest {
        coEvery { huntersApi.achievements("367520") } returns hunters("A" to " ", "B" to null)

        val out = enricher.enrich("367520", listOf(coin("A", "A", earned = false), coin("B", "B", earned = false)))

        assertEquals(listOf("", ""), out.map { it.description })
    }

    @Test
    fun `makes no roster request when Steam Hunters covered every coin`() = runTest {
        coEvery { huntersApi.achievements("440") } returns hunters("h1" to "From Steam Hunters.")

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("From Steam Hunters.", out.single().description)
        coVerify(exactly = 0) { communityApi.achievementsPage(any(), any(), any()) }
    }

    @Test
    fun `the roster fills only the earned coins Steam Hunters left blank`() = runTest {
        coEvery { huntersApi.achievements("440") } returns hunters("covered" to "From Steam Hunters.")
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns page(
            "Covered" to "From the roster.",
            "Earned gap" to "Roster fills this.",
            "Unearned gap" to "Roster must not fill this.",
        )

        val out = enricher.enrich(
            "440",
            listOf(
                coin("covered", "Covered"),
                coin("earnedGap", "Earned gap"),
                coin("unearnedGap", "Unearned gap", earned = false),
            ),
        ).associateBy { it.providerAchievementId }

        assertEquals("From Steam Hunters.", out.getValue("covered").description)
        assertEquals("Roster fills this.", out.getValue("earnedGap").description)
        assertEquals("", out.getValue("unearnedGap").description)
    }

    @Test
    fun `makes no request at all when no hidden coin is blank`() = runTest {
        enricher.enrich("440", listOf(coin("v", "Visible", hidden = false), coin("d", "Done", description = "Known")))

        coVerify(exactly = 0) { huntersApi.achievements(any()) }
        coVerify(exactly = 0) { communityApi.achievementsPage(any(), any(), any()) }
    }

    @Test
    fun `asks Steam Hunters once per enrich`() = runTest {
        enricher.enrich("440", listOf(coin("a", "A"), coin("b", "B", earned = false)))

        coVerify(exactly = 1) { huntersApi.achievements("440") }
    }

    @Test
    fun `falls back to the roster when Steam Hunters returns 404`() = runTest {
        coEvery { huntersApi.achievements("440") } returns Response.error(404, "".toResponseBody())
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns page("Secret" to "From the roster.")

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("From the roster.", out.single().description)
    }

    @Test
    fun `falls back to the roster when Steam Hunters throws an IOException`() = runTest {
        coEvery { huntersApi.achievements("440") } throws IOException("offline")
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns page("Secret" to "From the roster.")

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("From the roster.", out.single().description)
    }

    @Test
    fun `falls back to the roster when Steam Hunters returns malformed JSON`() = runTest {
        coEvery { huntersApi.achievements("440") } returns Response.success("<html>challenge</html>".toResponseBody())
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns page("Secret" to "From the roster.")

        val out = enricher.enrich("440", listOf(coin("h1", "Secret")))

        assertEquals("From the roster.", out.single().description)
    }

    @Test
    fun `an oversized Steam Hunters body is discarded, not read whole`() = runTest {
        // Steam Hunters serves gzip + chunked, so there is no declared length to check up front: the
        // cap is enforced while reading. A body past it is dropped even though it would match.
        val padding = " ".repeat(5_000_000)
        coEvery { huntersApi.achievements("440") } returns Response.success(
            """[{"apiName":"h1","description":"Too big to trust."}$padding]""".toResponseBody(),
        )

        val out = enricher.enrich("440", listOf(coin("h1", "Secret", earned = false)))

        assertEquals("", out.single().description)
    }

    @Test
    fun `cancellation from Steam Hunters propagates`() = runTest {
        coEvery { huntersApi.achievements("440") } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { enricher.enrich("440", listOf(coin("h1", "Secret"))) }
    }
}
